# Deployment Checklist for Schema History on Staging

## Pre-Deployment Steps

- [ ] **1. Create Schema History Topic Using tmapi**
  ```bash
  # Use Shopify's tmapi to create the topic
  tmapi topic create \
    --name globaldb-staging-schemachanges \
    --partitions 1 \
    --replication-factor 3 \
    --config retention.ms=604800000 \
    --environment staging
  ```
  
- [ ] **2. Verify Topic Creation**
  ```bash
  # Confirm topic exists
  kafka-topics --list --bootstrap-server <kafka-bootstrap> | grep globaldb-staging-schemachanges
  
  # Check topic details
  kafka-topics --describe --topic globaldb-staging-schemachanges --bootstrap-server <kafka-bootstrap>
  ```

- [ ] **3. Build the Connector**
  ```bash
  cd /workspace
  mvn clean package -DskipTests
  ```

- [ ] **4. Update Configuration File**
  - Edit `staging-config-example.json`
  - Replace all `REPLACE_WITH_*` placeholders with actual values:
    - `REPLACE_WITH_YUGABYTEDB_HOST`
    - `REPLACE_WITH_MASTER_ADDRESSES` (format: `host1:7100,host2:7100,host3:7100`)
    - `REPLACE_WITH_USERNAME`
    - `REPLACE_WITH_PASSWORD`
    - `REPLACE_WITH_STREAM_ID_OR_SLOT_NAME`
    - `REPLACE_WITH_KAFKA_BOOTSTRAP_SERVERS` (format: `broker1:9092,broker2:9092`)

## Deployment Steps

- [ ] **5. Deploy Connector to Kafka Connect**
  ```bash
  # Delete existing connector if it exists
  curl -X DELETE http://<kafka-connect-host>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging
  
  # Deploy new connector
  curl -X POST http://<kafka-connect-host>:8083/connectors \
    -H "Content-Type: application/json" \
    -d @staging-config-example.json
  ```

- [ ] **6. Verify Connector Status**
  ```bash
  # Check connector status
  curl http://<kafka-connect-host>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging/status | jq
  
  # Should show: "state": "RUNNING"
  ```

## Verification Steps

- [ ] **7. Check Connector Logs**
  ```bash
  # Look for schema history initialization
  # Expected: "Database history initialized: io.debezium.relational.history.KafkaDatabaseHistory"
  ```

- [ ] **8. Verify Initial Schema Event**
  ```bash
  # Consume from schema history topic
  kafka-console-consumer \
    --bootstrap-server <kafka-bootstrap> \
    --topic globaldb-staging-schemachanges \
    --from-beginning \
    --property print.key=true \
    --max-messages 10
  
  # Should see schema change event for core.products table
  ```

- [ ] **9. Check Data Topic**
  ```bash
  # Verify data is flowing to main topic
  kafka-console-consumer \
    --bootstrap-server <kafka-bootstrap> \
    --topic globaldb-staging.core.products \
    --from-beginning \
    --max-messages 5
  ```

## Testing Schema Changes

- [ ] **10. Test DDL Operations**
  Connect to YugabyteDB and run test DDL:
  ```sql
  -- Add a test column
  ALTER TABLE core.products ADD COLUMN test_schema_history_field VARCHAR(100);
  
  -- Wait a few seconds, then check schema history topic
  ```

- [ ] **11. Verify DDL Captured**
  ```bash
  # Check for new schema change event
  kafka-console-consumer \
    --bootstrap-server <kafka-bootstrap> \
    --topic globaldb-staging-schemachanges \
    --from-beginning \
    --property print.key=true
  
  # Should see new event for the ALTER TABLE
  ```

- [ ] **12. Verify New Column in Data**
  ```bash
  # Insert data with new column
  # Then check if it appears in Kafka
  kafka-console-consumer \
    --bootstrap-server <kafka-bootstrap> \
    --topic globaldb-staging.core.products \
    --from-beginning \
    --max-messages 1
  ```

- [ ] **13. Clean Up Test Column (Optional)**
  ```sql
  ALTER TABLE core.products DROP COLUMN test_schema_history_field;
  ```

## Monitoring and Validation

- [ ] **14. Monitor Key Metrics**
  - Connector uptime
  - Schema history topic size
  - Data topic lag
  - Error count in connector logs

- [ ] **15. Verify Schema History Topic Health**
  ```bash
  # Check message count
  kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list <kafka-bootstrap> \
    --topic globaldb-staging-schemachanges
  ```

## Troubleshooting Commands

```bash
# View connector logs
kubectl logs <kafka-connect-pod> -n <namespace> | grep -i "schema\|history"

# Check connector tasks
curl http://<kafka-connect-host>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging/tasks | jq

# Restart connector if needed
curl -X POST http://<kafka-connect-host>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging/restart

# Delete and recreate
curl -X DELETE http://<kafka-connect-host>:8083/connectors/yugabytedb-connector-globaldb-core-products-staging
curl -X POST http://<kafka-connect-host>:8083/connectors -H "Content-Type: application/json" -d @staging-config-example.json
```

## Success Criteria

✅ Connector is in RUNNING state
✅ Schema history topic contains initial schema event
✅ Data is flowing to globaldb-staging.core.products topic
✅ DDL changes are captured in schema history topic
✅ No errors in connector logs related to schema history

## Notes

- **Rollback Plan**: If issues occur, remove `database.history.*` properties and redeploy
- **Performance**: Schema history recording has minimal performance impact
- **Retention**: Schema history topic retention is 7 days by default
- **Next Steps**: After validation, consider expanding to other tables in globaldb

## Contact

For issues or questions:
- Check logs first
- Review SCHEMA_HISTORY_SETUP.md for detailed configuration
- Verify all prerequisites are met
