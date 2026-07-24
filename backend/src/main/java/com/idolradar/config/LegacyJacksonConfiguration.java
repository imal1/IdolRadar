package com.idolradar.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为仍使用 Jackson 2 API 的种子导入和命令结果输出提供独立 Mapper。
 * HTTP JSON 继续由 Spring Boot 4 的 Jackson 3 配置管理，二者不混用。
 */
@Configuration(proxyBeanMethods = false)
public class LegacyJacksonConfiguration {

    @Bean
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
