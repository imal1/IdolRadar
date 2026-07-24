package com.idolradar.worker;

/** 表示 WeChat 全局失败；当前通知 fanout 必须停止并持久化重试。 */
public class NotificationAbortException extends RuntimeException {
    private final Integer wechatCode;

    NotificationAbortException(Integer wechatCode) {
        super("微信订阅消息发送已中止");
        this.wechatCode = wechatCode;
    }

    public Integer wechatCode() {
        return wechatCode;
    }
}
