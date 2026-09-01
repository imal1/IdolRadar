package com.idolradar.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理端审计存取接口；只接收和返回经过筛选的元数据，不接触请求体或凭据。 */
public interface AdminAuditRepository {
    void record(AuditEvent event);

    /** 只读查询使用键集分页，避免日志增长后 offset 扫描越来越慢。 */
    AuditPage find(AuditQuery query);

    record AuditEvent(
            UUID adminId,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            int httpStatus,
            boolean succeeded) {
    }

    record AuditQuery(String search, String result, Integer rangeHours, String cursor) {
    }

    /** API 白名单字段；故意不暴露 detail、adminId 或任何身份凭据。 */
    record AuditEntry(
            UUID id,
            String operator,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            Integer httpStatus,
            boolean succeeded,
            Instant createdAt) {
    }

    record AuditPage(List<AuditEntry> audits, boolean hasMore, String nextCursor) {
    }
}
