package com.idolradar.worker;

/** 在网络与资源安全边界内下载 RSS 原始内容。 */
public interface FeedDownloader {
    byte[] fetch(String url);
}
