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

    @BeforeAll
    void migrateDatabase() {
        String enabled = System.getProperty(
                "idolradar.it.enabled",
                System.getenv().getOrDefault("IDOLRADAR_IT_ENABLED", "false"));
        Assumptions.assumeTrue(Boolean.parseBoolean(enabled),
                "enable with -Didolradar.it.enabled=true or IDOLRADAR_IT_ENABLED=true");

        DatabaseCredentials credentials = databaseCredentials();
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
        jdbc.execute("TRUNCATE notification_outbox, notification_deliveries, sessions, posts, users, sources, idols CASCADE");
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
                "idols",
                "notification_deliveries",
                "notification_outbox",
                "posts",
                "sessions",
                "sources",
                "users")));

        List<String> deliveryColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = 'notification_deliveries'",
                String.class);
        assertTrue(deliveryColumns.containsAll(List.of(
                "attempted_at",
                "finished_at",
                "template_id",
                "attempt_count",
                "next_attempt_at",
                "quota_reserved")));

        List<String> userColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'users'",
                String.class);
        assertTrue(userColumns.contains("subscribe_template_id"));

        List<String> outboxColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = 'notification_outbox'",
                String.class);
        assertTrue(outboxColumns.containsAll(List.of(
                "idol_id",
                "post_id",
                "status",
                "attempt_count",
                "next_attempt_at",
                "lease_expires_at",
                "error_code")));
    }

    @Test
    @Order(2)
    void deliveryAndSessionConstraintsMatchTheStateMachine() {
        jdbc.update("INSERT INTO idols (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO sources (id, idol_id, rss_url) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml')");
        jdbc.update("INSERT INTO posts "
                + "(id, idol_id, source_id, title, link, published_at, fetched_at) "
                + "VALUES ('post-1', 'idol-1', 'source-1', '动态', "
                + "'https://example.com/posts/1', now(), now())");
        UUID userId = jdbc.queryForObject(
                "INSERT INTO users (openid, subscribe_template_id) "
                        + "VALUES ('openid-1', 'template-1') RETURNING id",
                UUID.class);

        jdbc.update("INSERT INTO sessions (token_hash, user_id, expires_at) "
                + "VALUES (?, ?, now() + interval '1 day')", "a".repeat(64), userId);
        jdbc.update("INSERT INTO notification_deliveries "
                + "(post_id, user_id, status, template_id, attempt_count, quota_reserved) "
                + "VALUES ('post-1', ?, 'reserved', 'template-1', 1, true)", userId);

        assertEquals("reserved", jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE post_id = 'post-1'",
                String.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE notification_deliveries SET status = 'pending' WHERE post_id = 'post-1'"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE users SET subscribe_quota = 101 WHERE id = ?", userId));
        jdbc.update("INSERT INTO notification_outbox (idol_id, post_id) VALUES ('idol-1', 'post-1')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE notification_outbox SET status = 'lost' WHERE idol_id = 'idol-1'"));
    }

    @Test
    @Order(3)
    void postInsertAndOutboxMergeAreTransactionalAndLeaseRecovers() {
        jdbc.update("INSERT INTO idols (id, name) VALUES ('idol-1', '示例')");
        jdbc.update("INSERT INTO sources (id, idol_id, rss_url) "
                + "VALUES ('source-1', 'idol-1', 'https://example.com/feed.xml')");
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
                "SELECT post_id FROM notification_outbox WHERE idol_id = 'idol-1'", String.class));
        WorkerModels.OutboxTask claimed = store.claimNextOutbox(Duration.ofMinutes(5)).orElseThrow();
        assertEquals("post-new", claimed.postId());
        jdbc.update("UPDATE notification_outbox SET lease_expires_at = NOW() - INTERVAL '1 second' "
                + "WHERE idol_id = 'idol-1'");
        assertEquals(1, store.recoverStaleOutbox());
        assertEquals("retryable", jdbc.queryForObject(
                "SELECT status FROM notification_outbox WHERE idol_id = 'idol-1'", String.class));

        jdbc.execute("""
                CREATE FUNCTION reject_notification_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'reject outbox'; END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_notification_outbox "
                + "BEFORE INSERT OR UPDATE ON notification_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION reject_notification_outbox()");
        WorkerModels.Post atomic = new WorkerModels.Post(
                "post-atomic", "idol-1", "source-1", "RSS", "原子动态", "", "https://example.com/atomic",
                Instant.parse("2026-01-04T00:00:00Z"), fetched);
        try {
            assertThrows(DataAccessException.class, () -> store.insertPostAndEnqueue(atomic));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM posts WHERE id = 'post-atomic'", Long.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_notification_outbox ON notification_outbox");
            jdbc.execute("DROP FUNCTION reject_notification_outbox()");
        }
    }

    @Test
    @Order(4)
    void seedIsIdempotentAndPreservesFetchState(@TempDir Path seedDirectory) throws Exception {
        Files.writeString(seedDirectory.resolve("idols.seed.jsonl"),
                "{\"_id\":\"idol-1\",\"name\":\"初始名字\",\"avatar\":\"\","
                        + "\"bio\":\"初始简介\",\"enabled\":true}\n");
        Files.writeString(seedDirectory.resolve("sources.seed.jsonl"),
                "{\"_id\":\"source-1\",\"idolId\":\"idol-1\","
                        + "\"rssUrl\":\"https://example.com/feed.xml\","
                        + "\"channel\":\"初始频道\",\"enabled\":true,"
                        + "\"lastFetchStatus\":\"never\"}\n");

        SeedProperties properties = new SeedProperties();
        properties.setDirectory(seedDirectory);
        SeedService service = new SeedService(jdbc, new ObjectMapper(), properties);
        SeedService.SeedResult first = service.seed();
        assertEquals(1, first.idols());
        assertEquals(1, first.sources());

        jdbc.update("UPDATE sources SET last_fetch_status = 'success', "
                + "last_fetch_item_count = 7, last_fetch_new_count = 3 WHERE id = 'source-1'");
        Files.writeString(seedDirectory.resolve("idols.seed.jsonl"),
                "{\"_id\":\"idol-1\",\"name\":\"更新名字\",\"avatar\":\"\","
                        + "\"bio\":\"更新简介\",\"enabled\":true}\n");
        Files.writeString(seedDirectory.resolve("sources.seed.jsonl"),
                "{\"_id\":\"source-1\",\"idolId\":\"idol-1\","
                        + "\"rssUrl\":\"https://example.com/feed.xml\","
                        + "\"channel\":\"更新频道\",\"enabled\":true,"
                        + "\"lastFetchStatus\":\"never\"}\n");

        SeedService.SeedResult second = service.seed();
        assertEquals(first, second);
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM idols", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sources", Long.class));
        assertEquals("更新名字", jdbc.queryForObject(
                "SELECT name FROM idols WHERE id = 'idol-1'", String.class));
        assertEquals("更新频道", jdbc.queryForObject(
                "SELECT channel FROM sources WHERE id = 'source-1'", String.class));
        assertEquals("success", jdbc.queryForObject(
                "SELECT last_fetch_status FROM sources WHERE id = 'source-1'", String.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT last_fetch_item_count FROM sources WHERE id = 'source-1'", Integer.class));

        OffsetDateTime updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM sources WHERE id = 'source-1'", OffsetDateTime.class);
        assertEquals(second, service.seed());
        assertEquals(updatedAt, jdbc.queryForObject(
                "SELECT updated_at FROM sources WHERE id = 'source-1'", OffsetDateTime.class));
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
