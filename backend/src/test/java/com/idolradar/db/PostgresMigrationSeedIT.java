package com.idolradar.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
