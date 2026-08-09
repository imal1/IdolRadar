package com.idolradar.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从配置目录中的 JSONL 文件幂等导入偶像与 RSS 源。
 * 整批导入位于同一事务：任一记录无效时不留下半套数据。
 */
@Service
public class SeedService {

    private static final String IDOLS_FILE = "idols.seed.jsonl";
    private static final String SOURCES_FILE = "sources.seed.jsonl";
    private static final Set<String> FETCH_STATUSES = Set.of("never", "success", "failed");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SeedProperties properties;

    public SeedService(JdbcTemplate jdbc, ObjectMapper objectMapper, SeedProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
    }

    @Transactional
    public SeedResult seed() {
        // 先完整解析并校验两个文件，再执行 SQL，降低数据错误导致部分写入的风险。
        List<IdolSeed> idols = readJsonLines(IDOLS_FILE).stream()
                .map(entry -> new IdolSeed(
                        requiredText(entry.node(), "_id", entry.location()),
                        requiredText(entry.node(), "name", entry.location()),
                        optionalText(entry.node(), "avatar", "", entry.location()),
                        optionalText(entry.node(), "bio", "", entry.location()),
                        optionalBoolean(entry.node(), "enabled", true, entry.location())))
                .toList();
        List<SourceSeed> sources = readJsonLines(SOURCES_FILE).stream()
                .map(entry -> new SourceSeed(
                        requiredText(entry.node(), "_id", entry.location()),
                        requiredText(entry.node(), "idolId", entry.location()),
                        sourceUrl(entry.node(), entry.location()),
                        requiredText(entry.node(), "displayName", entry.location()),
                        optionalText(entry.node(), "channel", "RSS", entry.location()),
                        optionalBoolean(entry.node(), "enabled", true, entry.location()),
                        fetchStatus(entry.node(), entry.location())))
                .toList();

        for (IdolSeed idol : idols) {
            // IS DISTINCT FROM 兼容 NULL，并避免内容未变时制造无意义的 updated_at/数据库写放大。
            jdbc.update("""
                    INSERT INTO idr_idol (id, name, avatar, bio, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                      name = EXCLUDED.name,
                      avatar = EXCLUDED.avatar,
                      bio = EXCLUDED.bio,
                      enabled = EXCLUDED.enabled,
                      version = idr_idol.version + 1,
                      updated_at = now()
                    WHERE (idr_idol.name, idr_idol.avatar, idr_idol.bio, idr_idol.enabled)
                      IS DISTINCT FROM
                      (EXCLUDED.name, EXCLUDED.avatar, EXCLUDED.bio, EXCLUDED.enabled)
                    """, idol.id(), idol.name(), idol.avatar(), idol.bio(), idol.enabled());
        }

        for (SourceSeed source : sources) {
            // last_fetch_* 是 Worker 运行状态；重复导入配置时不得覆盖抓取进度。
            jdbc.update("""
                    INSERT INTO idr_source
                      (id, idol_id, rss_url, display_name, channel, enabled, last_fetch_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                      idol_id = EXCLUDED.idol_id,
                      rss_url = EXCLUDED.rss_url,
                      display_name = EXCLUDED.display_name,
                      channel = EXCLUDED.channel,
                      enabled = EXCLUDED.enabled,
                      version = idr_source.version + 1,
                      updated_at = now()
                    WHERE (idr_source.idol_id, idr_source.rss_url, idr_source.display_name,
                           idr_source.channel, idr_source.enabled)
                      IS DISTINCT FROM
                      (EXCLUDED.idol_id, EXCLUDED.rss_url, EXCLUDED.display_name,
                       EXCLUDED.channel, EXCLUDED.enabled)
                    """, source.id(), source.idolId(), source.rssUrl(), source.displayName(),
                    source.channel(), source.enabled(), source.lastFetchStatus());
        }

        return new SeedResult(idols.size(), sources.size());
    }

    private List<JsonLine> readJsonLines(String filename) {
        // 文件名来自内部常量；location 保留行号，配置错误能直接定位到源文件。
        Path file = properties.getDirectory().resolve(filename).normalize();
        List<JsonLine> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String location = filename + ":" + lineNumber;
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException error) {
                    throw new IllegalArgumentException(location + " is not valid JSON", error);
                }
                if (node == null || !node.isObject()) {
                    throw new IllegalArgumentException(location + " must contain a JSON object");
                }
                records.add(new JsonLine(node, location));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read seed file: " + file, error);
        }
        return records;
    }

    private String requiredText(JsonNode record, String field, String location) {
        JsonNode value = record.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(location + " requires non-blank text field " + field);
        }
        return value.textValue().trim();
    }

    /**
     * 普通源可直接给 rssUrl；RSSHub 源保存稳定 route，按环境拼接实例 origin。
     * 这样同一份业务 seed 可在本机 RSSHub 与生产 HTTPS RSSHub 间切换。
     */
    private String sourceUrl(JsonNode record, String location) {
        String rssUrl = nullableText(record, "rssUrl", location);
        String rsshubRoute = nullableText(record, "rsshubRoute", location);
        if ((rssUrl == null) == (rsshubRoute == null)) {
            throw new IllegalArgumentException(location + " requires exactly one of rssUrl or rsshubRoute");
        }
        if (rssUrl != null) {
            return rssUrl;
        }
        if (!rsshubRoute.startsWith("/") || rsshubRoute.startsWith("//")) {
            throw new IllegalArgumentException(location + " has invalid rsshubRoute");
        }
        URI base = properties.getRsshubBaseUrl();
        if (base == null || base.getScheme() == null || base.getHost() == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null
                || (base.getRawPath() != null && !base.getRawPath().isBlank()
                    && !"/".equals(base.getRawPath()))) {
            throw new IllegalStateException("idolradar.seed.rsshub-base-url must be an origin");
        }
        String scheme = base.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("http", "https").contains(scheme)) {
            throw new IllegalStateException("idolradar.seed.rsshub-base-url must use HTTP or HTTPS");
        }
        try {
            String authority = base.getHost().contains(":") ? "[" + base.getHost() + "]" : base.getHost();
            if (base.getPort() >= 0) authority += ":" + base.getPort();
            return new URI(scheme, authority, rsshubRoute, null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(location + " has invalid rsshubRoute", error);
        }
    }

    private String nullableText(JsonNode record, String field, String location) {
        JsonNode value = record.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(location + " requires non-blank text field " + field);
        }
        return value.textValue().trim();
    }

    private String optionalText(JsonNode record, String field, String fallback, String location) {
        JsonNode value = record.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(location + " requires text field " + field);
        }
        String normalized = value.textValue().trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private boolean optionalBoolean(JsonNode record, String field, boolean fallback, String location) {
        JsonNode value = record.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(location + " requires boolean field " + field);
        }
        return value.booleanValue();
    }

    private String fetchStatus(JsonNode record, String location) {
        String status = optionalText(record, "lastFetchStatus", "never", location);
        if (!FETCH_STATUSES.contains(status)) {
            throw new IllegalArgumentException(location + " has invalid lastFetchStatus");
        }
        return status;
    }

    public record SeedResult(int idols, int sources) {
    }

    private record JsonLine(JsonNode node, String location) {
    }

    private record IdolSeed(
            String id,
            String name,
            String avatar,
            String bio,
            boolean enabled) {
    }

    private record SourceSeed(
            String id,
            String idolId,
            String rssUrl,
            String displayName,
            String channel,
            boolean enabled,
            String lastFetchStatus) {
    }
}
