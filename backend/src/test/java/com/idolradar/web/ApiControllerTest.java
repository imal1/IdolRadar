package com.idolradar.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.idolradar.api.IdolRadarStore;
import com.idolradar.auth.AuthInterceptor;
import com.idolradar.auth.AuthService;
import com.idolradar.config.BackendProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class ApiControllerTest {
    private AuthService auth;
    private IdolRadarStore store;
    private MockMvc protectedMvc;
    private MockMvc publicMvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        store = mock(IdolRadarStore.class);
        ApiController controller = new ApiController(
                auth, store, new BackendProperties(Duration.ofDays(30), "template-test"));
        ApiExceptionHandler advice = new ApiExceptionHandler();
        JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder()
                        .findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build());
        publicMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setMessageConverters(json)
                .build();
        protectedMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setMessageConverters(json)
                .addInterceptors(new AuthInterceptor(auth))
                .build();
    }

    @Test
    void loginValidatesInputAndUsesEnvelope() throws Exception {
        when(auth.login("wx-code")).thenReturn(new AuthService.LoginResult(
                "a".repeat(43), Instant.parse("2026-08-01T00:00:00Z")));

        publicMvc.perform(post("/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        publicMvc.perform(post("/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        publicMvc.perform(post("/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.token").value("a".repeat(43)));
    }

    @Test
    void protectedEndpointRejectsMissingBearerToken() throws Exception {
        when(auth.authenticate(null)).thenThrow(new com.idolradar.api.AppException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "登录已失效，请重新进入小程序"));

        protectedMvc.perform(get("/v1/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedRoutesPreserveClientContract() throws Exception {
        AuthService.Identity identity = new AuthService.Identity(
                UUID.fromString("815bd2ca-cf30-4b4e-8a91-5e90f8fe8750"),
                "openid-1",
                Instant.now().plusSeconds(3600));
        when(auth.authenticate("Bearer valid-token")).thenReturn(identity);
        when(store.getFeed("openid-1", "cursor-1"))
                .thenReturn(Map.of("posts", List.of(), "hasMore", false, "nextCursor", "cursor-1"));
        when(store.setIdol("openid-1", "idol-1"))
                .thenReturn(Map.of("idol", Map.of("_id", "idol-1")));
        when(store.recordSubscription("openid-1", true, "template-test"))
                .thenReturn(Map.of("subscribeQuota", 1));

        protectedMvc.perform(get("/v1/feed")
                        .header("Authorization", "Bearer valid-token")
                        .param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value("cursor-1"));
        protectedMvc.perform(put("/v1/me/idol")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idolId\":\"idol-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idol._id").value("idol-1"));
        protectedMvc.perform(post("/v1/me/subscriptions")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribeQuota").value(1));

        verify(store).getFeed("openid-1", "cursor-1");
        verify(store).setIdol("openid-1", "idol-1");
        verify(store).recordSubscription("openid-1", true, "template-test");
    }

    @Test
    void malformedJsonKeepsErrorEnvelope() throws Exception {
        publicMvc.perform(post("/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
