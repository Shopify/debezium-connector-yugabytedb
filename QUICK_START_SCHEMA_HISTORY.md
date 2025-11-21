# Quick Start: Schema History Topic Configuration

## TL;DR - What You Need

### 1. Topic Name Format
```
<database-server-name>-schemachanges
```

For your case: `globaldb-core-products-schemachanges`

### 2. Required Connector Config Properties

```json
{
  "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
  "database.history.kafka.topic": "globaldb-core-products-schemachanges",
  "database.history.kafka.bootstrap.servers": "your-kafka-brokers:9092"
}
```

### 3. tmapi Topic Creation

**Topic Specs for `globaldb-core-products-schemachanges`:**
- **Partitions**: 1 (must be single partition for ordering)
- **Replication Factor**: 3
- **Retention**: 604800000ms (7 days minimum)
- **Cleanup Policy**: delete (NOT compact)
- **Min In-Sync Replicas**: 2

### 4. Complete Minimal Config

```json
{
  "name": "globaldb-core-products-connector-staging",
  "config": {
    "connector.class": "io.debezium.connector.yugabytedb.YugabyteDBgRPCConnector",
    "database.hostname": "YOUR_HOST",
    "database.port": "5433",
    "database.master.addresses": "YOUR_MASTER:7100",
    "database.user": "yugabyte",
    "database.password": "YOUR_PASSWORD",
    "database.dbname": "globaldb",
    "database.server.name": "globaldb-core-products",
    "database.streamid": "YOUR_STREAM_ID",
    "table.include.list": "core.products",
    "snapshot.mode": "never",
    
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.topic": "globaldb-core-products-schemachanges",
    "database.history.kafka.bootstrap.servers": "YOUR_KAFKA_BROKERS"
  }
}
```

## How Schema History Works

```
DDL Change in DB → Connector Detects → Writes to Schema History Topic
                                     ↓
                         Used for connector recovery/restart
```

## Key Points

1. **Topic MUST exist before starting connector** - Create it via tmapi first
2. **Single partition required** - Schema changes must be ordered
3. **NOT a regular CDC topic** - This is metadata, not data changes
4. **Data changes go to**: `globaldb-core-products.core.products`
5. **Schema changes go to**: `globaldb-core-products-schemachanges`

## Testing the Setup

### 1. Deploy connector with config above

### 2. Make a schema change:
```sql
ALTER TABLE core.products ADD COLUMN test_col VARCHAR(100);
```

### 3. Verify in logs:
```
INFO  Recorded schema change to history: database=globaldb, schema=core, ddl=ALTER TABLE...
```

### 4. Check the topic:
```bash
kafka-console-consumer --bootstrap-server YOUR_BROKERS \
  --topic globaldb-core-products-schemachanges \
  --from-beginning
```

## Troubleshooting One-Liners

```bash
# Check connector status
curl -s http://kafka-connect:8083/connectors/globaldb-core-products-connector-staging/status | jq .

# Check if topic exists
kafka-topics --bootstrap-server YOUR_BROKERS --describe --topic globaldb-core-products-schemachanges

# Tail connector logs for schema history messages
kubectl logs -f kafka-connect-pod | grep "schema history"

# Restart connector
curl -X POST http://kafka-connect:8083/connectors/globaldb-core-products-connector-staging/restart
```

## What Gets Recorded

Schema history records:
- ALTER TABLE statements
- ADD/DROP COLUMN
- CREATE TABLE (if new tables added)
- Table schema snapshots

Schema history does NOT record:
- Data changes (INSERT/UPDATE/DELETE) - those go to the data topic
- Filtered out tables
- Non-DDL statements

## Files Created

- `/workspace/staging-config-example.json` - Full config template
- `/workspace/SCHEMA_HISTORY_DEPLOYMENT_GUIDE.md` - Detailed guide
- `/workspace/QUICK_START_SCHEMA_HISTORY.md` - This file
