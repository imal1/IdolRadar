package com.idolradar.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.idolradar.api.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端目录与运营数据的持久化边界：源健康度、idol/源维护、idol 申请审核。
 *
 * <p>全部读取直连数据库且不加缓存——看板存在的意义就是反映最近一轮抓取的真实结果，
 * 缓存带来的滞后会让「静默失败」这个它要防的事故重新变得不可见。
 */
@Repository
public class AdminCatalogStore {
    /** 业务标识由管理员显式给定，沿用 seed 的可读 slug，便于人工核对与跨环境搬运。 */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9_-]{2,127}$");
    private static final Set<String> SOURCE_HEALTH = Set.of("healthy", "failed", "stale", "waiting", "disabled");
    private static final Set<String> REQUEST_STATUS = Set.of("pending", "approved", "rejected");
    /** 超过该时长没有一次成功抓取即视为静默失败；worker 默认轮次为 30 分钟，留足重试余量。 */
    private static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final int MAX_PAGE = 200;

    private final JdbcClient jdbc;

    public AdminCatalogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // 抓取源健康度
    // -------------------------------------------------------------------------

    /**
     * 列出全部源及其最近一次抓取结果，并按派生健康度分类。
     *
     * <p>健康度在 SQL 内派生，保证筛选与展示使用同一套判定，不会出现「筛选出的行看起来正常」。
     */
    public Map<String, Object> listSourceHealth(String idolId, String health) {
        String healthFilter = normalizeFilter(health, SOURCE_HEALTH, "health");
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(STALE_AFTER);
        // 健康度筛选必须在 LIMIT 之前生效，否则「失败」筛选会在源数超过上限时静默漏掉真正的故障源。
        List<Map<String, Object>> sources = jdbc.sql("""
                        SELECT * FROM (
                          SELECT s.*, i.name AS idol_name, %s AS health
                          FROM idr_source s JOIN idr_idol i ON i.id = s.idol_id
                          WHERE (:idolId IS NULL OR s.idol_id = :idolId)
                        ) rows
                        WHERE (:health IS NULL OR rows.health = :health)
                        ORDER BY rows.enabled DESC, rows.consecutive_failures DESC,
                                 rows.idol_name ASC, rows.id ASC
                        LIMIT :limit
                        """.formatted(healthExpression()))
                .param("idolId", blankToNull(idolId))
                .param("health", healthFilter)
                .param("staleBefore", staleBefore)
                .param("limit", MAX_PAGE)
                .query((resultSet, rowNumber) -> serializeSourceHealth(resultSet))
                .list();

        // 汇总与列表用同一个 idol 条件，避免筛选后的列表挂在全局总数下面产生误读。
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String value : List.of("healthy", "failed", "stale", "waiting", "disabled")) {
            summary.put(value, 0);
        }
        jdbc.sql("""
                        SELECT %s AS health, COUNT(*)::integer AS total
                        FROM idr_source s
                        WHERE (:idolId IS NULL OR s.idol_id = :idolId)
                        GROUP BY 1
                        """.formatted(healthExpression()))
                .param("idolId", blankToNull(idolId))
                .param("staleBefore", staleBefore)
                .query((resultSet, rowNumber) -> summary.put(
                        resultSet.getString("health"), resultSet.getInt("total")))
                .list();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", sources);
        result.put("summary", summary);
        return result;
    }

    /**
     * 派生健康度：停用优先于抓取结果，失败优先于陈旧。
     *
     * <p>{@code stale} 覆盖「最后一次抓取成功、但此后一直没有再成功」这种最危险的静默失败：
     * 状态字段仍是 success，只有时间能暴露问题。
     */
    private static String healthExpression() {
        return """
                CASE
                  WHEN s.enabled = FALSE THEN 'disabled'
                  WHEN s.last_fetch_status = 'failed' THEN 'failed'
                  WHEN s.last_fetch_status = 'never' THEN 'waiting'
                  WHEN s.last_success_at IS NULL OR s.last_success_at < :staleBefore THEN 'stale'
                  ELSE 'healthy'
                END""";
    }

    // -------------------------------------------------------------------------
    // idol 与源维护
    // -------------------------------------------------------------------------

    public Map<String, Object> listIdols() {
        List<Map<String, Object>> idols = jdbc.sql("""
                        SELECT i.*,
                               COUNT(DISTINCT s.id)::integer AS source_count,
                               COUNT(DISTINCT g.user_id)::integer AS guard_count
                        FROM idr_idol i
                        LEFT JOIN idr_source s ON s.idol_id = i.id
                        LEFT JOIN idr_user_guard g ON g.idol_id = i.id
                        GROUP BY i.id ORDER BY i.enabled DESC, i.name ASC, i.id ASC LIMIT :limit
                        """)
                .param("limit", MAX_PAGE)
                .query((resultSet, rowNumber) -> serializeIdol(resultSet))
                .list();
        return Map.of("idols", idols);
    }

    public Map<String, Object> createIdol(String id, String name, String avatar, String bio, boolean enabled) {
        validateSlug(id, "idolId");
        String idolName = requireText(name, 64, "name");
        int inserted = jdbc.sql("""
                        INSERT INTO idr_idol (id, name, avatar, bio, enabled)
                        VALUES (:id, :name, :avatar, :bio, :enabled)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("name", idolName)
                .param("avatar", optionalText(avatar, 512, "avatar"))
                .param("bio", optionalText(bio, 500, "bio"))
                .param("enabled", enabled)
                .update();
        if (inserted == 0) {
            throw new AppException(HttpStatus.CONFLICT, "IDOL_EXISTS", "该 idol 标识已存在");
        }
        return findIdol(id);
    }

    /** 乐观锁更新；版本不匹配时报冲突，避免两名管理员互相覆盖。 */
    public Map<String, Object> updateIdol(
            String id, String name, String avatar, String bio, Boolean enabled, int expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE idr_idol SET
                          name = COALESCE(:name, name),
                          avatar = COALESCE(:avatar, avatar),
                          bio = COALESCE(:bio, bio),
                          enabled = COALESCE(:enabled, enabled),
                          version = version + 1,
                          updated_at = NOW()
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("id", id)
                .param("name", name == null ? null : requireText(name, 64, "name"))
                .param("avatar", avatar == null ? null : optionalText(avatar, 512, "avatar"))
                .param("bio", bio == null ? null : optionalText(bio, 500, "bio"))
                .param("enabled", enabled)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 0) {
            requireExists("idr_idol", id, "IDOL_NOT_FOUND", "idol 不存在");
            throw versionConflict();
        }
        return findIdol(id);
    }

    public Map<String, Object> createSource(
            String id, String idolId, String rssUrl, String displayName, String channel, boolean enabled) {
        validateSlug(id, "sourceId");
        requireExists("idr_idol", idolId, "IDOL_NOT_FOUND", "idol 不存在");
        int inserted = duplicateAsConflict(() -> jdbc.sql("""
                        INSERT INTO idr_source (id, idol_id, rss_url, display_name, channel, enabled)
                        VALUES (:id, :idolId, :rssUrl, :displayName, :channel, :enabled)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("idolId", idolId)
                .param("rssUrl", requireText(rssUrl, 2048, "rssUrl"))
                .param("displayName", requireText(displayName, 128, "displayName"))
                .param("channel", requireText(channel, 32, "channel"))
                .param("enabled", enabled)
                .update());
        if (inserted == 0) {
            throw new AppException(HttpStatus.CONFLICT, "SOURCE_EXISTS", "该源标识已存在");
        }
        return findSource(id);
    }

    public Map<String, Object> updateSource(
            String id, String rssUrl, String displayName, String channel, Boolean enabled, int expectedVersion) {
        int updated = duplicateAsConflict(() -> jdbc.sql("""
                        UPDATE idr_source SET
                          rss_url = COALESCE(:rssUrl, rss_url),
                          display_name = COALESCE(:displayName, display_name),
                          channel = COALESCE(:channel, channel),
                          enabled = COALESCE(:enabled, enabled),
                          version = version + 1,
                          updated_at = NOW()
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("id", id)
                .param("rssUrl", rssUrl == null ? null : requireText(rssUrl, 2048, "rssUrl"))
                .param("displayName", displayName == null ? null : requireText(displayName, 128, "displayName"))
                .param("channel", channel == null ? null : requireText(channel, 32, "channel"))
                .param("enabled", enabled)
                .param("expectedVersion", expectedVersion)
                .update());
        if (updated == 0) {
            requireExists("idr_source", id, "SOURCE_NOT_FOUND", "源不存在");
            throw versionConflict();
        }
        return findSource(id);
    }

    /** 抓取验证只需要地址与展示名；返回空表示源不存在。 */
    public Optional<SourceTarget> findSourceTarget(String sourceId) {
        return jdbc.sql("SELECT id, display_name, rss_url FROM idr_source WHERE id = :id")
                .param("id", sourceId)
                .query((resultSet, rowNumber) -> new SourceTarget(
                        resultSet.getString("id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("rss_url")))
                .optional();
    }

    /** 判断哪些链接已在库中，供抓取验证在不写任何数据的前提下报告「本次会新增多少条」。 */
    public Set<String> findExistingPostLinks(List<String> links) {
        if (links.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("SELECT link FROM idr_post WHERE link IN (:links)")
                .param("links", links)
                .query(String.class)
                .list());
    }

    // -------------------------------------------------------------------------
    // idol 申请审核
    // -------------------------------------------------------------------------

    public Map<String, Object> listIdolRequests(String status) {
        String filter = normalizeFilter(status, REQUEST_STATUS, "status");
        List<Map<String, Object>> requests = jdbc.sql("""
                        SELECT r.*,
                               COUNT(s.user_id)::integer AS supporter_count,
                               a.username AS reviewer_username
                        FROM idr_idol_request r
                        LEFT JOIN idr_idol_request_supporter s ON s.request_id = r.id
                        LEFT JOIN idr_admin_account a ON a.id = r.reviewed_by
                        WHERE (:status IS NULL OR r.status = :status)
                        GROUP BY r.id, a.username
                        ORDER BY COUNT(s.user_id) DESC, r.created_at DESC
                        LIMIT :limit
                        """)
                .param("status", filter)
                .param("limit", MAX_PAGE)
                .query((resultSet, rowNumber) -> serializeRequest(resultSet))
                .list();
        int pending = jdbc.sql("SELECT COUNT(*)::integer FROM idr_idol_request WHERE status = 'pending'")
                .query(Integer.class)
                .single();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests);
        result.put("pendingCount", pending);
        return result;
    }

    /**
     * 通过申请：复用既有 idol 新增路径落地正式目录，再把申请标记为已通过。
     *
     * <p>创建与审核结论同事务提交，避免出现「目录里多了 idol，但申请仍显示待审核」的中间态。
     */
    @Transactional
    public Map<String, Object> approveRequest(
            UUID requestId, UUID adminId, String reviewNote, String idolId, String idolName, String bio) {
        Map<String, Object> request = requirePendingRequest(requestId);
        String targetName = idolName == null || idolName.isBlank()
                ? String.valueOf(request.get("displayName"))
                : idolName;
        boolean exists = jdbc.sql("SELECT COUNT(*)::integer FROM idr_idol WHERE id = :id")
                .param("id", idolId)
                .query(Integer.class)
                .single() > 0;
        if (!exists) {
            createIdol(idolId, targetName, "", bio == null ? "" : bio, true);
        }
        jdbc.sql("""
                        UPDATE idr_idol_request SET
                          status = 'approved', reviewed_by = :adminId, reviewed_at = NOW(),
                          review_note = :note, approved_idol_id = :idolId, updated_at = NOW()
                        WHERE id = :id AND status = 'pending'
                        """)
                .param("id", requestId)
                .param("adminId", adminId)
                .param("note", optionalText(reviewNote, 500, "reviewNote"))
                .param("idolId", idolId)
                .update();
        return findRequest(requestId);
    }

    public Map<String, Object> rejectRequest(UUID requestId, UUID adminId, String reviewNote) {
        requirePendingRequest(requestId);
        jdbc.sql("""
                        UPDATE idr_idol_request SET
                          status = 'rejected', reviewed_by = :adminId, reviewed_at = NOW(),
                          review_note = :note, updated_at = NOW()
                        WHERE id = :id AND status = 'pending'
                        """)
                .param("id", requestId)
                .param("adminId", adminId)
                .param("note", requireText(reviewNote, 500, "reviewNote"))
                .update();
        return findRequest(requestId);
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    private Map<String, Object> requirePendingRequest(UUID requestId) {
        Map<String, Object> request = findRequest(requestId);
        if (!"pending".equals(request.get("status"))) {
            throw new AppException(HttpStatus.CONFLICT, "REQUEST_ALREADY_REVIEWED", "该申请已被处理");
        }
        return request;
    }

    private Map<String, Object> findRequest(UUID requestId) {
        return jdbc.sql("""
                        SELECT r.*,
                               COUNT(s.user_id)::integer AS supporter_count,
                               a.username AS reviewer_username
                        FROM idr_idol_request r
                        LEFT JOIN idr_idol_request_supporter s ON s.request_id = r.id
                        LEFT JOIN idr_admin_account a ON a.id = r.reviewed_by
                        WHERE r.id = :id GROUP BY r.id, a.username
                        """)
                .param("id", requestId)
                .query((resultSet, rowNumber) -> serializeRequest(resultSet))
                .optional()
                .orElseThrow(() -> new AppException(
                        HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "申请不存在"));
    }

    private Map<String, Object> findIdol(String id) {
        return jdbc.sql("""
                        SELECT i.*,
                               COUNT(DISTINCT s.id)::integer AS source_count,
                               COUNT(DISTINCT g.user_id)::integer AS guard_count
                        FROM idr_idol i
                        LEFT JOIN idr_source s ON s.idol_id = i.id
                        LEFT JOIN idr_user_guard g ON g.idol_id = i.id
                        WHERE i.id = :id GROUP BY i.id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> serializeIdol(resultSet))
                .optional()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "IDOL_NOT_FOUND", "idol 不存在"));
    }

    private Map<String, Object> findSource(String id) {
        return jdbc.sql("""
                        SELECT s.*, i.name AS idol_name, %s AS health
                        FROM idr_source s JOIN idr_idol i ON i.id = s.idol_id
                        WHERE s.id = :id
                        """.formatted(healthExpression()))
                .param("id", id)
                .param("staleBefore", OffsetDateTime.now().minus(STALE_AFTER))
                .query((resultSet, rowNumber) -> serializeSourceHealth(resultSet))
                .optional()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "源不存在"));
    }

    private void requireExists(String table, String id, String code, String message) {
        Integer found = jdbc.sql("SELECT COUNT(*)::integer FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
        if (found == null || found == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, code, message);
        }
    }

    private static Map<String, Object> serializeIdol(ResultSet resultSet) throws SQLException {
        Map<String, Object> idol = new LinkedHashMap<>();
        idol.put("id", resultSet.getString("id"));
        idol.put("name", resultSet.getString("name"));
        idol.put("avatar", resultSet.getString("avatar"));
        idol.put("bio", resultSet.getString("bio"));
        idol.put("enabled", resultSet.getBoolean("enabled"));
        idol.put("version", resultSet.getInt("version"));
        idol.put("sourceCount", resultSet.getInt("source_count"));
        idol.put("guardCount", resultSet.getInt("guard_count"));
        idol.put("updatedAt", instant(resultSet, "updated_at"));
        return idol;
    }

    private static Map<String, Object> serializeSourceHealth(ResultSet resultSet) throws SQLException {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", resultSet.getString("id"));
        source.put("idolId", resultSet.getString("idol_id"));
        source.put("idolName", resultSet.getString("idol_name"));
        source.put("displayName", resultSet.getString("display_name"));
        source.put("channel", resultSet.getString("channel"));
        source.put("rssUrl", resultSet.getString("rss_url"));
        source.put("enabled", resultSet.getBoolean("enabled"));
        source.put("health", resultSet.getString("health"));
        source.put("lastFetchAt", instant(resultSet, "last_fetch_at"));
        source.put("lastFetchStatus", resultSet.getString("last_fetch_status"));
        source.put("lastFetchErrorCode", resultSet.getString("last_fetch_error_code"));
        source.put("lastFetchItemCount", resultSet.getInt("last_fetch_item_count"));
        source.put("lastFetchNewCount", resultSet.getInt("last_fetch_new_count"));
        source.put("lastSuccessAt", instant(resultSet, "last_success_at"));
        source.put("consecutiveFailures", resultSet.getInt("consecutive_failures"));
        source.put("version", resultSet.getInt("version"));
        return source;
    }

    private static Map<String, Object> serializeRequest(ResultSet resultSet) throws SQLException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", resultSet.getString("id"));
        request.put("displayName", resultSet.getString("display_name"));
        request.put("normalizedName", resultSet.getString("normalized_name"));
        request.put("note", resultSet.getString("note"));
        request.put("status", resultSet.getString("status"));
        request.put("supporterCount", resultSet.getInt("supporter_count"));
        request.put("reviewer", resultSet.getString("reviewer_username"));
        request.put("reviewNote", resultSet.getString("review_note"));
        request.put("approvedIdolId", resultSet.getString("approved_idol_id"));
        request.put("createdAt", instant(resultSet, "created_at"));
        request.put("reviewedAt", instant(resultSet, "reviewed_at"));
        return request;
    }

    private static OffsetDateTime instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class);
    }

    private static String normalizeFilter(String value, Set<String> allowed, String field) {
        String normalized = blankToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "无效的" + field + "筛选值");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateSlug(String value, String field) {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "INVALID_ID", field + " 只能使用小写字母、数字、下划线或中划线");
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", field + " 无效");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", field + " 过长");
        }
        return normalized;
    }

    /** 同一 idol 重复配置同一抓取地址由数据库唯一约束拒绝，这里翻译成管理员看得懂的冲突。 */
    private static int duplicateAsConflict(java.util.function.IntSupplier update) {
        try {
            return update.getAsInt();
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw new AppException(
                    HttpStatus.CONFLICT, "SOURCE_URL_EXISTS", "该 idol 下已配置相同抓取地址");
        }
    }

    private static AppException versionConflict() {
        return new AppException(
                HttpStatus.CONFLICT, "VERSION_CONFLICT", "记录已被其他管理员修改，请刷新后重试");
    }

    /** 抓取验证所需的最小源信息。 */
    public record SourceTarget(String id, String displayName, String rssUrl) {
    }
}
