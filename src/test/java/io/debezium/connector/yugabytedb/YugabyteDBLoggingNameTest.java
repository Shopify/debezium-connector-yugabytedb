package io.debezium.connector.yugabytedb;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.debezium.config.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the names that make a log line traceable to its connector and table, see
 * {@link YugabyteDBConnectorConfig#resolveLoggingName(Configuration, String)} and
 * {@link YugabyteDBStreamingChangeEventSource#tableNameForLogging(java.util.Map, String)}.
 */
public class YugabyteDBLoggingNameTest {

    @Test
    public void shouldLogUnderTheNameKafkaConnectGaveTheConnector() {
        Configuration config = Configuration.create().with("name", "globaldb-core-collections").build();

        assertEquals("globaldb-core-collections",
                YugabyteDBConnectorConfig.resolveLoggingName(config, "globaldb"));
    }

    @Test
    public void shouldLogUnderTheLogicalNameWhenKafkaConnectSuppliedNoName() {
        Configuration config = Configuration.create().build();

        assertEquals("globaldb", YugabyteDBConnectorConfig.resolveLoggingName(config, "globaldb"));
    }

    @Test
    public void shouldFallBackToTheTableIdWhenTheTableIsNotKnown() {
        assertEquals("000043000000300080000000000308a1",
                YugabyteDBStreamingChangeEventSource.tableNameForLogging(
                        Collections.emptyMap(), "000043000000300080000000000308a1"));
    }
}
