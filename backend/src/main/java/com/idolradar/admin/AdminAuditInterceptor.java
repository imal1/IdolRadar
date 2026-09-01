package com.idolradar.admin;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
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
        AuditDescriptor descriptor = descriptor(request, handler);
        AdminAuditContext.Details details = AdminAuditContext.details(request);
        int status = response.getStatus();
        auditRepository.record(new AdminAuditRepository.AuditEvent(
                identity.adminId(),
                descriptor.action(),
                descriptor.resourceType(),
                details.resourceId() == null ? descriptor.resourceId() : truncate(details.resourceId()),
                MDC.get("requestId"),
                status,
                exception == null && status < 400,
                details.beforeSummary(),
                details.afterSummary()));
    }

    private static AuditDescriptor descriptor(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod method) {
            AdminAuditOperation operation = method.getMethodAnnotation(AdminAuditOperation.class);
            if (operation != null) {
                return new AuditDescriptor(
                        operation.action(),
                        operation.resourceType(),
                        pathVariable(request, operation.resourceIdVariable()));
            }
        }
        return new AuditDescriptor(
                "HTTP_" + request.getMethod(), "admin_route", truncate(request.getRequestURI()));
    }

    private static String pathVariable(HttpServletRequest request, String name) {
        if (name.isBlank()) {
            return null;
        }
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attribute instanceof java.util.Map<?, ?> variables) {
            Object value = variables.get(name);
            return value == null ? null : truncate(String.valueOf(value));
        }
        return null;
    }

    private static String truncate(String value) {
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    private record AuditDescriptor(String action, String resourceType, String resourceId) {
    }
}
