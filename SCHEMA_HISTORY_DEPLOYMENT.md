# Schema History Topic Deployment Guide

## Overview

The YugabyteDB connector has been updated to automatically append "-schemachanges" to the schema history topic name. When the connector is configured with `database.history`, it will automatically create a topic named `{database.server.name}-schemachanges` if the topic name is not explicitly configured.

## Changes Made

The `getDatabaseHistory()` method in `YugabyteDBConnectorConfig.java` has been updated to:
- Automatically append "-schemachanges" to the logical name (server name) when `database.history.kafka.topic` is not explicitly set
- Log the auto-configured topic name for debugging

## Configuration

### Basic Configuration

To enable schema history tracking, add the following to your connector configuration:

```json
{
  "name": "yugabyte-connector",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.server.name": "globaldb.core.products",
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    // ... other connector config ...
  }
}
```

### Topic Name Behavior

- **If `database.history.kafka.topic` is NOT set**: The topic will be automatically named `{database.server.name}-schemachanges`
  - Example: `database.server.name` = "globaldb.core.products" → topic = "globaldb.core.products-schemachanges"
  
- **If `database.history.kafka.topic` IS set**: The explicitly configured topic name will be used

### Example Configuration for Staging

```json
{
  "name": "yugabyte-connector-staging",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.hostname": "<your-host>",
    "database.port": "5433",
    "database.user": "<your-user>",
    "database.password": "<your-password>",
    "database.dbname": "globaldb",
    "database.server.name": "globaldb.core.products",
    "table.include.list": "core.products",
    "database.streamid": "<your-stream-id>",
    "snapshot.mode": "never",
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.bootstrap.servers": "<kafka-bootstrap-servers>"
  }
}
```

## Creating the Topic Using tmapi (Shopify)

Since you mentioned using tmapi at Shopify to create the topic, you'll need to create the topic before deploying the connector:

### Expected Topic Name
Based on the configuration above, the topic name will be:
```
globaldb.core.products-schemachanges
```

### Using tmapi

You can use tmapi to create the topic. The exact command depends on your tmapi setup, but typically it would be something like:

```bash
# Example tmapi command (adjust based on your actual tmapi CLI)
tmapi topic create globaldb.core.products-schemachanges \
  --partitions <num-partitions> \
  --replication-factor <replication-factor> \
  --config retention.ms=<retention-ms>
```

**Note**: Make sure to create the topic with appropriate settings:
- **Partitions**: Typically 1 partition is sufficient for schema history (low volume)
- **Replication Factor**: Match your Kafka cluster's replication factor
- **Retention**: Consider setting a longer retention period for schema history (e.g., 7 days or more)

## Testing

### 1. Create the Topic First
Before deploying the connector, create the schema history topic using tmapi.

### 2. Deploy the Connector
Deploy the connector with the configuration shown above, ensuring:
- `database.server.name` is set to "globaldb.core.products"
- `database.history` is set to `io.debezium.relational.history.KafkaDatabaseHistory`
- `table.include.list` includes "core.products" (or matches your table filter)

### 3. Verify Topic Creation
Check that the connector logs show:
```
Auto-configured schema history topic name: globaldb.core.products-schemachanges
Database history initialized: io.debezium.relational.history.KafkaDatabaseHistory
```

### 4. Trigger Schema Changes
Make a schema change to the `core.products` table (e.g., add a column):
```sql
ALTER TABLE core.products ADD COLUMN test_column VARCHAR(255);
```

### 5. Verify Schema History Records
Consume from the schema history topic to verify records are being written:
```bash
# Using kafka-console-consumer or your preferred tool
kafka-console-consumer \
  --bootstrap-server <kafka-bootstrap-servers> \
  --topic globaldb.core.products-schemachanges \
  --from-beginning
```

You should see schema change events in JSON format containing the DDL statements.

## Troubleshooting

### Topic Not Created
- Verify that `database.history` is set in the connector configuration
- Check connector logs for any errors during initialization
- Ensure Kafka connectivity is working

### Wrong Topic Name
- If you want a different topic name, explicitly set `database.history.kafka.topic` in the connector config
- Verify that `database.server.name` matches your expected value

### No Schema Changes Recorded
- Ensure the table is included in `table.include.list`
- Check that schema changes are actually happening on monitored tables
- Verify the connector is running and processing events

## Rollback

If you need to rollback this change:
1. Stop the connector
2. Remove or comment out the `database.history` configuration
3. Redeploy the connector

The schema history topic will remain but won't receive new updates.
