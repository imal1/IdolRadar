package com.idolradar.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_NICKNAME_LENGTH = 128;
    private static final int MAX_REQUEST_NAME_LENGTH = 64;
    private static final int MAX_REQUEST_NOTE_LENGTH = 200;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;
    private final CursorCodec cursorCodec;

    public JdbcIdolRadarStore(JdbcClient jdbc, CursorCodec cursorCodec) {
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
    }

    @Override
    @Transactional
    public boolean deleteAccount(UUID userId, UUID requestId) {
        // 匿名回执先占坑：同一 requestId 的并发请求只有一个能继续，且表中不记录用户身份。
        if (!claimAccountDeletion(requestId)) {
            // 唯一键冲突会等首个事务结束。同 ID 只能为已经不存在的账号重放；
            // 存活账号复用别人的完成回执必须拒绝，不能误报其个人数据已删除。
            return isAccountDeletionCompleted(requestId) && !userExists(userId);
        }

        // 外键以 ON DELETE CASCADE 清理个人数据；共享 idol、来源、动态等父表不受影响。
        int deleted = jdbc.sql("DELETE FROM idr_user WHERE id = :userId")
                .param("userId", userId)
                .update();

        completeAccountDeletion(requestId);
        if (deleted == 0) {
            // 身份在认证后可能已被另一 requestId 并发删除；账号已经不存在，应收敛为完成，
            // 但不能重复累计匿名注销数，也不能返回 409 让客户端恢复普通登录。
            return true;
        }
        int counted = jdbc.sql("INSERT INTO idr_account_deletion_daily (deletion_date, deletion_count) "
                        + "VALUES ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date, 1) "
                        + "ON CONFLICT (deletion_date) DO UPDATE "
                        + "SET deletion_count = idr_account_deletion_daily.deletion_count + 1")
                .update();
        if (counted != 1) {
            // 匿名汇总也属于注销事务，失败不能留下“已完成”回执。
            throw new IllegalStateException("Account deletion daily count update failed");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean recoverAccountDeletion(String openId, UUID requestId) {
        Optional<UUID> userId = jdbc.sql("SELECT id FROM idr_user WHERE openid = :openId")
                .param("openId", openId)
                .query(UUID.class)
                .optional();
        if (userId.isPresent()) {
            return deleteAccount(userId.get(), requestId);
        }
        if (!claimAccountDeletion(requestId)) {
            return isAccountDeletionCompleted(requestId);
        }
        // 微信身份已重新验证且库内已无该账号：可能由同 ID 在途请求或另一并发请求完成。
        // 写完成回执但不计数，因为本事务没有物理删除用户。
        completeAccountDeletion(requestId);
        return true;
    }

    @Override
    public boolean isAccountDeletionCompleted(UUID requestId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM idr_account_deletion_receipt "
                        + "WHERE request_id = :requestId AND completed = TRUE)")
                .param("requestId", requestId)
                .query(Boolean.class)
                .single();
    }

    private boolean claimAccountDeletion(UUID requestId) {
        return jdbc.sql("INSERT INTO idr_account_deletion_receipt (request_id, completed) "
                        + "VALUES (:requestId, FALSE) ON CONFLICT (request_id) DO NOTHING")
                .param("requestId", requestId)
                .update() == 1;
    }

    private void completeAccountDeletion(UUID requestId) {
        int completed = jdbc.sql("UPDATE idr_account_deletion_receipt SET completed = TRUE "
                        + "WHERE request_id = :requestId AND completed = FALSE")
                .param("requestId", requestId)
                .update();
        if (completed != 1) {
            // 删除与完成回执必须同事务提交；异常时抛出以触发整体回滚。
            throw new IllegalStateException("Account deletion receipt update failed");
        }
    }

    private boolean userExists(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM idr_user WHERE id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
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
                        "SELECT COUNT(*)::integer FROM idr_source WHERE idol_id = :idolId AND enabled = TRUE")
                .param("idolId", user.idolId())
                .query(Integer.class)
                .single();
        DayWindow today = shanghaiDayWindow(Instant.now());
        int todayPosts = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM idr_post "
                                + "WHERE idol_id = :idolId "
                                + "AND published_at >= :startOfDay AND published_at < :startOfNextDay")
                .param("idolId", user.idolId())
                // “今日”使用上海自然日闭开区间，既不受服务器时区影响，也不误计上游未来时间。
                .param("startOfDay", today.startInclusive())
                .param("startOfNextDay", today.endExclusive())
                .query(Integer.class)
                .single();
        FeedPage feed = queryFeedPage(user.idolId(), null);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayPosts", todayPosts);
        stats.put("sourceCount", sourceCount);
        // 产品已决议信号强度仅为状态文案，不代表来源健康率或预测概率。
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
                                + "FROM idr_idol i LEFT JOIN idr_source s ON s.idol_id = i.id "
                                + "WHERE i.enabled = TRUE GROUP BY i.id ORDER BY i.name ASC, i.id ASC")
                .query((resultSet, rowNumber) -> serializeIdol(mapIdol(resultSet), resultSet.getInt("source_count")))
                .list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idols", idols);
        result.put("currentIdolId", user.idolId());
        return result;
    }

    @Override
    public Map<String, Object> updateNickname(String openId, String nickname) {
        String normalized = normalizeNickname(nickname);
        UserRow user = requireUser(openId);
        // 只有用户主动提交本接口才记录授权时间；静默登录只负责建档与签发会话。
        jdbc.sql("UPDATE idr_user SET nickname = :nickname, profile_authorized_at = NOW(), "
                        + "updated_at = NOW() WHERE id = :userId")
                .param("nickname", normalized)
                .param("userId", user.id())
                .update();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", serializeUser(findUser(openId).orElseThrow()));
        return result;
    }

    /** 在同一事务中原子建立守护关系并校验上限，返回该事务读到的视图。 */
    @Override
    @Transactional
    public Map<String, Object> setIdol(String openId, String idolId, int guardLimit) {
        validateId(idolId, "idolId");
        UserRow user = requireUser(openId);
        IdolRow idol = findIdol(idolId, true).orElseThrow(() -> new AppException(
                HttpStatus.NOT_FOUND, "IDOL_NOT_FOUND", "守护对象不存在或已停用"));

        // 先锁住用户行：并发的守护请求必须串行地走「数已守护数 -> 判上限 -> 插入」，
        // 否则两个请求各自读到未超限的计数，插入后总数会越过上限。
        jdbc.sql("SELECT id FROM idr_user WHERE id = :userId FOR UPDATE")
                .param("userId", user.id())
                .query(UUID.class)
                .single();
        // user.idolId() 只是按 guarding_since 取到的其中一条守护关系，多守护场景下不能用来
        // 判断「是否已守护该 idol」，这里显式查这一对关系是否存在。
        boolean alreadyGuarding = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM idr_user_guard "
                                + "WHERE user_id = :userId AND idol_id = :idolId)")
                .param("userId", user.id())
                .param("idolId", idolId)
                .query(Boolean.class)
                .single();

        if (!alreadyGuarding) {
            int guarded = jdbc.sql("SELECT COUNT(*)::integer FROM idr_user_guard WHERE user_id = :userId")
                    .param("userId", user.id())
                    .query(Integer.class)
                    .single();
            if (guarded >= guardLimit) {
                if (guardLimit == 1) {
                    // 单守护客户端（微信小程序）语义是换人即替换，不是把第二次守护判成超限。
                    // 上限被从大调回 1 时也走这里：本次换人会把存量守护收敛成配置要求的一条。
                    // 收敛虽然会删关系，但停在 409 会让用户永远换不了人，而且没有取消守护的接口可以自救。
                    jdbc.sql("DELETE FROM idr_user_guard WHERE user_id = :userId AND idol_id <> :idolId")
                            .param("userId", user.id())
                            .param("idolId", idolId)
                            .update();
                } else {
                    // 文案只说上限，不指导「先取消一位」：目前没有取消守护的接口，
                    // 多守护客户端接入时要连同取消接口一起做。
                    throw new AppException(HttpStatus.CONFLICT, "GUARD_LIMIT_REACHED",
                            "最多只能同时守护 " + guardLimit + " 位");
                }
            }
            // idr_user 上只剩转化统计口径的 first_guarded_at：首次守护才写入，之后换人不重置。
            jdbc.sql("UPDATE idr_user SET first_guarded_at = COALESCE(first_guarded_at, NOW()), "
                            + "updated_at = NOW() WHERE id = :userId")
                    .param("userId", user.id())
                    .update();
            jdbc.sql("INSERT INTO idr_user_guard (user_id, idol_id, guarding_since) "
                            + "VALUES (:userId, :idolId, NOW()) "
                            + "ON CONFLICT (user_id, idol_id) DO UPDATE SET "
                            + "guarding_since = EXCLUDED.guarding_since, updated_at = NOW()")
                    .param("userId", user.id())
                    .param("idolId", idolId)
                    .update();
        }
        UserRow updated = findUser(openId).orElseThrow();
        int sourceCount = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM idr_source WHERE idol_id = :idolId AND enabled = TRUE")
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
        Optional<SubscriptionState> updated = jdbc.sql(
                        "UPDATE idr_user SET subscribe_quota = CASE "
                                + "WHEN subscribe_template_id IS DISTINCT FROM :templateId THEN 1 "
                                + "ELSE subscribe_quota + 1 END, "
                                + "subscribe_template_id = :templateId, subscribed_at = NOW(), "
                                + "first_subscribed_at = COALESCE(first_subscribed_at, NOW()), updated_at = NOW() "
                                + "WHERE id = :userId "
                                + "AND (subscribe_template_id IS DISTINCT FROM :templateId "
                                + "OR subscribe_quota < :maxQuota) "
                                + "AND (subscribed_at IS NULL OR subscribed_at < NOW() - INTERVAL '5 seconds') "
                                + "RETURNING subscribe_quota, subscribed_at")
                .param("templateId", templateId)
                .param("userId", user.id())
                .param("maxQuota", MAX_SUBSCRIBE_QUOTA)
                // 这条 UPDATE 只作用于 idr_user，取不到守护关联表的列，因此不复用 mapUser。
                .query((resultSet, rowNumber) -> new SubscriptionState(
                        resultSet.getInt("subscribe_quota"),
                        instant(resultSet, "subscribed_at")))
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

    /**
     * 提交想蹲的 idol；同名申请聚合为一条，重复提交只增加支持人数。
     *
     * <p>聚合的意义是让审核看到真实需求量，因此按规范化名去重而不是按用户去重：
     * 同一个人重复提交不会灌水，不同的人提交同一个名字会累加。
     */
    @Override
    @Transactional
    public Map<String, Object> submitIdolRequest(String openId, String displayName, String note) {
        UserRow user = requireUser(openId);
        String name = requestText(displayName, MAX_REQUEST_NAME_LENGTH, "申请名称");
        String normalized = normalizeRequestName(name);
        if (normalized.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "申请名称无效");
        }
        String comment = note == null ? "" : requestText(note, MAX_REQUEST_NOTE_LENGTH, "补充说明");

        // 已在目录里的 idol 不需要申请，直接引导用户去选，比让他等审核诚实。
        // 比对必须与 normalizeRequestName 一致地压缩空白，否则名字里有连续空格的 idol 永远匹配不上。
        boolean alreadyListed = jdbc.sql(
                        "SELECT COUNT(*)::integer FROM idr_idol WHERE enabled = TRUE "
                                + "AND lower(btrim(regexp_replace(name, '\\s+', ' ', 'g'))) = :name")
                .param("name", normalized)
                .query(Integer.class)
                .single() > 0;
        if (alreadyListed) {
            throw new AppException(HttpStatus.CONFLICT, "IDOL_ALREADY_LISTED", "该 idol 已经可以直接选择");
        }

        // 名称唯一约束覆盖全部状态，因此已审核的同名申请要给出明确结论，而不是静默新建一条。
        Optional<RequestRow> existing = findRequestByNormalizedName(normalized);
        if (existing.isPresent() && !"pending".equals(existing.get().status())) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "approved".equals(existing.get().status()) ? "REQUEST_ALREADY_APPROVED" : "REQUEST_REJECTED",
                    "approved".equals(existing.get().status()) ? "该申请已通过，稍后即可选择" : "该申请已被驳回");
        }

        UUID requestId = existing.map(RequestRow::id).orElseGet(() -> jdbc.sql(
                        "INSERT INTO idr_idol_request (normalized_name, display_name, note) "
                                + "VALUES (:normalized, :displayName, :note) RETURNING id")
                .param("normalized", normalized)
                .param("displayName", name)
                .param("note", comment)
                .query(UUID.class)
                .single());
        jdbc.sql("INSERT INTO idr_idol_request_supporter (request_id, user_id) VALUES (:requestId, :userId) "
                        + "ON CONFLICT (request_id, user_id) DO NOTHING")
                .param("requestId", requestId)
                .param("userId", user.id())
                .update();
        return serializeRequest(findRequestById(requestId).orElseThrow(), supporterCount(requestId));
    }

    @Override
    public Map<String, Object> listMyIdolRequests(String openId) {
        UserRow user = requireUser(openId);
        List<Map<String, Object>> requests = jdbc.sql(
                        "SELECT r.*, "
                                + "(SELECT COUNT(*)::integer FROM idr_idol_request_supporter c "
                                + "WHERE c.request_id = r.id) AS supporter_count "
                                + "FROM idr_idol_request r "
                                + "JOIN idr_idol_request_supporter s ON s.request_id = r.id "
                                + "WHERE s.user_id = :userId ORDER BY r.created_at DESC LIMIT 50")
                .param("userId", user.id())
                .query((resultSet, rowNumber) ->
                        serializeRequest(mapRequest(resultSet), resultSet.getInt("supporter_count")))
                .list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests);
        return result;
    }

    private Optional<RequestRow> findRequestByNormalizedName(String normalizedName) {
        return jdbc.sql("SELECT * FROM idr_idol_request WHERE normalized_name = :name")
                .param("name", normalizedName)
                .query((resultSet, rowNumber) -> mapRequest(resultSet))
                .optional();
    }

    private Optional<RequestRow> findRequestById(UUID requestId) {
        return jdbc.sql("SELECT * FROM idr_idol_request WHERE id = :id")
                .param("id", requestId)
                .query((resultSet, rowNumber) -> mapRequest(resultSet))
                .optional();
    }

    private int supporterCount(UUID requestId) {
        return jdbc.sql("SELECT COUNT(*)::integer FROM idr_idol_request_supporter WHERE request_id = :id")
                .param("id", requestId)
                .query(Integer.class)
                .single();
    }

    private static RequestRow mapRequest(ResultSet resultSet) throws SQLException {
        return new RequestRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getString("review_note"),
                resultSet.getString("approved_idol_id"),
                instant(resultSet, "created_at"),
                instant(resultSet, "reviewed_at"));
    }

    private static Map<String, Object> serializeRequest(RequestRow request, int supporterCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_id", request.id().toString());
        result.put("displayName", request.displayName());
        result.put("status", request.status());
        result.put("reviewNote", request.reviewNote());
        result.put("approvedIdolId", request.approvedIdolId());
        result.put("supporterCount", supporterCount);
        result.put("createdAt", format(request.createdAt()));
        result.put("reviewedAt", format(request.reviewedAt()));
        return result;
    }

    /** 规范化只做大小写与空白归一：过度归一会把不同的名字合并成一条申请。 */
    static String normalizeRequestName(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String requestText(String value, int maxLength, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maxLength
                || hasControlCharacter(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", fieldName + "无效");
        }
        return normalized;
    }

    @Override
    @Transactional
    public Map<String, Object> recordNotificationOpen(String openId, String postId) {
        validateId(postId, "postId");
        UserRow user = requireUser(openId);

        // 首次打开时间只写一次，最近打开时间每次刷新，次数累加——三者要一起满足
        // ck_idr_notification_delivery_open_times 约束。RETURNING 取的是更新后的值，
        // 因此 open_count = 1 即代表这是首次回访。
        Optional<Map<String, Object>> updated = jdbc.sql(
                        "UPDATE idr_notification_delivery "
                                + "SET first_opened_at = COALESCE(first_opened_at, NOW()), "
                                + "    last_opened_at = NOW(), "
                                + "    open_count = open_count + 1, "
                                + "    updated_at = NOW() "
                                + "WHERE post_id = :postId AND user_id = :userId "
                                + "RETURNING open_count")
                .param("postId", postId)
                .param("userId", user.id())
                .query((resultSet, rowNumber) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("openCount", resultSet.getInt("open_count"));
                    row.put("firstOpen", resultSet.getInt("open_count") == 1);
                    return row;
                })
                .optional();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postId", postId);
        // 没有匹配的投递记录不是错误：用户可能只是正常打开小程序，或带了一个过期的 postId。
        // 返回 200 而非 404，客户端无须为此弹错误提示。
        result.put("recorded", updated.isPresent());
        updated.ifPresent(result::putAll);
        return result;
    }

    @Override
    public Map<String, Object> listMySources(String openId) {
        UserRow user = requireUser(openId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (user.idolId() == null) {
            result.put("sources", List.of());
            return result;
        }
        // rss_url 是内部抓取地址，属于运维配置，绝不能随小程序接口下发。
        List<Map<String, Object>> sources = jdbc.sql(
                        "SELECT s.id, s.display_name, s.channel, "
                                + "(m.source_id IS NOT NULL) AS muted "
                                + "FROM idr_source s "
                                + "LEFT JOIN idr_user_source_mute m "
                                + "  ON m.source_id = s.id AND m.user_id = :userId "
                                + "WHERE s.idol_id = :idolId AND s.enabled = TRUE "
                                + "ORDER BY s.display_name ASC, s.id ASC")
                .param("userId", user.id())
                .param("idolId", user.idolId())
                .query((resultSet, rowNumber) -> {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("_id", resultSet.getString("id"));
                    source.put("displayName", resultSet.getString("display_name"));
                    source.put("channel", resultSet.getString("channel"));
                    source.put("muted", resultSet.getBoolean("muted"));
                    return source;
                })
                .list();
        result.put("sources", sources);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> setSourceMuted(String openId, String sourceId, boolean muted) {
        validateId(sourceId, "sourceId");
        UserRow user = requireUser(openId);
        // 只允许操作当前守护 idol 名下的启用来源：否则任何人都能往关联表里塞任意 source_id，
        // 既能探测来源是否存在，也会留下换回该 idol 后突然静默的脏记录。
        boolean belongsToCurrentIdol = user.idolId() != null && jdbc.sql(
                        "SELECT COUNT(*)::integer FROM idr_source "
                                + "WHERE id = :sourceId AND idol_id = :idolId AND enabled = TRUE")
                .param("sourceId", sourceId)
                .param("idolId", user.idolId())
                .query(Integer.class)
                .single() > 0;
        if (!belongsToCurrentIdol) {
            throw new AppException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "来源不存在或不属于当前守护对象");
        }

        if (muted) {
            // 存「被关掉的」而非「已订阅的」：重复关闭是幂等的，新增来源也无需为每个用户回填。
            jdbc.sql("INSERT INTO idr_user_source_mute (user_id, source_id) "
                            + "VALUES (:userId, :sourceId) ON CONFLICT DO NOTHING")
                    .param("userId", user.id())
                    .param("sourceId", sourceId)
                    .update();
        } else {
            jdbc.sql("DELETE FROM idr_user_source_mute WHERE user_id = :userId AND source_id = :sourceId")
                    .param("userId", user.id())
                    .param("sourceId", sourceId)
                    .update();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        result.put("muted", muted);
        return result;
    }

    private UserRow requireUser(String openId) {
        // 用户只能在登录验证后创建；API 读取绝不能为任意身份建档。
        return findUser(openId).orElseThrow(() -> new AppException(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已失效，请重新进入小程序"));
    }

    private Optional<UserRow> findUser(String openId) {
        // 当前守护对象只存在于 idr_user_guard；idr_user 上的旧单值字段已由 V7 删除。
        // LATERAL + LIMIT 1 保证无论关联表有几条守护关系都只返回一行，
        // 否则用户一旦守护多位 idol，这里的 optional() 会直接抛错。
        // 「当前」定义为最近开始的那条守护关系，与小程序「换人即替换」的语义一致。
        return jdbc.sql("""
                        SELECT u.*,
                               g.idol_id AS guard_idol_id,
                               g.guarding_since AS guard_guarding_since
                        FROM idr_user u
                        LEFT JOIN LATERAL (
                          SELECT idol_id, guarding_since
                          FROM idr_user_guard
                          WHERE user_id = u.id
                          ORDER BY guarding_since DESC, idol_id ASC
                          LIMIT 1
                        ) g ON TRUE
                        WHERE u.openid = :openId
                        """)
                .param("openId", openId)
                .query(this::mapUser)
                .optional();
    }

    private Optional<IdolRow> findIdol(String idolId, boolean enabledOnly) {
        String sql = "SELECT * FROM idr_idol WHERE id = :idolId" + (enabledOnly ? " AND enabled = TRUE" : "");
        return jdbc.sql(sql)
                .param("idolId", idolId)
                .query((resultSet, rowNumber) -> mapIdol(resultSet))
                .optional();
    }

    private FeedPage queryFeedPage(String idolId, String rawCursor) {
        CursorCodec.Cursor cursor = cursorCodec.decode(rawCursor);
        // 元组键集分页在动态时间相同时仍保持确定性，并避免 offset 漂移。
        // 列名必须带表别名：idr_post 与 idr_source 都有 id，不限定会直接报歧义。
        String cursorCondition = cursor == null
                ? ""
                : " AND (p.published_at, p.id) < (:publishedAt, :postId)";
        // LEFT JOIN 而非 INNER：来源被停用或数据异常时，动态本身仍应正常展示。
        JdbcClient.StatementSpec statement = jdbc.sql(
                        "SELECT p.*, s.display_name AS source_display_name "
                                + "FROM idr_post p LEFT JOIN idr_source s ON s.id = p.source_id "
                                + "WHERE p.idol_id = :idolId" + cursorCondition
                                + " ORDER BY p.published_at DESC, p.id DESC LIMIT :limit")
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
        result.put("nickname", user.nickname());
        result.put("profileAuthorizedAt", format(user.profileAuthorizedAt()));
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
        // 同一 idol 下「本人的微博」和「后援会的微博」channel 相同，只有展示名能区分。
        result.put("sourceName", post.sourceName());
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
                resultSet.getString("nickname"),
                instant(resultSet, "profile_authorized_at"),
                resultSet.getString("guard_idol_id"),
                instant(resultSet, "guard_guarding_since"),
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
                resultSet.getString("source_display_name"),
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

    /** 返回指定时刻所属的上海自然日边界，供首页“今日动态”统计使用。 */
    static DayWindow shanghaiDayWindow(Instant now) {
        LocalDate today = now.atZone(SHANGHAI).toLocalDate();
        return new DayWindow(
                OffsetDateTime.ofInstant(today.atStartOfDay(SHANGHAI).toInstant(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(today.plusDays(1).atStartOfDay(SHANGHAI).toInstant(), ZoneOffset.UTC));
    }

    private static void validateId(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > MAX_ID_LENGTH || hasControlCharacter(value)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", fieldName + "无效");
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private static String normalizeNickname(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAX_NICKNAME_LENGTH
                || hasControlCharacter(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "微信昵称无效");
        }
        return normalized;
    }

    private record UserRow(
            UUID id,
            String openId,
            String nickname,
            Instant profileAuthorizedAt,
            String idolId,
            Instant guardingSince,
            int subscribeQuota,
            String subscribeTemplateId,
            Instant subscribedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 订阅授权确认的返回值；该路径不涉及守护关系，因此不用完整的 UserRow。 */
    private record SubscriptionState(int subscribeQuota, Instant subscribedAt) {
    }

    private record IdolRow(String id, String name, String avatar, String bio, boolean enabled) {
    }

    private record PostRow(
            String id,
            String idolId,
            String sourceId,
            String sourceName,
            String channel,
            String title,
            String summary,
            String link,
            Instant publishedAt,
            Instant fetchedAt) {
    }

    private record RequestRow(
            UUID id,
            String displayName,
            String status,
            String reviewNote,
            String approvedIdolId,
            Instant createdAt,
            Instant reviewedAt) {
    }

    private record FeedPage(List<Map<String, Object>> posts, boolean hasMore, String nextCursor) {
    }

    record DayWindow(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
    }
}
