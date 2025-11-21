# YugabyteDB Connector - Schema History Setup Guide

## Overview
This guide explains how to configure the YugabyteDB connector to write schema changes to a dedicated Kafka topic with the `-schemachanges` suffix for testing on `globaldb.core.products` in staging.

## Recent Changes
The connector now supports writing schema change events to a Kafka topic using Debezium's `DatabaseHistory` mechanism. Key changes include:
- Added `database.history` configuration field in `YugabyteDBConnectorConfig.java`
- Schema changes are recorded during both snapshot and streaming phases
- Uses Kafka topic for persistent schema history storage

## Configuration

### Required Properties

To enable schema history recording, add these properties to your connector configuration:

```json
{
  "name": "yugabytedb-connector-globaldb-core-products",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.hostname": "<your-yugabytedb-host>",
    "database.port": "5433",
    "database.master.addresses": "<master-addresses>",
    "database.user": "<username>",
    "database.password": "<password>",
    "database.dbname": "globaldb",
    "database.server.name": "globaldb-staging",
    "table.include.list": "core.products",
    
    // Schema History Configuration
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.topic": "globaldb-staging-schemachanges",
    "database.history.kafka.bootstrap.servers": "<kafka-bootstrap-servers>",
    
    // Optional: Additional Kafka settings for schema history topic
    "database.history.kafka.recovery.poll.interval.ms": "100",
    "database.history.kafka.recovery.attempts": "100"
  }
}
```

### Key Configuration Points

1. **Topic Name Pattern**: Use `<database.server.name>-schemachanges` format
   - In this example: `globaldb-staging-schemachanges`

2. **Compatibility**: Uses `database.history` prefix (not `schema.history.internal`) for Debezium 1.9.5 compatibility

3. **Kafka Settings**: The schema history topic should use the same Kafka cluster as your other connector topics

## Deployment Steps for Staging

### 1. Create the Schema History Topic Using tmapi

Before deploying the connector, create the Kafka topic using Shopify's tmapi:

```bash
# Example tmapi command (adjust based on your Shopify setup)
tmapi topic create \
  --name globaldb-staging-schemachanges \
  --partitions 1 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --environment staging
```

**Topic Configuration Recommendations:**
- **Partitions**: 1 (schema history is sequential)
- **Replication Factor**: 3 (for durability)
- **Retention**: 7 days (604800000 ms) or based on your policy
- **Cleanup Policy**: `delete` (default)

### 2. Build the Connector

```bash
mvn clean package -DskipTests
```

This creates the connector JAR in `target/debezium-connector-yugabytedb-*.jar`

### 3. Deploy to Staging

Deploy the connector with the configuration above to your staging Kafka Connect cluster.

### 4. Verify Schema History Recording

After deploying, verify schema changes are being recorded:

```bash
# List messages in the schema history topic
kafka-console-consumer \
  --bootstrap-server <kafka-bootstrap-servers> \
  --topic globaldb-staging-schemachanges \
  --from-beginning \
  --property print.key=true
```

You should see schema change records for `core.products` table.

## Testing on globaldb.core.products

### Test Scenarios

1. **Initial Snapshot**
   - Start the connector with `snapshot.mode=initial`
   - Verify schema change event is recorded for initial table structure

2. **Schema Alterations**
   ```sql
   -- Add a column
   ALTER TABLE core.products ADD COLUMN new_field VARCHAR(255);
   
   -- Modify a column
   ALTER TABLE core.products ALTER COLUMN existing_field TYPE TEXT;
   
   -- Drop a column
   ALTER TABLE core.products DROP COLUMN old_field;
   ```
   - Each DDL should create a schema change record in the topic

3. **Verify Events**
   - Check that each schema change event includes:
     - Database name: `globaldb`
     - Schema name: `core`
     - DDL statement
     - Table structure

## Monitoring

### Check Connector Logs

Look for log messages indicating schema history recording:

```
INFO Recorded schema change to history: database=globaldb, schema=core, ddl=...
```

### Verify Topic Health

```bash
# Check topic exists
kafka-topics --list --bootstrap-server <kafka-bootstrap-servers> | grep schemachanges

# Check topic configuration
kafka-topics --describe --topic globaldb-staging-schemachanges \
  --bootstrap-server <kafka-bootstrap-servers>
```

## Troubleshooting

### Schema History Not Recording

1. **Check configuration**:
   ```bash
   # Verify connector configuration via Kafka Connect REST API
   curl http://localhost:8083/connectors/yugabytedb-connector-globaldb-core-products/config
   ```

2. **Check logs**:
   - Look for "Database history not configured" (means config missing)
   - Look for "Failed to initialize database history" (connection issue)

3. **Verify topic access**:
   - Ensure connector has write permissions to the schema history topic
   - Check Kafka ACLs if using authenticated Kafka

### Topic Creation Issues

If the topic doesn't exist:
- Schema history recording will fail
- Connector may fail to start
- Create the topic manually using tmapi before starting the connector

## Notes

- **For Compatibility**: This implementation uses the `database.history` prefix instead of Debezium 2.x's `schema.history.internal` for compatibility with Debezium 1.9.5
- **Table Filtering**: Only tables matching `table.include.list` will have schema changes recorded
- **Write-Only Mode**: The connector is configured in write-only mode (doesn't read history on startup)
- **DDL Comments**: Current implementation creates placeholder DDL comments like `-- DDL change for table ...`

## Next Steps

1. Create the schema history topic using tmapi
2. Update your connector configuration with the schema history properties
3. Deploy to staging Kafka Connect
4. Test with DDL operations on `core.products`
5. Monitor the schema history topic for events
6. Once validated, consider expanding to other tables/databases
