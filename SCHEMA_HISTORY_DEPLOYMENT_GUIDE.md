# YugabyteDB Connector Schema History Deployment Guide

## Overview

This guide explains how to deploy the YugabyteDB connector with schema history topic support to staging for testing on `globaldb.core.products`.

## Prerequisites

1. YugabyteDB connector with schema history support (current branch)
2. Access to Shopify's tmapi for topic creation
3. Staging Kafka cluster access
4. YugabyteDB staging environment access

## Step 1: Create the Schema History Topic Using tmapi

### Topic Details

- **Topic Name**: `globaldb-core-products-schemachanges`
- **Purpose**: Store schema change events for the products table
- **Retention**: Consider using a long retention period (e.g., 7-30 days) for schema history
- **Partitions**: 1 (schema history topics typically use a single partition for ordering)
- **Replication Factor**: 3 (for production/staging environments)

### Using tmapi at Shopify

```bash
# Example tmapi command (adjust based on Shopify's tmapi interface)
# You'll need to use Shopify's internal tooling for this

# Topic creation parameters:
# - name: globaldb-core-products-schemachanges
# - partitions: 1
# - replication-factor: 3
# - retention.ms: 604800000 (7 days) or longer
# - cleanup.policy: delete
# - min.insync.replicas: 2
```

**Important Topic Configuration:**
- **cleanup.policy**: `delete` (not `compact`) - schema history needs to preserve all changes
- **retention.ms**: Set based on your recovery requirements (default: 7 days minimum)
- **min.insync.replicas**: 2 (ensures durability)

## Step 2: Build and Deploy the Connector

### Build the Connector

```bash
cd /workspace
mvn clean package -Dquick
```

This will create:
- JAR file: `target/debezium-connector-yugabytedb-dz.1.9.5.yb.grpc.2024.2-SNAPSHOT.jar`
- Docker image: `quay.io/yugabyte/debezium-connector:dz.1.9.5.yb.grpc.2024.2-SNAPSHOT`

### Deploy to Staging

Upload the connector to your staging Kafka Connect cluster following your organization's deployment process.

## Step 3: Configure the Connector

### Configuration File

Use the provided `staging-config-example.json` and replace the placeholders:

```json
{
  "name": "globaldb-core-products-connector-staging",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    
    "database.hostname": "<staging-yugabyte-host>",
    "database.port": "5433",
    "database.master.addresses": "<master-host>:7100",
    "database.user": "yugabyte",
    "database.password": "<password>",
    "database.dbname": "globaldb",
    "database.server.name": "globaldb-core-products",
    
    "database.streamid": "<your-stream-id>",
    
    "table.include.list": "core.products",
    
    "snapshot.mode": "never",
    
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.topic": "globaldb-core-products-schemachanges",
    "database.history.kafka.bootstrap.servers": "<kafka-brokers>"
  }
}
```

### Key Configuration Parameters

| Parameter | Description | Value for Staging |
|-----------|-------------|-------------------|
| `database.server.name` | Logical name for this database server | `globaldb-core-products` |
| `table.include.list` | Tables to capture | `core.products` |
| `database.history` | History implementation class | `io.debezium.relational.history.KafkaDatabaseHistory` |
| `database.history.kafka.topic` | Schema history topic name | `globaldb-core-products-schemachanges` |
| `database.history.kafka.bootstrap.servers` | Kafka brokers | Your staging Kafka brokers |

### Optional Database History Parameters

```json
{
  "database.history.kafka.recovery.poll.interval.ms": "100",
  "database.history.kafka.recovery.attempts": "100",
  "database.history.skip.unparseable.ddl": "false",
  "database.history.store.only.monitored.tables.ddl": "true"
}
```

## Step 4: Deploy the Connector Configuration

### Using Kafka Connect REST API

```bash
# Deploy the connector
curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" \
  http://<kafka-connect-host>:8083/connectors/ \
  -d @staging-config-example.json

# Check connector status
curl -s http://<kafka-connect-host>:8083/connectors/globaldb-core-products-connector-staging/status | jq .

# View connector config
curl -s http://<kafka-connect-host>:8083/connectors/globaldb-core-products-connector-staging | jq .
```

## Step 5: Verify Schema History Recording

### Monitor the Schema History Topic

```bash
# Consume from the schema history topic
kafka-console-consumer --bootstrap-server <kafka-brokers> \
  --topic globaldb-core-products-schemachanges \
  --from-beginning
```

### Test Schema Change Detection

1. Connect to your staging YugabyteDB:
   ```sql
   -- Add a column to the products table
   ALTER TABLE core.products ADD COLUMN test_column VARCHAR(100);
   ```

2. Check if the schema change is recorded in the topic:
   - The connector should detect the schema change during streaming
   - A record should be written to `globaldb-core-products-schemachanges`
   - Check connector logs for "Recorded schema change to history" messages

### Expected Log Output

```
INFO  Recorded schema change to history: database=globaldb, schema=core, ddl=ALTER TABLE core.products ADD COLUMN test_column VARCHAR(100)
```

## Step 6: Monitor and Troubleshoot

### Check Connector Logs

```bash
# If using Docker
docker logs <kafka-connect-container> | grep -i "schema history"

# If using systemd
journalctl -u kafka-connect -f | grep -i "schema history"
```

### Common Issues

1. **Topic Not Found**
   - Ensure `globaldb-core-products-schemachanges` topic is created
   - Check Kafka broker connectivity

2. **Permission Issues**
   - Verify connector has write access to the schema history topic
   - Check ACLs if using Kafka security

3. **Schema History Not Recording**
   - Check if `database.history` is properly configured
   - Verify table is not filtered out by `table.include.list`
   - Check connector logs for errors

## Step 7: Data Change Topics

In addition to the schema history topic, the connector will write data changes to:

- **Data Topic**: `globaldb-core-products.core.products`
  - This topic contains the actual CDC events (inserts, updates, deletes)
  - Create this topic via tmapi as well if needed

## Rollback Plan

If issues occur:

1. Delete/pause the connector:
   ```bash
   curl -X DELETE http://<kafka-connect-host>:8083/connectors/globaldb-core-products-connector-staging
   ```

2. The schema history topic can be retained for future use or deleted:
   ```bash
   # If needed, delete the topic (be careful!)
   kafka-topics --bootstrap-server <kafka-brokers> \
     --delete --topic globaldb-core-products-schemachanges
   ```

## Production Considerations

Before moving to production:

1. **Test thoroughly** in staging with:
   - Schema changes (ADD COLUMN, DROP COLUMN, ALTER COLUMN)
   - Table renames
   - Connector restarts
   - Kafka broker failures

2. **Monitor performance**:
   - Schema history topic should have minimal traffic
   - Check connector lag

3. **Document recovery procedures**:
   - How to restore from schema history
   - How to handle corrupted schema history

4. **Set up alerting**:
   - Connector failures
   - Schema history write failures
   - Topic lag

## References

- [Debezium Schema History Documentation](https://debezium.io/documentation/reference/stable/configuration/schema-history.html)
- YugabyteDB Connector: `/workspace/README.md`
- Configuration: `/workspace/staging-config-example.json`
