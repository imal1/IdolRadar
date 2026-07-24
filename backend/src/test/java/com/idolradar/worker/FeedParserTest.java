package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class FeedParserTest {
    private final FeedParser parser = new FeedParser();

    @Test
    void parsesRssSanitizesTextAndDeduplicatesCanonicalLinks() {
        String xml = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item>
                    <title><![CDATA[<b> 新  动态 </b>]]></title>
                    <description><![CDATA[<script>bad()</script><p>正文</p>]]></description>
                    <link>https://example.com/post?id=1&amp;from=rss#top</link>
                    <pubDate>Tue, 10 Jun 2003 04:00:00 GMT</pubDate>
                  </item>
                  <item>
                    <title>重复</title>
                    <link>https://example.com/post?id=1&amp;from=rss</link>
                  </item>
                </channel></rss>
                """;

        List<WorkerModels.FeedEntry> entries = parser.parse(
                xml.getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().title()).isEqualTo("新 动态");
        assertThat(entries.getFirst().summary()).isEqualTo("正文");
        assertThat(entries.getFirst().link()).isEqualTo("https://example.com/post?id=1&from=rss");
        assertThat(entries.getFirst().publishedAt()).isEqualTo(Instant.parse("2003-06-10T04:00:00Z"));
        assertThat(parser.postIdForLink(entries.getFirst().link())).hasSize(64);
    }

    @Test
    void parsesAtomAlternateLinkAndFallsBackToFetchTime() {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>Atom post</title>
                    <summary>Summary</summary>
                    <link rel="self" href="https://example.com/api/1"/>
                    <link rel="alternate" href="https://example.com/post/1"/>
                    <updated>not-a-date</updated>
                  </entry>
                </feed>
                """;
        Instant fetchedAt = Instant.parse("2026-01-01T00:00:00Z");

        WorkerModels.FeedEntry entry = parser.parse(xml.getBytes(StandardCharsets.UTF_8), fetchedAt).getFirst();

        assertThat(entry.link()).isEqualTo("https://example.com/post/1");
        assertThat(entry.publishedAt()).isEqualTo(fetchedAt);
    }

    @Test
    void rejectsDoctypeBeforeXmlParserCanResolveExternalEntity() {
        String xml = """
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>&xxe;</title><link>https://example.com/1</link></item></channel></rss>
                """;

        assertThatThrownBy(() -> parser.parse(xml.getBytes(StandardCharsets.UTF_8), Instant.now()))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("UNSAFE_XML");
    }

    @Test
    void skipsInvalidLinksButRejectsFeedWithoutAnyUsableEntry() {
        String xml = """
                <rss><channel><item><title>bad</title><link>javascript:alert(1)</link></item></channel></rss>
                """;

        assertThatThrownBy(() -> parser.parse(xml.getBytes(StandardCharsets.UTF_8), Instant.now()))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("EMPTY_FEED");
    }
}
