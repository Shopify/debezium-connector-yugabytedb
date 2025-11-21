#!/bin/bash

# Verification script for YugabyteDB Connector Schema History
# This script helps verify that schema history is properly configured and working

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
CONNECTOR_NAME="${CONNECTOR_NAME:-yugabytedb-connector-globaldb-core-products-staging}"
KAFKA_CONNECT_HOST="${KAFKA_CONNECT_HOST:-localhost:8083}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
SCHEMA_HISTORY_TOPIC="${SCHEMA_HISTORY_TOPIC:-globaldb-staging-schemachanges}"

echo "=========================================="
echo "YugabyteDB Connector Schema History Check"
echo "=========================================="
echo ""

# Function to print success message
success() {
    echo -e "${GREEN}✓${NC} $1"
}

# Function to print error message
error() {
    echo -e "${RED}✗${NC} $1"
}

# Function to print warning message
warning() {
    echo -e "${YELLOW}!${NC} $1"
}

# Check 1: Connector exists
echo "Checking connector status..."
if curl -s "http://${KAFKA_CONNECT_HOST}/connectors/${CONNECTOR_NAME}/status" | grep -q "RUNNING"; then
    success "Connector is RUNNING"
else
    error "Connector is not running or doesn't exist"
    curl -s "http://${KAFKA_CONNECT_HOST}/connectors/${CONNECTOR_NAME}/status" | jq '.' || true
fi
echo ""

# Check 2: Connector configuration includes schema history
echo "Checking connector configuration..."
CONFIG=$(curl -s "http://${KAFKA_CONNECT_HOST}/connectors/${CONNECTOR_NAME}/config" || echo "{}")

if echo "$CONFIG" | grep -q "database.history"; then
    success "Schema history is configured"
    echo "  - database.history: $(echo "$CONFIG" | jq -r '."database.history"' 2>/dev/null || echo "N/A")"
    echo "  - database.history.kafka.topic: $(echo "$CONFIG" | jq -r '."database.history.kafka.topic"' 2>/dev/null || echo "N/A")"
    echo "  - database.history.kafka.bootstrap.servers: $(echo "$CONFIG" | jq -r '."database.history.kafka.bootstrap.servers"' 2>/dev/null || echo "N/A")"
else
    error "Schema history is NOT configured"
fi
echo ""

# Check 3: Schema history topic exists
echo "Checking schema history topic..."
if kafka-topics.sh --list --bootstrap-server "${KAFKA_BOOTSTRAP}" 2>/dev/null | grep -q "^${SCHEMA_HISTORY_TOPIC}$"; then
    success "Schema history topic exists: ${SCHEMA_HISTORY_TOPIC}"
    
    # Get topic details
    echo "  Topic details:"
    kafka-topics.sh --describe --topic "${SCHEMA_HISTORY_TOPIC}" --bootstrap-server "${KAFKA_BOOTSTRAP}" 2>/dev/null | grep -E "Topic:|PartitionCount:|ReplicationFactor:" | sed 's/^/    /'
else
    error "Schema history topic does NOT exist: ${SCHEMA_HISTORY_TOPIC}"
    warning "Create it using: tmapi topic create --name ${SCHEMA_HISTORY_TOPIC} --partitions 1 --replication-factor 3"
fi
echo ""

# Check 4: Messages in schema history topic
echo "Checking schema history messages..."
MESSAGE_COUNT=$(kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "${KAFKA_BOOTSTRAP}" --topic "${SCHEMA_HISTORY_TOPIC}" 2>/dev/null | awk -F ':' '{sum += $3} END {print sum}' || echo "0")

if [ "$MESSAGE_COUNT" -gt 0 ]; then
    success "Schema history topic has $MESSAGE_COUNT message(s)"
    echo ""
    echo "  Sample messages (last 3):"
    kafka-console-consumer.sh \
        --bootstrap-server "${KAFKA_BOOTSTRAP}" \
        --topic "${SCHEMA_HISTORY_TOPIC}" \
        --from-beginning \
        --max-messages 3 \
        --property print.key=true \
        --property print.timestamp=true \
        --timeout-ms 5000 2>/dev/null | sed 's/^/    /' || warning "Could not read messages"
else
    warning "Schema history topic is empty (no messages yet)"
    echo "    This is expected if:"
    echo "      - Connector just started"
    echo "      - No schema changes have occurred"
    echo "      - Initial snapshot hasn't completed"
fi
echo ""

# Check 5: Connector logs
echo "Checking recent connector logs for schema history..."
echo "  (Note: This requires access to Kafka Connect logs)"
warning "Check your Kafka Connect logs for these messages:"
echo '    - "Database history initialized: io.debezium.relational.history.KafkaDatabaseHistory"'
echo '    - "Recorded schema change to history: database=..., schema=..., ddl=..."'
echo ""

# Check 6: Data topic
DATA_TOPIC="globaldb-staging.core.products"
echo "Checking data topic..."
if kafka-topics.sh --list --bootstrap-server "${KAFKA_BOOTSTRAP}" 2>/dev/null | grep -q "^${DATA_TOPIC}$"; then
    success "Data topic exists: ${DATA_TOPIC}"
    
    DATA_MESSAGE_COUNT=$(kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "${KAFKA_BOOTSTRAP}" --topic "${DATA_TOPIC}" 2>/dev/null | awk -F ':' '{sum += $3} END {print sum}' || echo "0")
    echo "  - Message count: $DATA_MESSAGE_COUNT"
else
    warning "Data topic does NOT exist yet: ${DATA_TOPIC}"
    echo "    This may be expected if snapshot hasn't completed"
fi
echo ""

# Summary
echo "=========================================="
echo "Summary"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  1. If connector is not running, check Kafka Connect logs"
echo "  2. If schema history topic doesn't exist, create it using tmapi"
echo "  3. If no messages in schema history, wait for initial snapshot or trigger a DDL change"
echo "  4. Test with: ALTER TABLE core.products ADD COLUMN test_field VARCHAR(100);"
echo ""
echo "For detailed configuration, see: SCHEMA_HISTORY_SETUP.md"
echo "For deployment steps, see: DEPLOYMENT_CHECKLIST.md"
echo ""
