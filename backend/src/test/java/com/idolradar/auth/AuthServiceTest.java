package com.idolradar.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.idolradar.api.AppException;
import com.idolradar.config.BackendProperties;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private static final UUID USER_ID = UUID.fromString("815bd2ca-cf30-4b4e-8a91-5e90f8fe8750");

    @Test
    void loginStoresOnlySha256TokenHash() {
        FakeRepository repository = new FakeRepository();
        WechatGateway wechat = code -> new WechatGateway.WechatIdentity("openid-from-wechat", null);
        AuthService service = new AuthService(
                repository,
                wechat,
                new BackendProperties(Duration.ofDays(30), "template-test"),
                new SecureRandom());

        AuthService.LoginResult result = service.login("temporary-code");

        assertEquals("openid-from-wechat", repository.openId);
        assertEquals(USER_ID, repository.userId);
        assertEquals(43, result.token().length());
        assertEquals(AuthService.hashToken(result.token()), repository.tokenHash);
        assertNotEquals(result.token(), repository.tokenHash);
        assertTrue(result.expiresAt().isAfter(Instant.now().plus(Duration.ofDays(29))));
    }

    @Test
    void authenticateAcceptsOnlyLiveBearerSession() {
        String token = "a".repeat(43);
        FakeRepository repository = new FakeRepository();
        repository.expectedHash = AuthService.hashToken(token);
        AuthService service = new AuthService(
                repository,
                code -> new WechatGateway.WechatIdentity("unused", null),
                new BackendProperties(Duration.ofDays(30), ""));

        AuthService.Identity identity = service.authenticate("Bearer " + token);

        assertEquals("openid", identity.openId());
        AppException malformed = assertThrows(
                AppException.class, () -> service.authenticate("Bearer invalid"));
        AppException missing = assertThrows(AppException.class, () -> service.authenticate(null));
        assertEquals("UNAUTHORIZED", malformed.code());
        assertEquals("UNAUTHORIZED", missing.code());
    }

    @Test
    void loginRejectsMalformedCodeBeforeWechatCall() {
        FakeRepository repository = new FakeRepository();
        AuthService service = new AuthService(
                repository,
                code -> {
                    throw new AssertionError("Wechat must not be called");
                },
                new BackendProperties(Duration.ofDays(30), ""));

        AppException error = assertThrows(AppException.class, () -> service.login("x"));
        assertEquals("INVALID_INPUT", error.code());
    }

    private static final class FakeRepository implements AuthRepository {
        private String openId;
        private UUID userId;
        private String tokenHash;
        private String expectedHash;

        @Override
        public UUID ensureUser(String openId) {
            this.openId = openId;
            return USER_ID;
        }

        @Override
        public void createSession(UUID userId, String tokenHash, Instant expiresAt) {
            this.userId = userId;
            this.tokenHash = tokenHash;
        }

        @Override
        public Optional<StoredIdentity> findSession(String tokenHash) {
            if (!tokenHash.equals(expectedHash)) {
                return Optional.empty();
            }
            return Optional.of(new StoredIdentity(
                    USER_ID, "openid", Instant.now().plus(Duration.ofHours(1))));
        }
    }
}
