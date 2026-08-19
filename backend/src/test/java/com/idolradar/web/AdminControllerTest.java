package com.idolradar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.idolradar.admin.AdminAuditInterceptor;
import com.idolradar.admin.AdminAuditRepository;
import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.api.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class AdminControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("c8b2df63-e75f-4c8e-af03-a56bdd8e15b5");

    private AdminAuthService auth;
    private AdminAuditRepository audit;
    private MockMvc publicMvc;
    private MockMvc protectedMvc;

    @BeforeEach
    void setUp() {
        auth = mock(AdminAuthService.class);
        audit = mock(AdminAuditRepository.class);
        AdminController controller = new AdminController(auth);
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
                .addInterceptors(new AdminAuthInterceptor(auth), new AdminAuditInterceptor(audit))
                .build();
    }

    @Test
    void loginUsesAdminCredentialsAndNeverReturnsPasswordHash() throws Exception {
        when(auth.login("ops-admin", "StrongAdmin!2026")).thenReturn(new AdminAuthService.LoginResult(
                "a".repeat(43),
                Instant.parse("2026-08-11T00:00:00Z"),
                new AdminAuthService.CurrentAdmin(ADMIN_ID, "ops-admin")));

        publicMvc.perform(post("/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"StrongAdmin!2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("a".repeat(43)))
                .andExpect(jsonPath("$.data.admin.username").value("ops-admin"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void shortPasswordReachesCredentialCheckInsteadOfBeingRejectedAsBadRequest() throws Exception {
        when(auth.login("ops-admin", "abc123")).thenThrow(
                new AppException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理员登录已失效，请重新登录"));

        // 口令下限只在创建时校验；登录必须走到验密并返回 401，而不是 400 INVALID_INPUT。
        publicMvc.perform(post("/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"abc123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
        verify(auth).login("ops-admin", "abc123");
    }

    @Test
    void missingOrWechatUserTokenCannotReadAdminRoute() throws Exception {
        when(auth.authenticate(null)).thenThrow(unauthorized());
        String userToken = "u".repeat(43);
        when(auth.authenticate("Bearer " + userToken)).thenThrow(unauthorized());

        protectedMvc.perform(get("/admin/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
        protectedMvc.perform(get("/admin/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }

    @Test
    void authenticatedWriteIsAuditedAndCanRevokeAdmin() throws Exception {
        AdminAuthService.Identity identity = identity();
        when(auth.authenticate("Bearer " + "a".repeat(43))).thenReturn(identity);

        protectedMvc.perform(post("/admin/v1/admins/{adminId}/revoke", ADMIN_ID)
                        .header("Authorization", "Bearer " + "a".repeat(43)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));

        verify(auth).revokeAccess(ADMIN_ID);
        ArgumentCaptor<AdminAuditRepository.AuditEvent> event =
                ArgumentCaptor.forClass(AdminAuditRepository.AuditEvent.class);
        verify(audit).record(event.capture());
        org.junit.jupiter.api.Assertions.assertEquals(ADMIN_ID, event.getValue().adminId());
        org.junit.jupiter.api.Assertions.assertEquals("HTTP_POST", event.getValue().action());
        org.junit.jupiter.api.Assertions.assertTrue(event.getValue().succeeded());
    }

    @Test
    void logoutRevokesCurrentSession() throws Exception {
        AdminAuthService.Identity identity = identity();
        when(auth.authenticate("Bearer " + "a".repeat(43))).thenReturn(identity);

        protectedMvc.perform(post("/admin/v1/auth/logout")
                        .header("Authorization", "Bearer " + "a".repeat(43)))
                .andExpect(status().isOk());

        verify(auth).logout(identity);
        verify(audit).record(any(AdminAuditRepository.AuditEvent.class));
    }

    private static AdminAuthService.Identity identity() {
        return new AdminAuthService.Identity(
                ADMIN_ID, "ops-admin", "f".repeat(64), Instant.now().plusSeconds(3600));
    }

    private static AppException unauthorized() {
        return new AppException(
                HttpStatus.UNAUTHORIZED,
                "ADMIN_UNAUTHORIZED",
                "管理员登录已失效，请重新登录");
    }
}
