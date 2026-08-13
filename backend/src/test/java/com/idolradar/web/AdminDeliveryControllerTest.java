package com.idolradar.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.admin.AdminDeliveryStore;
import com.idolradar.api.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AdminDeliveryControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("c8b2df63-e75f-4c8e-af03-a56bdd8e15b5");
    private static final String TOKEN = "a".repeat(43);

    private AdminDeliveryStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(AdminDeliveryStore.class);
        AdminAuthService auth = mock(AdminAuthService.class);
        when(auth.authenticate("Bearer " + TOKEN)).thenReturn(new AdminAuthService.Identity(
                ADMIN_ID, "ops-admin", "f".repeat(64), Instant.now().plusSeconds(3600)));
        when(auth.authenticate(null)).thenThrow(new AppException(
                HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理员登录已失效，请重新登录"));

        mvc = MockMvcBuilders.standaloneSetup(new AdminDeliveryController(store))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().findAndAddModules().build()))
                .addInterceptors(new AdminAuthInterceptor(auth))
                .build();
    }

    @Test
    void deliveryBoardRequiresAdminSessionAndPassesFiltersThrough() throws Exception {
        when(store.listDeliveries("idol-1", "stuck", 168)).thenReturn(Map.of(
                "deliveries", List.of(),
                "summary", Map.of("stuck", 3),
                "failures", List.of(),
                "queue", Map.of("backlog", 7)));

        mvc.perform(get("/admin/v1/deliveries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
        mvc.perform(get("/admin/v1/deliveries")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("idolId", "idol-1")
                        .param("status", "stuck")
                        .param("rangeHours", "168"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.stuck").value(3))
                .andExpect(jsonPath("$.data.queue.backlog").value(7));

        verify(store).listDeliveries("idol-1", "stuck", 168);
    }

    /** 看板是只读接口，鉴权失败之外的错误一律由 store 抛业务异常，控制器不做兜底转译。 */
    @Test
    void invalidFilterIsReportedAsBadRequest() throws Exception {
        when(store.listDeliveries(null, "nonsense", null)).thenThrow(
                new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "无效的投递状态筛选值"));

        mvc.perform(get("/admin/v1/deliveries")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("status", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FILTER"));
    }
}
