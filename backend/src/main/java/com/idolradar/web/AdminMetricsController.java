package com.idolradar.web;

import java.util.Map;

import com.idolradar.admin.AdminMetricsStore;
import com.idolradar.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 核心指标面板：只读聚合，不返回任何单个用户的字段。
 *
 * <p>鉴权由 AdminAuthInterceptor 统一处理，因此这里没有任何鉴权代码。
 */
@RestController
public class AdminMetricsController {
    private final AdminMetricsStore store;

    public AdminMetricsController(AdminMetricsStore store) {
        this.store = store;
    }

    @GetMapping("/admin/v1/metrics")
    public ApiResponse<Map<String, Object>> metrics(
            @RequestParam(required = false) @Positive Integer rangeDays) {
        return ApiResponse.ok(store.coreMetrics(rangeDays));
    }
}
