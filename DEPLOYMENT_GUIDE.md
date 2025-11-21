# Schema History Topic Deployment Guide

## Overview

The YugabyteDB connector has been updated to automatically append `-schemachanges` to the schema history topic name. This guide explains how to deploy and test this feature in staging.

## Changes Made

The connector now automatically generates the schema history topic name as:
```
{server.name}-schemachanges
```

For example, if your `database.server.name` is `globaldb`, the schema history topic will be:
```
globaldb-schemachanges
```

## Configuration

### Basic Configuration

To enable schema history recording, ensure your connector configuration includes:

```json
{
  "name": "yugabyte-connector",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.server.name": "globaldb",
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "table.include.list": "core.products",
    ...
  }
}
```

### Explicit Topic Name (Optional)

If you want to override the auto-generated topic name, you can explicitly set it:

```json
{
  "database.history.kafka.topic": "custom-topic-name-schemachanges"
}
```

## Staging Deployment for Testing

### Step 1: Create the Topic Using tmapi

Before deploying the connector, create the schema history topic using Shopify's tmapi:

```bash
# Example: Create topic for globaldb.core.products testing
# Topic name will be: globaldb-schemachanges
```

**Note:** The topic name should match the pattern `{database.server.name}-schemachanges`

### Step 2: Configure Connector for Staging

For testing on `globaldb.core.products` in staging, use this configuration:

```json
{
  "name": "yugabyte-connector-staging",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.hostname": "<your-staging-host>",
    "database.port": "5433",
    "database.master.addresses": "<your-staging-master>:7100",
    "database.user": "<user>",
    "database.password": "<password>",
    "database.dbname": "globaldb",
    "database.server.name": "globaldb",
    "table.include.list": "core.products",
    "database.streamid": "<your-stream-id>",
    "snapshot.mode": "never",
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.bootstrap.servers": "<kafka-bootstrap-servers>"
  }
}
```

### Step 3: Verify Topic Creation

After deploying the connector, verify that:
1. The topic `globaldb-schemachanges` exists
2. Schema change events are being written to the topic
3. The connector logs show: `Auto-configured schema history topic name: globaldb-schemachanges`

### Step 4: Test Schema Changes

To test that schema changes are being recorded:

1. Make a DDL change to the `core.products` table (e.g., add a column)
2. Check the `globaldb-schemachanges` topic for the schema change event
3. Verify the event contains the DDL statement and table metadata

## Monitoring

### Log Messages

The connector will log:
- `Auto-configured schema history topic name: {topic-name}` - When auto-generating the topic name
- `Using explicitly configured schema history topic name: {topic-name}` - When using explicit configuration
- `Recorded schema change to history: database={}, schema={}, ddl={}` - When recording schema changes

### Topic Monitoring

Monitor the schema history topic for:
- Message count (should increase with each DDL change)
- Message size
- Consumer lag (if consuming the topic)

## Troubleshooting

### Topic Not Created

If the topic is not created automatically:
1. Verify `database.history` is set to `io.debezium.relational.history.KafkaDatabaseHistory`
2. Check connector logs for initialization errors
3. Ensure Kafka connectivity is working

### Schema Changes Not Recorded

If schema changes are not being recorded:
1. Verify the table is in the `table.include.list`
2. Check connector logs for filtering messages
3. Ensure DDL changes are being detected (check YugabyteDB CDC stream)

### Topic Name Mismatch

If the topic name doesn't match expectations:
1. Check the `database.server.name` configuration
2. Verify no explicit `database.history.kafka.topic` is set
3. Review connector logs for the actual topic name used

## Next Steps

After successful testing on `globaldb.core.products`:
1. Expand to additional tables as needed
2. Monitor topic growth and performance
3. Plan production rollout
