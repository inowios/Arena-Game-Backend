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
BID=$(curl -sf -X POST "$B/club/buildings" -H "$H" -H "$A" -d '{"type":"ACADEMY","x":0,"y":10}' | j "print(d['data']['id'])")
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
step "update club colours + emblem"
curl -sf -X PATCH "$B/club" -H "$H" -H "$A" -d '{"primaryColor":"#ff6600","emblemJson":"{\"shape\":\"shield\",\"icon\":\"ball\"}"}' | j "assert d['data']['primaryColor']=='#FF6600' and 'shield' in d['data']['emblemJson']"
step "my leagues: public league with me in it"
LID=$(curl -sf "$B/leagues/mine" -H "$A" | j "p=d['data']['public']; assert p and len(p['table'])>=1; print(p['league']['id'])")
step "friendly vs bot"
MID=$(curl -sf -X POST "$B/friendlies" -H "$H" -H "$A" -d '{}' | j "f=d['data']['fixture']; assert f['status']=='PLAYED' and d['data']['result']['events']; print(f['id'])")
curl -sf "$B/matches/$MID" -H "$A" | j "assert d['data']['result']['events'][0]['type']=='KICKOFF'"
curl -sf "$B/club/fixtures" -H "$A" | j "assert len(d['data']['recent'])==1"
step "private league create + join with a second guest"
CODE=$(curl -sf -X POST "$B/leagues/private" -H "$H" -H "$A" -d '{"name":"Office league"}' | j "print(d['data']['code'])")
AT2=$(curl -sf -X POST "$B/auth/guest" -H "$H" -d "{\"deviceId\":\"$DEV-second\"}" | j "print(d['data']['accessToken'])")
curl -sf -X POST "$B/club" -H "$H" -H "Authorization: Bearer $AT2" -d '{"name":"Smoke United","primaryColor":"#004400","secondaryColor":"#ffffff","timezone":"UTC"}' >/dev/null
curl -sf -X POST "$B/leagues/private/join" -H "$H" -H "Authorization: Bearer $AT2" -d "{\"code\":\"$CODE\"}" | j "assert d['data']['memberCount']==2"
if [ "${SMOKE_MATCHDAY:-1}" = "1" ] && command -v psql >/dev/null; then
  step "force matchday: backdate season and run the scheduler tick (dev route)"
  psql -q -d "${SMOKE_DB:-matchdayclub}" -c "update leagues set season_start = current_date - 1 where id = '$LID'"
  curl -sf -X POST "$B/dev/matchday/run" | j "assert d['data']['played']>=4, d"
  curl -sf "$B/leagues/mine" -H "$A" | j "p=d['data']['public']; assert len(p['table'])==8 and p['table'][0]['played']>=1; assert len(d['data']['private'][0]['table'])==2"
  curl -sf "$B/leagues/$LID/fixtures?round=1" -H "$A" | j "assert len(d['data'])==4 and all(f['status']=='PLAYED' for f in d['data'])"
  curl -sf "$B/club" -H "$A" | j "assert d['data']['club']['reputation']>=1"
fi
step "economy: daily reward, pass, dev purchase, ad reward, push token"
curl -sf -X POST "$B/club/daily/claim" -H "$A" | j "assert d['data']['day']==1 and d['data']['reward']['coins']==150"
test "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/club/daily/claim" -H "$A")" = "409"
curl -sf "$B/club/pass" -H "$A" | j "assert len(d['data']['tasks'])==3 and len(d['data']['levels'])==30"
curl -sf -X POST "$B/purchases/verify" -H "$H" -H "$A" -d '{"store":"DEV","productId":"gold_small","token":"dev:smoke-'$DEV'"}' | j "assert d['data']['status']=='GRANTED' and d['data']['gold']==100"
curl -sf -X POST "$B/purchases/verify" -H "$H" -H "$A" -d '{"store":"DEV","productId":"gold_small","token":"dev:smoke-'$DEV'"}' | j "assert d['data']['status']=='DUPLICATE'"
curl -sf -X POST "$B/ads/reward" -H "$H" -H "$A" -d '{"placement":"DOUBLE_INCOME"}' | j "assert d['data']['id']"
curl -sf -X POST "$B/push-tokens" -H "$H" -H "$A" -d '{"token":"fcm-smoke-'$DEV'","platform":"android"}' | j "assert d['data']['ok']"
echo "SMOKE OK"
