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
}
