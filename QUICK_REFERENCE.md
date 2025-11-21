# Quick Reference: Schema History Implementation

## What Was Implemented

The YugabyteDB connector now writes schema change events to a dedicated Kafka topic. This implementation:

✅ Records DDL changes during snapshot phase  
✅ Records DDL changes during streaming phase  
✅ Uses Debezium's `KafkaDatabaseHistory` class  
✅ Compatible with Debezium 1.9.5  
✅ Filters schema changes based on table include/exclude lists  

## Key Files Modified

- `YugabyteDBConnectorConfig.java` - Added database history configuration
- `YugabyteDBSchema.java` - Added `recordSchemaHistory()` method
- `YugabyteDBSnapshotChangeEventSource.java` - Records schema during snapshot
- `YugabyteDBStreamingChangeEventSource.java` - Records schema during streaming

## Configuration Properties

### Required
```properties
database.history=io.debezium.relational.history.KafkaDatabaseHistory
database.history.kafka.topic=<your-topic-name>-schemachanges
database.history.kafka.bootstrap.servers=<kafka-brokers>
```

### Optional
```properties
database.history.kafka.recovery.poll.interval.ms=100
database.history.kafka.recovery.attempts=100
```

## Topic Naming Convention

For `globaldb.core.products` with server name `globaldb-staging`:
```
Topic: globaldb-staging-schemachanges
```

Pattern: `{database.server.name}-schemachanges`

## tmapi Command to Create Topic

```bash
tmapi topic create \
  --name globaldb-staging-schemachanges \
  --partitions 1 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --environment staging
```

## Quick Start Commands

### 1. Build Connector
```bash
mvn clean package -DskipTests
```

### 2. Deploy to Kafka Connect
```bash
curl -X POST http://<kafka-connect>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @staging-config-example.json
```

### 3. Verify Schema History
```bash
./verify-schema-history.sh
```

### 4. Watch Schema History Events
```bash
kafka-console-consumer \
  --bootstrap-server <kafka-bootstrap> \
  --topic globaldb-staging-schemachanges \
  --from-beginning \
  --property print.key=true
```

### 5. Test DDL Change
```sql
ALTER TABLE core.products ADD COLUMN test_field VARCHAR(100);
```

## Log Messages to Look For

### Success Messages
```
✅ Database history initialized: io.debezium.relational.history.KafkaDatabaseHistory
✅ Recorded schema change to history: database=globaldb, schema=core, ddl=...
```

### Warning Messages
```
⚠️  Database history not configured, skipping schema history recording
⚠️  Failed to initialize database history
```

## Troubleshooting Quick Fixes

### Issue: Schema history not recording
**Fix:** Verify topic exists and connector has write permissions

### Issue: Connector fails to start
**Fix:** Check that `database.history.kafka.bootstrap.servers` is correct

### Issue: Topic doesn't exist error
**Fix:** Create topic using tmapi before starting connector

### Issue: No schema events appearing
**Fix:** Ensure `table.include.list` matches your table pattern

## Important Notes

1. **Write-Only Mode**: Current implementation doesn't read history on startup
2. **Table Filtering**: Only tables in `table.include.list` have schema recorded
3. **DDL Format**: Current DDL is a placeholder comment, not actual DDL statement
4. **Compatibility**: Uses `database.history` prefix (not `schema.history.internal`)

## Files Created for You

| File | Purpose |
|------|---------|
| `SCHEMA_HISTORY_SETUP.md` | Comprehensive setup guide |
| `DEPLOYMENT_CHECKLIST.md` | Step-by-step deployment checklist |
| `staging-config-example.json` | Sample connector configuration |
| `verify-schema-history.sh` | Automated verification script |
| `QUICK_REFERENCE.md` | This file |

## Testing Strategy for globaldb.core.products

1. **Start Fresh**
   - Create schema history topic
   - Deploy connector with schema history enabled
   - Verify initial schema event is recorded

2. **Test DDL Operations**
   ```sql
   -- Add column
   ALTER TABLE core.products ADD COLUMN test_col VARCHAR(100);
   
   -- Modify column
   ALTER TABLE core.products ALTER COLUMN test_col TYPE TEXT;
   
   -- Drop column
   ALTER TABLE core.products DROP COLUMN test_col;
   ```

3. **Verify Each Change**
   - Check schema history topic for new events
   - Verify timestamps match DDL execution time
   - Confirm data continues flowing normally

## Next Steps After Validation

1. ✅ Validate on `globaldb.core.products` in staging
2. Consider expanding to other tables in `core` schema
3. Monitor performance and topic size
4. Plan production rollout
5. Document any Shopify-specific configurations

## Getting Help

- Review logs: Check Kafka Connect logs for schema history messages
- Verify config: Use Kafka Connect REST API to inspect configuration
- Test connectivity: Ensure connector can write to Kafka topic
- Check permissions: Verify ACLs allow writing to schema history topic

---

**Remember**: Always create the schema history topic BEFORE deploying the connector!
