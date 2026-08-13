package com.idolradar.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.idolradar.api.AppException;
import com.idolradar.worker.FeedDownloader;
import com.idolradar.worker.FeedException;
import com.idolradar.worker.FeedParser;
import com.idolradar.worker.WorkerModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceVerifierTest {
    private AdminCatalogStore store;
    private FeedDownloader downloader;
    private FeedParser parser;
    private SourceVerifier verifier;

    @BeforeEach
    void setUp() {
        store = mock(AdminCatalogStore.class);
        downloader = mock(FeedDownloader.class);
        parser = mock(FeedParser.class);
        verifier = new SourceVerifier(store, downloader, parser);
        when(store.findSourceTarget("source-1")).thenReturn(Optional.of(
                new AdminCatalogStore.SourceTarget("source-1", "示例源", "https://example.com/feed.xml")));
    }

    @Test
    void manualFetchReportsNewCountWithoutWritingAnything() {
        when(downloader.fetch("https://example.com/feed.xml")).thenReturn("<rss/>".getBytes());
        when(parser.parse(any(), any())).thenReturn(List.of(
                entry("已入库", "https://example.com/known"),
                entry("新动态", "https://example.com/fresh")));
        when(store.findExistingPostLinks(anyList())).thenReturn(Set.of("https://example.com/known"));

        Map<String, Object> result = verifier.verify("source-1");

        assertEquals(true, result.get("ok"));
        assertEquals(2, result.get("itemCount"));
        assertEquals(1, result.get("newCount"));
        assertEquals(false, result.get("persisted"));
        // 只读是这条路径的核心保证：与定时轮次并发时不会重复入库，也不会给真实用户发消息。
        verify(store, never()).createIdol(anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
        verify(store, never()).createSource(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
    }

    @Test
    void fetchFailureIsReportedAsResultNotAsServerError() {
        when(downloader.fetch(anyString())).thenThrow(new FeedException("HTTP_ERROR", "RSS 服务返回异常状态"));

        Map<String, Object> result = verifier.verify("source-1");

        assertFalse((Boolean) result.get("ok"));
        assertEquals("HTTP_ERROR", result.get("errorCode"));
        assertEquals(0, result.get("newCount"));
        assertTrue(((List<?>) result.get("samples")).isEmpty());
    }

    @Test
    void unknownSourceIsRejectedBeforeAnyNetworkCall() {
        when(store.findSourceTarget("missing")).thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class, () -> verifier.verify("missing"));

        assertEquals("SOURCE_NOT_FOUND", error.code());
        verify(downloader, never()).fetch(anyString());
    }

    private static WorkerModels.FeedEntry entry(String title, String link) {
        return new WorkerModels.FeedEntry(title, "", link, Instant.parse("2026-08-01T00:00:00Z"));
    }
}
