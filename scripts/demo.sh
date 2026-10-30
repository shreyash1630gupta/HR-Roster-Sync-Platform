#!/usr/bin/env bash
# ==============================================================================
# MoveInSync HR Roster Sync Service — End-to-End Demonstration Script
# Demonstrates: Auth, Real-Time Sync, Idempotency, Out-of-Order Handling,
#               Field Ownership, Nightly CSV Diff, Safety Brake & Outbox DLQ
# ==============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="11111111-1111-1111-1111-111111111111"

# ANSI Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log_step() {
    echo -e "\n${BLUE}======================================================================${NC}"
    echo -e "${GREEN}[STEP] $1${NC}"
    echo -e "${BLUE}======================================================================${NC}"
}

# 1. Health Probe
log_step "1. Probing Actuator Health Endpoint"
curl -s -f "${BASE_URL}/actuator/health" | jq . || curl -s "${BASE_URL}/actuator/health"

# 2. Authenticate & Obtain JWT Tokens
log_step "2. Issuing JWT Tokens for HR_SYSTEM, TRANSPORT_ADMIN, and OPERATOR"

HR_TOKEN=$(curl -s -X POST "${BASE_URL}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d "{\"tenant_id\": \"${TENANT_ID}\", \"role\": \"HR_SYSTEM\", \"subject\": \"hr-client\"}" | jq -r .token)
echo -e "${YELLOW}Obtained HR_SYSTEM Token:${NC} ${HR_TOKEN:0:30}..."

TRANSPORT_TOKEN=$(curl -s -X POST "${BASE_URL}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d "{\"tenant_id\": \"${TENANT_ID}\", \"role\": \"TRANSPORT_ADMIN\", \"subject\": \"transport-admin\"}" | jq -r .token)
echo -e "${YELLOW}Obtained TRANSPORT_ADMIN Token:${NC} ${TRANSPORT_TOKEN:0:30}..."

OPERATOR_TOKEN=$(curl -s -X POST "${BASE_URL}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d "{\"tenant_id\": \"${TENANT_ID}\", \"role\": \"OPERATOR\", \"subject\": \"ops-user\"}" | jq -r .token)
echo -e "${YELLOW}Obtained OPERATOR Token:${NC} ${OPERATOR_TOKEN:0:30}..."

# 3. Ingest Real-Time Events (CREATE & UPDATE)
log_step "3. Ingesting Real-Time Batch Events (CREATE EMP-001 at v1 & EMP-002 at v1)"
BATCH_PAYLOAD=$(cat <<EOF
{
  "events": [
    {
      "event_id": "$(uuidgen || echo "evt-1-$(date +%s)")",
      "event_type": "CREATE",
      "tenant_id": "${TENANT_ID}",
      "external_employee_id": "EMP-001",
      "source_version": 1,
      "data": {
        "name": "Sarah Connor",
        "email": "sarah.connor@example.com",
        "office_code": "BLR-EC"
      }
    },
    {
      "event_id": "$(uuidgen || echo "evt-2-$(date +%s)")",
      "event_type": "CREATE",
      "tenant_id": "${TENANT_ID}",
      "external_employee_id": "EMP-002",
      "source_version": 1,
      "data": {
        "name": "John Connor",
        "email": "john.connor@example.com",
        "office_code": "BLR-EC"
      }
    }
  ]
}
EOF
)

curl -s -X POST "${BASE_URL}/api/v1/sync/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${HR_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -d "${BATCH_PAYLOAD}" | jq .

# 4. Out-of-Order Handling (Send v3 first, then stale v2)
log_step "4. Demonstrating Monotonic Version Ordering (Forward-Progress Only)"
echo "Ingesting newer version v3 for EMP-001..."
curl -s -X POST "${BASE_URL}/api/v1/sync/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${HR_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -d "{
    \"events\": [{
      \"event_id\": \"evt-v3-$(date +%s)\",
      \"event_type\": \"UPDATE\",
      \"tenant_id\": \"${TENANT_ID}\",
      \"external_employee_id\": \"EMP-001\",
      \"source_version\": 3,
      \"data\": { \"name\": \"Sarah Connor Updated\", \"email\": \"sarah.connor@example.com\", \"office_code\": \"BLR-EC\" }
    }]
  }" | jq .

echo -e "\nIngesting older/stale version v2 for EMP-001 (must be SKIPPED)..."
curl -s -X POST "${BASE_URL}/api/v1/sync/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${HR_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -d "{
    \"events\": [{
      \"event_id\": \"evt-v2-$(date +%s)\",
      \"event_type\": \"UPDATE\",
      \"tenant_id\": \"${TENANT_ID}\",
      \"external_employee_id\": \"EMP-001\",
      \"source_version\": 2,
      \"data\": { \"name\": \"Sarah Connor Stale\", \"email\": \"sarah.connor@example.com\", \"office_code\": \"BLR-EC\" }
    }]
  }" | jq .

# 5. Field Ownership Protection
log_step "5. Demonstrating Field Ownership (Transport Admin vs HR System)"
EMP_ID=$(curl -s -X GET "${BASE_URL}/api/v1/employees" \
  -H "Authorization: Bearer ${TRANSPORT_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" | jq -r '.content[0].id')
echo -e "Found Employee Database UUID: ${YELLOW}${EMP_ID}${NC}"

echo -e "\nTransport Admin updates pickup_point to 'Forum Mall':"
curl -s -X PUT "${BASE_URL}/api/v1/employees/${EMP_ID}/transport-fields" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TRANSPORT_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -d '{"pickup_point": "Forum Mall", "special_assistance_needs": "Wheelchair Access"}' | jq .

echo -e "\nHR System attempts to update transport-fields (must be 403 Forbidden):"
curl -s -w "\nHTTP Status: %{http_code}\n" -X PUT "${BASE_URL}/api/v1/employees/${EMP_ID}/transport-fields" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${HR_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -d '{"pickup_point": "Illegal HR Overwrite"}'

# 6. Nightly CSV Reconciliation & Safety Brake
log_step "6. Nightly Reconciliation Upload & Safety Brake"
CSV_FILE="/tmp/nightly_export_demo.csv"
cat <<EOF > "${CSV_FILE}"
employee_id,version,name,email,phone,manager_id,office_code,shift,department,designation
EMP-001,4,Sarah Connor,sarah.connor@example.com,9876543210,,BLR-EC,MORNING,OPERATIONS,LEAD
EOF

echo "Uploading CSV with only 1 employee out of 2 active (50% missing ratio >= 30% brake threshold)..."
curl -s -X POST "${BASE_URL}/api/v1/sync/reconcile" \
  -H "Authorization: Bearer ${HR_SYSTEM_TOKEN:-$HR_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -F "file=@${CSV_FILE}" \
  -F "export_id=NIGHTLY-DEMO-001" \
  -F "generated_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")" | jq .

# 7. DLQ and Metrics Inspection
log_step "7. Inspecting Prometheus Live Observability Metrics"
curl -s -X GET "${BASE_URL}/actuator/prometheus" \
  -H "Authorization: Bearer ${OPERATOR_TOKEN}" | grep -E "sync_events_|outbox_" | head -n 25

echo -e "\n${GREEN}======================================================================${NC}"
echo -e "${GREEN}✓ All Demonstration Scenarios Completed Successfully!${NC}"
echo -e "${GREEN}======================================================================${NC}"

