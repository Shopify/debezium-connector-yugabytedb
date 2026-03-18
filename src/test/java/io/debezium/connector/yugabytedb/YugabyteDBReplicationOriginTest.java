package io.debezium.connector.yugabytedb;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.debezium.config.Configuration;
import io.debezium.connector.yugabytedb.common.YugabyteDBContainerTestBase;

public class YugabyteDBReplicationOriginTest extends YugabyteDBContainerTestBase {

    @BeforeAll
    public static void beforeClass() throws SQLException {
        initializeYBContainer();
        TestHelper.dropAllSchemas();
    }

    @BeforeEach
    public void before() {
        initializeConnectorTestFramework();
    }

    @AfterEach
    public void after() throws Exception {
        stopConnector();
        TestHelper.executeDDL("drop_tables_and_databases.ddl");
    }

    @AfterAll
    public static void afterClass() throws Exception {
        shutdownYBContainer();
    }

    @ParameterizedTest
    @MethodSource("io.debezium.connector.yugabytedb.TestHelper#streamTypeProviderForStreaming")
    public void shouldPropagateReplicationOriginOnDmlEvents(boolean consistentSnapshot, boolean useSnapshot) throws Exception {
        TestHelper.initDB("yugabyte_create_tables.ddl");

        String dbStreamId = TestHelper.getNewDbStreamId("yugabyte", "t1", consistentSnapshot, useSnapshot);
        Configuration.Builder configBuilder = TestHelper.getConfigBuilder("public.t1", dbStreamId);
        startEngine(configBuilder);

        awaitUntilConnectorIsReady();

        // Set up a replication origin and insert with a non-zero origin id.
        TestHelper.execute("SELECT pg_replication_origin_create('test_origin');");
        TestHelper.execute("SELECT pg_replication_origin_session_setup('test_origin');");
        TestHelper.execute("INSERT INTO t1 VALUES (1, 'Alice', 'A', 12.345);");
        TestHelper.execute("SELECT pg_replication_origin_session_reset();");

        List<SourceRecord> records = new ArrayList<>();
        waitAndFailIfCannotConsume(records, 1);

        SourceRecord record = records.get(0);
        Struct value = (Struct) record.value();
        Struct source = value.getStruct("source");
        assertNotNull(source);

        Integer originId = source.getInt32(SourceInfo.XREPL_ORIGIN_ID);
        assertNotNull(originId, "origin should be present for records with a replication origin");
    }

    @ParameterizedTest
    @MethodSource("io.debezium.connector.yugabytedb.TestHelper#streamTypeProviderForStreaming")
    public void shouldOmitReplicationOriginWhenZero(boolean consistentSnapshot, boolean useSnapshot) throws Exception {
        TestHelper.initDB("yugabyte_create_tables.ddl");

        String dbStreamId = TestHelper.getNewDbStreamId("yugabyte", "t1", consistentSnapshot, useSnapshot);
        Configuration.Builder configBuilder = TestHelper.getConfigBuilder("public.t1", dbStreamId);
        startEngine(configBuilder);

        awaitUntilConnectorIsReady();

        // Insert without setting a replication origin — origin id should be 0 / omitted.
        TestHelper.execute("INSERT INTO t1 VALUES (1, 'Alice', 'A', 12.345);");

        List<SourceRecord> records = new ArrayList<>();
        waitAndFailIfCannotConsume(records, 1);

        SourceRecord record = records.get(0);
        Struct value = (Struct) record.value();
        Struct source = value.getStruct("source");
        assertNotNull(source);

        Integer originId = source.getInt32(SourceInfo.XREPL_ORIGIN_ID);
        assertNull(originId, "origin should be absent when origin is zero");
    }

    @ParameterizedTest
    @MethodSource("io.debezium.connector.yugabytedb.TestHelper#streamTypeProviderForStreaming")
    public void shouldHandleMultipleOriginIdsInSameSession(boolean consistentSnapshot, boolean useSnapshot) throws Exception {
        TestHelper.initDB("yugabyte_create_tables.ddl");

        String dbStreamId = TestHelper.getNewDbStreamId("yugabyte", "t1", consistentSnapshot, useSnapshot);
        Configuration.Builder configBuilder = TestHelper.getConfigBuilder("public.t1", dbStreamId);
        startEngine(configBuilder);

        awaitUntilConnectorIsReady();

        // Create two replication origins and alternate between them.
        TestHelper.execute("SELECT pg_replication_origin_create('origin_a');");
        TestHelper.execute("SELECT pg_replication_origin_create('origin_b');");

        TestHelper.execute("SELECT pg_replication_origin_session_setup('origin_a');");
        TestHelper.execute("INSERT INTO t1 VALUES (1, 'Alice', 'A', 1.0);");
        TestHelper.execute("SELECT pg_replication_origin_session_reset();");

        TestHelper.execute("SELECT pg_replication_origin_session_setup('origin_b');");
        TestHelper.execute("INSERT INTO t1 VALUES (2, 'Bob', 'B', 2.0);");
        TestHelper.execute("SELECT pg_replication_origin_session_reset();");

        List<SourceRecord> records = new ArrayList<>();
        waitAndFailIfCannotConsume(records, 2);

        Struct source0 = ((Struct) records.get(0).value()).getStruct("source");
        Struct source1 = ((Struct) records.get(1).value()).getStruct("source");

        Integer originA = source0.getInt32(SourceInfo.XREPL_ORIGIN_ID);
        Integer originB = source1.getInt32(SourceInfo.XREPL_ORIGIN_ID);

        assertNotNull(originA, "First record should have a non-zero origin id");
        assertNotNull(originB, "Second record should have a non-zero origin id");

        // The two origins should have different IDs since they are different replication origins.
        assertNotEquals(originA, originB,
                "Records from different replication origins should have different origin ids");
    }
}
