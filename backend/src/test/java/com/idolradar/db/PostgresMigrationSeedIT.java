package com.idolradar.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idolradar.admin.AdminAuditRepository;
import com.idolradar.admin.JdbcAdminAuditRepository;
import com.idolradar.admin.AdminCatalogStore;
import com.idolradar.admin.AdminDeliveryStore;
import com.idolradar.admin.JdbcAdminAuthRepository;
import com.idolradar.api.AppException;
import com.idolradar.api.CursorCodec;
import com.idolradar.api.JdbcIdolRadarStore;
import com.idolradar.seed.SeedProperties;
import com.idolradar.seed.SeedService;
import com.idolradar.worker.WorkerModels;
import com.idolradar.worker.WorkerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresMigrationSeedIT {

    private PostgreSQLContainer<?> container;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private DataSource testDataSource;
    private String schema;
    private DatabaseCredentials credentials;

    @BeforeAll
    void migrateDatabase() {
        String enabled = System.getProperty(
                "idolradar.it.enabled",
                System.getenv().getOrDefault("IDOLRADAR_IT_ENABLED", "false"));
        Assumptions.assumeTrue(Boolean.parseBoolean(enabled),
                "enable with -Didolradar.it.enabled=true or IDOLRADAR_IT_ENABLED=true");

        credentials = databaseCredentials();
        DataSource adminDataSource = dataSource(credentials, null);
        adminJdbc = new JdbcTemplate(adminDataSource);
        schema = "idolradar_it_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);

        testDataSource = dataSource(credentials, schema);
        jdbc = new JdbcTemplate(testDataSource);
        Flyway.configure()
                .dataSource(testDataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clearBusinessData() {
        jdbc.execute("TRUNCATE idr_admin_audit_log, idr_admin_session, idr_admin_account, "
                + "idr_idol_request_supporter, idr_idol_request, idr_user_source_mute, idr_user_guard, "
                + "idr_notification_outbox, idr_notification_delivery, idr_user_session, "
                + "idr_post, idr_user, idr_source, idr_idol CASCADE");
    }

    @AfterAll
    void closeDatabase() {
        if (adminJdbc != null && schema != null) {
            adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Order(1)
    void flywayCreatesRequiredTablesAndDeliveryState() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() ORDER BY table_name",
                String.class);

        assertTrue(tables.containsAll(List.of(
                "flyway_schema_history",
                "idr_admin_account",
                "idr_admin_audit_log",
                "idr_admin_session",
                "idr_idol",
                "idr_idol_request",
                "idr_idol_request_supporter",
                "idr_notification_delivery",
                "idr_notification_outbox",
                "idr_post",
                "idr_source",
                "idr_user",
                "idr_user_guard",
                "idr_user_session",
                "idr_user_source_mute")));

        List<String> deliveryColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = 'idr_notification_delivery'",
                String.class);
        assertTrue(deliveryColumns.containsAll(List.of(
                "attempted_at",
                "finished_at",
                "template_id",
                "attempt_count",
                "next_attempt_at",
                "quota_reserved",
                "first_opened_at",
                "last_opened_at",
                "open_count")));

        List<String> userColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'idr_user'",
                String.class);
        assertTrue(userColumns.containsAll(List.of(
                "subscribe_template_id",
                "first_guarded_at",
                "first_subscribed_at",
                "nickname",
                "avatar_url",
                "profile_authorized_at")));

        List<String> idolRequestColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'idr_idol_request'",
                String.class);
        assertTrue(idolRequestColumns.contains("approved_idol_id"));

        List<String> outboxColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = 'idr_notification_outbox'",
                String.class);
        assertTrue(outboxColumns.containsAll(List.of(
                "idol_id",
                "post_id",
                "status",
                "attempt_count",
                "next_attempt_at",
                "lease_expires_at",
                "error_code")));

        // PostgreSQL COMMENT ON 才会进入元数据；SQL 文件中的 -- 注释不会被 Navicat 展示。
        Integer undocumentedTables = jdbc.queryForObject("""
                SELECT count(*)::integer
                FROM pg_class table_info
                JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace
                WHERE schema_info.nspname = current_schema()
                  AND table_info.relkind = 'r'
                  AND table_info.relname LIKE 'idr_%'
                  AND obj_description(table_info.oid, 'pg_class') IS NULL
                """, Integer.class);
        Integer undocumentedColumns = jdbc.queryForObject("""
                SELECT count(*)::integer
                FROM information_schema.columns column_info
                JOIN pg_class table_info ON table_info.relname = column_info.table_name
                JOIN pg_namespace schema_info
                  ON schema_info.oid = table_info.relnamespace
                 AND schema_info.nspname = column_info.table_schema
                WHERE column_info.table_schema = current_schema()
                  AND column_info.table_name LIKE 'idr_%'
                  AND col_description(table_info.oid, column_info.ordinal_position) IS NULL
                """, Integer.class);
        assertEquals(0, undocumentedTables);
        assertEquals(0, undocumentedColumns);
    }

    @Test
    @Order(2)
    void deliveryAndSessionConstraintsMatchTheStateMachine() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post "
                + "(id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态', "
                + "'https://example.com/posts/1', now(), now())");
        UUID userId = jdbc.queryForObject(
                "INSERT INTO idr_user (openid, subscribe_template_id) "
                        + "VALUES ('openid-1', 'template-1') RETURNING id",
                UUID.class);

        jdbc.update("INSERT INTO idr_user_session (token_hash, user_id, expires_at) "
                + "VALUES (?, ?, now() + interval '1 day')", "a".repeat(64), userId);
        jdbc.update("INSERT INTO idr_notification_delivery "
                + "(post_id, user_id, status, template_id, attempt_count, quota_reserved) "
                + "VALUES ('post-1', ?, 'reserved', 'template-1', 1, true)", userId);

        assertEquals("reserved", jdbc.queryForObject(
                "SELECT status FROM idr_notification_delivery WHERE post_id = 'post-1'",
                String.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE idr_notification_delivery SET status = 'pending' WHERE post_id = 'post-1'"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE idr_user SET subscribe_quota = 101 WHERE id = ?", userId));
        jdbc.update("INSERT INTO idr_notification_outbox (idol_id, post_id) VALUES ('idol-1', 'post-1')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE idr_notification_outbox SET status = 'lost' WHERE idol_id = 'idol-1'"));
    }

    @Test
    @Order(3)
    void postInsertAndOutboxMergeAreTransactionalAndLeaseRecovers() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        WorkerStore store = new WorkerStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));
        Instant fetched = Instant.parse("2026-01-03T00:00:00Z");
        WorkerModels.Post newest = new WorkerModels.Post(
                "post-new", "idol-1", "source-1", "RSS", "新动态", "", "https://example.com/new",
                Instant.parse("2026-01-02T00:00:00Z"), fetched);
        WorkerModels.Post older = new WorkerModels.Post(
                "post-old", "idol-1", "source-1", "RSS", "旧动态", "", "https://example.com/old",
                Instant.parse("2026-01-01T00:00:00Z"), fetched);

        store.insertPostAndEnqueue(newest);
        store.insertPostAndEnqueue(older);

        assertEquals("post-new", jdbc.queryForObject(
                "SELECT post_id FROM idr_notification_outbox WHERE idol_id = 'idol-1'", String.class));
        WorkerModels.OutboxTask claimed = store.claimNextOutbox(Duration.ofMinutes(5)).orElseThrow();
        assertEquals("post-new", claimed.postId());
        jdbc.update("UPDATE idr_notification_outbox SET lease_expires_at = NOW() - INTERVAL '1 second' "
                + "WHERE idol_id = 'idol-1'");
        assertEquals(1, store.recoverStaleOutbox());
        assertEquals("retryable", jdbc.queryForObject(
                "SELECT status FROM idr_notification_outbox WHERE idol_id = 'idol-1'", String.class));

        jdbc.execute("""
                CREATE FUNCTION reject_notification_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'reject outbox'; END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_notification_outbox "
                + "BEFORE INSERT OR UPDATE ON idr_notification_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION reject_notification_outbox()");
        WorkerModels.Post atomic = new WorkerModels.Post(
                "post-atomic", "idol-1", "source-1", "RSS", "原子动态", "", "https://example.com/atomic",
                Instant.parse("2026-01-04T00:00:00Z"), fetched);
        try {
            assertThrows(DataAccessException.class, () -> store.insertPostAndEnqueue(atomic));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM idr_post WHERE id = 'post-atomic'", Long.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_notification_outbox ON idr_notification_outbox");
            jdbc.execute("DROP FUNCTION reject_notification_outbox()");
        }
    }

    @Test
    @Order(4)
    void sourceHealthPreservesLastSuccessAndCountsConsecutiveFailures() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        WorkerStore store = new WorkerStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));

        store.updateSourceStatus("source-1", WorkerModels.SourceStatus.failed("UPSTREAM_TIMEOUT", 0));
        store.updateSourceStatus("source-1", WorkerModels.SourceStatus.failed("UPSTREAM_TIMEOUT", 0));
        assertEquals(2, jdbc.queryForObject(
                "SELECT consecutive_failures FROM idr_source WHERE id = 'source-1'", Integer.class));

        store.updateSourceStatus("source-1", WorkerModels.SourceStatus.success(5, 2));
        OffsetDateTime lastSuccessAt = jdbc.queryForObject(
                "SELECT last_success_at FROM idr_source WHERE id = 'source-1'", OffsetDateTime.class);
        assertEquals(0, jdbc.queryForObject(
                "SELECT consecutive_failures FROM idr_source WHERE id = 'source-1'", Integer.class));

        store.updateSourceStatus("source-1", WorkerModels.SourceStatus.failed("PARSE_ERROR", 0));
        assertEquals(lastSuccessAt, jdbc.queryForObject(
                "SELECT last_success_at FROM idr_source WHERE id = 'source-1'", OffsetDateTime.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT consecutive_failures FROM idr_source WHERE id = 'source-1'", Integer.class));
    }

    @Test
    @Order(5)
    void seedIsIdempotentAndPreservesFetchState(@TempDir Path seedDirectory) throws Exception {
        Files.writeString(seedDirectory.resolve("idols.seed.jsonl"),
                "{\"_id\":\"idol-1\",\"name\":\"初始名字\",\"avatar\":\"\","
                        + "\"bio\":\"初始简介\",\"enabled\":true}\n");
        Files.writeString(seedDirectory.resolve("sources.seed.jsonl"),
                "{\"_id\":\"source-1\",\"idolId\":\"idol-1\","
                        + "\"rsshubRoute\":\"/weibo/user/5492443184\","
                        + "\"displayName\":\"初始来源\","
                        + "\"channel\":\"初始频道\",\"enabled\":true,"
                        + "\"lastFetchStatus\":\"never\"}\n");

        SeedProperties properties = new SeedProperties();
        properties.setDirectory(seedDirectory);
        properties.setRsshubBaseUrl(java.net.URI.create("http://rsshub.test:1200"));
        SeedService service = new SeedService(jdbc, new ObjectMapper(), properties);
        SeedService.SeedResult first = service.seed();
        assertEquals(1, first.idols());
        assertEquals(1, first.sources());
        assertEquals("http://rsshub.test:1200/weibo/user/5492443184", jdbc.queryForObject(
                "SELECT rss_url FROM idr_source WHERE id = 'source-1'", String.class));

        jdbc.update("UPDATE idr_source SET last_fetch_status = 'success', "
                + "last_fetch_item_count = 7, last_fetch_new_count = 3 WHERE id = 'source-1'");
        Files.writeString(seedDirectory.resolve("idols.seed.jsonl"),
                "{\"_id\":\"idol-1\",\"name\":\"更新名字\",\"avatar\":\"\","
                        + "\"bio\":\"更新简介\",\"enabled\":true}\n");
        Files.writeString(seedDirectory.resolve("sources.seed.jsonl"),
                "{\"_id\":\"source-1\",\"idolId\":\"idol-1\","
                        + "\"rssUrl\":\"https://example.com/feed.xml\","
                        + "\"displayName\":\"更新来源\","
                        + "\"channel\":\"更新频道\",\"enabled\":true,"
                        + "\"lastFetchStatus\":\"never\"}\n");

        SeedService.SeedResult second = service.seed();
        assertEquals(first, second);
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM idr_idol", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM idr_source", Long.class));
        assertEquals("更新名字", jdbc.queryForObject(
                "SELECT name FROM idr_idol WHERE id = 'idol-1'", String.class));
        assertEquals("更新频道", jdbc.queryForObject(
                "SELECT channel FROM idr_source WHERE id = 'source-1'", String.class));
        assertEquals("更新来源", jdbc.queryForObject(
                "SELECT display_name FROM idr_source WHERE id = 'source-1'", String.class));
        assertEquals("success", jdbc.queryForObject(
                "SELECT last_fetch_status FROM idr_source WHERE id = 'source-1'", String.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT last_fetch_item_count FROM idr_source WHERE id = 'source-1'", Integer.class));

        OffsetDateTime updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM idr_source WHERE id = 'source-1'", OffsetDateTime.class);
        assertEquals(second, service.seed());
        assertEquals(updatedAt, jdbc.queryForObject(
                "SELECT updated_at FROM idr_source WHERE id = 'source-1'", OffsetDateTime.class));
    }

    @Test
    @Order(6)
    void v4RemovesOnlyLegacyDemoCatalogAndKeepsUserAccount() {
        String cleanupSchema = "idolradar_cleanup_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + cleanupSchema);
        try {
            DataSource cleanupDataSource = dataSource(credentials, cleanupSchema);
            JdbcTemplate cleanup = new JdbcTemplate(cleanupDataSource);
            Flyway.configure()
                    .dataSource(cleanupDataSource)
                    .defaultSchema(cleanupSchema)
                    .schemas(cleanupSchema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3"))
                    .load()
                    .migrate();

            cleanup.update("INSERT INTO idols (id, name) VALUES "
                    + "('idol_demo_lin_wan', '林晚'), ('idol_demo_su_nian', '苏念'), ('idol-real', '真实人物')");
            cleanup.update("INSERT INTO sources (id, idol_id, rss_url) VALUES "
                    + "('source_demo_lin_wan_official', 'idol_demo_lin_wan', 'https://example.com/demo'), "
                    + "('source-real', 'idol-real', 'https://example.com/real')");
            cleanup.update("INSERT INTO posts "
                    + "(id, idol_id, source_id, title, link, published_at, fetched_at) VALUES "
                    + "('post-demo', 'idol_demo_lin_wan', 'source_demo_lin_wan_official', '演示', "
                    + "'https://example.com/demo/post', now(), now())");
            UUID userId = cleanup.queryForObject(
                    "INSERT INTO users (openid, idol_id, guarding_since) "
                            + "VALUES ('openid-demo', 'idol_demo_lin_wan', now()) RETURNING id",
                    UUID.class);
            UUID realUserId = cleanup.queryForObject(
                    "INSERT INTO users (openid, idol_id, guarding_since) "
                            + "VALUES ('openid-real', 'idol-real', now()) RETURNING id",
                    UUID.class);
            cleanup.update("INSERT INTO notification_outbox (idol_id, post_id) "
                    + "VALUES ('idol_demo_lin_wan', 'post-demo')");

            Flyway.configure()
                    .dataSource(cleanupDataSource)
                    .defaultSchema(cleanupSchema)
                    .schemas(cleanupSchema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertEquals(0L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_idol WHERE id LIKE 'idol_demo_%'", Long.class));
            assertEquals(0L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_post WHERE id = 'post-demo'", Long.class));
            assertEquals(0L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_notification_outbox WHERE post_id = 'post-demo'", Long.class));
            assertEquals(1L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_idol WHERE id = 'idol-real'", Long.class));
            assertEquals(1L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_user WHERE id = ? AND idol_id IS NULL AND guarding_since IS NULL",
                    Long.class,
                    userId));
            assertEquals(1L, cleanup.queryForObject(
                    "SELECT count(*) FROM idr_user_guard WHERE user_id = ? AND idol_id = 'idol-real'",
                    Long.class,
                    realUserId));
        } finally {
            adminJdbc.execute("DROP SCHEMA " + cleanupSchema + " CASCADE");
        }
    }

    @Test
    @Order(7)
    void v6EnforcesSourceUniquenessAndApprovedRequestLink() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例一'), ('idol-2', '示例二')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源一')");

        // 同一地址可服务不同 idol；同一 idol 重复配置则必须由数据库拒绝。
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-2', 'idol-2', 'https://example.com/feed.xml', '示例源二')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                        + "VALUES ('source-duplicate', 'idol-1', 'https://example.com/feed.xml', '重复源')"));

        UUID adminId = jdbc.queryForObject(
                "INSERT INTO idr_admin_account (username, password_hash) "
                        + "VALUES ('admin', ?) RETURNING id",
                UUID.class,
                "a".repeat(60));
        UUID requestId = jdbc.queryForObject(
                "INSERT INTO idr_idol_request (normalized_name, display_name) "
                        + "VALUES ('example', '申请示例') RETURNING id",
                UUID.class);

        // 审核通过必须同时落下正式 idol 关联，避免出现无法追溯的“空通过”记录。
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE idr_idol_request SET status = 'approved', reviewed_by = ?, reviewed_at = now() "
                        + "WHERE id = ?",
                adminId,
                requestId));
        jdbc.update("UPDATE idr_idol_request SET status = 'approved', reviewed_by = ?, "
                        + "reviewed_at = now(), approved_idol_id = 'idol-1' WHERE id = ?",
                adminId,
                requestId);
        assertEquals("idol-1", jdbc.queryForObject(
                "SELECT approved_idol_id FROM idr_idol_request WHERE id = ?",
                String.class,
                requestId));
    }

    @Test
    @Order(8)
    void adminSessionRevocationAndAuditUseDedicatedTables() {
        JdbcClient client = JdbcClient.create(testDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(testDataSource);
        JdbcAdminAuthRepository auth = new JdbcAdminAuthRepository(client, transactionManager);
        JdbcAdminAuditRepository audit = new JdbcAdminAuditRepository(client);

        UUID adminId = auth.createAdmin("ops-admin", "pbkdf2-sha256$210000$salt$hash-value").adminId();
        // bootstrap 会被运维重复执行：同名账号必须返回既有 ID 且不改写口令哈希。
        var repeated = auth.createAdmin("ops-admin", "pbkdf2-sha256$210000$salt$other-hash");
        assertEquals(adminId, repeated.adminId());
        assertFalse(repeated.created());
        assertEquals("pbkdf2-sha256$210000$salt$hash-value", jdbc.queryForObject(
                "SELECT password_hash FROM idr_admin_account WHERE id = ?", String.class, adminId));
        String tokenHash = "b".repeat(64);
        assertTrue(auth.createSession(adminId, tokenHash, Instant.now().plus(Duration.ofHours(1))));
        assertTrue(auth.findSession(tokenHash).isPresent());

        audit.record(new AdminAuditRepository.AuditEvent(
                adminId,
                "HTTP_POST",
                "admin_route",
                "/admin/v1/auth/logout",
                "request-admin-1",
                200,
                true));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM idr_admin_audit_log WHERE admin_id = ?",
                Long.class,
                adminId));

        assertTrue(auth.revokeAccess(adminId));
        assertFalse(auth.findSession(tokenHash).isPresent());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT enabled FROM idr_admin_account WHERE id = ?",
                Boolean.class,
                adminId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM idr_admin_session WHERE admin_id = ? AND revoked_at IS NOT NULL",
                Long.class,
                adminId));
    }

    @Test
    @Order(9)
    void disablingIdolKeepsExistingGuardsButStopsCatalogAndFetching() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        UUID userId = jdbc.queryForObject(
                "INSERT INTO idr_user (openid, idol_id, guarding_since) "
                        + "VALUES ('openid-1', 'idol-1', now()) RETURNING id",
                UUID.class);
        jdbc.update("INSERT INTO idr_user_guard (user_id, idol_id, guarding_since) VALUES (?, 'idol-1', now())",
                userId);

        JdbcClient client = JdbcClient.create(testDataSource);
        AdminCatalogStore admin = new AdminCatalogStore(client);
        JdbcIdolRadarStore api = new JdbcIdolRadarStore(client, new CursorCodec());
        WorkerStore worker = new WorkerStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));

        // 新插入的 idol version 默认为 0（见 V5 迁移），乐观锁的期望版本必须跟着写 0。
        admin.updateIdol("idol-1", null, null, null, false, 0);

        // 停用只影响“新增”：候选名单与抓取立刻停止，已存在的守护关系一律保留，
        // 否则管理员一次停用就会静默清空用户的守护历史。
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM idr_user_guard WHERE user_id = ? AND idol_id = 'idol-1'",
                Long.class,
                userId));
        assertTrue(worker.loadEnabledSources().isEmpty());

        Map<String, Object> catalog = api.listIdols("openid-1");
        assertTrue(((List<?>) catalog.get("idols")).isEmpty());
        assertEquals("idol-1", catalog.get("currentIdolId"));
        AppException rejected = assertThrows(
                AppException.class, () -> api.setIdol("openid-1", "idol-1"));
        assertEquals("IDOL_NOT_FOUND", rejected.code());
    }

    @Test
    @Order(10)
    void deliveryDashboardAggregatesStatusesFailuresAndQueueBacklog() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post (id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态一', 'https://example.com/1', now(), now())");
        UUID userId = jdbc.queryForObject(
                "INSERT INTO idr_user (openid, idol_id) VALUES ('openid-1', 'idol-1') RETURNING id",
                UUID.class);
        UUID otherUserId = jdbc.queryForObject(
                "INSERT INTO idr_user (openid, idol_id) VALUES ('openid-2', 'idol-1') RETURNING id",
                UUID.class);
        UUID thirdUserId = jdbc.queryForObject(
                "INSERT INTO idr_user (openid, idol_id) VALUES ('openid-3', 'idol-1') RETURNING id",
                UUID.class);
        jdbc.update("INSERT INTO idr_notification_delivery "
                + "(post_id, user_id, status, attempt_count, first_opened_at, last_opened_at, open_count) "
                + "VALUES ('post-1', ?, 'sent', 1, now(), now(), 2)", userId);
        jdbc.update("INSERT INTO idr_notification_delivery "
                + "(post_id, user_id, status, error_code, attempt_count) "
                + "VALUES ('post-1', ?, 'failed', 'WECHAT_43101', 1)", otherUserId);
        // 尝试 4 次仍未送达：这正是「反复重试」筛选要捞出来的那一条。
        jdbc.update("INSERT INTO idr_notification_delivery "
                + "(post_id, user_id, status, error_code, attempt_count) "
                + "VALUES ('post-1', ?, 'retryable', 'WECHAT_45009', 4)", thirdUserId);
        jdbc.update("INSERT INTO idr_notification_outbox (idol_id, post_id, status, created_at) "
                + "VALUES ('idol-1', 'post-1', 'pending', now() - interval '2 hours')");

        AdminDeliveryStore store = new AdminDeliveryStore(JdbcClient.create(testDataSource));
        Map<String, Object> board = store.listDeliveries(null, null, 24);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) board.get("summary");
        assertEquals(3, summary.get("total"));
        assertEquals(1, summary.get("sent"));
        assertEquals(1, summary.get("stuck"));
        // 成功率分母只算已出结论的投递（sent + failed + uncertain），重试中的不计入。
        assertEquals(50.0, summary.get("successRate"));
        assertEquals(100.0, summary.get("openRate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) board.get("failures");
        assertEquals(2, failures.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) board.get("queue");
        assertEquals(1, queue.get("backlog"));
        assertTrue(queue.get("oldestQueuedAt") != null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stuck =
                (List<Map<String, Object>>) store.listDeliveries(null, "stuck", 24).get("deliveries");
        assertEquals(1, stuck.size());
        assertEquals(thirdUserId.toString(), stuck.get(0).get("userId"));

        // idol 维度筛选：另一个 idol 下没有任何投递与积压。
        @SuppressWarnings("unchecked")
        Map<String, Object> otherIdol = (Map<String, Object>) store.listDeliveries("idol-2", null, 24).get("summary");
        assertEquals(0, otherIdol.get("total"));
        assertThrows(AppException.class, () -> store.listDeliveries(null, "nonsense", 24));
        assertThrows(AppException.class, () -> store.listDeliveries(null, null, 24 * 31));
    }

    @Test
    @Order(11)
    void pushTargetsComeFromGuardTableAndIgnoreLegacyUserColumn() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post (id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态一', 'https://example.com/1', now(), now()),"
                + "       ('post-2', 'idol-2', 'source-1', '动态二', 'https://example.com/2', now(), now())");

        // 旧字段刻意写成与守护关系不一致：只有读路径确实切到关联表，候选名单才会正确。
        UUID single = insertUser("openid-single", null, "tpl-1", 3);
        UUID multi = insertUser("openid-multi", "idol-2", "tpl-1", 3);
        UUID noQuota = insertUser("openid-no-quota", "idol-1", "tpl-1", 0);
        UUID otherTemplate = insertUser("openid-other-template", "idol-1", "tpl-2", 3);
        UUID legacyOnly = insertUser("openid-legacy-only", "idol-1", "tpl-1", 3);
        guard(single, "idol-1");
        guard(multi, "idol-1");
        guard(multi, "idol-2");
        guard(noQuota, "idol-1");
        guard(otherTemplate, "idol-1");
        // legacyOnly 只有旧字段、没有守护关系，切换后不应再收到推送。

        WorkerStore store = new WorkerStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));

        // 保留数据库返回的顺序：PostgreSQL 按无符号字节序比较 uuid，
        // 与 Java 的 UUID.compareTo（有符号）不一致，重排会让游标断言取错起点。
        List<UUID> targets = store.loadEligibleUsers("post-1", "idol-1", "tpl-1", null, 50)
                .stream().map(WorkerModels.UserTarget::id).toList();
        assertEquals(2, targets.size(), targets.toString());
        assertTrue(targets.contains(single) && targets.contains(multi), targets.toString());
        assertFalse(targets.contains(legacyOnly), "只有旧字段、没有守护关系的用户不应再收到推送");
        assertFalse(targets.contains(noQuota) || targets.contains(otherTemplate), targets.toString());

        // 守护多位 idol 的用户，在单条动态上只出现一次——关联表按 idol 过滤后每人至多一行，
        // 不会因为多条守护关系把同一次推送放大成多条。
        assertEquals(1, targets.stream().filter(multi::equals).count());
        // 同一用户守护的另一位 idol 发动态时，同样能命中。
        assertEquals(
                List.of(multi),
                store.loadEligibleUsers("post-2", "idol-2", "tpl-1", null, 50)
                        .stream().map(WorkerModels.UserTarget::id).toList());

        // 游标分页变体走同一条守护表路径：从第一条之后继续，只剩后半段。
        assertEquals(
                targets.subList(1, targets.size()),
                store.loadEligibleUsers("post-1", "idol-1", "tpl-1", targets.get(0), 50)
                        .stream().map(WorkerModels.UserTarget::id).toList());

        // 额度扣减同样以守护关系为准：没有守护关系的用户无法被扣减，delivery 一并回滚。
        assertFalse(store.claimDelivery("post-1", legacyOnly, "idol-1", "tpl-1"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM idr_notification_delivery WHERE user_id = ?", Long.class, legacyOnly));
        assertTrue(store.claimDelivery("post-1", single, "idol-1", "tpl-1"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT subscribe_quota FROM idr_user WHERE id = ?", Integer.class, single));

        // 已有 delivery 的用户不再进入候选名单，避免同轮重复推送。
        assertEquals(
                List.of(multi),
                store.loadEligibleUsers("post-1", "idol-1", "tpl-1", null, 50)
                        .stream().map(WorkerModels.UserTarget::id).toList());
    }

    @Test
    @Order(12)
    void retryStopsWhenGuardRelationNoLongerCoversThePostIdol() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post (id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态一', 'https://example.com/1', now(), now())");
        // quota_reserved = TRUE 表示这次投递已经预扣过一次额度，因此账面余额是扣减后的 2。
        UUID userId = insertUser("openid-1", "idol-1", "tpl-1", 2);
        guard(userId, "idol-1");
        jdbc.update("INSERT INTO idr_notification_delivery "
                + "(post_id, user_id, template_id, status, attempt_count, quota_reserved, next_attempt_at) "
                + "VALUES ('post-1', ?, 'tpl-1', 'retryable', 1, TRUE, now() - interval '1 minute')", userId);

        WorkerStore store = new WorkerStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(testDataSource)));
        WorkerModels.RetryDelivery candidate = store.loadDueDeliveries(10).get(0);

        // 用户改守 idol-2：旧字段仍写着 idol-1，但守护关系已经不覆盖这条动态的 idol。
        jdbc.update("DELETE FROM idr_user_guard WHERE user_id = ?", userId);
        guard(userId, "idol-2");

        assertFalse(store.claimRetryDelivery(candidate, "tpl-1", 5));
        assertEquals("SUBSCRIPTION_CHANGED", jdbc.queryForObject(
                "SELECT error_code FROM idr_notification_delivery WHERE post_id = 'post-1' AND user_id = ?",
                String.class, userId));
        // 预留的额度必须退还，否则用户会因为换守护对象白白损失一次配额。
        assertEquals(3, jdbc.queryForObject(
                "SELECT subscribe_quota FROM idr_user WHERE id = ?", Integer.class, userId),
                "退还后应回到预扣之前的 3");
    }

    @Test
    @Order(13)
    void pushTargetQueryUsesGuardIndexInsteadOfScanningEveryUser() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post (id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态一', 'https://example.com/1', now(), now())");
        // 造够数据量，让规划器有理由选索引；行数太少时顺序扫描本来就更快，断言会失去意义。
        jdbc.update("INSERT INTO idr_user (openid, subscribe_template_id, subscribe_quota) "
                + "SELECT 'openid-' || i, 'tpl-1', 3 FROM generate_series(1, 5000) AS i");
        jdbc.update("INSERT INTO idr_user_guard (user_id, idol_id, guarding_since) "
                + "SELECT id, CASE WHEN random() < 0.01 THEN 'idol-1' ELSE 'idol-2' END, now() FROM idr_user");
        jdbc.execute("ANALYZE idr_user");
        jdbc.execute("ANALYZE idr_user_guard");

        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT u.id, u.openid
                FROM idr_user_guard g
                JOIN idr_user u ON u.id = g.user_id
                LEFT JOIN idr_notification_delivery d
                  ON d.post_id = 'post-1' AND d.user_id = u.id
                WHERE g.idol_id = 'idol-1'
                  AND u.subscribe_template_id = 'tpl-1'
                  AND u.subscribe_quota > 0
                  AND d.user_id IS NULL
                ORDER BY u.id ASC
                LIMIT 50
                """, String.class));

        assertTrue(plan.contains("idx_idr_user_guard_idol_id_user_id"), plan);
        assertFalse(plan.contains("Seq Scan on idr_user_guard"), plan);
    }

    @Test
    @Order(14)
    void clientReadPathsResolveCurrentIdolFromGuardTableNotLegacyColumn() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        jdbc.update("INSERT INTO idr_source (id, idol_id, rss_url, display_name) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml', '示例源')");
        jdbc.update("INSERT INTO idr_post (id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态一', 'https://example.com/1', now(), now())");

        // 旧字段写 idol-2、守护关系写 idol-1：只有读路径确实切到关联表，结果才会是 idol-1。
        UUID userId = insertUser("openid-1", "idol-2", "tpl-1", 3);
        guard(userId, "idol-1");
        // 旧字段有值但没有任何守护关系：切换后必须回到引导态。
        insertUser("openid-legacy-only", "idol-1", "tpl-1", 3);

        JdbcIdolRadarStore api = new JdbcIdolRadarStore(JdbcClient.create(testDataSource), new CursorCodec());

        assertEquals(Boolean.TRUE, api.bootstrap("openid-1").get("hasIdol"));
        assertEquals("idol-1", api.listIdols("openid-1").get("currentIdolId"));
        assertEquals("idol-1", currentIdolIdOfHome(api.getHome("openid-1")));
        assertEquals(1, ((List<?>) api.getFeed("openid-1", null).get("posts")).size());

        assertEquals(Boolean.FALSE, api.bootstrap("openid-legacy-only").get("hasIdol"));
        assertNull(api.listIdols("openid-legacy-only").get("currentIdolId"));
        assertTrue(((List<?>) api.getFeed("openid-legacy-only", null).get("posts")).isEmpty());

        // 更换守护对象 = 替换唯一的守护关系；旧字段在 #29 之前仍然同步写入。
        api.setIdol("openid-1", "idol-2");
        assertEquals(
                List.of("idol-2"),
                jdbc.queryForList("SELECT idol_id FROM idr_user_guard WHERE user_id = ?", String.class, userId));
        assertEquals("idol-2", jdbc.queryForObject(
                "SELECT idol_id FROM idr_user WHERE id = ?", String.class, userId));
        assertEquals("idol-2", api.listIdols("openid-1").get("currentIdolId"));
    }

    @Test
    @Order(15)
    void multipleGuardRowsResolveToTheMostRecentInsteadOfFailing() {
        jdbc.update("INSERT INTO idr_idol (id, name) VALUES ('idol-1', '示例'), ('idol-2', '其他')");
        UUID userId = insertUser("openid-1", null, "tpl-1", 3);
        jdbc.update("INSERT INTO idr_user_guard (user_id, idol_id, guarding_since) "
                + "VALUES (?, 'idol-1', now() - interval '2 days'), (?, 'idol-2', now())", userId, userId);

        JdbcIdolRadarStore api = new JdbcIdolRadarStore(JdbcClient.create(testDataSource), new CursorCodec());

        // 客户端当前限制一位，但后端模型已允许多条守护关系。读取必须稳定返回一行，
        // 否则一旦出现第二条，单值查询会直接抛错而不是给出确定结果。
        assertEquals("idol-2", api.listIdols("openid-1").get("currentIdolId"));
        assertEquals(Boolean.TRUE, api.bootstrap("openid-1").get("hasIdol"));
    }

    @SuppressWarnings("unchecked")
    private static String currentIdolIdOfHome(Map<String, Object> home) {
        Map<String, Object> idol = (Map<String, Object>) home.get("idol");
        // 序列化后的 idol 主键是 _id，沿用小程序侧的字段约定。
        return idol == null ? null : (String) idol.get("_id");
    }

    private UUID insertUser(String openid, String legacyIdolId, String templateId, int quota) {
        return jdbc.queryForObject(
                "INSERT INTO idr_user (openid, idol_id, subscribe_template_id, subscribe_quota) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, openid, legacyIdolId, templateId, quota);
    }

    private void guard(UUID userId, String idolId) {
        jdbc.update("INSERT INTO idr_user_guard (user_id, idol_id, guarding_since) VALUES (?, ?, now())",
                userId, idolId);
    }

    private DatabaseCredentials databaseCredentials() {
        String url = System.getenv("IDOLRADAR_TEST_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            return new DatabaseCredentials(
                    url,
                    System.getenv().getOrDefault("IDOLRADAR_TEST_DATABASE_USER", "idolradar"),
                    System.getenv().getOrDefault("IDOLRADAR_TEST_DATABASE_PASSWORD", "idolradar"));
        }

        container = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("idolradar")
                .withUsername("idolradar")
                .withPassword("idolradar");
        container.start();
        return new DatabaseCredentials(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private DataSource dataSource(DatabaseCredentials credentials, String currentSchema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(credentials.url());
        dataSource.setUser(credentials.username());
        dataSource.setPassword(credentials.password());
        if (currentSchema != null) {
            dataSource.setCurrentSchema(currentSchema);
        }
        return dataSource;
    }

    private record DatabaseCredentials(String url, String username, String password) {
    }
}
