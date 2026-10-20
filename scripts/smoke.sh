#!/usr/bin/env bash
# End-to-end smoke test against a running server (default http://localhost:8080): the five islands map, heroes, the
# story «Іскра», the Arena match, leagues, economy, live ops, legal pages. Exits non-zero on the first failure.
set -euo pipefail
B="${BASE_URL:-http://localhost:8080}/api/v1"
H='Content-Type: application/json'
DEV="smoke-$(date +%s)-$RANDOM"
j() { python3 -c "import json,sys; d=json.load(sys.stdin); $1"; }
step() { echo "-- $1"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

step "guest login"
AT=$(curl -sf -X POST "$B/auth/guest" -H "$H" -d "{\"deviceId\":\"$DEV\"}" | j "print(d['data']['accessToken'])")
A="Authorization: Bearer $AT"
step "create club"
curl -sf -X POST "$B/club" -H "$H" -H "$A" -d '{"name":"Smoke FC","primaryColor":"#112233","secondaryColor":"#ffffff","timezone":"UTC"}' >/dev/null
step "club state: the stadium open, nothing else; the builder's and the athletes' fields are gone"
curl -sf "$B/club" -H "$A" | j "c=d['data']; assert c['club']['coins']==2500 and c['stadium']==1 and c['spots']==['stadium'] and 'buildings' not in c and 'live' not in c and c['energy']['current']==30"
step "map spots: open the academy (coins for gold first)"
curl -sf "$B/catalog/spots" | j "assert len(d['data'])==10 and d['data'][1]['id']=='academy'"
curl -sf -X POST "$B/club/shop/coins/coins_small" -H "$A" | j "assert d['data']['coins']==5500"
curl -sf -X POST "$B/club/spots/academy/unlock" -H "$A" | j "assert d['data']['stadium']==2 and d['data']['spots']==['stadium','academy'] and d['data']['club']['coins']==2500"
test "$(code -X POST "$B/club/spots/academy/unlock" -H "$A")" = "409"
step "the routes that went answer 404"
for P in club/athletes club/packs club/offer market live/recent club/museum club/scouting club/lineup/football store/offers; do test "$(code "$B/$P" -H "$A")" = "404" || { echo "GET $P is still there"; exit 1; }; done
for P in club/academy/claim club/trainings club/rest live/start auth/logout challenges/mine; do test "$(code -X POST "$B/$P" -H "$A")" = "404" || { echo "POST $P is still there"; exit 1; }; done
step "heroes: the start six, one bought for gems, the lineup, training"
curl -sf "$B/club/heroes" -H "$A" | j "r=d['data']; assert len(r['heroes'])==51 and r['owned']==6 and r['stadium']==2; k=[h for h in r['heroes'] if h['id']=='p001'][0]; assert k['owned'] and k['level']==1 and k['source']=='START'"
test "$(code -X POST "$B/club/heroes/p006/unlock" -H "$A")" = "402"
curl -sf -X POST "$B/purchases/verify" -H "$H" -H "$A" -d '{"store":"DEV","productId":"gold_medium","token":"dev:smoke-gold-'$DEV'"}' | j "assert d['data']['status']=='GRANTED' and d['data']['gold']==550"
curl -sf -X POST "$B/club/heroes/p006/unlock" -H "$A" | j "assert d['data']['spent']==500 and d['data']['hero']['source']=='CRYSTALS'"
curl -sf -X POST "$B/club/heroes/p006/unlock" -H "$A" | j "assert d['data']['spent']==0"
test "$(code -X POST "$B/club/heroes/p012/unlock" -H "$A")" = "400"
curl -sf -X PUT "$B/club/heroes/lineup" -H "$H" -H "$A" -d '{"field":["p001","p003","p007"],"keeper":"p011","bench":["p006"]}' | j "assert d['data']['saved']"
test "$(code -X PUT "$B/club/heroes/lineup" -H "$H" -H "$A" -d '{"field":["p010"]}')" = "400"
curl -sf "$B/club/heroes/training" -H "$A" | j "t=d['data']; assert t['available'] and t['slots']==1 and t['coins']==350"
curl -sf -X POST "$B/club/heroes/p003/train" -H "$A" | j "assert d['data']['spent']==350 and d['data']['training']['busy']==1"
test "$(code -X POST "$B/club/heroes/p003/train/claim" -H "$A")" = "409"
step "story: chapter 1 active; an Arena match start takes energy, its result pays coins, hero experience and story progress"
curl -sf "$B/club/story" -H "$A" | j "s=d['data']; assert s['chapter']==1 and len(s['chapters'])==6 and s['chapters'][0]['state']=='ACTIVE'"
curl -sf -X POST "$B/club/arena/start" -H "$A" | j "assert d['data']['energy']['current']==25"
curl -sf -X POST "$B/club/arena/result" -H "$H" -H "$A" -d '{"seed":1,"won":true,"goalsFor":1,"goalsAgainst":0,"heroes":["p001","p003","p007"]}' | j "r=d['data']; assert r['coins']==120 and r['heroXp']==50 and [h['id'] for h in r['heroes']]==['p001','p003','p007'] and r['story']['chapter']['number']==1"
test "$(code -X POST "$B/club/arena/result" -H "$H" -H "$A" -d '{"seed":2,"won":true,"heroes":["p001","p002","p003","p004","p005","p006","p007","p008","p009","p010","p011"]}')" = "400"
step "update club colours + emblem"
curl -sf -X PATCH "$B/club" -H "$H" -H "$A" -d '{"primaryColor":"#ff6600","emblemJson":"{\"crest\":3}"}' | j "assert d['data']['primaryColor']=='#FF6600' and 'crest' in d['data']['emblemJson']"
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
curl -sf "$B/friends" -H "$A" | j "assert [f['club']['name'] for f in d['data']]==['Smoke United']"
if [ "${SMOKE_MATCHDAY:-1}" = "1" ] && command -v psql >/dev/null; then
  step "force matchday: backdate season and run the scheduler tick (dev route)"
  psql -q -d "${SMOKE_DB:-arenacity}" -c "update leagues set season_start = current_date - 1 where id = '$LID'"
  curl -sf -X POST "$B/dev/matchday/run" | j "assert d['data']['played']>=4, d"
  curl -sf "$B/leagues/mine" -H "$A" | j "p=d['data']['public']; assert len(p['table'])==8 and p['table'][0]['played']>=1; assert len(d['data']['private'][0]['table'])==2"
  curl -sf "$B/club" -H "$A" | j "assert d['data']['club']['reputation']>=1"
fi
step "economy: daily reward, pass, dev purchase, push token"
curl -sf -X POST "$B/club/daily/claim" -H "$A" | j "assert d['data']['day']==1 and d['data']['reward']['coins']==150"
test "$(code -X POST "$B/club/daily/claim" -H "$A")" = "409"
curl -sf "$B/club/pass" -H "$A" | j "assert len(d['data']['tasks'])==3 and len(d['data']['levels'])==30"
curl -sf -X POST "$B/purchases/verify" -H "$H" -H "$A" -d '{"store":"DEV","productId":"gold_small","token":"dev:smoke-'$DEV'"}' | j "assert d['data']['status']=='GRANTED' and d['data']['gold']==100"
curl -sf -X POST "$B/purchases/verify" -H "$H" -H "$A" -d '{"store":"DEV","productId":"gold_small","token":"dev:smoke-'$DEV'"}' | j "assert d['data']['status']=='DUPLICATE'"
if [ "${SMOKE_MATCHDAY:-1}" = "1" ]; then
  step "store refund and its reversal (dev route)"
  G1=$(curl -sf "$B/club" -H "$A" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['club']['gold'])")
  curl -sf -X POST "$B/dev/store/refund" -H "$H" -d '{"externalId":"dev:gold_small:smoke-'$DEV'","action":"REFUND"}' | j "assert d['data']['outcome']=='APPLIED'"
  curl -sf -X POST "$B/dev/store/refund" -H "$H" -d '{"externalId":"dev:gold_small:smoke-'$DEV'","action":"REFUND"}' | j "assert d['data']['outcome']=='ALREADY'"
  curl -sf "$B/club" -H "$A" | j "assert d['data']['club']['gold']==$G1-100"
  curl -sf "$B/club/inbox" -H "$A" | j "assert any(i['kind']=='REFUND' for i in d['data']['items'])"
  curl -sf -X POST "$B/dev/store/refund" -H "$H" -d '{"externalId":"dev:gold_small:smoke-'$DEV'","action":"RESTORE"}' | j "assert d['data']['outcome']=='APPLIED'"
  curl -sf "$B/club" -H "$A" | j "assert d['data']['club']['gold']==$G1"
fi
curl -sf -X POST "$B/push-tokens" -H "$H" -H "$A" -d '{"token":"fcm-smoke-'$DEV'","platform":"android"}' | j "assert d['data']['ok']"
step "the arena chest, roads, achievements"
curl -sf "$B/live/chest" -H "$A" | j "assert d['data']['arena']=='YARD' and d['data']['available']"
curl -sf -X POST "$B/live/chest/claim" -H "$A" | j "assert not d['data']['available'] and d['data']['club']"
curl -sf "$B/club/roads" -H "$A" | j "assert [r['road'] for r in d['data']]==['TROPHY','LEVEL']"
curl -sf "$B/club/achievements" -H "$A" | j "assert any(a['id']=='friendly_face' and a['value']==1 for a in d['data'])"
step "weekly cup tab, events and mail, analytics batch, legal pages"
curl -sf "$B/cups/current" -H "$A" | j "assert d['data']['state'] in ('UPCOMING','ACTIVE','FINISHED') and len(d['data']['prizes'])==5"
curl -sf "$B/events" -H "$A" >/dev/null; curl -sf "$B/club/mail" -H "$A" | j "assert isinstance(d['data'], list)"
curl -sf -X POST "$B/analytics/events" -H "$H" -H "$A" -d '{"platform":"other","appVersion":"smoke","events":[{"name":"session_start"}]}' | j "assert d['data']['accepted']==1"
for P in privacy terms delete-account; do test "$(code "${BASE_URL:-http://localhost:8080}/$P")" = "200"; done
if [ -n "${ADMIN_TOKEN:-}" ]; then
  step "ops endpoints (ADMIN_TOKEN set)"
  curl -sf "${BASE_URL:-http://localhost:8080}/metrics" -H "X-Admin-Token: $ADMIN_TOKEN" | grep -q "^http_requests_total"
  curl -sf "${BASE_URL:-http://localhost:8080}/admin/stats" -H "X-Admin-Token: $ADMIN_TOKEN" | j "assert d['data']['counts']['clubs']>=1"
  curl -sf "${BASE_URL:-http://localhost:8080}/admin/metrics?days=7" -H "X-Admin-Token: $ADMIN_TOKEN" | j "assert d['data']['dau']>=1"
fi
echo "SMOKE OK"
