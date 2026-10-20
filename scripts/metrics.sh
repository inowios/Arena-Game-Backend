#!/usr/bin/env bash
# Launch KPIs from the running server (design spec §1 targets):
#   BASE_URL=https://arenacity.arenaproplay.com ADMIN_TOKEN=… scripts/metrics.sh [days=30]
set -euo pipefail
B="${BASE_URL:-http://localhost:8080}"
: "${ADMIN_TOKEN:?set ADMIN_TOKEN}"
json=$(curl -sf "$B/admin/metrics?days=${1:-30}" -H "X-Admin-Token: $ADMIN_TOKEN")
python3 - "$json" <<'PY'
import json, sys
d = json.loads(sys.argv[1])["data"]
pct = lambda v: "-" if v is None else f"{v * 100:.1f}%"
print(f"Period {d['from']} .. {d['to']}   installs {d['installs']}   DAU {d['dau']}  WAU {d['wau']}  MAU {d['mau']}")
for name, k in d["kpis"].items():
    v = k["value"]
    shown = (f"{v:.2f}" if v is not None else "-") if name == "kFactor" else pct(v)
    goal = f"{k['target']:.2f}" if name == "kFactor" else pct(k["target"])
    print(f"  {name:20} {shown:>8}   goal {goal:>6}   {k['status']}")
print(f"  private leagues: {d['inPrivateLeague']}/{d['activeLast7']} active players; invited new players: {d['invitedNewPlayers']}")
print("Purchases:", ", ".join(f"{k} {v}" for k, v in d["purchases"].items()) or "none", "; ad rewards:", d["adRewards"])
print("Top events:", ", ".join(f"{k} {v}" for k, v in list(d["events"].items())[:12]) or "none")
print("Cohorts (day, installs, D1, D7):")
for c in d["cohorts"][-14:]:
    print(f"  {c['day']} {c['installs']:>6} {c['d1']:>5} {c['d7']:>5}")
PY
