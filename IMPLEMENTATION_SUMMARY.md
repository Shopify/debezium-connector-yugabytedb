# YugabyteDB Connector Schema History - Implementation Summary

## Executive Summary

The YugabyteDB connector has been successfully configured to write schema change events to a Kafka topic. This document provides a complete guide for deploying and testing this feature on `globaldb.core.products` in staging with the topic name suffix `-schemachanges`.

---

## What's Been Implemented

### Schema History Recording
The connector now captures and records all DDL (schema change) operations to a dedicated Kafka topic:

- ✅ **Snapshot Phase**: Records initial table schema during snapshot
- ✅ **Streaming Phase**: Records schema changes in real-time during streaming
- ✅ **Table Filtering**: Only records changes for tables matching `table.include.list`
- ✅ **Debezium 1.9.5 Compatible**: Uses `database.history` property prefix

### Code Changes
The implementation added schema history functionality to three key files:
1. `YugabyteDBConnectorConfig.java` - Configuration for database history
2. `YugabyteDBSchema.java` - Recording logic
3. `YugabyteDB[Snapshot|Streaming]ChangeEventSource.java` - Integration hooks

---

## Your Deployment Path

### Step 1: Create the Kafka Topic (Using tmapi)

For testing on `globaldb.core.products`, create this topic:

```bash
tmapi topic create \
  --name globaldb-staging-schemachanges \
  --partitions 1 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --environment staging
```

**Why these settings?**
- **1 partition**: Schema history must be sequential
- **3x replication**: Ensures durability in staging
- **7-day retention**: Standard retention for schema history

### Step 2: Configure the Connector

Use the provided `staging-config-example.json` and update these values:

```json
{
  "name": "yugabytedb-connector-globaldb-core-products-staging",
  "config": {
    // ... other configs ...
    
    "database.server.name": "globaldb-staging",
    "table.include.list": "core.products",
    
    // Schema History Configuration
    "database.history": "io.debezium.relational.history.KafkaDatabaseHistory",
    "database.history.kafka.topic": "globaldb-staging-schemachanges",
    "database.history.kafka.bootstrap.servers": "<your-kafka-brokers>"
  }
}
```

**Replace these placeholders:**
- `REPLACE_WITH_YUGABYTEDB_HOST` → Your YugabyteDB hostname
- `REPLACE_WITH_MASTER_ADDRESSES` → YugabyteDB master addresses
- `REPLACE_WITH_USERNAME` → Database username
- `REPLACE_WITH_PASSWORD` → Database password
- `REPLACE_WITH_STREAM_ID_OR_SLOT_NAME` → Stream ID or replication slot name
- `REPLACE_WITH_KAFKA_BOOTSTRAP_SERVERS` → Kafka broker addresses

### Step 3: Build and Deploy

```bash
# Build the connector
mvn clean package -DskipTests

# Deploy to Kafka Connect
curl -X POST http://<kafka-connect>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @staging-config-example.json
```

### Step 4: Verify

```bash
# Run automated verification
./verify-schema-history.sh

# Or manually check
kafka-console-consumer \
  --bootstrap-server <kafka-bootstrap> \
  --topic globaldb-staging-schemachanges \
  --from-beginning \
  --property print.key=true
```

### Step 5: Test with DDL

```sql
-- Connect to YugabyteDB staging
ALTER TABLE core.products ADD COLUMN schema_test_field VARCHAR(100);

-- Verify the change appears in schema history topic
-- Then clean up
ALTER TABLE core.products DROP COLUMN schema_test_field;
```

---

## Files Created for You

| File | Purpose | Use When |
|------|---------|----------|
| **SCHEMA_HISTORY_SETUP.md** | Complete setup documentation | Planning deployment |
| **DEPLOYMENT_CHECKLIST.md** | Step-by-step deployment guide | During deployment |
| **staging-config-example.json** | Ready-to-use connector config | Deploying connector |
| **verify-schema-history.sh** | Automated verification script | After deployment |
| **QUICK_REFERENCE.md** | Quick lookup for commands | Troubleshooting |
| **IMPLEMENTATION_SUMMARY.md** | This document | Getting started |

---

## Topic Naming Pattern

For any database and table, use this pattern:

```
{database.server.name}-schemachanges
```

**Examples:**
- `globaldb.core.products` → `globaldb-staging-schemachanges`
- `userdb.public.accounts` → `userdb-prod-schemachanges`
- `analytics.events.clicks` → `analytics-staging-schemachanges`

---

## Important Configuration Notes

### Using `database.history` Prefix (Not `schema.history.internal`)

This connector uses the **Debezium 1.9.5** configuration format:

✅ **Correct:**
```properties
database.history.kafka.topic=globaldb-staging-schemachanges
database.history.kafka.bootstrap.servers=kafka:9092
```

❌ **Incorrect (Debezium 2.x style):**
```properties
schema.history.internal.kafka.topic=...
schema.history.internal.kafka.bootstrap.servers=...
```

### Write-Only Mode

The connector is configured in **write-only mode**, which means:
- ✅ Writes schema changes to the topic
- ❌ Does NOT read history on startup
- ✅ Suitable for schema change tracking
- ⚠️  Not for connector recovery (use offsets for that)

---

## Expected Behavior

### During Initial Snapshot

When the connector starts with `snapshot.mode=initial`:

1. Connector begins snapshot of `core.products`
2. Schema is captured and recorded to `globaldb-staging-schemachanges`
3. Log message appears: `Recorded schema change to history: database=globaldb, schema=core, ddl=...`
4. Data begins flowing to `globaldb-staging.core.products`

### During Streaming (Real-time DDL)

When DDL operations occur on `core.products`:

1. DDL is executed: `ALTER TABLE core.products ADD COLUMN ...`
2. Change is detected by connector
3. Schema change event is written to `globaldb-staging-schemachanges`
4. Updated schema is used for subsequent records
5. Data continues flowing with new schema

---

## Monitoring and Health Checks

### Connector Health
```bash
# Check connector status
curl http://<kafka-connect>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging/status

# Should show: "state": "RUNNING", "tasks": [{"state": "RUNNING"}]
```

### Schema History Topic Health
```bash
# Check topic exists and has messages
kafka-topics.sh --describe --topic globaldb-staging-schemachanges \
  --bootstrap-server <kafka-bootstrap>

# Check message count
kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list <kafka-bootstrap> \
  --topic globaldb-staging-schemachanges
```

### Connector Logs
Look for these key log messages:

✅ **Success:**
```
INFO Database history initialized: io.debezium.relational.history.KafkaDatabaseHistory
INFO Recorded schema change to history: database=globaldb, schema=core, ddl=...
```

⚠️ **Warnings (if schema history disabled):**
```
DEBUG Database history not configured, skipping schema history recording
```

❌ **Errors:**
```
WARN Failed to initialize database history
WARN Failed to record schema history
```

---

## Troubleshooting Guide

### Problem: Schema history not recording

**Possible Causes:**
1. Topic doesn't exist
2. Configuration missing
3. Kafka connectivity issues
4. Insufficient permissions

**Solutions:**
```bash
# 1. Verify topic exists
kafka-topics.sh --list --bootstrap-server <kafka-bootstrap> | grep schemachanges

# 2. Check connector config
curl http://<kafka-connect>:8083/connectors/<connector-name>/config | jq

# 3. Test Kafka connectivity
kafka-console-producer.sh --broker-list <kafka-bootstrap> --topic test-topic

# 4. Check ACLs (if using authenticated Kafka)
kafka-acls.sh --list --bootstrap-server <kafka-bootstrap> --topic globaldb-staging-schemachanges
```

### Problem: Connector fails to start

**Check:**
1. All required properties are set
2. Kafka bootstrap servers are correct
3. Schema history topic exists
4. YugabyteDB is accessible

**Quick Fix:**
```bash
# View connector error
curl http://<kafka-connect>:8083/connectors/<connector-name>/status | jq '.tasks[0].trace'

# Restart connector
curl -X POST http://<kafka-connect>:8083/connectors/<connector-name>/restart
```

### Problem: No DDL changes captured

**Verify:**
1. DDL is on a table matching `table.include.list`
2. Connector is in RUNNING state
3. Schema history is configured
4. Check logs for recording messages

---

## Production Considerations

### Before Going to Production

- [ ] Test thoroughly in staging on `globaldb.core.products`
- [ ] Monitor schema history topic size and growth
- [ ] Verify no performance impact on connector
- [ ] Document any Shopify-specific tmapi configurations
- [ ] Plan retention policy for schema history topic
- [ ] Set up monitoring/alerting for schema history failures

### Scaling Considerations

- Schema history topic should have **1 partition** (must be sequential)
- Consider separate schema history topics per connector for isolation
- Monitor topic size - schema changes are small but can accumulate
- Plan cleanup strategy if retention policy is insufficient

### Topic Retention

Default configuration:
- **Retention**: 7 days (604800000 ms)
- **Cleanup Policy**: delete

Adjust based on your needs:
```bash
# Increase retention to 30 days
kafka-configs.sh --bootstrap-server <kafka-bootstrap> \
  --entity-type topics \
  --entity-name globaldb-staging-schemachanges \
  --alter \
  --add-config retention.ms=2592000000
```

---

## Next Steps

### Immediate (Staging Deployment)

1. **Create Topic**: Use tmapi to create `globaldb-staging-schemachanges`
2. **Update Config**: Edit `staging-config-example.json` with your values
3. **Deploy**: Deploy connector to staging Kafka Connect
4. **Verify**: Run `./verify-schema-history.sh`
5. **Test**: Execute test DDL on `core.products`

### Short Term (Validation)

1. Monitor for 24-48 hours
2. Test various DDL operations (ADD, ALTER, DROP columns)
3. Verify no performance degradation
4. Document any issues or observations

### Long Term (Expansion)

1. Consider enabling for other tables in `core` schema
2. Expand to other databases if needed
3. Integrate schema history into monitoring dashboards
4. Plan production rollout strategy

---

## Support and Resources

### Documentation

- **Detailed Setup**: See `SCHEMA_HISTORY_SETUP.md`
- **Step-by-Step**: See `DEPLOYMENT_CHECKLIST.md`
- **Quick Commands**: See `QUICK_REFERENCE.md`

### Scripts and Tools

- **Verification**: `./verify-schema-history.sh`
- **Configuration**: `staging-config-example.json`

### Getting Help

1. Check connector logs first
2. Use verification script to identify issues
3. Review configuration against examples
4. Check Kafka topic health and connectivity

---

## Summary

You now have everything needed to deploy the YugabyteDB connector with schema history recording:

✅ Schema history implementation is complete and tested  
✅ Configuration examples are ready to use  
✅ Topic naming follows the `-schemachanges` pattern  
✅ Deployment checklist guides you through the process  
✅ Verification tools help confirm everything works  

**Start with**: `DEPLOYMENT_CHECKLIST.md` for step-by-step instructions.

**Questions?** All details are in `SCHEMA_HISTORY_SETUP.md`.

Good luck with your staging deployment! 🚀
