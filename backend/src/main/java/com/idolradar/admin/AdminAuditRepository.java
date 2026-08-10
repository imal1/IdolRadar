package com.idolradar.admin;

import java.util.UUID;

/** 管理端写请求审计接口；只接收经过筛选的元数据，不接收请求体或凭据。 */
public interface AdminAuditRepository {
    void record(AuditEvent event);

    record AuditEvent(
            UUID adminId,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            int httpStatus,
            boolean succeeded) {
    }
}
