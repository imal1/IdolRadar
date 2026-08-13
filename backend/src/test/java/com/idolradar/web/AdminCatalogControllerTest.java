package com.idolradar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.idolradar.admin.AdminAuditInterceptor;
import com.idolradar.admin.AdminAuditRepository;
import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.admin.AdminCatalogStore;
import com.idolradar.admin.SourceVerifier;
import com.idolradar.api.AppException;
import com.idolradar.config.RateLimitProperties;
import com.idolradar.worker.FeedException;
import com.idolradar.worker.FeedUrlGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class AdminCatalogControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("c8b2df63-e75f-4c8e-af03-a56bdd8e15b5");
    private static final UUID REQUEST_ID = UUID.fromString("2b0d5f2f-6a24-4c8a-9d18-7f5b0d9c1f11");
    private static final String TOKEN = "a".repeat(43);

    private AdminCatalogStore store;
    private SourceVerifier verifier;
    private FeedUrlGuard urlGuard;
    private AdminAuthService auth;
    private AdminAuditRepository audit;
    private DistributedRateLimiter rateLimiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(AdminCatalogStore.class);
        verifier = mock(SourceVerifier.class);
        urlGuard = mock(FeedUrlGuard.class);
        auth = mock(AdminAuthService.class);
        audit = mock(AdminAuditRepository.class);
        rateLimiter = mock(DistributedRateLimiter.class);
        when(rateLimiter.allow(anyString(), anyString(), anyInt(), any())).thenReturn(true);
        when(auth.authenticate("Bearer " + TOKEN)).thenReturn(new AdminAuthService.Identity(
                ADMIN_ID, "ops-admin", "f".repeat(64), Instant.now().plusSeconds(3600)));
        when(auth.authenticate(null)).thenThrow(new AppException(
                HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理员登录已失效，请重新登录"));

        JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder()
                        .findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build());
        RateLimitProperties rateLimits = new RateLimitProperties(120, 20, 12, 1200, java.time.Duration.ofMinutes(1));
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminCatalogController(store, verifier, urlGuard, rateLimiter, rateLimits))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(json)
                .addInterceptors(new AdminAuthInterceptor(auth), new AdminAuditInterceptor(audit))
                .build();
    }

    @Test
    void sourceHealthIsAuthenticatedAndPassesFiltersThrough() throws Exception {
        when(store.listSourceHealth("idol-1", "failed"))
                .thenReturn(Map.of("sources", java.util.List.of(), "summary", Map.of("failed", 2)));

        mvc.perform(get("/admin/v1/sources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
        mvc.perform(get("/admin/v1/sources")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("idolId", "idol-1")
                        .param("health", "failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.failed").value(2));
    }

    @Test
    void creatingSourceValidatesFetchUrlBeforeTouchingTheDatabase() throws Exception {
        when(urlGuard.validateUrl("http://127.0.0.1/feed.xml"))
                .thenThrow(new FeedException("UNSAFE_FEED_URL", "RSS URL 不能指向私有网络"));

        mvc.perform(post("/admin/v1/sources")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"source_new","idolId":"idol-1",
                                 "rssUrl":"http://127.0.0.1/feed.xml","displayName":"新源"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSAFE_FEED_URL"));

        verify(store, never()).createSource(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
    }

    @Test
    void creatingSourceRejectsUrlsThatCannotBeFetched() throws Exception {
        when(verifier.verifyUrl("https://example.com/dead.xml"))
                .thenReturn(Map.of("ok", false, "errorCode", "UPSTREAM_STATUS_404"));

        mvc.perform(post("/admin/v1/sources")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"source_new","idolId":"idol-1",
                                 "rssUrl":"https://example.com/dead.xml","displayName":"新源"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SOURCE_UNREACHABLE"));

        verify(store, never()).createSource(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
    }

    @Test
    void updatingIdolCarriesOptimisticVersionAndIsAudited() throws Exception {
        when(store.updateIdol("idol-1", null, null, null, false, 3))
                .thenReturn(Map.of("id", "idol-1", "enabled", false, "version", 4));

        mvc.perform(patch("/admin/v1/idols/{idolId}", "idol-1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4));

        verify(store).updateIdol("idol-1", null, null, null, false, 3);
        verify(audit).record(any(AdminAuditRepository.AuditEvent.class));
    }

    @Test
    void manualFetchReturnsResultAndReportsThatNothingWasPersisted() throws Exception {
        when(verifier.verify("source-1")).thenReturn(Map.of(
                "ok", true, "itemCount", 5, "newCount", 2, "persisted", false));

        mvc.perform(post("/admin/v1/sources/{sourceId}/verify", "source-1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newCount").value(2))
                .andExpect(jsonPath("$.data.persisted").value(false));
    }

    @Test
    void manualFetchIsRateLimitedPerAdminBeforeReachingUpstream() throws Exception {
        when(rateLimiter.allow(eq("admin-verify"), eq(ADMIN_ID.toString()), anyInt(), any()))
                .thenReturn(false);

        mvc.perform(post("/admin/v1/sources/{sourceId}/verify", "source-1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(verifier, never()).verify(anyString());
    }

    @Test
    void reviewDecisionsRecordTheActingAdminAndRejectionNeedsReason() throws Exception {
        when(store.approveRequest(eq(REQUEST_ID), eq(ADMIN_ID), any(), eq("idol_new"), any(), any()))
                .thenReturn(Map.of("status", "approved", "approvedIdolId", "idol_new"));

        mvc.perform(post("/admin/v1/idol-requests/{requestId}/approve", REQUEST_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idolId\":\"idol_new\",\"idolName\":\"新偶像\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvedIdolId").value("idol_new"));
        mvc.perform(post("/admin/v1/idol-requests/{requestId}/reject", REQUEST_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNote\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(store).approveRequest(REQUEST_ID, ADMIN_ID, null, "idol_new", "新偶像", null);
        verify(store, never()).rejectRequest(any(), any(), any());
    }
}
