/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.yugabytedb.metrics;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.debezium.util.Collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class YugabyteDBMetricsTest {

    @Test
    public void shouldAppendConnectorTagAfterExistingTags() {
        Map<String, String> tags = YugabyteDBMetrics.withConnectorTag(Collect.linkMapOf(
                "server", "globaldb",
                "task", "0",
                "context", "streaming",
                "partition", "000043000000300080000000000308a1.9e0f2c4b"),
                "globaldb-core-collections");

        assertEquals("server, task, context, partition, connector", String.join(", ", tags.keySet()));
        assertEquals("globaldb-core-collections", tags.get("connector"));
    }

    @Test
    public void shouldOmitConnectorTagWhenNameIsUnavailable() {
        assertFalse(YugabyteDBMetrics.withConnectorTag(
                Collect.linkMapOf("server", "globaldb", "task", "0", "context", "streaming"), "")
                .containsKey("connector"));

        assertFalse(YugabyteDBMetrics.withConnectorTag(
                Collect.linkMapOf("server", "globaldb", "task", "0", "context", "streaming"), null)
                .containsKey("connector"));
    }
}
