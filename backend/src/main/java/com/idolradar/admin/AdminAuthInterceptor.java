package com.idolradar.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证管理端请求，并附加只能由服务端生成的管理员身份。 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    public static final String IDENTITY_ATTRIBUTE = "idolradar.admin.identity";

    private final AdminAuthService authService;

    public AdminAuthInterceptor(AdminAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminAuthService.Identity identity = authService.authenticate(request.getHeader("Authorization"));
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }
}
