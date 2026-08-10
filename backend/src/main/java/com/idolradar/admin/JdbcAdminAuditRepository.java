package com.idolradar.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 将管理端写请求追加到不可由管理接口修改的 PostgreSQL 审计表。 */
@Repository
public class JdbcAdminAuditRepository implements AdminAuditRepository {
    private final JdbcClient jdbc;

    public JdbcAdminAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEvent event) {
        // detail 只记录路由与状态，禁止把密码、token、OpenID 或请求体写入审计表。
        jdbc.sql("INSERT INTO idr_admin_audit_log "
                        + "(admin_id, action, resource_type, resource_id, request_id, detail, succeeded) "
                        + "VALUES (:adminId, :action, :resourceType, :resourceId, :requestId, "
                        + "jsonb_build_object('httpStatus', :httpStatus), :succeeded)")
                .param("adminId", event.adminId())
                .param("action", event.action())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("requestId", event.requestId())
                .param("httpStatus", event.httpStatus())
                .param("succeeded", event.succeeded())
                .update();
    }
}
