package com.idolradar.auth;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idolradar.api.AppException;
import com.idolradar.config.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** WeChat code2Session 的服务端适配器，并提供稳定的本地错误分类。 */
@Component
public class WechatClient implements WechatGateway {
    private static final Set<Integer> INVALID_LOGIN_CODES = Set.of(40029, 40163, 40226);
    private static final Logger log = LoggerFactory.getLogger(WechatClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatProperties properties;

    public WechatClient(
            @Qualifier("wechatRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            WechatProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 交换短期 code；刻意不暴露或持久化 session_key。 */
    @Override
    public WechatIdentity exchangeCode(String code) {
        properties.validateForApi();
        WechatSessionPayload payload;
        try {
            // App 凭证只保留在服务端边界，API 请求数据不得提供或覆盖。
            String responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            // 微信可能以 text/plain 返回 JSON；显式解析，避免依赖响应 Content-Type。
            payload = responseBody == null ? null : objectMapper.readValue(responseBody, WechatSessionPayload.class);
        } catch (RestClientException | JsonProcessingException error) {
            // URL 包含 AppSecret，日志只记录异常类型，禁止输出异常消息或请求地址。
            log.warn("微信 code2Session 请求失败，异常类型：{}", error.getClass().getSimpleName());
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WECHAT_UNAVAILABLE",
                    "微信服务暂时不可用",
                    error);
        }

        if (payload == null) {
            throw unavailable();
        }
        int errorCode = payload.errcode() == null ? 0 : payload.errcode();
        // 区分调用方错误、上游限流和可重试的 WeChat 可用性故障。
        if (errorCode != 0) {
            // errcode 不含凭据，可用于定位 AppID、AppSecret、登录 code 或微信限流问题。
            log.warn("微信 code2Session 返回失败，errcode={}", errorCode);
            if (errorCode == -1) {
                throw unavailable();
            }
            if (errorCode == 45011) {
                throw new AppException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "WECHAT_LOGIN_RATE_LIMITED",
                        "登录请求太频繁，请稍后重试");
            }
            if (INVALID_LOGIN_CODES.contains(errorCode)) {
                throw loginFailed();
            }
            throw unavailable();
        }
        if (payload.openid() == null || payload.openid().isBlank() || payload.openid().length() > 128) {
            throw loginFailed();
        }
        return new WechatIdentity(payload.openid(), payload.unionid());
    }

    private static AppException unavailable() {
        return new AppException(
                HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE", "微信服务暂时不可用");
    }

    private static AppException loginFailed() {
        return new AppException(HttpStatus.UNAUTHORIZED, "WECHAT_LOGIN_FAILED", "微信登录失败，请重试");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WechatSessionPayload(String openid, String unionid, Integer errcode, String errmsg) {
    }
}
