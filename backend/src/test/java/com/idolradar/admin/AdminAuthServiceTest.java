package com.idolradar.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.idolradar.api.AppException;
import com.idolradar.auth.AuthService;
import org.junit.jupiter.api.Test;

class AdminAuthServiceTest {
    private static final UUID ADMIN_ID = UUID.fromString("c8b2df63-e75f-4c8e-af03-a56bdd8e15b5");
    private static final String PASSWORD = "StrongAdmin!2026";

    @Test
    void bootstrapStoresStrongHashAndCreatesIndependentSession() {
        FakeRepository repository = new FakeRepository();
        AdminAuthService service = new AdminAuthService(
                repository, new AdminProperties(Duration.ofHours(12)), new SecureRandom());

        AdminAuthService.BootstrapResult bootstrap = service.bootstrap("ops-admin", PASSWORD);
        AdminAuthService.LoginResult login = service.login("ops-admin", PASSWORD);
        AdminAuthService.Identity identity = service.authenticate("Bearer " + login.token());

        assertEquals(ADMIN_ID, bootstrap.adminId());
        assertTrue(repository.passwordHash.startsWith("pbkdf2-sha256$"));
        assertNotEquals(PASSWORD, repository.passwordHash);
        assertFalse(repository.passwordHash.contains(PASSWORD));
        assertEquals(AuthService.hashToken(login.token()), repository.sessionTokenHash);
        assertEquals(ADMIN_ID, identity.adminId());
        assertEquals("ops-admin", identity.username());
    }

    @Test
    void wrongPasswordAndUserTokenCannotAuthenticateAsAdmin() {
        FakeRepository repository = new FakeRepository();
        AdminAuthService service = new AdminAuthService(
                repository, new AdminProperties(Duration.ofHours(12)), new SecureRandom());
        service.bootstrap("ops-admin", PASSWORD);

        AppException passwordError = assertThrows(
                AppException.class, () -> service.login("ops-admin", "WrongPassword!2026"));
        AppException userTokenError = assertThrows(
                AppException.class, () -> service.authenticate("Bearer " + "u".repeat(43)));

        assertEquals("ADMIN_UNAUTHORIZED", passwordError.code());
        assertEquals("ADMIN_UNAUTHORIZED", userTokenError.code());
    }

    @Test
    void revokingAdminDisablesLoginAndAllExistingSessions() {
        FakeRepository repository = new FakeRepository();
        AdminAuthService service = new AdminAuthService(
                repository, new AdminProperties(Duration.ofHours(12)), new SecureRandom());
        service.bootstrap("ops-admin", PASSWORD);
        AdminAuthService.LoginResult login = service.login("ops-admin", PASSWORD);

        service.revokeAccess(ADMIN_ID);

        assertThrows(AppException.class, () -> service.authenticate("Bearer " + login.token()));
        assertThrows(AppException.class, () -> service.login("ops-admin", PASSWORD));
        assertFalse(repository.enabled);
        assertTrue(repository.sessionRevoked);
    }

    private static final class FakeRepository implements AdminAuthRepository {
        private String username;
        private String passwordHash;
        private boolean enabled;
        private String sessionTokenHash;
        private Instant sessionExpiresAt;
        private boolean sessionRevoked;

        @Override
        public Optional<Credential> findCredential(String username) {
            if (!username.equals(this.username)) return Optional.empty();
            return Optional.of(new Credential(ADMIN_ID, username, passwordHash, enabled));
        }

        @Override
        public boolean createSession(UUID adminId, String tokenHash, Instant expiresAt) {
            if (!ADMIN_ID.equals(adminId) || !enabled) return false;
            sessionTokenHash = tokenHash;
            sessionExpiresAt = expiresAt;
            sessionRevoked = false;
            return true;
        }

        @Override
        public Optional<StoredIdentity> findSession(String tokenHash) {
            if (!enabled || sessionRevoked || !tokenHash.equals(sessionTokenHash)) return Optional.empty();
            return Optional.of(new StoredIdentity(ADMIN_ID, username, sessionExpiresAt));
        }

        @Override
        public void revokeSession(String tokenHash) {
            if (tokenHash.equals(sessionTokenHash)) sessionRevoked = true;
        }

        @Override
        public boolean revokeAccess(UUID adminId) {
            if (!ADMIN_ID.equals(adminId) || username == null) return false;
            enabled = false;
            sessionRevoked = true;
            return true;
        }

        @Override
        public UUID createAdmin(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
            enabled = true;
            return ADMIN_ID;
        }
    }
}
