package com.idolradar.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL 实现，保持小程序既有响应字段结构。 */
@Repository
public class JdbcIdolRadarStore implements IdolRadarStore {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_SUBSCRIBE_QUOTA = 100;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;
    private final CursorCodec cursorCodec;

    public JdbcIdolRadarStore(JdbcClient jdbc, CursorCodec cursorCodec) {
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
    }

    @Override
    public Map<String, Object> bootstrap(String openId) {
        UserRow user = requireUser(openId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", serializeUser(user));
        result.put("hasIdol", user.idolId() != null);
        return result;
    }

    @Override
    public Map<String, Object> getHome(String openId) {
        UserRow user = requireUser(openId);
        if (user.idolId() == null) {
            return emptyHome(user);
        }

        Optional<IdolRow> idol = findIdol(user.idolId(), false);
        if (idol.isEmpty()) {
            return emptyHome(user);
        }

        int sourceCount = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM sources WHERE idol_id = :idolId AND enabled = TRUE")
                .param("idolId", user.idolId())
                .query(Integer.class)
                .single();
        int todayPosts = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM posts "
                                + "WHERE idol_id = :idolId AND published_at >= :startOfDay")
                .param("idolId", user.idolId())
                // 产品指标中的“今日”按中国标准时间计算，不受服务器时区影响。
                .param("startOfDay", OffsetDateTime.ofInstant(
                        java.time.LocalDate.now(SHANGHAI).atStartOfDay(SHANGHAI).toInstant(),
                        ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        FeedPage feed = queryFeedPage(user.idolId(), null);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayPosts", todayPosts);
        stats.put("sourceCount", sourceCount);
        stats.put("signalStrength", sourceCount > 0 ? "满格" : "无信号");
        stats.put("latestUpdateAt", feed.posts().isEmpty() ? null : feed.posts().getFirst().get("publishedAt"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", serializeUser(user));
        result.put("idol", serializeIdol(idol.get(), sourceCount));
        result.put("stats", stats);
        result.put("posts", feed.posts());
        result.put("hasMore", feed.hasMore());
        result.put("nextCursor", feed.nextCursor());
        return result;
    }

    @Override
    public Map<String, Object> getFeed(String openId, String cursor) {
        UserRow user = requireUser(openId);
        if (user.idolId() == null) {
            return feedResponse(new FeedPage(List.of(), false, null));
        }
        return feedResponse(queryFeedPage(user.idolId(), cursor));
    }

    @Override
    public Map<String, Object> listIdols(String openId) {
        UserRow user = requireUser(openId);
        // 单次聚合各 idol 的来源数，避免逐 idol 产生 N+1 查询。
        List<Map<String, Object>> idols = jdbc.sql(
                        "SELECT i.*, COUNT(s.id) FILTER (WHERE s.enabled = TRUE)::integer AS source_count "
                                + "FROM idols i LEFT JOIN sources s ON s.idol_id = i.id "
                                + "WHERE i.enabled = TRUE GROUP BY i.id ORDER BY i.name ASC, i.id ASC")
                .query((resultSet, rowNumber) -> serializeIdol(mapIdol(resultSet), resultSet.getInt("source_count")))
                .list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idols", idols);
        result.put("currentIdolId", user.idolId());
        return result;
    }

    /** 在同一事务中原子更换守护 idol，并返回该事务读到的视图。 */
    @Override
    @Transactional
    public Map<String, Object> setIdol(String openId, String idolId) {
        validateId(idolId, "idolId");
        UserRow user = requireUser(openId);
        IdolRow idol = findIdol(idolId, true).orElseThrow(() -> new AppException(
                HttpStatus.NOT_FOUND, "IDOL_NOT_FOUND", "守护对象不存在或已停用"));

        if (!Objects.equals(user.idolId(), idolId)) {
            jdbc.sql("UPDATE users SET idol_id = :idolId, guarding_since = NOW(), updated_at = NOW() "
                            + "WHERE id = :userId")
                    .param("idolId", idolId)
                    .param("userId", user.id())
                    .update();
        }
        UserRow updated = findUser(openId).orElseThrow();
        int sourceCount = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM sources WHERE idol_id = :idolId AND enabled = TRUE")
                .param("idolId", idolId)
                .query(Integer.class)
                .single();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", serializeUser(updated));
        result.put("idol", serializeIdol(idol, sourceCount));
        return result;
    }

    @Override
    public Map<String, Object> recordSubscription(String openId, boolean accepted, String templateId) {
        if (!accepted) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "订阅结果无效");
        }
        if (templateId == null || templateId.isBlank()) {
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CONFIGURATION_ERROR", "订阅消息模板未配置");
        }
        UserRow user = requireUser(openId);
        // 单条条件 UPDATE 原子处理额度累加、模板重置、上限和冷却时间。
        // 把这些约束留在 PostgreSQL，避免并发授权确认重复增加额度。
        Optional<UserRow> updated = jdbc.sql(
                        "UPDATE users SET subscribe_quota = CASE "
                                + "WHEN subscribe_template_id IS DISTINCT FROM :templateId THEN 1 "
                                + "ELSE subscribe_quota + 1 END, "
                                + "subscribe_template_id = :templateId, subscribed_at = NOW(), updated_at = NOW() "
                                + "WHERE id = :userId "
                                + "AND (subscribe_template_id IS DISTINCT FROM :templateId "
                                + "OR subscribe_quota < :maxQuota) "
                                + "AND (subscribed_at IS NULL OR subscribed_at < NOW() - INTERVAL '5 seconds') "
                                + "RETURNING *")
                .param("templateId", templateId)
                .param("userId", user.id())
                .param("maxQuota", MAX_SUBSCRIBE_QUOTA)
                .query(this::mapUser)
                .optional();
        if (updated.isEmpty()) {
            UserRow current = findUser(openId).orElseThrow();
            if (templateId.equals(current.subscribeTemplateId())
                    && current.subscribeQuota() >= MAX_SUBSCRIBE_QUOTA) {
                throw new AppException(
                        HttpStatus.CONFLICT, "SUBSCRIPTION_QUOTA_LIMIT", "提醒额度已达上限");
            }
            throw new AppException(
                    HttpStatus.TOO_MANY_REQUESTS, "SUBSCRIPTION_RATE_LIMITED", "操作太频繁，请稍后再试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscribeQuota", updated.get().subscribeQuota());
        result.put("subscribedAt", format(updated.get().subscribedAt()));
        return result;
    }

    private UserRow requireUser(String openId) {
        // 用户只能在登录验证后创建；API 读取绝不能为任意身份建档。
        return findUser(openId).orElseThrow(() -> new AppException(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已失效，请重新进入小程序"));
    }

    private Optional<UserRow> findUser(String openId) {
        return jdbc.sql("SELECT * FROM users WHERE openid = :openId")
                .param("openId", openId)
                .query(this::mapUser)
                .optional();
    }

    private Optional<IdolRow> findIdol(String idolId, boolean enabledOnly) {
        String sql = "SELECT * FROM idols WHERE id = :idolId" + (enabledOnly ? " AND enabled = TRUE" : "");
        return jdbc.sql(sql)
                .param("idolId", idolId)
                .query((resultSet, rowNumber) -> mapIdol(resultSet))
                .optional();
    }

    private FeedPage queryFeedPage(String idolId, String rawCursor) {
        CursorCodec.Cursor cursor = cursorCodec.decode(rawCursor);
        // 元组键集分页在动态时间相同时仍保持确定性，并避免 offset 漂移。
        String cursorCondition = cursor == null
                ? ""
                : " AND (published_at, id) < (:publishedAt, :postId)";
        JdbcClient.StatementSpec statement = jdbc.sql(
                        "SELECT * FROM posts WHERE idol_id = :idolId" + cursorCondition
                                + " ORDER BY published_at DESC, id DESC LIMIT :limit")
                .param("idolId", idolId)
                .param("limit", PAGE_SIZE + 1);
        if (cursor != null) {
            statement = statement
                    .param("publishedAt", OffsetDateTime.ofInstant(cursor.publishedAt(), ZoneOffset.UTC))
                    .param("postId", cursor.id());
        }
        // 多取一条哨兵记录判断 hasMore，无须额外 COUNT 查询。
        List<PostRow> rows = statement.query(this::mapPost).list();
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<PostRow> pageRows = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        List<Map<String, Object>> posts = pageRows.stream().map(this::serializePost).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? cursorCodec.encode(pageRows.getLast().publishedAt(), pageRows.getLast().id())
                : null;
        return new FeedPage(posts, hasMore, nextCursor);
    }

    private Map<String, Object> emptyHome(UserRow user) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayPosts", 0);
        stats.put("sourceCount", 0);
        stats.put("signalStrength", "无信号");
        stats.put("latestUpdateAt", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", serializeUser(user));
        result.put("idol", null);
        result.put("stats", stats);
        result.put("posts", List.of());
        result.put("hasMore", false);
        result.put("nextCursor", null);
        return result;
    }

    private static Map<String, Object> feedResponse(FeedPage page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("posts", page.posts());
        result.put("hasMore", page.hasMore());
        result.put("nextCursor", page.nextCursor());
        return result;
    }

    private static Map<String, Object> serializeUser(UserRow user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idolId", user.idolId());
        result.put("guardingSince", format(user.guardingSince()));
        result.put("subscribeQuota", Math.max(0, Math.min(MAX_SUBSCRIBE_QUOTA, user.subscribeQuota())));
        result.put("subscribedAt", format(user.subscribedAt()));
        result.put("createdAt", format(user.createdAt()));
        result.put("updatedAt", format(user.updatedAt()));
        return result;
    }

    private static Map<String, Object> serializeIdol(IdolRow idol, int sourceCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_id", idol.id());
        result.put("name", idol.name());
        result.put("avatar", idol.avatar());
        result.put("bio", idol.bio());
        result.put("enabled", idol.enabled());
        result.put("sourceCount", sourceCount);
        return result;
    }

    private Map<String, Object> serializePost(PostRow post) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_id", post.id());
        result.put("idolId", post.idolId());
        result.put("sourceId", post.sourceId());
        result.put("channel", post.channel());
        result.put("title", post.title());
        result.put("summary", post.summary());
        result.put("link", post.link());
        result.put("publishedAt", format(post.publishedAt()));
        result.put("fetchedAt", format(post.fetchedAt()));
        return result;
    }

    private UserRow mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("openid"),
                resultSet.getString("idol_id"),
                instant(resultSet, "guarding_since"),
                resultSet.getInt("subscribe_quota"),
                resultSet.getString("subscribe_template_id"),
                instant(resultSet, "subscribed_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static IdolRow mapIdol(ResultSet resultSet) throws SQLException {
        return new IdolRow(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("avatar"),
                resultSet.getString("bio"),
                resultSet.getBoolean("enabled"));
    }

    private PostRow mapPost(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PostRow(
                resultSet.getString("id"),
                resultSet.getString("idol_id"),
                resultSet.getString("source_id"),
                Optional.ofNullable(resultSet.getString("channel")).orElse("RSS"),
                resultSet.getString("title"),
                Optional.ofNullable(resultSet.getString("summary")).orElse(""),
                resultSet.getString("link"),
                instant(resultSet, "published_at"),
                instant(resultSet, "fetched_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String format(Instant value) {
        return value == null ? null : value.toString();
    }

    private static void validateId(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > MAX_ID_LENGTH || hasControlCharacter(value)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", fieldName + "无效");
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private record UserRow(
            UUID id,
            String openId,
            String idolId,
            Instant guardingSince,
            int subscribeQuota,
            String subscribeTemplateId,
            Instant subscribedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    private record IdolRow(String id, String name, String avatar, String bio, boolean enabled) {
    }

    private record PostRow(
            String id,
            String idolId,
            String sourceId,
            String channel,
            String title,
            String summary,
            String link,
            Instant publishedAt,
            Instant fetchedAt) {
    }

    private record FeedPage(List<Map<String, Object>> posts, boolean hasMore, String nextCursor) {
    }
}
