package com.idolradar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.idolradar.admin.AdminAuditRepository;
import com.idolradar.admin.AdminAuditRepository.AuditEntry;
import com.idolradar.admin.AdminAuditRepository.AuditPage;
import com.idolradar.admin.AdminAuditRepository.AuditQuery;
import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.api.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AdminAuditControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("c8b2df63-e75f-4c8e-af03-a56bdd8e15b5");
    private static final String TOKEN = "a".repeat(43);

    private AdminAuditRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(AdminAuditRepository.class);
        AdminAuthService auth = mock(AdminAuthService.class);
        when(auth.authenticate("Bearer " + TOKEN)).thenReturn(new AdminAuthService.Identity(
                ADMIN_ID, "ops-admin", "f".repeat(64), Instant.now().plusSeconds(3600)));
        when(auth.authenticate(null)).thenThrow(new AppException(
                HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理员登录已失效，请重新登录"));

        mvc = MockMvcBuilders.standaloneSetup(new AdminAuditController(repository))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().findAndAddModules().build()))
                .addInterceptors(new AdminAuthInterceptor(auth))
                .build();
    }

    @Test
    void authenticatedQueryPassesServerFiltersAndReturnsOnlySafeFields() throws Exception {
        UUID auditId = UUID.fromString("f720d0d8-383e-4d72-beb2-d34651369d78");
        when(repository.find(any())).thenReturn(new AuditPage(List.of(new AuditEntry(
                auditId,
                "ops-admin",
                "HTTP_PATCH",
                "admin_route",
                "/admin/v1/idols/idol-1",
                "request-67",
                200,
                true,
                "名称：旧名；状态：启用",
                "名称：新名；状态：停用",
                Instant.parse("2026-09-01T01:02:03Z"))), false, null));

        mvc.perform(get("/admin/v1/audit-logs")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("search", "ops-admin")
                        .param("result", "success")
                        .param("rangeHours", "168")
                        .param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audits[0].operator").value("ops-admin"))
                .andExpect(jsonPath("$.data.audits[0].requestId").value("request-67"))
                .andExpect(jsonPath("$.data.audits[0].httpStatus").value(200))
                .andExpect(jsonPath("$.data.audits[0].succeeded").value(true))
                .andExpect(jsonPath("$.data.audits[0].beforeSummary").value("名称：旧名；状态：启用"))
                .andExpect(jsonPath("$.data.audits[0].afterSummary").value("名称：新名；状态：停用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("detail"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("openid"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(repository).find(query.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                new AuditQuery("ops-admin", "success", 168, "cursor-1"), query.getValue());
    }

    @Test
    void emptyQueryReturnsAnEmptyPage() throws Exception {
        when(repository.find(any())).thenReturn(new AuditPage(List.of(), false, null));

        mvc.perform(get("/admin/v1/audit-logs")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("search", "missing-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audits").isEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void queryRequiresAnAdminSession() throws Exception {
        mvc.perform(get("/admin/v1/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }
}
