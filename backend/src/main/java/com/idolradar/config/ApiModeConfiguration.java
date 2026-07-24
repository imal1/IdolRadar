package com.idolradar.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API 模式启动门禁：缺少微信凭据或使用不安全地址时直接拒绝启动。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class ApiModeConfiguration {

    @Bean
    Runnable apiModeConfigurationGuard(WechatProperties wechatProperties) {
        // Bean 创建发生在服务接流量前，避免错误配置延迟到首次登录才暴露。
        wechatProperties.validateForApi();
        return () -> { };
    }
}
