package com.idolradar.worker;

/** WeChat API 或传输失败；可携带原始 errcode。 */
public class WechatException extends RuntimeException {
    private final Integer wechatCode;

    public WechatException(String message, Integer wechatCode) {
        super(message);
        this.wechatCode = wechatCode;
    }

    public WechatException(String message, Integer wechatCode, Throwable cause) {
        super(message, cause);
        this.wechatCode = wechatCode;
    }

    public Integer wechatCode() {
        return wechatCode;
    }
}
