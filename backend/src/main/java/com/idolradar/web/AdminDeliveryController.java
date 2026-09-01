package com.idolradar.web;

import java.util.Map;
import java.util.UUID;

import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.admin.AdminAuditContext;
import com.idolradar.admin.AdminAuditOperation;
import com.idolradar.admin.AdminDeliveryStore;
import com.idolradar.api.ApiResponse;
import com.idolradar.api.AppException;
import com.idolradar.config.BackendProperties;
import com.idolradar.config.RateLimitProperties;
import com.idolradar.worker.NotificationAbortException;
import com.idolradar.worker.NotificationService;
import com.idolradar.worker.WorkerModels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推送投递看板与单账号定向发送入口；只回传聚合与非身份字段。
 *
 * <p>鉴权与写操作审计由管理端拦截器统一处理。
 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class AdminDeliveryController {
    private final AdminDeliveryStore store;
    private final NotificationService notifications;
    private final BackendProperties backendProperties;
    private final DistributedRateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public AdminDeliveryController(
            AdminDeliveryStore store,
            NotificationService notifications,
            BackendProperties backendProperties,
            DistributedRateLimiter rateLimiter,
            RateLimitProperties rateLimits) {
        this.store = store;
        this.notifications = notifications;
        this.backendProperties = backendProperties;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/admin/v1/deliveries")
    public ApiResponse<Map<String, Object>> deliveries(
            @RequestParam(required = false) @Size(max = 128) String idolId,
            @RequestParam(required = false) @Size(max = 16) String status,
            @RequestParam(required = false) @Positive Integer rangeHours) {
        return ApiResponse.ok(store.listDeliveries(idolId, status, rangeHours));
    }

    @GetMapping("/admin/v1/notification-targets")
    public ApiResponse<Map<String, Object>> notificationTargets() {
        return ApiResponse.ok(store.listNotificationTargets(backendProperties.subscribeTemplateId()));
    }

    /** 真实发送前按管理员限流；额度、目标校验、去重均由 NotificationService 原子完成。 */
    @PostMapping("/admin/v1/notification-targets/{userId}/send")
    @AdminAuditOperation(
            action = "定向推送",
            resourceType = "notification_target",
            resourceIdVariable = "userId")
    public ApiResponse<Map<String, String>> sendToTarget(
            @PathVariable UUID userId,
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity,
            @Valid @RequestBody TargetedSendRequest request,
            HttpServletRequest servletRequest) {
        if (!rateLimiter.allow(
                "admin-targeted-notification",
                identity.adminId().toString(),
                rateLimits.subscriptionLimit(),
                rateLimits.window())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "定向推送太频繁，请稍后再试");
        }

        WorkerModels.DeliveryOutcome outcome;
        try {
            outcome = notifications.sendPostToUser(request.postId(), userId);
        } catch (IllegalArgumentException error) {
            throw new AppException(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "待推送动态不存在");
        } catch (IllegalStateException error) {
            // 模板未配置或字段名格式非法：属于部署配置问题，要让管理员一眼看出不是账号或额度的锅。
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CONFIGURATION_ERROR", "订阅消息配置不完整，无法发送");
        } catch (NotificationAbortException error) {
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WECHAT_SEND_BLOCKED",
                    "微信暂时拒绝发送，投递已进入安全重试");
        }

        return switch (outcome) {
            case SENT -> {
                AdminAuditContext.attach(
                        servletRequest, null, AdminAuditContext.deliverySummary(request.postId(), "sent"));
                yield ApiResponse.ok(Map.of("status", "sent"));
            }
            case SKIPPED -> throw new AppException(
                    HttpStatus.CONFLICT,
                    "TARGET_NOT_SENDABLE",
                    "账号无可用额度、已收到该动态，或已屏蔽对应来源");
            case RETRYING -> throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DELIVERY_RETRYING",
                    "发送前发生异常，投递已进入安全重试");
            case FAILED -> throw new AppException(
                    HttpStatus.BAD_GATEWAY,
                    "WECHAT_SEND_FAILED",
                    "微信未确认送达，请查看投递详情");
        };
    }

    public record TargetedSendRequest(@NotBlank @Size(max = 128) String postId) {
    }
}
