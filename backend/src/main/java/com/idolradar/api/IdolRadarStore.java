package com.idolradar.api;

import java.util.Map;

/** 面向已认证用户的数据访问契约；调用方必须提供经 WeChat 验证的 openId。 */
public interface IdolRadarStore {
    Map<String, Object> bootstrap(String openId);

    Map<String, Object> getHome(String openId);

    Map<String, Object> getFeed(String openId, String cursor);

    Map<String, Object> listIdols(String openId);

    Map<String, Object> setIdol(String openId, String idolId);

    Map<String, Object> recordSubscription(String openId, boolean accepted, String templateId);

    Map<String, Object> submitIdolRequest(String openId, String displayName, String note);

    Map<String, Object> listMyIdolRequests(String openId);

    /** 当前守护 idol 的全部来源，含用户自己的开关状态；不返回内部 rss_url。 */
    Map<String, Object> listMySources(String openId);

    /** 关闭或重新开启指定来源的推送；同一状态重复调用是安全的。 */
    Map<String, Object> setSourceMuted(String openId, String sourceId, boolean muted);
}
