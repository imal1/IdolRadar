package com.idolradar.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 微信开放接口专用 HTTP 客户端；连接和读取共用受控超时。 */
@Configuration(proxyBeanMethods = false)
public class WechatHttpConfiguration {

    @Bean
    @Qualifier("wechatRestClient")
    RestClient wechatRestClient(WechatProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        // 固定 base URL，调用方只提交微信 API 相对路径，减少地址拼接分歧。
        return RestClient.builder()
                .baseUrl(properties.apiBaseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
