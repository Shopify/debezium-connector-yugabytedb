/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.yugabytedb;

import java.sql.SQLException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.ThreadSafe;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.connector.yugabytedb.connection.ReplicationConnection;
import io.debezium.connector.yugabytedb.connection.YugabyteDBConnection;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.TableId;
import io.debezium.schema.TopicSelector;
import io.debezium.util.LoggingContext;

/**
 * The context of a {@link YugabyteDBConnectorTask}. This deals with most of the brunt of reading
 * various configuration options and creating other objects with these various options.
 *
 * @author Suranjan Kumar (skumar@yugabyte.com)
 */
@ThreadSafe
public class YugabyteDBTaskContext extends CdcSourceTaskContext {

    protected final static Logger LOGGER = LoggerFactory.getLogger(YugabyteDBTaskContext.class);

    private final YugabyteDBConnectorConfig config;
    private final TopicSelector<TableId> topicSelector;
    private final YugabyteDBSchema schema;

    private final boolean sendBeforeImage;

    private final boolean enableExplicitCheckpointing;

    private final String loggingName;

    protected YugabyteDBTaskContext(YugabyteDBConnectorConfig config, YugabyteDBSchema schema,
                                    TopicSelector<TableId> topicSelector, String taskId,
                                    boolean sendBeforeImage, boolean enableExplicitCheckpointing) {
        super(config.getContextName(), config.getLogicalName(), taskId, Collections::emptySet);
        this.config = config;
        this.topicSelector = topicSelector;
        assert schema != null;
        this.schema = schema;
        this.sendBeforeImage = sendBeforeImage;
        this.enableExplicitCheckpointing = enableExplicitCheckpointing;
        this.loggingName = config.getLoggingName();
    }

    /**
     * Logs under the name Kafka Connect gave the connector. The parent class would use the logical
     * name, which is only the topic prefix and is shared by every connector reading from the same
     * database, so a log line could not be traced back to its connector. Metrics are not affected:
     * {@link #getConnectorName()} still returns the logical name.
     */
    @Override
    public LoggingContext.PreviousContext configureLoggingContext(String contextName) {
        return LoggingContext.forConnector(getConnectorType(), loggingName, contextName);
    }

    @Override
    public LoggingContext.PreviousContext configureLoggingContext(String contextName, Partition partition) {
        return LoggingContext.forConnector(getConnectorType(), loggingName, getTaskId(), contextName, partition);
    }

    protected TopicSelector<TableId> topicSelector() {
        return topicSelector;
    }

    protected YugabyteDBSchema schema() {
        return schema;
    }

    protected YugabyteDBConnectorConfig config() {
        return config;
    }

    protected boolean isBeforeImageEnabled() {
        return this.sendBeforeImage;
    }

    protected boolean shouldEnableExplicitCheckpointing() {
        return this.enableExplicitCheckpointing;
    }

    protected void refreshSchema(YugabyteDBConnection connection,
                                 boolean printReplicaIdentityInfo)
            throws SQLException {
        // schema.refresh(connection, printReplicaIdentityInfo);
    }

    protected ReplicationConnection createReplicationConnection(boolean doSnapshot)
            throws SQLException {
        return ReplicationConnection.builder(config)
                // .withSlot(config.slotName())
                // .withPublication(config.publicationName())
                .withTableFilter(config.getTableFilters())
                // .withPublicationAutocreateMode(config.publicationAutocreateMode())
                .withPlugin(config.plugin())
                .withTruncateHandlingMode(config.truncateHandlingMode())
                // .dropSlotOnClose(dropSlotOnStop)
                .streamParams(config.streamParams())
                .statusUpdateInterval(config.statusUpdateInterval())
                .withTypeRegistry(schema.getTypeRegistry())
                .doSnapshot(doSnapshot)
                .withSchema(schema)
                .build();
    }

    YugabyteDBConnectorConfig getConfig() {
        return config;
    }
}
