package com.idolradar.config;

import java.util.List;

import com.idolradar.auth.AuthInterceptor;
import com.idolradar.web.PreAuthRateLimitInterceptor;
import com.idolradar.web.RateLimitInterceptor;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** API 拦截器顺序与 JSON 输入策略的集中配置。 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfiguration implements WebMvcConfigurer {
    private static final List<String> PROTECTED_ENDPOINTS = List.of(
            "/v1/me/bootstrap",
            "/v1/home",
            "/v1/feed",
            "/v1/idols",
            "/v1/me/idol",
            "/v1/me/subscriptions");

    private final AuthInterceptor authInterceptor;
    private final PreAuthRateLimitInterceptor preAuthRateLimitInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfiguration(
            AuthInterceptor authInterceptor,
            PreAuthRateLimitInterceptor preAuthRateLimitInterceptor,
            RateLimitInterceptor rateLimitInterceptor) {
        this.authInterceptor = authInterceptor;
        this.preAuthRateLimitInterceptor = preAuthRateLimitInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Bean
    JsonMapperBuilderCustomizer rejectUnknownJsonFields() {
        // 客户端字段拼错时立即报错，避免“请求成功但字段被静默忽略”。
        return builder -> builder.enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序不可交换：先按 IP 拦截匿名洪泛，再认证，最后按用户做细粒度限流。
        registry.addInterceptor(preAuthRateLimitInterceptor)
                .addPathPatterns("/v1/auth/wechat/login")
                .addPathPatterns(PROTECTED_ENDPOINTS)
                .order(0);
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(PROTECTED_ENDPOINTS)
                .order(1);
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(PROTECTED_ENDPOINTS)
                .order(2);
    }
}
