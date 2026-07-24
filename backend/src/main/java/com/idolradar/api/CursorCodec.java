package com.idolradar.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 编码并严格校验键集分页所用的带版本不透明游标。 */
@Component
public class CursorCodec {
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final int MAX_ID_LENGTH = 128;
    private static final Pattern BASE64_URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern PAYLOAD = Pattern.compile(
            "\\{\\\"v\\\":1,\\\"p\\\":\\\"([^\\\"]+)\\\",\\\"i\\\":\\\"((?:\\\\.|[^\\\"\\\\])+)\\\"}");

    /**
     * 解码不可信的客户端游标；从第一页开始时返回 {@code null}。
     * 游标格式错误、过长或版本不受支持时，对外返回 {@code INVALID_CURSOR}。
     */
    public Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_CURSOR_LENGTH || !BASE64_URL.matcher(value).matches()) {
            throw invalidCursor();
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            // 游标结构固定；严格解析会拒绝多余字段和有歧义的 JSON 形式。
            Matcher matcher = PAYLOAD.matcher(json);
            if (!matcher.matches()) {
                throw invalidCursor();
            }
            String id = unescapeJson(matcher.group(2));
            if (id.isBlank() || id.length() > MAX_ID_LENGTH || hasControlCharacter(id)) {
                throw invalidCursor();
            }
            return new Cursor(Instant.parse(matcher.group(1)), id);
        } catch (IllegalArgumentException | DateTimeParseException error) {
            if (error instanceof AppException appException) {
                throw appException;
            }
            throw invalidCursor();
        }
    }

    /** 为不包含边界的 {@code (published_at, id)} 分页位置创建 URL 安全游标。 */
    public String encode(Instant publishedAt, String id) {
        if (publishedAt == null || id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Cannot create cursor from invalid post");
        }
        String json = "{\"v\":1,\"p\":\"" + publishedAt + "\",\"i\":\"" + escapeJson(id) + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped) {
                if (character == '\\') {
                    escaped = true;
                } else {
                    result.append(character);
                }
                continue;
            }
            if (character == '\\' || character == '"' || character == '/') {
                result.append(character);
            } else {
                throw invalidCursor();
            }
            escaped = false;
        }
        if (escaped) {
            throw invalidCursor();
        }
        return result.toString();
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private static AppException invalidCursor() {
        return new AppException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页参数无效");
    }

    public record Cursor(Instant publishedAt, String id) {
    }
}
