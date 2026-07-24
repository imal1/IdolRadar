package com.idolradar.worker;

import java.io.ByteArrayInputStream;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 将受大小限制的 RSS 2.0/Atom XML 解析为规范化、去重条目。
 *
 * <p>禁用 DTD、实体展开、XInclude、外部 DTD/Schema 访问，防止 XXE 与实体扩展攻击；
 * 规范化链接用于生成确定性 post ID。
 */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class FeedParser {
    // 防御纵深：即使绕过 downloader 直接调用 parser，也拒绝超大 XML。
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TITLE_CODEPOINTS = 120;
    private static final int MAX_SUMMARY_CODEPOINTS = 500;
    private static final int MAX_LINK_LENGTH = 2048;
    private static final Pattern UNSAFE_DECLARATION = Pattern.compile("<!\\s*(doctype|entity)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE = Pattern.compile("<style\\b[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    public List<WorkerModels.FeedEntry> parse(byte[] xml, Instant fetchedAt) {
        if (xml == null || xml.length == 0 || xml.length > MAX_XML_BYTES) {
            throw new FeedException("INVALID_XML", "RSS XML 无效");
        }
        String declarationScan = new String(xml, StandardCharsets.ISO_8859_1);
        if (UNSAFE_DECLARATION.matcher(declarationScan).find()) {
            throw new FeedException("UNSAFE_XML", "RSS XML 包含不安全声明");
        }

        Document document;
        try {
            DocumentBuilderFactory factory = secureFactory();
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (FeedException error) {
            throw error;
        } catch (SAXException | java.io.IOException | javax.xml.parsers.ParserConfigurationException error) {
            throw new FeedException("INVALID_XML", "RSS XML 解析失败", error);
        }

        Element root = document.getDocumentElement();
        List<Element> rawEntries;
        boolean atom;
        if (root != null && "rss".equalsIgnoreCase(localName(root))) {
            Element channel = firstDirectChild(root, "channel");
            rawEntries = channel == null ? List.of() : directChildren(channel, "item");
            atom = false;
        } else if (root != null && "feed".equalsIgnoreCase(localName(root))) {
            rawEntries = directChildren(root, "entry");
            atom = true;
        } else {
            throw new FeedException("UNSUPPORTED_FEED", "仅支持 RSS 2.0 或 Atom");
        }
        if (rawEntries.isEmpty()) {
            throw new FeedException("EMPTY_FEED", "RSS 未包含任何动态");
        }

        Instant fallback = fetchedAt == null ? Instant.now() : fetchedAt;
        Map<String, WorkerModels.FeedEntry> unique = new LinkedHashMap<>();
        for (Element entry : rawEntries) {
            try {
                WorkerModels.FeedEntry normalized = atom
                        ? normalizeAtom(entry, fallback)
                        : normalizeRss(entry, fallback);
                unique.putIfAbsent(normalized.link(), normalized);
            } catch (FeedException error) {
                if (!"INVALID_LINK".equals(error.code())) throw error;
            }
        }
        if (unique.isEmpty()) {
            throw new FeedException("EMPTY_FEED", "RSS 未包含可用动态");
        }
        return List.copyOf(unique.values());
    }

    public String canonicalizeLink(String rawLink) {
        if (rawLink == null || rawLink.isBlank() || rawLink.length() > MAX_LINK_LENGTH) {
            throw new FeedException("INVALID_LINK", "动态链接无效");
        }
        URI parsed;
        try {
            parsed = URI.create(rawLink.trim());
        } catch (IllegalArgumentException error) {
            throw new FeedException("INVALID_LINK", "动态链接无效", error);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null) {
            throw new FeedException("INVALID_LINK", "动态链接无效");
        }
        try {
            String host = parsed.getHost().contains(":")
                    ? parsed.getHost().toLowerCase(Locale.ROOT)
                    : IDN.toASCII(parsed.getHost().toLowerCase(Locale.ROOT));
            String authority = host.contains(":") ? "[" + host + "]" : host;
            if (parsed.getPort() >= 0) authority += ":" + parsed.getPort();
            String path = parsed.getRawPath();
            String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
            return new URI(scheme + "://" + authority
                    + (path == null || path.isEmpty() ? "/" : path)
                    + query).toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new FeedException("INVALID_LINK", "动态链接无效", error);
        }
    }

    public String postIdForLink(String link) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalizeLink(link).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private WorkerModels.FeedEntry normalizeRss(Element item, Instant fallback) {
        String title = directText(item, "title");
        String summary = firstNonBlank(
                directText(item, "description"),
                directText(item, "encoded"),
                directText(item, "content"));
        String link = directText(item, "link");
        if (link.isBlank()) {
            String guid = directText(item, "guid");
            if (guid.startsWith("http://") || guid.startsWith("https://")) link = guid;
        }
        String date = firstNonBlank(
                directText(item, "pubDate"),
                directText(item, "date"),
                directText(item, "updated"));
        return normalize(title, summary, link, date, fallback);
    }

    private WorkerModels.FeedEntry normalizeAtom(Element entry, Instant fallback) {
        String link = "";
        for (Element candidate : directChildren(entry, "link")) {
            String href = candidate.getAttribute("href");
            String rel = candidate.getAttribute("rel");
            if (!href.isBlank() && (rel.isBlank() || "alternate".equalsIgnoreCase(rel))) {
                link = href;
                break;
            }
            if (link.isBlank() && !href.isBlank()) link = href;
        }
        if (link.isBlank()) link = directText(entry, "link");
        return normalize(
                directText(entry, "title"),
                firstNonBlank(directText(entry, "summary"), directText(entry, "content")),
                link,
                firstNonBlank(directText(entry, "published"), directText(entry, "updated")),
                fallback);
    }

    private WorkerModels.FeedEntry normalize(
            String rawTitle,
            String rawSummary,
            String rawLink,
            String rawDate,
            Instant fallback) {
        String cleanTitle = stripHtml(rawTitle);
        String cleanSummary = stripHtml(rawSummary);
        String title = truncate(firstNonBlank(cleanTitle, cleanSummary, "新动态"), MAX_TITLE_CODEPOINTS);
        String summary = truncate(firstNonBlank(cleanSummary, title), MAX_SUMMARY_CODEPOINTS);
        return new WorkerModels.FeedEntry(
                title,
                summary,
                canonicalizeLink(rawLink),
                parseDate(rawDate, fallback));
    }

    private static DocumentBuilderFactory secureFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory;
        } catch (javax.xml.parsers.ParserConfigurationException | IllegalArgumentException error) {
            throw new FeedException("INVALID_XML", "XML 安全解析器不可用", error);
        }
    }

    private static Instant parseDate(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        List<java.util.function.Function<String, Instant>> parsers = List.of(
                text -> Instant.parse(text),
                text -> OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(),
                text -> ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        for (var parser : parsers) {
            try {
                return parser.apply(value.trim());
            } catch (DateTimeParseException ignored) {
                // 尝试下一种受支持的 RSS 日期格式。
            }
        }
        return fallback;
    }

    static String stripHtml(String value) {
        if (value == null) return "";
        String cleaned = SCRIPT.matcher(value).replaceAll(" ");
        cleaned = STYLE.matcher(cleaned).replaceAll(" ");
        cleaned = TAG.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        return SPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    private static String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String directText(Element parent, String name) {
        Element child = firstDirectChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element firstDirectChild(Element parent, String name) {
        for (Element child : directChildren(parent, name)) return child;
        return null;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && name.equalsIgnoreCase(localName(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null) return local;
        String tag = element.getTagName();
        int separator = tag.indexOf(':');
        return separator < 0 ? tag : tag.substring(separator + 1);
    }
}
