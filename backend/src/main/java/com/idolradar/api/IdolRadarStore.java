package com.idolradar.api;

import java.util.Map;
import java.util.UUID;

/** 面向已认证用户的数据访问契约；调用方只能提供服务端解析出的可信身份。 */
public interface IdolRadarStore {
    /**
     * 领取匿名请求回执后物理删除账号；同 ID 重放或认证后账号已被并发请求删除时，
     * 均收敛为成功但不重复计数。
     * 全部个人关联数据随用户级联删除，共享 idol、来源和动态不受影响；requestId
     * 不得与用户身份建立任何持久化关联。
     */
    boolean deleteAccount(UUID userId, UUID requestId);

    /** 用重新验证的 openId 恢复注销；只删除已有账号，绝不负责建档。 */
    boolean recoverAccountDeletion(String openId, UUID requestId);

    /** 公开查询匿名注销回执；未知或未完成的请求统一返回 false。 */
    boolean isAccountDeletionCompleted(UUID requestId);

    Map<String, Object> bootstrap(String openId);

    Map<String, Object> getHome(String openId);

    Map<String, Object> getFeed(String openId, String cursor);

    Map<String, Object> listIdols(String openId);

    /** 保存用户主动确认的微信昵称；静默登录不得调用。 */
    Map<String, Object> updateNickname(String openId, String nickname);

    /**
     * 守护一位 idol。
     *
     * <p>{@code guardLimit} 由服务端按客户端解析后传入：上限为 1 时语义是「换人即替换」，
     * 上限大于 1 时是追加，达到上限后再守护新的 idol 会被拒绝。
     */
    Map<String, Object> setIdol(String openId, String idolId, int guardLimit);

    Map<String, Object> recordSubscription(String openId, boolean accepted, String templateId);

    Map<String, Object> submitIdolRequest(String openId, String displayName, String note);

    Map<String, Object> listMyIdolRequests(String openId);

    /**
     * 记录一次「从推送落地小程序」的回访。
     *
     * <p>归因依据是投递表主键 (post_id, user_id)：没有对应投递记录时静默忽略，
     * 因此普通打开或伪造的 postId 都不会被计成回访。
     */
    Map<String, Object> recordNotificationOpen(String openId, String postId);

    /** 当前守护 idol 的全部来源，含用户自己的开关状态；不返回内部 rss_url。 */
    Map<String, Object> listMySources(String openId);

    /** 关闭或重新开启指定来源的推送；同一状态重复调用是安全的。 */
    Map<String, Object> setSourceMuted(String openId, String sourceId, boolean muted);
}
