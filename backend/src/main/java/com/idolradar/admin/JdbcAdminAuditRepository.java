package com.idolradar.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.idolradar.api.AppException;
import com.idolradar.api.CursorCodec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 管理端审计日志的追加与只读检索；管理 API 不提供修改或删除能力。 */
@Repository
public class JdbcAdminAuditRepository implements AdminAuditRepository {
    private static final int PAGE_SIZE = 50;
    private static final int DEFAULT_RANGE_HOURS = 24;
    private static final int MAX_RANGE_HOURS = 24 * 30;

    private final JdbcClient jdbc;
    private final CursorCodec cursorCodec;

    public JdbcAdminAuditRepository(JdbcClient jdbc, CursorCodec cursorCodec) {
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
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

    @Override
    public AuditPage find(AuditQuery query) {
        String search = blankToNull(query.search());
        Boolean succeeded = result(query.result());
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(range(query.rangeHours()));
        CursorBoundary cursor = cursor(query.cursor());

        String resultCondition = succeeded == null ? "" : " AND l.succeeded = :succeeded";
        String searchCondition = search == null
                ? ""
                : " AND (POSITION(lower(:search) IN lower(a.username)) > 0 OR l.request_id = :search)";
        String cursorCondition = cursor == null
                ? ""
                : " AND (l.created_at, l.id) < (:cursorTime, :cursorId)";

        /*
         * 每个管理员先按 (admin_id, created_at DESC) 索引取一页候选，再做全局归并。
         * 管理员数量远小于日志量，因此日志增长不会退化成全表排序。
         */
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT page.id, a.username AS operator, page.action, page.resource_type,
                               page.resource_id, page.request_id, page.http_status,
                               page.succeeded, page.created_at
                        FROM idr_admin_account a
                        CROSS JOIN LATERAL (
                          SELECT l.id, l.action, l.resource_type, l.resource_id, l.request_id,
                                 CASE WHEN (l.detail ->> 'httpStatus') ~ '^[1-5][0-9]{2}$'
                                      THEN (l.detail ->> 'httpStatus')::integer END AS http_status,
                                 l.succeeded, l.created_at
                          FROM idr_admin_audit_log l
                          WHERE l.admin_id = a.id
                            AND l.created_at >= :since
                        """ + resultCondition + searchCondition + cursorCondition + """
                          ORDER BY l.created_at DESC, l.id DESC
                          LIMIT :limit
                        ) page
                        ORDER BY page.created_at DESC, page.id DESC
                        LIMIT :limit
                        """)
                .param("since", since)
                .param("limit", PAGE_SIZE + 1);
        if (succeeded != null) {
            statement = statement.param("succeeded", succeeded);
        }
        if (search != null) {
            statement = statement.param("search", search);
        }
        if (cursor != null) {
            statement = statement
                    .param("cursorTime", cursor.createdAt())
                    .param("cursorId", cursor.id());
        }

        List<AuditEntry> rows = statement.query((resultSet, rowNumber) -> new AuditEntry(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("operator"),
                        resultSet.getString("action"),
                        resultSet.getString("resource_type"),
                        resultSet.getString("resource_id"),
                        resultSet.getString("request_id"),
                        resultSet.getObject("http_status", Integer.class),
                        resultSet.getBoolean("succeeded"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<AuditEntry> audits = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        String nextCursor = hasMore && !audits.isEmpty()
                ? cursorCodec.encode(audits.getLast().createdAt(), audits.getLast().id().toString())
                : null;
        return new AuditPage(audits, hasMore, nextCursor);
    }

    private CursorBoundary cursor(String value) {
        CursorCodec.Cursor decoded = cursorCodec.decode(value);
        if (decoded == null) {
            return null;
        }
        try {
            return new CursorBoundary(
                    OffsetDateTime.ofInstant(decoded.publishedAt(), ZoneOffset.UTC),
                    UUID.fromString(decoded.id()));
        } catch (IllegalArgumentException error) {
            // 通用游标只保证 ID 是安全字符串；审计游标还必须是 UUID，拒绝交给数据库隐式转换。
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页参数无效");
        }
    }

    private static Duration range(Integer rangeHours) {
        int hours = rangeHours == null ? DEFAULT_RANGE_HOURS : rangeHours;
        if (hours < 1 || hours > MAX_RANGE_HOURS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "时间区间必须在 1 到 720 小时之间");
        }
        return Duration.ofHours(hours);
    }

    private static Boolean result(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "success" -> true;
            case "failed" -> false;
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "无效的审计结果筛选值");
        };
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record CursorBoundary(OffsetDateTime createdAt, UUID id) {
    }
}
