package com.idolradar.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证受保护请求，并向下游处理器附加可信身份。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String IDENTITY_ATTRIBUTE = "idolradar.identity";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthService.Identity identity = authService.authenticate(request.getHeader("Authorization"));
        // 下游只读取服务端生成的身份，绝不采用客户端提供的 openId。
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }
}
