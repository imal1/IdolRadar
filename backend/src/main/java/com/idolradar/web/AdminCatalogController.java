package com.idolradar.web;

import java.util.Map;
import java.util.UUID;

import com.idolradar.admin.AdminAuditContext;
import com.idolradar.admin.AdminAuditOperation;
import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.admin.AdminCatalogStore;
import com.idolradar.admin.SourceVerifier;
import com.idolradar.api.ApiResponse;
import com.idolradar.api.AppException;
import com.idolradar.config.RateLimitProperties;
import com.idolradar.worker.FeedUrlGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端运营入口：抓取源健康度、idol/源维护、idol 申请审核。
 *
 * <p>鉴权由 AdminAuthInterceptor 统一处理，写操作由 AdminAuditInterceptor 统一落库；
 * Controller 只提供不含凭据和原始请求体的白名单业务摘要。
 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class AdminCatalogController {
    private final AdminCatalogStore store;
    private final SourceVerifier verifier;
    private final FeedUrlGuard urlGuard;
    private final DistributedRateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public AdminCatalogController(
            AdminCatalogStore store,
            SourceVerifier verifier,
            FeedUrlGuard urlGuard,
            DistributedRateLimiter rateLimiter,
            RateLimitProperties rateLimits) {
        this.store = store;
        this.verifier = verifier;
        this.urlGuard = urlGuard;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/admin/v1/sources")
    public ApiResponse<Map<String, Object>> sourceHealth(
            @RequestParam(required = false) @Size(max = 128) String idolId,
            @RequestParam(required = false) @Size(max = 16) String health) {
        return ApiResponse.ok(store.listSourceHealth(idolId, health));
    }

    @GetMapping("/admin/v1/idols")
    public ApiResponse<Map<String, Object>> idols() {
        return ApiResponse.ok(store.listIdols());
    }

    @PostMapping("/admin/v1/idols")
    @AdminAuditOperation(action = "新增 idol", resourceType = "idol")
    public ApiResponse<Map<String, Object>> createIdol(
            @Valid @RequestBody CreateIdolRequest request,
            HttpServletRequest servletRequest) {
        Map<String, Object> created = store.createIdol(
                request.id(), request.name(), request.avatar(), request.bio(),
                request.enabled() == null || request.enabled());
        AdminAuditContext.attach(
                servletRequest, request.id(), null, AdminAuditContext.idolSummary(created, false, false));
        return ApiResponse.ok(created);
    }

    @PatchMapping("/admin/v1/idols/{idolId}")
    @AdminAuditOperation(action = "修改 idol", resourceType = "idol", resourceIdVariable = "idolId")
    public ApiResponse<Map<String, Object>> updateIdol(
            @PathVariable @Size(max = 128) String idolId,
            @Valid @RequestBody UpdateIdolRequest request,
            HttpServletRequest servletRequest) {
        String before = store.idolAuditSummary(idolId);
        Map<String, Object> updated = store.updateIdol(
                idolId, request.name(), request.avatar(), request.bio(), request.enabled(), request.version());
        AdminAuditContext.attach(
                servletRequest,
                before,
                AdminAuditContext.idolSummary(
                        updated, request.avatar() != null, request.bio() != null));
        return ApiResponse.ok(updated);
    }

    @PostMapping("/admin/v1/sources")
    @AdminAuditOperation(action = "新增动态源", resourceType = "source")
    public ApiResponse<Map<String, Object>> createSource(
            @Valid @RequestBody CreateSourceRequest request,
            HttpServletRequest servletRequest) {
        // 新建即校验抓取地址，把 SSRF 防护挡在入库之前，而不是等 worker 抓取时才发现。
        urlGuard.validateUrl(request.rssUrl());
        requireFetchable(request.rssUrl());
        Map<String, Object> created = store.createSource(
                request.id(), request.idolId(), request.rssUrl(), request.displayName(),
                request.channel() == null ? "RSS" : request.channel(),
                request.enabled() == null || request.enabled());
        AdminAuditContext.attach(
                servletRequest, request.id(), null, AdminAuditContext.sourceSummary(created, false));
        return ApiResponse.ok(created);
    }

    @PatchMapping("/admin/v1/sources/{sourceId}")
    @AdminAuditOperation(action = "修改动态源", resourceType = "source", resourceIdVariable = "sourceId")
    public ApiResponse<Map<String, Object>> updateSource(
            @PathVariable @Size(max = 128) String sourceId,
            @Valid @RequestBody UpdateSourceRequest request,
            HttpServletRequest servletRequest) {
        if (request.rssUrl() != null) {
            urlGuard.validateUrl(request.rssUrl());
            requireFetchable(request.rssUrl());
        }
        String before = store.sourceAuditSummary(sourceId);
        Map<String, Object> updated = store.updateSource(
                sourceId, request.rssUrl(), request.displayName(), request.channel(),
                request.enabled(), request.version());
        AdminAuditContext.attach(
                servletRequest,
                before,
                AdminAuditContext.sourceSummary(updated, request.rssUrl() != null));
        return ApiResponse.ok(updated);
    }

    /**
     * 落库前先真抓一次，确认地址能取到可解析的内容。
     *
     * <p>SSRF 校验只看地址本身，抓不通或不是 RSS 的地址仍会进生产并在 worker 里持续失败；
     * 这里提前把错误还给管理员，他还记得自己填的是什么。
     */
    private void requireFetchable(String rssUrl) {
        Map<String, Object> result = verifier.verifyUrl(rssUrl);
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "SOURCE_UNREACHABLE",
                    "抓取地址暂时取不到内容（" + result.get("errorCode") + "），请确认后再保存");
        }
    }

    /** 手动抓取一次并返回结果；只读，不入库、不推送。 */
    @PostMapping("/admin/v1/sources/{sourceId}/verify")
    @AdminAuditOperation(action = "验证动态源", resourceType = "source", resourceIdVariable = "sourceId")
    public ApiResponse<Map<String, Object>> verifySource(
            @PathVariable @Size(max = 128) String sourceId,
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity) {
        // 每次验证都会对上游站点发起真实请求，因此按管理员限流，防止管理端被当成压测入口。
        if (!rateLimiter.allow(
                "admin-verify", identity.adminId().toString(),
                rateLimits.subscriptionLimit(), rateLimits.window())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "手动抓取太频繁，请稍后再试");
        }
        return ApiResponse.ok(verifier.verify(sourceId));
    }

    @GetMapping("/admin/v1/idol-requests")
    public ApiResponse<Map<String, Object>> idolRequests(
            @RequestParam(required = false) @Size(max = 16) String status) {
        return ApiResponse.ok(store.listIdolRequests(status));
    }

    @PostMapping("/admin/v1/idol-requests/{requestId}/approve")
    @AdminAuditOperation(
            action = "通过 idol 申请",
            resourceType = "idol_request",
            resourceIdVariable = "requestId")
    public ApiResponse<Map<String, Object>> approveRequest(
            @PathVariable UUID requestId,
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity,
            @Valid @RequestBody ApproveRequest request,
            HttpServletRequest servletRequest) {
        Map<String, Object> updated = store.approveRequest(
                requestId, identity.adminId(), request.reviewNote(),
                request.idolId(), request.idolName(), request.bio());
        AdminAuditContext.attach(
                servletRequest, "状态：pending", AdminAuditContext.requestSummary(updated));
        return ApiResponse.ok(updated);
    }

    @PostMapping("/admin/v1/idol-requests/{requestId}/reject")
    @AdminAuditOperation(
            action = "驳回 idol 申请",
            resourceType = "idol_request",
            resourceIdVariable = "requestId")
    public ApiResponse<Map<String, Object>> rejectRequest(
            @PathVariable UUID requestId,
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity,
            @Valid @RequestBody RejectRequest request,
            HttpServletRequest servletRequest) {
        Map<String, Object> updated = store.rejectRequest(requestId, identity.adminId(), request.reviewNote());
        AdminAuditContext.attach(
                servletRequest, "状态：pending", AdminAuditContext.requestSummary(updated));
        return ApiResponse.ok(updated);
    }

    public record CreateIdolRequest(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 512) String avatar,
            @Size(max = 500) String bio,
            Boolean enabled) {
    }

    public record UpdateIdolRequest(
            @Size(max = 64) String name,
            @Size(max = 512) String avatar,
            @Size(max = 500) String bio,
            Boolean enabled,
            @NotNull @PositiveOrZero Integer version) {
    }

    public record CreateSourceRequest(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String idolId,
            @NotBlank @Size(max = 2048) String rssUrl,
            @NotBlank @Size(max = 128) String displayName,
            @Size(max = 32) String channel,
            Boolean enabled) {
    }

    public record UpdateSourceRequest(
            @Size(max = 2048) String rssUrl,
            @Size(max = 128) String displayName,
            @Size(max = 32) String channel,
            Boolean enabled,
            @NotNull @PositiveOrZero Integer version) {
    }

    public record ApproveRequest(
            @NotBlank @Size(max = 128) String idolId,
            @Size(max = 64) String idolName,
            @Size(max = 500) String bio,
            @Size(max = 500) String reviewNote) {
    }

    /** 驳回必须写理由：申请人会看到这条结论，没有理由的驳回等于没有回应。 */
    public record RejectRequest(@NotBlank @Size(max = 500) String reviewNote) {
    }
}
