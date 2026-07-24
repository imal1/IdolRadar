package com.idolradar.config;

import java.util.Objects;

import com.idolradar.worker.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Worker 模式启动门禁，统一校验抓取配置与微信发送身份。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class WorkerModeConfiguration {

    /** 阻止 Worker 使用与 API 不一致的身份发送订阅消息。 */
    @Bean
    Runnable workerModeConfigurationGuard(
            WorkerProperties worker,
            WechatProperties wechat,
            BackendProperties backend) {
        worker.validateForRun();
        wechat.validateForApi();
        if (!Objects.equals(worker.getWechatAppId(), wechat.appId())
                || !Objects.equals(worker.getWechatAppSecret(), wechat.appSecret())
                || !Objects.equals(worker.getWechatApiBaseUrl().normalize(), wechat.apiBaseUrl().normalize())
                || !Objects.equals(worker.getSubscribeTemplateId(), backend.subscribeTemplateId())) {
            throw new IllegalStateException("API and worker WeChat configuration must be identical");
        }
        return () -> { };
    }
}
