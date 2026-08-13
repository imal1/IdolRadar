package com.idolradar.admin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.idolradar.api.AppException;
import com.idolradar.worker.FeedDownloader;
import com.idolradar.worker.FeedException;
import com.idolradar.worker.FeedParser;
import com.idolradar.worker.WorkerModels;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 管理端手动抓取：按真实抓取链路取回并解析一次，但只报告结果，不写任何数据。
 *
 * <p>「只读」是这里的核心约束，而不是省事：定时轮次与本次验证可能同时跑同一个源，
 * 一旦手动路径也入库就必须自己处理并发去重与「本次是否该触发推送」；
 * 只读让这两个问题在结构上不存在——不会重复入库，也不会给真实用户发消息。
 * 新增条数由链接与既有 post 比对得出，因此结论仍然可信。
 */
@Service
public class SourceVerifier {
    /** 抓取结果只回显少量样本，够管理员判断「这个源解析得对不对」即可。 */
    private static final int SAMPLE_SIZE = 3;

    private final AdminCatalogStore store;
    private final FeedDownloader downloader;
    private final FeedParser parser;

    public SourceVerifier(AdminCatalogStore store, FeedDownloader downloader, FeedParser parser) {
        this.store = store;
        this.downloader = downloader;
        this.parser = parser;
    }

    public Map<String, Object> verify(String sourceId) {
        AdminCatalogStore.SourceTarget source = store.findSourceTarget(sourceId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "源不存在"));
        return verifyUrl(source.rssUrl());
    }

    /**
     * 抓取并解析给定地址。
     *
     * <p>抓取失败属于被检查对象的正常结果，因此返回 ok=false 而不是抛异常——
     * 管理员需要看到具体错误码来判断是源挂了还是地址写错了。
     */
    public Map<String, Object> verifyUrl(String rssUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rssUrl", rssUrl);
        result.put("checkedAt", Instant.now().toString());
        result.put("persisted", false);
        try {
            List<WorkerModels.FeedEntry> entries = parser.parse(downloader.fetch(rssUrl), Instant.now());
            Set<String> known = store.findExistingPostLinks(entries.stream()
                    .map(WorkerModels.FeedEntry::link)
                    .toList());
            result.put("ok", true);
            result.put("errorCode", null);
            result.put("itemCount", entries.size());
            result.put("newCount", (int) entries.stream()
                    .filter(entry -> !known.contains(entry.link()))
                    .count());
            result.put("samples", entries.stream()
                    .limit(SAMPLE_SIZE)
                    .map(entry -> {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("title", entry.title());
                        sample.put("link", entry.link());
                        sample.put("publishedAt", entry.publishedAt() == null
                                ? null
                                : entry.publishedAt().toString());
                        sample.put("known", known.contains(entry.link()));
                        return sample;
                    })
                    .toList());
        } catch (FeedException error) {
            result.put("ok", false);
            result.put("errorCode", error.code());
            result.put("itemCount", 0);
            result.put("newCount", 0);
            result.put("samples", List.of());
        }
        return result;
    }
}
