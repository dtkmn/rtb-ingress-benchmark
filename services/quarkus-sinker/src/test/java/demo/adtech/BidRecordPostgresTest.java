package demo.adtech;

import org.hibernate.cfg.Configuration;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real ORM mapping against the shipped SQL schema, rather than
 * allowing Hibernate to generate a different schema that hides mapping errors.
 * Run with SINKER_TEST_JDBC_URL pointing to a disposable PostgreSQL database;
 * SINKER_TEST_DB_USER/PASSWORD default to the local Compose credentials.
 */
@EnabledIfEnvironmentVariable(named = "SINKER_TEST_JDBC_URL", matches = ".+")
class BidRecordPostgresTest {

    @Test
    void persistsSiteAndAppRequestsIntoTheShippedSchema() throws Exception {
        String url = System.getenv("SINKER_TEST_JDBC_URL");
        String user = System.getenv().getOrDefault("SINKER_TEST_DB_USER", "user");
        String password = System.getenv().getOrDefault("SINKER_TEST_DB_PASSWORD", "password");
        String schema = "sinker_mapping_" + UUID.randomUUID().toString().replace("-", "");
        Instant started = Instant.now();

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute(shippedSchema());

                var configuration = new Configuration()
                        .addAnnotatedClass(BidRecord.class)
                        .setProperty("hibernate.connection.url", url)
                        .setProperty("hibernate.connection.username", user)
                        .setProperty("hibernate.connection.password", password)
                        .setProperty("hibernate.default_schema", schema)
                        .setProperty("hibernate.hbm2ddl.auto", "validate");

                BidRequest siteRequest = new BidRequest();
                siteRequest.id = "schema-site";
                siteRequest.site = new BidRequest.Site();
                siteRequest.site.domain = "schema-smoke.example";
                siteRequest.device = device(0);

                BidRequest appRequest = new BidRequest();
                appRequest.id = "schema-app";
                appRequest.app = new BidRequest.App();
                appRequest.app.bundle = "dev.adtech.schema.smoke";
                appRequest.device = device(1);

                // Quarkus supplies enhancement at build time, so no runtime Byte Buddy
                // dependency is present. These simple entities need no lazy proxies.
                var registry = new StandardServiceRegistryBuilder()
                        .applySettings(configuration.getProperties())
                        .addService(BytecodeProvider.class, new BytecodeProviderImpl())
                        .build();
                try (var sessionFactory = configuration.buildSessionFactory(registry)) {
                    sessionFactory.inTransaction(session -> {
                        session.persist(new BidRecord(siteRequest));
                        session.persist(new BidRecord(appRequest));
                    });
                } finally {
                    StandardServiceRegistryBuilder.destroy(registry);
                }

                try (var rows = statement.executeQuery("SELECT id, bid_request_id, domain, app_bundle, "
                        + "ip, os, limit_ad_tracking, processed_at FROM bid_records ORDER BY id")) {
                    assertTrue(rows.next());
                    long siteId = rows.getLong("id");
                    assertTrue(siteId > 0);
                    assertEquals("schema-site", rows.getString("bid_request_id"));
                    assertEquals("schema-smoke.example", rows.getString("domain"));
                    assertNull(rows.getString("app_bundle"));
                    assertEquals("192.0.2.42", rows.getString("ip"));
                    assertEquals("smoke-os", rows.getString("os"));
                    assertFalse(rows.getBoolean("limit_ad_tracking"));
                    assertFalse(rows.getTimestamp("processed_at").toInstant().isBefore(started));

                    assertTrue(rows.next());
                    assertTrue(rows.getLong("id") > siteId);
                    assertEquals("schema-app", rows.getString("bid_request_id"));
                    assertNull(rows.getString("domain"));
                    assertEquals("dev.adtech.schema.smoke", rows.getString("app_bundle"));
                    assertEquals("192.0.2.42", rows.getString("ip"));
                    assertEquals("smoke-os", rows.getString("os"));
                    assertTrue(rows.getBoolean("limit_ad_tracking"));
                    assertFalse(rows.getTimestamp("processed_at").toInstant().isBefore(started));
                    assertFalse(rows.next());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static BidRequest.Device device(int limitAdTracking) {
        var device = new BidRequest.Device();
        device.ip = "192.0.2.42";
        device.os = "smoke-os";
        device.lmt = limitAdTracking;
        return device;
    }

    private static String shippedSchema() throws IOException {
        try (var input = BidRecordPostgresTest.class.getResourceAsStream(
                "/db/migration/V1__Create_bid_records_table.sql")) {
            assertNotNull(input, "The production schema must be on the test classpath");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
