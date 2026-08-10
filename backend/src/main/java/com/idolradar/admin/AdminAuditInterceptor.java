package com.idolradar.admin;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 集中审计所有已认证的管理端写请求，避免各 Controller 遗漏记录。 */
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AdminAuditRepository auditRepository;

    public AdminAuditInterceptor(AdminAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return;
        }
        Object attribute = request.getAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE);
        if (!(attribute instanceof AdminAuthService.Identity identity)) {
            // 未认证请求不会进入管理业务；也没有可信 admin_id 可写入审计外键。
            return;
        }
        String path = request.getRequestURI();
        if (path.length() > 128) {
            path = path.substring(0, 128);
        }
        int status = response.getStatus();
        auditRepository.record(new AdminAuditRepository.AuditEvent(
                identity.adminId(),
                "HTTP_" + request.getMethod(),
                "admin_route",
                path,
                MDC.get("requestId"),
                status,
                exception == null && status < 400));
    }
}
