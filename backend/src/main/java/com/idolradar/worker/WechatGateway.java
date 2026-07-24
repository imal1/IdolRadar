package com.idolradar.worker;

/** WeChat 订阅消息发送边界。 */
public interface WechatGateway {
    /**
     * {@code beforeSend} 必须在首次 HTTP POST 前且仅执行一次；回调失败不得发送。
     * 回调成功后，传输异常可能表示发送结果未知。
     */
    void sendSubscribeMessage(WorkerModels.SubscribeMessage message, Runnable beforeSend);
}
