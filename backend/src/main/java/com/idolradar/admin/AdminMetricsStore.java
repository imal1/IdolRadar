package com.idolradar.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.idolradar.api.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PRD 定义的两个转化类成功指标。
 *
 * <p>全部从既有业务数据聚合，不依赖任何前端埋点，因此天然跨端：未来 app 客户端接入后无需重复实现。
 *
 * <p>两处口径需要显式说明，否则数字会被误读：
 * <ul>
 *   <li>闭环转化率按注册队列计算——分母是区间内注册的新用户，分子是这些人中后来完成守护与授权的人数。
 *       若改成「区间内完成授权的人数」，一个第 1 天注册、第 3 天授权的用户会同时被算成失败和成功。</li>
 *   <li>回访率限定在投递完成后 24 小时内，与 PRD「收到推送后 24h 内打开小程序」一致。
 *       投递看板上的 openRate 没有这个时间窗，两者数字不同是预期的。</li>
 * </ul>
 *
 * <p>趋势按 Asia/Shanghai 自然日分桶，与动态流「今日」口径保持一致，否则同一天在两处会对不上。
 */
@Repository
public class AdminMetricsStore {
    private static final Duration DEFAULT_RANGE = Duration.ofDays(7);
    private static final int MAX_RANGE_DAYS = 90;
    private static final String TIMEZONE = "Asia/Shanghai";

    private final JdbcClient jdbc;

    public AdminMetricsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> coreMetrics(Integer rangeDays) {
        Duration range = range(rangeDays);
        OffsetDateTime since = OffsetDateTime.now().minus(range);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rangeDays", (int) range.toDays());
        result.put("funnel", funnel(since));
        result.put("delivery", deliveryOpenRate(since));
        result.put("trend", trend(since));
        return result;
    }

    /** 注册队列漏斗：新增、完成守护、完成订阅，三级人数一并返回，便于看出在哪一步流失。 */
    private Map<String, Object> funnel(OffsetDateTime since) {
        Map<String, Integer> counts = jdbc.sql("""
                        SELECT COUNT(*)::integer AS new_users,
                               COUNT(*) FILTER (WHERE first_guarded_at IS NOT NULL)::integer AS guarded,
                               COUNT(*) FILTER (WHERE first_subscribed_at IS NOT NULL)::integer AS subscribed
                        FROM idr_user
                        WHERE created_at >= :since
                        """)
                .param("since", since)
                .query((resultSet, rowNumber) -> Map.of(
                        "newUsers", resultSet.getInt("new_users"),
                        "guarded", resultSet.getInt("guarded"),
                        "subscribed", resultSet.getInt("subscribed")))
                .single();

        int newUsers = counts.get("newUsers");
        int guarded = counts.get("guarded");
        int subscribed = counts.get("subscribed");
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("newUsers", newUsers);
        funnel.put("guarded", guarded);
        funnel.put("subscribed", subscribed);
        // 分步转化让流失点可见：整体数字下滑时能直接看出是没人选 idol 还是选了不授权。
        funnel.put("guardRate", ratio(guarded, newUsers));
        funnel.put("subscribeRate", ratio(subscribed, guarded));
        funnel.put("conversionRate", ratio(subscribed, newUsers));
        return funnel;
    }

    /** 回访率：已送达投递中，在送达后 24 小时内被打开的比例。 */
    private Map<String, Object> deliveryOpenRate(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT COUNT(*)::integer AS sent,
                               COUNT(*) FILTER (
                                 WHERE first_opened_at IS NOT NULL
                                   AND first_opened_at < finished_at + INTERVAL '24 hours'
                               )::integer AS opened
                        FROM idr_notification_delivery
                        WHERE status = 'sent' AND finished_at IS NOT NULL AND finished_at >= :since
                        """)
                .param("since", since)
                .query((resultSet, rowNumber) -> {
                    int sent = resultSet.getInt("sent");
                    int opened = resultSet.getInt("opened");
                    Map<String, Object> delivery = new LinkedHashMap<>();
                    delivery.put("sent", sent);
                    delivery.put("openedWithin24h", opened);
                    delivery.put("openRate", ratio(opened, sent));
                    return delivery;
                })
                .single();
    }

    /** 按自然日的四条序列；空白日期也补零，否则折线会在没有数据的那天断开。 */
    private List<Map<String, Object>> trend(OffsetDateTime since) {
        return jdbc.sql("""
                        WITH days AS (
                          SELECT generate_series(
                            ((:since AT TIME ZONE :zone))::date,
                            ((NOW() AT TIME ZONE :zone))::date,
                            INTERVAL '1 day')::date AS day
                        )
                        SELECT d.day,
                               (SELECT COUNT(*)::integer FROM idr_user u
                                WHERE ((u.created_at AT TIME ZONE :zone))::date = d.day) AS new_users,
                               (SELECT COUNT(*)::integer FROM idr_user u
                                WHERE ((u.first_guarded_at AT TIME ZONE :zone))::date = d.day) AS guarded,
                               (SELECT COUNT(*)::integer FROM idr_user u
                                WHERE ((u.first_subscribed_at AT TIME ZONE :zone))::date = d.day) AS subscribed,
                               (SELECT COUNT(*)::integer FROM idr_notification_delivery n
                                WHERE ((n.first_opened_at AT TIME ZONE :zone))::date = d.day) AS opened
                        FROM days d
                        ORDER BY d.day
                        """)
                .param("since", since)
                .param("zone", TIMEZONE)
                .query((resultSet, rowNumber) -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("date", resultSet.getString("day"));
                    point.put("newUsers", resultSet.getInt("new_users"));
                    point.put("guarded", resultSet.getInt("guarded"));
                    point.put("subscribed", resultSet.getInt("subscribed"));
                    point.put("opened", resultSet.getInt("opened"));
                    return point;
                })
                .list();
    }

    private static Duration range(Integer rangeDays) {
        if (rangeDays == null) {
            return DEFAULT_RANGE;
        }
        if (rangeDays < 1 || rangeDays > MAX_RANGE_DAYS) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "INVALID_INPUT", "统计区间必须在 1 到 " + MAX_RANGE_DAYS + " 天之间");
        }
        return Duration.ofDays(rangeDays);
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0d : Math.round(part * 10000d / total) / 100d;
    }
}
