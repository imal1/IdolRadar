package com.idolradar.auth;

/** WeChat code2Session 客户端实现的最小身份验证边界。 */
public interface WechatGateway {
    /** 仅返回已验证身份字段；服务商会话密钥保留在内部。 */
    WechatIdentity exchangeCode(String code);

    record WechatIdentity(String openId, String unionId) {
    }
}
