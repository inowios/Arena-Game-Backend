#!/usr/bin/env bash
# End-to-end smoke test against a running server (default http://localhost:8080). Exits non-zero on the first failure.
set -euo pipefail
B="${BASE_URL:-http://localhost:8080}/api/v1"
H='Content-Type: application/json'
DEV="smoke-$(date +%s)-$RANDOM"
j() { python3 -c "import json,sys; d=json.load(sys.stdin); $1"; }
step() { echo "-- $1"; }

step "guest login"
AT=$(curl -sf -X POST "$B/auth/guest" -H "$H" -d "{\"deviceId\":\"$DEV\"}" | j "print(d['data']['accessToken'])")
A="Authorization: Bearer $AT"
step "create club"
curl -sf -X POST "$B/club" -H "$H" -H "$A" -d '{"name":"Smoke FC","primaryColor":"#112233","secondaryColor":"#ffffff","timezone":"UTC"}' >/dev/null
step "club state"
curl -sf "$B/club" -H "$A" | j "assert d['data']['club']['coins']==2500 and len(d['data']['buildings'])==2"
step "place academy, speed up, claim graduate"
BID=$(curl -sf -X POST "$B/club/buildings" -H "$H" -H "$A" -d '{"type":"ACADEMY","x":0,"y":7}' | j "print(d['data']['id'])")
curl -sf -X POST "$B/club/buildings/$BID/speedup" -H "$A" | j "assert d['data']['state']=='IDLE'"
curl -sf -X POST "$B/club/academy/claim" -H "$A" | j "assert d['data']['overall']>0"
step "second claim must be 409"
test "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/club/academy/claim" -H "$A")" = "409"
step "squad and default lineup"
curl -sf "$B/club/athletes" -H "$A" | j "assert len(d['data']['athletes'])==15"
BODY=$(curl -sf "$B/club/lineup/football" -H "$A" | j "l=d['data']['lineup']; assert l['isDefault'] and len(l['starters'])==11; print(json.dumps({'starters':l['starters'],'bench':l['bench'],'tactics':{'style':'DEFEND','tempo':'LOW','plan':'COUNTER'}}))")
curl -sf -X PUT "$B/club/lineup/football" -H "$H" -H "$A" -d "$BODY" | j "assert d['data']['tactics']['plan']=='COUNTER' and not d['data']['isDefault']"
step "collect ticket office income (0 right after creation)"
OID=$(curl -sf "$B/club" -H "$A" | j "print([b['id'] for b in d['data']['buildings'] if b['type']=='TICKET_OFFICE'][0])")
curl -sf -X POST "$B/club/buildings/$OID/collect" -H "$A" | j "assert d['data']['coins']==0"
echo "SMOKE OK"
