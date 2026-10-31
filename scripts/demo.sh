#!/usr/bin/env bash
# Walks through every main flow against a running `docker compose up`. Needs curl, jq, python3.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

# Log in once per user (DAO authentication), then send the JWT on every call
token() {
  curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"demo123\"}" | jq -r .accessToken
}
HR="Authorization: Bearer $(token hr-acme)"; ADMIN="Authorization: Bearer $(token admin-acme)"
OPS="Authorization: Bearer $(token ops-acme)"; EMP="Authorization: Bearer $(token emp-e1001)"
GLOBEX="Authorization: Bearer $(token admin-globex)"
post_events() { curl -s -X POST "$BASE/api/v1/hr/events" -H "$HR" -H 'Content-Type: application/json' -d "$1"; }
step() { echo; echo "=== $* ==="; }

E1='"name":"Demo Employee 1","email":"employee1@acme.example","phone":"+919876543201"'

step "1. A batch with bad records still succeeds; each bad one is reported"
post_events '{"events":[
  {"eventType":"CREATE","employeeId":"E2001","version":1,"name":"New Joiner","email":"nj@acme.example","phone":"+919812345678","officeCode":"BLR1","shiftCode":"DAY"},
  {"eventType":"CREATE","employeeId":"E2002","version":1,"name":"Bad Phone","email":"bp@acme.example","phone":"12345","officeCode":"BLR1","shiftCode":"DAY"},
  {"eventType":"CREATE","employeeId":"E2003","version":1,"name":"Bad Office","email":"bo@acme.example","phone":"+919812345679","officeCode":"XXX","shiftCode":"DAY"}]}' | jq

step "2. Out of order: v7 transfer arrives, then the v6 shift change (stale, ignored)"
post_events "{\"events\":[
  {\"eventType\":\"UPDATE\",\"employeeId\":\"E1001\",\"version\":7,$E1,\"officeCode\":\"HYD1\",\"shiftCode\":\"NIGHT\"},
  {\"eventType\":\"UPDATE\",\"employeeId\":\"E1001\",\"version\":6,$E1,\"officeCode\":\"BLR1\",\"shiftCode\":\"EVENING\"}]}" | jq '{applied, stale}'
curl -s "$BASE/api/v1/employees/E1001" -H "$ADMIN" | jq '{employeeId, officeCode, shiftCode, hrVersion}'

step "3. The same event again: duplicate, nothing changes"
post_events "{\"events\":[{\"eventType\":\"UPDATE\",\"employeeId\":\"E1001\",\"version\":7,$E1,\"officeCode\":\"HYD1\",\"shiftCode\":\"NIGHT\"}]}" | jq '{applied, duplicates}'

step "4. Ownership: admin sets the pickup point, then HR tries to overwrite it"
curl -s -X PATCH "$BASE/api/v1/employees/E1001/transport" -H "$ADMIN" \
  -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 4, Tower B"}' | jq '{employeeId, pickupPoint}'
post_events "{\"events\":[{\"eventType\":\"UPDATE\",\"employeeId\":\"E1001\",\"version\":8,$E1,\"officeCode\":\"HYD1\",\"shiftCode\":\"DAY\",\"pickupPoint\":\"HR says main road\"}]}" | jq '{applied, ownershipIgnored}'
curl -s "$BASE/api/v1/employees/E1001" -H "$ADMIN" | jq '{pickupPoint, shiftCode, hrVersion}'

step "5. A leaver: the outbox delivers the event and the future cab booking is cancelled"
post_events '{"events":[{"eventType":"DEACTIVATE","employeeId":"E1002","version":2}]}' | jq '{applied}'
sleep 1
curl -s "$BASE/api/v1/ops/outbox" -H "$OPS" | jq

step "6. A truncated nightly file trips the safety brake"
mkdir -p target   # relative path, so it works on Linux, macOS and Git Bash on Windows
python3 scripts/generate_snapshot.py 10 target/demo-truncated.csv --cut 2 >/dev/null
RUN=$(curl -s -X POST "$BASE/api/v1/hr/snapshots" -H "$HR" -F "file=@target/demo-truncated.csv")
echo "$RUN" | jq '{runId, status, activeBefore, missingFromFile, missingRatio, deactivated}'
RUN_ID=$(echo "$RUN" | jq -r .runId)
curl -s "$BASE/api/v1/ops/reconciliations/$RUN_ID/rejections" -H "$OPS" | jq
echo "The operator rejects the broken file:"
curl -s -X POST "$BASE/api/v1/ops/reconciliations/$RUN_ID/reject" -H "$OPS" | jq '{status, decidedBy}'

step "7. Live failure: the relay crashes 3 times; the event waits in the outbox, then is delivered"
curl -s -X POST "$BASE/api/v1/ops/failures/PUBLISH?times=3" -H "$OPS" | jq
post_events '{"events":[{"eventType":"UPDATE","employeeId":"E1003","version":2,"name":"Demo Employee 3","email":"employee3@acme.example","phone":"+919876543203","officeCode":"HYD1","shiftCode":"DAY"}]}' | jq '{applied}'
curl -s "$BASE/api/v1/ops/outbox" -H "$OPS" | jq
echo "(waiting for retries with backoff...)"; sleep 9
curl -s "$BASE/api/v1/ops/outbox" -H "$OPS" | jq

step "8. Tenant isolation and roles"
echo -n "globex admin reads acme's E1001 -> HTTP "; curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/v1/employees/E1001" -H "$GLOBEX"
echo -n "employee reads someone else    -> HTTP "; curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/v1/employees/E1003" -H "$EMP"
curl -s "$BASE/api/v1/employees/me" -H "$EMP" | jq '{employeeId, officeCode}'

step "9. What on-call looks at"
curl -s "$BASE/actuator/prometheus" -H "$OPS" | grep -E '^rostersync_' | head -30
