package com.idolradar.admin;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.idolradar.api.AppException;
import com.idolradar.auth.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 管理员登录、会话解析和访问吊销；不接受微信用户身份。 */
@Service
public class AdminAuthService {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");
    private static final Pattern BEARER_TOKEN = Pattern.compile("^Bearer ([A-Za-z0-9_-]{32,256})$");
    private static final String PASSWORD_SCHEME = "pbkdf2-sha256";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_SALT_BYTES = 16;
    private static final int PASSWORD_HASH_BYTES = 32;
    private static final String DUMMY_PASSWORD_HASH = encodePassword(
            "not-a-real-admin-password".toCharArray(), new byte[PASSWORD_SALT_BYTES], PASSWORD_ITERATIONS);

    private final AdminAuthRepository repository;
    private final AdminProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public AdminAuthService(AdminAuthRepository repository, AdminProperties properties) {
        this(repository, properties, new SecureRandom());
    }

    AdminAuthService(
            AdminAuthRepository repository,
            AdminProperties properties,
            SecureRandom secureRandom) {
        this.repository = repository;
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    /** 验证管理员密码，返回只存哈希的不透明会话 token。 */
    public LoginResult login(String suppliedUsername, String suppliedPassword) {
        String username = suppliedUsername == null ? "" : suppliedUsername.trim();
        char[] password = suppliedPassword == null ? new char[0] : suppliedPassword.toCharArray();
        try {
            if (password.length > 256) {
                throw unauthorized();
            }
            Optional<AdminAuthRepository.Credential> found = USERNAME.matcher(username).matches()
                    ? repository.findCredential(username)
                    : Optional.empty();
            AdminAuthRepository.Credential credential = found.orElse(null);
            // 未知账号也执行同成本 PBKDF2，减少通过响应时间枚举管理员用户名的信号。
            String passwordHash = credential == null ? DUMMY_PASSWORD_HASH : credential.passwordHash();
            boolean passwordMatches = verifyPassword(password, passwordHash);
            if (credential == null || !credential.enabled() || !passwordMatches) {
                throw unauthorized();
            }

            String token = generateToken();
            Instant expiresAt = Instant.now().plus(properties.sessionTtl());
            if (!repository.createSession(
                    credential.adminId(), AuthService.hashToken(token), expiresAt)) {
                // 验密后账号可能已被另一请求停用；不得为其创建可用会话。
                throw unauthorized();
            }
            return new LoginResult(
                    token,
                    expiresAt,
                    new CurrentAdmin(credential.adminId(), credential.username()));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** 只从管理员会话表解析身份；小程序 token 即使格式相同也不能提权。 */
    public Identity authenticate(String authorization) {
        Matcher matcher = authorization == null ? null : BEARER_TOKEN.matcher(authorization);
        if (matcher == null || !matcher.matches()) {
            throw unauthorized();
        }
        String tokenHash = AuthService.hashToken(matcher.group(1));
        AdminAuthRepository.StoredIdentity stored = repository.findSession(tokenHash)
                .orElseThrow(AdminAuthService::unauthorized);
        return new Identity(stored.adminId(), stored.username(), tokenHash, stored.expiresAt());
    }

    /** 吊销当前会话；原始 token 不进入日志或数据库。 */
    public void logout(Identity identity) {
        repository.revokeSession(identity.tokenHash());
    }

    /** 停用目标管理员并吊销其全部会话。 */
    public void revokeAccess(UUID adminId) {
        if (!repository.revokeAccess(adminId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "ADMIN_NOT_FOUND", "管理员不存在");
        }
    }

    /** 一次性创建管理员；仅供 admin-bootstrap 命令模式使用。 */
    public BootstrapResult bootstrap(String suppliedUsername, String suppliedPassword) {
        String username = suppliedUsername == null ? "" : suppliedUsername.trim();
        char[] password = suppliedPassword == null ? new char[0] : suppliedPassword.toCharArray();
        try {
            if (!USERNAME.matcher(username).matches()) {
                throw new IllegalArgumentException(
                        "IDOLRADAR_ADMIN_USERNAME must be 3-64 ASCII letters, digits, dot, underscore, or dash");
            }
            if (password.length < 12 || password.length > 256) {
                throw new IllegalArgumentException(
                        "IDOLRADAR_ADMIN_PASSWORD must be between 12 and 256 characters");
            }
            byte[] salt = new byte[PASSWORD_SALT_BYTES];
            secureRandom.nextBytes(salt);
            String passwordHash = encodePassword(password, salt, PASSWORD_ITERATIONS);
            AdminAuthRepository.CreatedAdmin created = repository.createAdmin(username, passwordHash);
            return new BootstrapResult(created.adminId(), username, created.created());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String generateToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static boolean verifyPassword(char[] password, String encoded) {
        try {
            String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
            if (parts.length != 4 || !PASSWORD_SCHEME.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < 16 || expected.length < 32) {
                return false;
            }
            byte[] actual = derivePassword(password, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String encodePassword(char[] password, byte[] salt, int iterations) {
        byte[] hash = derivePassword(password, salt, iterations, PASSWORD_HASH_BYTES);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PASSWORD_SCHEME + "$" + iterations + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(hash);
    }

    private static byte[] derivePassword(char[] password, byte[] salt, int iterations, int bytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * Byte.SIZE);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }

    private static AppException unauthorized() {
        return new AppException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理员登录已失效，请重新登录");
    }

    public record LoginResult(String token, Instant expiresAt, CurrentAdmin admin) {
    }

    public record CurrentAdmin(UUID id, String username) {
    }

    public record Identity(UUID adminId, String username, String tokenHash, Instant expiresAt) {
        public CurrentAdmin currentAdmin() {
            return new CurrentAdmin(adminId, username);
        }
    }

    /** created 为 false 表示用户名已存在，本次执行未创建也未修改任何凭据。 */
    public record BootstrapResult(UUID adminId, String username, boolean created) {
    }
}
