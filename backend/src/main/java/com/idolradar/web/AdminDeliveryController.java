package com.idolradar.web;

import java.util.Map;

import com.idolradar.admin.AdminDeliveryStore;
import com.idolradar.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推送投递看板：只读，且只回传聚合与非身份字段。
 *
 * <p>鉴权由 AdminAuthInterceptor 统一处理，因此这里没有任何鉴权代码。
 */
@RestController
public class AdminDeliveryController {
    private final AdminDeliveryStore store;

    public AdminDeliveryController(AdminDeliveryStore store) {
        this.store = store;
    }

    @GetMapping("/admin/v1/deliveries")
    public ApiResponse<Map<String, Object>> deliveries(
            @RequestParam(required = false) @Size(max = 128) String idolId,
            @RequestParam(required = false) @Size(max = 16) String status,
            @RequestParam(required = false) @Positive Integer rangeHours) {
        return ApiResponse.ok(store.listDeliveries(idolId, status, rangeHours));
    }
}
