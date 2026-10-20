package com.arenacity.presentation.route

/**
 * GET /admin/dashboard: a static page (no data in it) that asks for the admin token, keeps it for the browser tab only
 * (sessionStorage) and reads /admin/funnel, /admin/balance and /admin/metrics with the X-Admin-Token header.
 */
object DashboardPage {
    val HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Arena · dashboard</title>
<style>
:root{--bg:#0b1020;--card:#141b30;--ink:#e8eefc;--mut:#8b97b8;--cyan:#33d6ff;--mag:#ff4fd8;--amb:#ffb340;--ok:#41d67a;--bad:#ff5a6a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif}
header{display:flex;flex-wrap:wrap;gap:10px;align-items:center;padding:14px 16px;border-bottom:1px solid #223}
h1{font-size:18px;margin:0 12px 0 0;color:var(--cyan)}h2{font-size:15px;margin:0 0 10px;color:var(--mut);text-transform:uppercase;letter-spacing:.06em}
input,select,button{background:#0f1528;color:var(--ink);border:1px solid #2a3557;border-radius:8px;padding:7px 10px;font:inherit}
button{background:var(--cyan);color:#001018;border:0;font-weight:600;cursor:pointer}
main{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px;padding:16px}
.card{background:var(--card);border-radius:12px;padding:14px;overflow-x:auto}.wide{grid-column:1/-1}
table{border-collapse:collapse;width:100%}td,th{padding:5px 8px;text-align:right;white-space:nowrap}th{color:var(--mut);font-weight:500}td:first-child,th:first-child{text-align:left}
.bar{height:10px;border-radius:5px;background:linear-gradient(90deg,var(--cyan),var(--mag));min-width:2px}
.kpi{display:flex;gap:18px;flex-wrap:wrap}.kpi div{min-width:90px}.kpi b{display:block;font-size:22px}.mut{color:var(--mut)}.err{color:var(--bad)}
</style></head><body>
<header><h1>Arena</h1>
<input id="tok" type="password" placeholder="admin token" autocomplete="off">
<select id="days"><option>7</option><option selected>14</option><option>30</option><option>60</option></select>
<button id="go">Load</button><span id="msg" class="mut"></span></header>
<main>
<section class="card wide"><h2>First ten minutes</h2><div id="funnel" class="mut">—</div></section>
<section class="card"><h2>Launch KPIs</h2><div id="kpi" class="mut">—</div></section>
<section class="card"><h2>Arena matches</h2><div id="live" class="mut">—</div></section>
<section class="card"><h2>Active clubs</h2><div id="eco" class="mut">—</div></section>
<section class="card"><h2>Money</h2><div id="money" class="mut">—</div></section>
<section class="card wide"><h2>Map spots open</h2><div id="spots" class="mut">—</div></section>
</main>
<script>
const ${'$'} = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%';
const dur = s => s == null ? '—' : s < 60 ? s + 's' : s < 3600 ? Math.round(s / 60) + 'm' : (s / 3600).toFixed(1) + 'h';
try { ${'$'}('tok').value = sessionStorage.getItem('mc.admin') || ''; } catch (e) {}
async function get(path) {
  const r = await fetch(path, { headers: { 'X-Admin-Token': ${'$'}('tok').value } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error((j.errors && j.errors[0]) || r.status);
  return j.data;
}
const table = (head, rows) => '<table><tr>' + head.map(h => '<th>' + esc(h) + '</th>').join('') + '</tr>' + rows.map(r => '<tr>' + r.map(c => '<td>' + c + '</td>').join('') + '</tr>').join('') + '</table>';
const map = m => Object.entries(m || {}).map(([k, v]) => esc(k) + ': <b>' + v + '</b>').join(' · ') || '—';
const spread = (n, s) => s ? [esc(n), s.p10, s.p50, s.p90, s.max] : [esc(n), '—', '—', '—', '—'];
async function load() {
  try { sessionStorage.setItem('mc.admin', ${'$'}('tok').value); } catch (e) {}
  const days = ${'$'}('days').value; ${'$'}('msg').textContent = 'loading…';
  try {
    const [f, b, m] = await Promise.all([get('/admin/funnel?days=' + days), get('/admin/balance?days=' + Math.min(days, 90)), get('/admin/metrics?days=' + days)]);
    ${'$'}('funnel').innerHTML = '<p class="mut">' + esc(f.from) + ' – ' + esc(f.to) + ' · new clubs <b>' + f.cohort + '</b> · finished <b>' + f.finished + '</b> · closed the coach <b>' + f.skipped + '</b></p>' +
      table(['step', 'players', 'of cohort', 'from previous', 'median time', 'later', 'closed here', ''], f.steps.map(s =>
        [esc(s.step), s.users, pct(s.share), pct(s.fromPrevious), dur(s.medianSeconds), s.putOff || '', s.skippedHere || '', '<div class="bar" style="width:' + Math.round((s.share || 0) * 240) + 'px"></div>']));
    ${'$'}('kpi').innerHTML = '<div class="kpi">' + Object.entries(m.kpis || {}).map(([k, v]) => '<div><span class="mut">' + esc(k) + '</span><b>' + (k === 'kFactor' ? (v.value == null ? '—' : v.value.toFixed(2)) : pct(v.value)) + '</b></div>').join('') +
      '<div><span class="mut">DAU / WAU / MAU</span><b>' + m.dau + ' / ' + m.wau + ' / ' + m.mau + '</b></div></div>';
    const L = b.live;
    ${'$'}('live').innerHTML = '<div class="kpi"><div><span class="mut">kickoffs</span><b>' + L.kickoffs + '</b></div><div><span class="mut">finished</span><b>' + pct(L.finishRate) + '</b></div><div><span class="mut">vs bots</span><b>' + L.vsBots + '</b></div></div>' +
      '<p>results: ' + map(L.outcomes) + '</p><p>controls: ' + map(L.controls) + '</p>';
    ${'$'}('eco').innerHTML = '<p class="mut">' + b.activeClubs + ' clubs active ' + esc(b.from) + ' – ' + esc(b.to) + '</p>' +
      table(['', 'p10', 'median', 'p90', 'max'], [spread('coins', b.coins), spread('gold', b.gold), spread('trophies', b.trophies), spread('club level', b.clubLevel)]) +
      '<p>stadium levels: ' + map(b.stadium) + '</p><p>stadium level-ups: ' + map(b.stadiumUps) + '</p>';
    ${'$'}('money').innerHTML = '<p>purchases: ' + map(b.purchases) + '</p><p>checkout opened: ' + map(b.purchaseStarts) + '</p><p>rewarded ads: <b>' + b.adRewards + '</b></p>';
    ${'$'}('spots').innerHTML = table(['spot', 'clubs'], Object.entries(b.spots).map(([s, n]) => [esc(s), n]));
    ${'$'}('msg').textContent = 'updated ' + new Date().toLocaleTimeString();
  } catch (e) { ${'$'}('msg').innerHTML = '<span class="err">' + esc(e.message) + '</span>'; }
}
${'$'}('go').onclick = load;
if (${'$'}('tok').value) load();
</script></body></html>
""".trimIndent()
}
