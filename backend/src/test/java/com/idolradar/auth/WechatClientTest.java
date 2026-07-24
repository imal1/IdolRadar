package com.idolradar.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idolradar.api.AppException;
import com.idolradar.config.WechatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WechatClientTest {

    @Test
    void exchangeCodeParsesTextPlainJsonWithoutReturningSessionKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.weixin.qq.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("appid", "wx-test-app"))
                .andExpect(queryParam("secret", "server-only-secret"))
                .andExpect(queryParam("js_code", "valid-code"))
                .andRespond(withSuccess(
                        "{\"openid\":\"openid-1\",\"session_key\":\"must-not-leak\"}",
                        MediaType.TEXT_PLAIN));
        WechatClient client = new WechatClient(builder.build(), new ObjectMapper(), properties());

        WechatGateway.WechatIdentity identity = client.exchangeCode("valid-code");

        assertEquals("openid-1", identity.openId());
        assertNull(identity.unionId());
        server.verify();
    }

    @Test
    void exchangeCodeClassifiesWechatErrors() {
        assertWechatError(40029, "WECHAT_LOGIN_FAILED");
        assertWechatError(40163, "WECHAT_LOGIN_FAILED");
        assertWechatError(40226, "WECHAT_LOGIN_FAILED");
        assertWechatError(45011, "WECHAT_LOGIN_RATE_LIMITED");
        assertWechatError(-1, "WECHAT_UNAVAILABLE");
        assertWechatError(99999, "WECHAT_UNAVAILABLE");
    }

    @Test
    void rejectsPlainHttpWechatEndpointOutsideLoopbackTests() {
        WechatProperties unsafe = new WechatProperties(
                "wx-test-app",
                "server-only-secret",
                URI.create("http://api.example.com"),
                Duration.ofSeconds(10));
        assertThrows(IllegalStateException.class, unsafe::validateForApi);

        WechatProperties localTest = new WechatProperties(
                "wx-test-app",
                "server-only-secret",
                URI.create("http://127.0.0.1:18080"),
                Duration.ofSeconds(10));
        localTest.validateForApi();
    }

    private static void assertWechatError(int wechatCode, String expectedCode) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.weixin.qq.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess(
                        "{\"errcode\":" + wechatCode + ",\"errmsg\":\"upstream detail\"}",
                        MediaType.APPLICATION_JSON));
        WechatClient client = new WechatClient(builder.build(), new ObjectMapper(), properties());

        AppException error = assertThrows(AppException.class, () -> client.exchangeCode("valid-code"));

        assertEquals(expectedCode, error.code());
        server.verify();
    }

    private static WechatProperties properties() {
        return new WechatProperties(
                "wx-test-app",
                "server-only-secret",
                URI.create("https://api.weixin.qq.com"),
                Duration.ofSeconds(10));
    }
}
