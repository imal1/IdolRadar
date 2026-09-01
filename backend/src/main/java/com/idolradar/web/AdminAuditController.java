package com.idolradar.web;

import com.idolradar.admin.AdminAuditRepository;
import com.idolradar.admin.AdminAuditRepository.AuditPage;
import com.idolradar.admin.AdminAuditRepository.AuditQuery;
import com.idolradar.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理审计日志只读入口；鉴权由统一的管理端拦截器执行。 */
@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class AdminAuditController {
    private final AdminAuditRepository repository;

    public AdminAuditController(AdminAuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/admin/v1/audit-logs")
    public ApiResponse<AuditPage> auditLogs(
            @RequestParam(required = false) @Size(max = 64) String search,
            @RequestParam(required = false) @Size(max = 16) String result,
            @RequestParam(required = false) @Positive Integer rangeHours,
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        return ApiResponse.ok(repository.find(new AuditQuery(search, result, rangeHours, cursor)));
    }
}
