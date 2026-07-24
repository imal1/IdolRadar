package com.idolradar.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.idolradar.api.AppException;
import com.idolradar.config.BackendProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 将 WeChat 登录 code 交换为可撤销、由服务端管理的不透明会话。 */
@Service
public class AuthService {
    private static final Pattern BEARER_TOKEN = Pattern.compile("^Bearer ([A-Za-z0-9_-]{32,256})$");

    private final AuthRepository repository;
    private final WechatGateway wechat;
    private final BackendProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public AuthService(AuthRepository repository, WechatGateway wechat, BackendProperties properties) {
        this(repository, wechat, properties, new SecureRandom());
    }

    AuthService(
            AuthRepository repository,
            WechatGateway wechat,
            BackendProperties properties,
            SecureRandom secureRandom) {
        this.repository = repository;
        this.wechat = wechat;
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    /**
     * 验证一次性 WeChat code，幂等创建用户并返回新的 256-bit bearer token。
     * 原始 token 只返回一次，绝不持久化。
     */
    public LoginResult login(String code) {
        if (code == null || code.length() < 4 || code.length() > 512) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "微信登录凭证无效");
        }
        WechatGateway.WechatIdentity wechatIdentity = wechat.exchangeCode(code);
        UUID userId = repository.ensureUser(wechatIdentity.openId());
        // CSPRNG 生成的 256-bit token 不嵌入用户或会话元数据，保持不可猜测。
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(properties.sessionTtl());
        repository.createSession(userId, hashToken(token), expiresAt);
        return new LoginResult(token, expiresAt);
    }

    /** 校验 Bearer 语法，仅解析服务端仍有效的会话。 */
    public Identity authenticate(String authorization) {
        Matcher matcher = authorization == null ? null : BEARER_TOKEN.matcher(authorization);
        if (matcher == null || !matcher.matches()) {
            throw unauthorized();
        }
        AuthRepository.StoredIdentity stored = repository.findSession(hashToken(matcher.group(1)))
                .orElseThrow(AuthService::unauthorized);
        return new Identity(stored.userId(), stored.openId(), stored.expiresAt());
    }

    /**
     * 生成存入 PostgreSQL 的查询键。
     * 哈希可防止数据库读取直接泄露能够认证请求的凭证。
     */
    public static String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static AppException unauthorized() {
        return new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已失效，请重新进入小程序");
    }

    public record LoginResult(String token, Instant expiresAt) {
    }

    public record Identity(UUID userId, String openId, Instant expiresAt) {
    }
}
