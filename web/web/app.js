"use strict";
// Kijk – webversie. Zelfde ontwerp en bediening als de Android TV-app.

// ============================================================ hulpjes
const MIN = 60000, HOUR = 60 * MIN, DAY = 24 * HOUR;
const pad = n => String(n).padStart(2, '0');
const hm = t => { const d = new Date(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()) };
const startOfDay = t => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime() };
const clock = ms => { ms = Math.max(0, ms || 0); const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return h ? `${h}:${pad(m)}:${pad(x)}` : `${m}:${pad(x)}` };
const minsLeft = (stop, now) => { const m = Math.max(0, Math.round((stop - now) / MIN)); return m >= 60 ? `nog ${Math.floor(m / 60)} u ${m % 60} min` : `nog ${m} min` };
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const api = (p, opts) => fetch('/api/' + p, opts).then(r => r.json());
const post = (p, body) => api(p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
const sleep = ms => new Promise(r => setTimeout(r, ms));
function runtime(raw) {
  if (!raw) return null;
  const p = String(raw).split(':').map(x => parseInt(x, 10)).filter(x => !isNaN(x));
  const m = p.length >= 2 ? p[0] * 60 + p[1] : p[0];
  if (!m) return null;
  return m >= 60 ? `${Math.floor(m / 60)} u ${m % 60} min` : `${m} min`;
}
const COLORS = ['#E8562A', '#2E6FD8', '#3BA55D', '#C4161C', '#5B2A86', '#00548B', '#E4007C', '#0B3D91', '#F48C00', '#2C3E50'];
const colorFor = s => COLORS[[...String(s)].reduce((a, c) => a + c.charCodeAt(0), 0) % COLORS.length];
const gradFor = s => { const c = colorFor(s); return `linear-gradient(165deg,${c}99,#0f0f14)` };

// ============================================================ toestand
const S = {
  stack: [{ name: 'home' }], tab: 0, group: null, guideIdx: 0, movieCat: null, showCat: null,
  favs: new Set(), recent: [], recentCh: [], lastCh: null, subChoice: {}, pinHash: null, lockOn: true, unlocked: false, hidden: new Set(), hiddenM: new Set(), hiddenS: new Set(), needsT: {}, sub: { lang: 0, size: 1, bg: 0, pos: 0 }, offset: 0, progress: {}, lastEp: {},
};
try { const s = JSON.parse(localStorage.getItem('kijk') || 'null'); if (s) { S.favs = new Set(s.favs || []); Object.assign(S.sub, s.sub || {}); S.offset = s.offset || 0; S.progress = s.progress || {}; S.lastEp = s.lastEp || {}; S.tab = Math.min(s.tab || 0, 5); S.recent = s.recent || []; S.recentCh = s.recentCh || []; S.lastCh = s.lastCh || null; S.guideArch = !!s.guideArch; S.guideFav = !!s.guideFav; S.pinHash = s.pinHash || null; S.subChoice = s.subChoice || {}; S.lockOn = s.lockOn !== false; S.needsT = s.needsT || {}; S.hidden = new Set(s.hidden || []); S.hiddenM = new Set(s.hiddenM || []); S.hiddenS = new Set(s.hiddenS || []) } } catch (e) { }
function persist() { try { localStorage.setItem('kijk', JSON.stringify({ favs: [...S.favs], sub: S.sub, offset: S.offset, progress: S.progress, lastEp: S.lastEp, tab: S.tab, needsT: S.needsT, guideArch: S.guideArch, guideFav: S.guideFav, pinHash: S.pinHash, recent: S.recent, recentCh: S.recentCh, lastCh: S.lastCh, subChoice: S.subChoice, lockOn: S.lockOn, hidden: [...S.hidden], hiddenM: [...S.hiddenM], hiddenS: [...S.hiddenS] })) } catch (e) { } }
const SUB_LANG = ["Nederlands", "Engels", "Automatisch", "Uit"], SUB_SIZE = ["Klein", "Normaal", "Groot", "Extra groot"], SUB_BG = ["Omlijnd", "Zwarte balk", "Half doorzichtig"], SUB_POS = ["Laag", "Hoger"];
const TABS = ["Live", "Gids", "Films", "Series", "Zoeken", "Instellingen"];
const tv = document.getElementById('tv');
const top_ = () => S.stack[S.stack.length - 1];

let CH = [], ALLCH = [], GROUPS = [], ALLGROUPS = [], NN = {}, STATUS = {}, CONFIG = {};
const vodCache = {};
async function cached(key, fn) { if (!(key in vodCache)) vodCache[key] = await fn(); return vodCache[key] }

// ============================================================ focus-engine
let focusEl = null;
function setFocus(el, silent) {
  if (!el) return;
  if (focusEl && focusEl !== el) focusEl.classList.remove('focused');
  focusEl = el; el.classList.add('focused');
  if (el.tagName === 'INPUT') el.focus(); else if (document.activeElement && document.activeElement.tagName === 'INPUT') document.activeElement.blur();
  scrollToShow(el);
  if (!silent && el._onfocus) el._onfocus();
}
// Alleen de eigen lijst scrollen (scrollIntoView zou ook het hele scherm verschuiven).
function scrollToShow(el) {
  const box = el.parentElement && el.parentElement.closest('.scroll');
  if (box) {
    const b = box.getBoundingClientRect(), r = el.getBoundingClientRect(), sc = b.height / box.clientHeight || 1, m = 14 * sc;
    if (r.top < b.top + m) box.scrollTop -= (b.top + m - r.top) / sc;
    else if (r.bottom > b.bottom - m) box.scrollTop += (r.bottom - b.bottom + m) / sc;
  }
  tv.scrollTop = 0; tv.scrollLeft = 0;
}
function layer() { return tv.querySelector('[data-layer]:not(.off)') || tv }
function focusables(root) { return [...root.querySelectorAll('.f')].filter(e => e.offsetParent !== null && !e.closest('.off')) }
function restore(id, fallbackSel) {
  const root = layer();
  let el = id && root.querySelector(`.f[data-id="${CSS.escape(id)}"]`);
  if (!el && fallbackSel) el = root.querySelector(fallbackSel);
  if (!el) el = root.querySelector('.f.sel') || focusables(root)[0];
  setFocus(el, true);
}
function move(dir) {
  const root = layer(); const items = focusables(root);
  if (!focusEl || !root.contains(focusEl) || !focusEl.isConnected) { setFocus(items[0]); return }
  const r = focusEl.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  let best = null, bs = 1e9;
  for (const el of items) {
    if (el === focusEl) continue;
    const c = el.getBoundingClientRect(), ex = c.left + c.width / 2, ey = c.top + c.height / 2;
    let primary, ortho;
    const vGap = Math.max(0, Math.max(c.top - r.bottom, r.top - c.bottom)), hGap = Math.max(0, Math.max(c.left - r.right, r.left - c.right));
    if (dir === 'right') { if (c.left < r.right - 6) continue; primary = c.left - r.right; ortho = vGap || Math.abs(ey - cy) * 0.15 }
    if (dir === 'left') { if (c.right > r.left + 6) continue; primary = r.left - c.right; ortho = vGap || Math.abs(ey - cy) * 0.15 }
    if (dir === 'down') { if (c.top < r.bottom - 6) continue; primary = c.top - r.bottom; ortho = hGap || Math.abs(ex - cx) * 0.05 }
    if (dir === 'up') { if (c.bottom > r.top + 6) continue; primary = r.top - c.bottom; ortho = hGap || Math.abs(ex - cx) * 0.05 }
    const s = Math.max(0, primary) + ortho * 3;
    if (s < bs) { bs = s; best = el }
  }
  if (!best) return;
  const reg = best.closest('[data-region]'), cur = focusEl.closest('[data-region]');
  if (reg && reg !== cur) { const sel = reg.querySelector('.f.sel'); if (sel) best = sel }
  setFocus(best);
}

// ============================================================ lange lijsten in stukken
const CHUNK = 200;
function chunked(container, items, rowHtml, wire) {
  let shown = 0;
  const more = () => {
    const from = shown, end = Math.min(items.length, shown + CHUNK);
    if (from >= end) return;
    container.insertAdjacentHTML('beforeend', items.slice(from, end).map((it, k) => rowHtml(it, from + k)).join(''));
    const all = container.querySelectorAll(':scope > .f');
    shown = end;
    for (let j = from; j < end; j++) {
      const el = all[j]; if (!el) continue;
      wire(el, items[j], j);
      const o = el._onfocus;
      el._onfocus = () => { o && o(); if (j >= shown - 20) more() };
    }
  };
  more();
  // Ook bij scrollen met muis/touchpad bijladen, niet alleen bij bewegen met de pijltjes.
  const box = container.classList.contains('scroll') ? container : container.closest('.scroll');
  if (box) {
    const onScroll = () => { if (!container.isConnected) return box.removeEventListener('scroll', onScroll); if (box.scrollTop + box.clientHeight > box.scrollHeight - 400) more() };
    box.addEventListener('scroll', onScroll, { passive: true });
  }
}

// ============================================================ HTML-bouwstenen
function logoHtml(ch, w = 64, h = 40, fs = 12) {
  const txt = esc(ch.name.replace(/^[A-Z]{2,3}\s*[:|]\s*/, '').slice(0, 6).toUpperCase());
  return ch.logo
    ? `<div class="logo img" style="width:${w}px;height:${h}px"><img loading="lazy" src="${ch.logo}" onerror="this.parentNode.classList.remove('img');this.parentNode.style.background='${colorFor(ch.name)}';this.replaceWith(document.createTextNode('${txt}'))"></div>`
    : `<div class="logo" style="width:${w}px;height:${h}px;font-size:${fs}px;background:${colorFor(ch.name)}">${txt}</div>`;
}
function liveNow(ch, now = Date.now()) {
  const l = (NN[ch.key] || []).filter(p => p.stop > now);
  const cur = l[0] && l[0].start <= now ? l[0] : null;
  return { cur, nx: cur ? l[1] : l[0] };
}
function channelRow(ch, opts = {}) {
  const now = Date.now(), { cur, nx } = liveNow(ch, now);
  const fav = (S.favs.has(ch.key) ? ' <span class="star">★</span>' : '') + (ch.archive ? ` <span class="arch" title="Terugkijken: ${ch.archive} dagen">↺</span>` : '');
  return `<div class="ch f${opts.sel ? ' sel' : ''}" data-id="${opts.prefix || ''}${ch.key}">
  ${opts.compact ? '' : `<div class="num">${ch.number}</div>`}
  ${logoHtml(ch)}
  <div class="mid"><div class="name">${esc(ch.name)}${fav}</div>
   ${cur ? `<div class="now">${esc(cur.title)}</div><div class="prog"><div class="bar" style="width:${opts.compact ? 90 : 120}px"><i style="width:${(now - cur.start) / (cur.stop - cur.start) * 100}%"></i></div><span class="sec small">${minsLeft(cur.stop, now)}</span></div>`
      : `<div class="sec small">${ch.epg ? 'Nu geen programma-informatie' : 'Geen gidsinformatie'}</div>`}
  </div>
  ${!opts.compact && nx ? `<div class="next"><div class="sec small">Straks · ${hm(nx.start)}</div><div class="t2">${esc(nx.title)}</div></div>` : ''}
 </div>`;
}
function tabsBar() {
  return `<div class="top" data-region="tabs">${TABS.map((t, i) => `<div class="tab f${S.tab === i ? ' sel' : ''}" data-id="tab${i}" data-tab="${i}">${t}</div>`).join('')}<div class="clock" id="clk">${hm(Date.now())}</div></div>`;
}
const emptyHtml = (t, s) => `<div class="empty"><b>${t}</b><span>${s || ''}</span></div>`;

// ============================================================ schermen
let timers = [];
function stopTimers() { timers.forEach(t => { clearInterval(t); clearTimeout(t) }); timers = [] }
function render(keepId) {
  const id = keepId !== undefined ? keepId : (focusEl && focusEl.dataset.id);
  const t = top_();
  if (t.name !== 'live' && t.name !== 'vod') stopVideo();
  stopTimers();
  tv.scrollTop = 0; tv.scrollLeft = 0;
  ({ home: renderHome, live: renderLive, movie: renderMovie, show: renderShow, vod: renderVod, setup: renderSetup, groups: renderGroups, epglink: renderEpgLink, pin: renderPin, epgchans: renderEpgChans, confirm: renderConfirm, subsearch: renderSubSearch, osconfig: renderOsConfig, sendtv: renderSendTv })[t.name](t);
  restore(id);
}

function renderHome() {
  tv.innerHTML = `<div class="screen">${tabsBar()}<div class="status" id="status"></div><div class="body" id="body"></div></div>`;
  tv.querySelectorAll('.tab').forEach(el => { el._onfocus = () => { const i = +el.dataset.tab; if (S.tab !== i) { S.tab = i; persist(); tv.querySelectorAll('.tab').forEach(x => x.classList.toggle('sel', x === el)); renderTab() } }; el._ok = el._onfocus });
  renderTab(); showStatus();
}
function showStatus() {
  const st = document.getElementById('status'); if (!st) return;
  const p = STATUS.progress;
  if (p) {
    // Voortgangsbalk: met percentage als de server de grootte doorgeeft, anders een bewegende balk.
    const mb = p.done ? `${(p.done / 1048576).toFixed(1)} MB` : '';
    const pct = p.total ? Math.min(100, Math.round(p.done / p.total * 100)) : null;
    st.className = 'status';
    st.innerHTML = `<div class="pbarwrap"><span class="plabel">${esc(p.label)}…</span><div class="ptrack">${pct != null ? `<i style="width:${pct}%"></i>` : '<i class="indet"></i>'}</div><span class="pnum">${pct != null ? pct + ' %' : ''}${pct != null && mb ? ' · ' : ''}${mb}</span></div>`;
    return;
  }
  const msg = STATUS.playlistError || STATUS.playlist || STATUS.epg || '';
  st.textContent = msg; st.className = 'status' + (STATUS.playlistError ? ' err' : '');
}
function renderTab() {
  const body = document.getElementById('body');
  if (S.tab === 0) renderLiveTab(body);
  else if (S.tab === 1) renderGuide(body);
  else if (S.tab === 2 || S.tab === 3) renderVodTab(body, S.tab === 3);
  else if (S.tab === 4) renderSearch(body);
  else renderSettings(body);
}

// ---------------------------------------------------------------- Live
function liveList() { if (S.group && isLocked(S.group) && !S.unlocked) return []; return S.group === null ? CH : S.group === '★' ? CH.filter(c => S.favs.has(c.key)) : S.group === '↺' ? CH.filter(c => c.archive) : S.group === '🕘' ? (S.recentCh || []).map(k => CH.find(c => c.key === k)).filter(Boolean) : CH.filter(c => c.group === S.group) }
function renderLiveTab(body) {
  if (!CH.length) { body.innerHTML = emptyHtml(STATUS.playlist ? 'Zenders laden…' : 'Nog geen zenders', STATUS.playlistError || 'Controleer je playlist bij Instellingen.'); return }
  const all = [null, ...(S.favs.size ? ['★'] : []), ...((S.recentCh || []).length ? ['🕘'] : []), ...(CH.some(c => c.archive) ? ['↺'] : []), ...GROUPS];
  const lockedG = S.unlocked ? [] : GROUPS.filter(g => isLocked(g));
  if (!all.includes(S.group)) S.group = null;
  body.innerHTML = `<div class="col scroll" data-region="groups" style="width:230px"></div><div class="col scroll" data-region="list" id="chlist" style="flex:1"></div>`;
  const gcol = body.firstElementChild;
  chunked(gcol, all, g => `<div class="pill f${S.group === g ? ' sel' : ''}" data-id="g:${esc(g)}">${g === null ? 'Alle zenders' : g === '★' ? '★ Favorieten' : g === '↺' ? '<span class="arch">↺</span> Terugkijken' : g === '🕘' ? '🕘 Laatst bekeken' : (lockedG.includes(g) ? '🔒 ' : '') + esc(g)}</div>`,
    (el, g) => { el._onfocus = () => { if (S.group !== g) { S.group = g; gcol.querySelectorAll('.pill').forEach(x => x.classList.toggle('sel', x === el)); fillChannels() } }; el._ok = () => { if (lockedG.includes(g)) unlock(() => { S.group = g; render('g:' + g) }); else el._onfocus() } });
  fillChannels();
}
function fillChannels() {
  const list = liveList(), el = document.getElementById('chlist');
  el.innerHTML = ''; el.scrollTop = 0;
  if (S.group && isLocked(S.group) && !S.unlocked) { el.innerHTML = emptyHtml('🔒 Vergrendeld', 'Druk op OK op de groep om je pincode in te voeren.'); return }
  if (!list.length) { el.innerHTML = emptyHtml('Nog geen favorieten', 'Houd OK ingedrukt op een zender (of druk F) om hem hier te bewaren.'); return }
  chunked(el, list, c => channelRow(c), (r, ch, i) => { r._ok = () => openLive(list, i); r._long = () => toggleFav(ch) });
}
function toggleFav(ch) {
  const before = S.favs.size;
  S.favs.has(ch.key) ? S.favs.delete(ch.key) : S.favs.add(ch.key); persist();
  toast(S.favs.has(ch.key) ? `${ch.name} toegevoegd aan favorieten` : `${ch.name} verwijderd uit favorieten`);
  if (top_().name !== 'home') return;
  // Alleen het sterretje bijwerken, zodat je op dezelfde plek in de lijst/gids blijft.
  if (S.tab === 1) { if (S.guideFav) { renderGuide(document.getElementById('body')) } else drawGuide(); return }
  if (S.tab === 0) {
    const groupsChange = (before === 0) !== (S.favs.size === 0);
    if (S.group === '★' || groupsChange) return render();
    tv.querySelectorAll('#chlist .ch').forEach(r => {
      if (r.dataset.id !== ch.key) return;
      const n = r.querySelector('.name');
      if (n) n.innerHTML = esc(ch.name) + (S.favs.has(ch.key) ? ' <span class="star">★</span>' : '') + (ch.archive ? ` <span class="arch" title="Terugkijken: ${ch.archive} dagen">↺</span>` : '');
    });
  }
}

// ---------------------------------------------------------------- Gids: zie guide.js

// ---------------------------------------------------------------- Films & Series
async function renderVodTab(body, series) {
  if (!STATUS.hasVod) { body.innerHTML = emptyHtml(STATUS.playlist ? 'Laden…' : series ? 'Geen series gevonden' : 'Geen films gevonden', 'Je playlist bevat geen on-demand-aanbod. Met een Xtream-login krijg je films en series met posters en beschrijvingen.'); return }
  body.innerHTML = emptyHtml('Laden…');
  let cats;
  try { cats = await cached(series ? 'sc' : 'mc', () => api(series ? 'vod/show_cats' : 'vod/movie_cats')) } catch (e) { cats = [] }
  if (S.tab !== (series ? 3 : 2) || top_().name !== 'home' || !document.getElementById('body')) return;
  const hidV = series ? S.hiddenS : S.hiddenM;
  cats = cats.filter(c => !hidV.has(c.id));
  const lockedC = S.unlocked ? new Set() : new Set(cats.filter(c => isLocked(c.name)).map(c => c.id));
  if (!cats.length) { body.innerHTML = emptyHtml('Niets om te tonen', 'Alle categorieën zijn verborgen. Kies er een paar bij Instellingen.'); return }
  const key = series ? 'showCat' : 'movieCat';
  const cont = continueItems(series); if (cont.length) cats = [{ id: '__cont', name: '▶ Verder kijken' }, ...cats];
  if (!cats.some(c => c.id === S[key])) S[key] = cats[0].id;
  body.innerHTML = `<div class="col scroll" data-region="vcats" style="width:230px"></div><div class="scroll" data-region="vgrid" id="vgridwrap" style="flex:1;overflow-y:auto"><div class="grid" id="vgrid"></div></div>`;
  const col = body.firstElementChild;
  chunked(col, cats, c => `<div class="pill f${c.id === S[key] ? ' sel' : ''}" data-id="vc:${esc(c.id)}">${lockedC.has(c.id) ? '🔒 ' : ''}${esc(c.name)}</div>`, (el, c) => {
    el._onfocus = () => { if (S[key] !== c.id) { S[key] = c.id; col.querySelectorAll('.pill').forEach(x => x.classList.toggle('sel', x === el)); fillGrid(series) } };
    el._ok = () => { if (lockedC.has(c.id)) unlock(() => render('vc:' + c.id)); else el._onfocus() };
  });
  const hadFocus = focusEl && focusEl.dataset.id; if (hadFocus && !focusEl.isConnected) restore(hadFocus);
  fillGrid(series);
}
let gridToken = 0;
async function fillGrid(series) {
  const tok = ++gridToken, catId = series ? S.showCat : S.movieCat, g = document.getElementById('vgrid');
  const catName = ((vodCache[series ? 'sc' : 'mc'] || []).find(c => c.id === catId) || {}).name;
  if (isLocked(catName) && !S.unlocked) { g.innerHTML = `<div style="grid-column:1/-1">${emptyHtml('🔒 Vergrendeld', 'Druk op OK op de categorie om je pincode in te voeren.')}</div>`; return }
  g.innerHTML = `<div class="sec" style="grid-column:1/-1">Laden…</div>`;
  await sleep(220); if (tok !== gridToken) return;
  let items = [];
  if (catId === '__cont') items = continueItems(series);
  else try { items = await cached((series ? 's:' : 'm:') + catId, () => api(`vod/${series ? 'shows' : 'movies'}?cat=${encodeURIComponent(catId)}`)) } catch (e) { }
  if (tok !== gridToken || !document.getElementById('vgrid')) return;
  g.innerHTML = items.length ? '' : `<div class="sec" style="grid-column:1/-1">Deze categorie is leeg.</div>`;
  chunked(g, items, it => {
    const pr = !series && S.progress['m:' + it.id]; const frac = pr && pr.pos > 30000 && pr.pos < pr.dur * .95 ? pr.pos / pr.dur : 0;
    return `<div class="pc f" data-id="${series ? 's' : 'm'}${esc(it.id)}"><div class="poster" style="background:${gradFor(it.name)}"><span>${esc(it.name)}</span>${it.poster ? `<img loading="lazy" src="${it.poster}" onerror="this.remove()">` : ''}${frac ? `<div class="pbar"><i style="width:${frac * 100}%"></i></div>` : ''}</div><div class="pt">${esc(it.name)}</div></div>`;
  }, (el, it) => { el._ok = () => push(series ? { name: 'show', show: it } : { name: 'movie', movie: it }) });
}

// ---------------------------------------------------------------- Instellingen
function renderSettings(body) {
  const off = S.offset === 0 ? 'Geen' : (S.offset > 0 ? '+' : '') + (S.offset / 60).toString().replace('.', ',') + ' uur';
  const upd = STATUS.epgUpdated ? ` · bijgewerkt ${new Date(STATUS.epgUpdated).toLocaleString('nl-NL', { weekday: 'short', hour: '2-digit', minute: '2-digit' })}` : '';
  const rows = [
    ['sect', 'Bron'],
    ['Playlist en gids-link', shorten(CONFIG.playlist_url), () => push({ name: 'setup' })],
    ['Zendergroepen kiezen', `${ALLGROUPS.filter(g => !S.hidden.has(g)).length} van ${ALLGROUPS.length} groepen zichtbaar · ${CH.length} van ${ALLCH.length} zenders`, () => push({ name: 'groups', kind: 'live' })],
    ...(STATUS.hasVod ? [['Filmcategorieën kiezen', S.hiddenM.size ? `${S.hiddenM.size} verborgen` : 'Alles zichtbaar', () => push({ name: 'groups', kind: 'movie' })],
      ['Seriecategorieën kiezen', S.hiddenS.size ? `${S.hiddenS.size} verborgen` : 'Alles zichtbaar', () => push({ name: 'groups', kind: 'show' })]] : []),
    ['Playlist vernieuwen', REFRESH_P[CONFIG.refresh_playlist || 'daily'] + '  ·  OK om te wisselen', () => cycleRefresh('refresh_playlist', REFRESH_P)],
    ['Playlist nu vernieuwen', `${ALLCH.length} zenders${CONFIG.playlist_updated ? ' · laatst ' + whenText(CONFIG.playlist_updated) : ''}`, async () => { await post('reload'); toast('Playlist wordt vernieuwd…'); setTimeout(async () => { CONFIG = await api('config') }, 4000); pollSoon() }],
    ['sect', 'Gids'],
    ['Gids vernieuwen', REFRESH_E[CONFIG.refresh_epg || 'twice'] + '  ·  OK om te wisselen', () => cycleRefresh('refresh_epg', REFRESH_E)],
    [STATUS.epgBusy ? 'Gids wordt bijgewerkt…' : 'Gids nu bijwerken', STATUS.epg || (STATUS.programmes ? `${STATUS.programmes} programma's · ${STATUS.matched} van ${CH.length} zenders gekoppeld${upd}` : 'Nog geen gidsgegevens'), async () => { await post('epg/refresh'); toast('Gids wordt bijgewerkt…'); pollSoon() }],
    ['Gids koppelen per zender', `${CH.filter(c => c.epgCid).length} van ${CH.length} zenders hebben een gids${CH.some(c => c.linked) ? ' · ' + CH.filter(c => c.linked).length + ' zelf gekoppeld' : ''} · OK om te controleren of te wijzigen`, () => push({ name: 'epgchans' })],
    ['Tijdcorrectie gids', off + '  ·  gebruik dit als de gids verschoven is', async () => { S.offset = S.offset >= 180 ? -180 : S.offset + 30; persist(); guideReset(); await loadNowNext(); render() }],
    ['sect', 'Kijkgeschiedenis'],
    ['Films wissen', `${histCount('movie')} films met kijkvoortgang`, () => clearHistory('movie')],
    ['Series wissen', `${histCount('show')} series en afleveringen`, () => clearHistory('show')],
    ['Live wissen', 'Vorige zender en laatst bekeken zenders', () => clearHistory('live')],
    ['Alles wissen', 'Films, series en live. Favorieten, instellingen en gidskoppelingen blijven staan.', () => clearHistory('all')],
    ['sect', 'Kinderslot'],
    ['Kinderslot', S.lockOn ? `Aan · ${ALLGROUPS.filter(g => ADULT.test(g)).length} zendergroepen en de categorieën voor volwassenen vergrendeld${S.unlocked ? ' · nu ontgrendeld (OK = opnieuw vergrendelen)' : ''}` : 'Uit', async () => {
      if (S.lockOn && S.unlocked) { S.unlocked = false; applyHidden(); toast('Weer vergrendeld'); return render() }
      if (S.lockOn) { const c = await askPin('Kinderslot uitzetten', 'Voer je pincode in.', pinOk); if (c == null) return; S.lockOn = false }
      else { S.lockOn = true; S.unlocked = false }
      persist(); applyHidden(); render();
    }],
    ['Pincode wijzigen', S.pinHash ? 'Eigen pincode ingesteld' : 'Standaard: 0000', () => changePin()],
    ['sect', 'Geluid'],
    ['Geluid omzetten (ffmpeg)', CONFIG.ffmpeg ? 'Aan · Dolby/AC3-geluid van live zenders wordt omgezet zodat de browser het kan afspelen' : 'Niet gevonden · zenders met Dolby/AC3-geluid blijven stil. Zie LEESMIJ.txt', () => { }],
    ['sect', 'Ondertitels'],
    ['OpenSubtitles-account', CONFIG.os_key_set ? `Ingesteld${CONFIG.os_user ? ' (' + CONFIG.os_user + ')' : ''} · OK om te wijzigen of te testen` : 'Niet ingesteld · voor ondertitels bij films en series', () => push({ name: 'osconfig' })],
    ['Automatisch ondertitels zoeken', CONFIG.auto_subs === false ? 'Uit · alleen via ▲ › Ondertitels zoeken' : 'Aan · bij films en series zonder eigen ondertitels', async () => { await post('subs/config', { auto_subs: CONFIG.auto_subs === false }); CONFIG = await api('config'); render() }],
    ['Standaard ondertitels', SUB_LANG[S.sub.lang], () => { S.sub.lang = (S.sub.lang + 1) % 4; persist(); render() }],
    ['Tekstgrootte', SUB_SIZE[S.sub.size], () => { S.sub.size = (S.sub.size + 1) % 4; persist(); applyCueStyle(); render() }],
    ['Stijl', SUB_BG[S.sub.bg], () => { S.sub.bg = (S.sub.bg + 1) % 3; persist(); applyCueStyle(); render() }],
    ['Positie', SUB_POS[S.sub.pos], () => { S.sub.pos = (S.sub.pos + 1) % 2; persist(); render() }],
    ['sect', 'Android TV'],
    ['Instellingen naar tv sturen', 'Playlist, gids-links, favorieten, verborgen groepen, pincode, ondertitels en OpenSubtitles naar de Kijk-app op je tv', () => push({ name: 'sendtv' })],
    ['sect', 'Over'],
    ['Kijk', 'Webversie · draait op je eigen computer', () => { }],
  ];
  body.innerHTML = `<div class="col scroll" data-region="settings" style="flex:1">${rows.map((r, i) => r[0] === 'sect' ? `<div class="sect">${r[1]}</div>` : `<div class="srow f" data-id="set${i}"><b>${r[0]}</b><span>${esc(r[1])}</span></div>`).join('')}</div>
 <div style="width:290px;padding-top:30px"><div style="font-size:17px;font-weight:600">Tips</div><div class="sec" style="font-size:15px;line-height:1.5;margin-top:8px">• Druk op OK op een instelling om de waarde te wisselen.<br>• Houd OK ingedrukt op een zender voor favorieten.<br>• In beeld: ▲▼ zappen, ◀ vorige zender, ▶ ondertitels en audio.<br>• OK in beeld = infobalk; daarin ▲▼ andere zenders bekijken en ◀▶ programma's, OK = overschakelen. G = gids.<br>• Cijfers typen = naar dat zendernummer. N = gids terug naar nu.<br>• De gids toont de groep die je bij Live hebt gekozen.<br>• ↺ = terugkijken mogelijk. In de gids toont de knop "↺ Alleen terugkijken" alleen die zenders.<br>• Klopt de gids van een zender niet? Instellingen › Gids koppelen per zender.<br>• In de gids filter je met ★ Alleen favorieten en ↺ Alleen terugkijken.<br>• DVB-ondertitels kan een browser niet tonen; die werken wel in de Android-app.</div></div>`;
  body.querySelectorAll('.srow').forEach(el => { el._ok = rows[+el.dataset.id.slice(3)][2] });
}
const REFRESH_P = { start: 'Bij elke start', daily: '1× per dag', weekly: '1× per week', manual: 'Alleen handmatig' };
const REFRESH_E = { start: 'Bij elke start', twice: '2× per dag', daily: '1× per dag', manual: 'Alleen handmatig' };
async function cycleRefresh(key, opts) {
  const ks = Object.keys(opts), cur = CONFIG[key] || ks[1];
  const next = ks[(ks.indexOf(cur) + 1) % ks.length];
  await post('settings', { [key]: next }); CONFIG[key] = next; render();
}
function whenText(t) {
  const d = new Date(t), today = startOfDay(Date.now());
  const day = t >= today ? 'vandaag' : t >= today - DAY ? 'gisteren' : d.toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' });
  return day + ' ' + hm(t);
}
function shorten(u) { if (!u) return 'Niet ingesteld'; u = u.replace(/(password|username)=[^&]*/g, '$1=•••').replace(/^https?:\/\//, ''); return u.length > 60 ? u.slice(0, 57) + '…' : u }

// ---------------------------------------------------------------- Kinderslot (pincode)
// Groepen en categorieën voor volwassenen zijn vergrendeld met een pincode (standaard 0000).
const ADULT = /(adult|xxx|18\s*\+|\+\s*18|porn|erotic|erotiek|volwassen)/i;
const isLocked = name => S.lockOn && ADULT.test(name || '');
async function pinHash(code) {
  const data = new TextEncoder().encode('kijk:' + code);
  try { const h = await crypto.subtle.digest('SHA-256', data); return [...new Uint8Array(h)].map(b => b.toString(16).padStart(2, '0')).join('') }
  catch (e) { return 'p' + [...code].reverse().join('') }
}
async function pinOk(code) { return S.pinHash ? (await pinHash(code)) === S.pinHash : code === '0000' }
// askPin(titel, uitleg, check) → Promise met de ingevoerde code (check bepaalt of hij goed is)
function askPin(title, sub, check) {
  return new Promise(resolve => push({ name: 'pin', title, sub, check, resolve, code: '' }));
}
async function unlock(then) {
  if (S.unlocked) return then && then();
  const code = await askPin('Pincode', 'Deze inhoud is vergrendeld. Voer je pincode in.', pinOk);
  if (code == null) return;
  S.unlocked = true; applyHidden(); toast('Ontgrendeld tot je de pagina ververst');
  then && then();
}
function renderPin(t) {
  const dots = [0, 1, 2, 3].map(i => `<div class="pindot${i < t.code.length ? ' on' : ''}"></div>`).join('');
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✕'];
  tv.innerHTML = `<div class="screen" style="align-items:center;justify-content:center">
   <div style="font-size:30px;font-weight:700">🔒 ${esc(t.title)}</div>
   <div class="sec" style="font-size:15px;margin:6px 0 18px">${esc(t.sub || '')}</div>
   <div style="display:flex;gap:16px;margin-bottom:8px">${dots}</div>
   <div class="err" style="height:22px;font-size:15px">${esc(t.err || '')}</div>
   <div style="display:grid;grid-template-columns:repeat(3,64px);gap:10px;margin-top:6px" data-region="pad">${keys.map(k => `<div class="pinkey f${k === '5' ? ' sel' : ''}" data-id="pk${k}">${k}</div>`).join('')}</div>
   <div class="sec" style="font-size:13px;margin-top:14px">Cijfertoetsen werken ook · Terug = annuleren</div></div>`;
  tv.querySelectorAll('.pinkey').forEach(el => { const k = el.dataset.id.slice(2); el._ok = () => pinKey(t, k === '✕' ? 'cancel' : k === '⌫' ? 'del' : k) });
  t.onBack = () => pinKey(t, 'cancel');
}
async function pinKey(t, k) {
  if (k === 'cancel') { S.stack.pop(); render(top_().focusId); return t.resolve(null) }
  if (k === 'del') t.code = t.code.slice(0, -1);
  else if (/^[0-9]$/.test(k) && t.code.length < 4) t.code += k;
  t.err = '';
  if (t.code.length === 4) {
    const code = t.code;
    if (!t.check || await t.check(code)) { S.stack.pop(); render(top_().focusId); return t.resolve(code) }
    t.code = ''; t.err = 'Onjuiste code, probeer opnieuw';
  }
  render(focusEl && focusEl.dataset.id);
}
async function changePin() {
  const old = await askPin('Huidige pincode', S.pinHash ? 'Voer je huidige pincode in.' : 'Voer de huidige code in (standaard 0000).', pinOk); if (old == null) return;
  const a = await askPin('Nieuwe pincode', 'Kies een nieuwe code van 4 cijfers.'); if (a == null) return;
  const b = await askPin('Herhaal nieuwe pincode', 'Voer dezelfde code nog een keer in.', async c => c === a); if (b == null) return;
  S.pinHash = await pinHash(a); persist(); toast('Pincode gewijzigd'); render();
}

// ---------------------------------------------------------------- kijkgeschiedenis
function histCount(kind) {
  if (kind === 'movie') return Object.keys(S.progress).filter(k => k.startsWith('m:')).length;
  return Object.keys(S.lastEp).length + Object.keys(S.progress).filter(k => !k.startsWith('m:') && !k.startsWith('cu:')).length;
}
function askConfirm(title, text, yes) {
  return new Promise(resolve => push({ name: 'confirm', title, text, yes, resolve }));
}
function renderConfirm(t) {
  tv.innerHTML = `<div class="screen" style="align-items:center;justify-content:center;text-align:center">
   <div style="font-size:26px;font-weight:700;margin-bottom:8px">${esc(t.title)}</div>
   <div class="sec" style="font-size:15px;max-width:560px;margin-bottom:22px">${esc(t.text)}</div>
   <div data-region="cbtn"><span class="btn f" data-id="cyes" style="background:rgba(255,69,58,.35)">${esc(t.yes || 'Wissen')}</span><span class="btn f sel" data-id="cno">Annuleren</span></div></div>`;
  const done = v => { S.stack.pop(); render(top_().focusId); t.resolve(v) };
  tv.querySelector('[data-id=cyes]')._ok = () => done(true);
  tv.querySelector('[data-id=cno]')._ok = () => done(false);
  t.onBack = () => done(false);
}
async function clearHistory(kind) {
  const what = { movie: 'de kijkgeschiedenis van films', show: 'de kijkgeschiedenis van series', live: 'de kijkgeschiedenis van live tv', all: 'alle kijkgeschiedenis' }[kind];
  const ok = await askConfirm('Kijkgeschiedenis wissen', `Weet je zeker dat je ${what} wilt wissen? "Verder kijken" en waar je gebleven was, worden dan vergeten.`);
  if (!ok) return;
  if (kind === 'movie' || kind === 'all') {
    Object.keys(S.progress).filter(k => k.startsWith('m:')).forEach(k => delete S.progress[k]);
    S.recent = S.recent.filter(r => r.kind !== 'movie');
  }
  if (kind === 'show' || kind === 'all') {
    Object.keys(S.progress).filter(k => !k.startsWith('m:')).forEach(k => delete S.progress[k]);
    S.lastEp = {}; S.recent = S.recent.filter(r => r.kind !== 'show');
  }
  if (kind === 'live' || kind === 'all') { S.recentCh = []; S.lastCh = null }
  if (kind === 'all') { S.subChoice = {}; S.recent = [] }
  persist(); toast('Kijkgeschiedenis gewist'); render();
}

// ---------------------------------------------------------------- Groepen/categorieën kiezen
const PICK = {
  live: { title: 'Zendergroepen', set: () => S.hidden, what: 'groepen', unit: 'zenders', note: 'Verborgen groepen tellen nergens mee: niet in Live, niet in de gids en niet bij het zappen.' },
  movie: { title: 'Filmcategorieën', set: () => S.hiddenM, what: 'categorieën', unit: '', note: 'Verborgen categorieën verschijnen niet bij Films.' },
  show: { title: 'Seriecategorieën', set: () => S.hiddenS, what: 'categorieën', unit: '', note: 'Verborgen categorieën verschijnen niet bij Series.' },
};
async function pickItems(kind) {
  if (kind === 'live') { const counts = {}; ALLCH.forEach(c => counts[c.group] = (counts[c.group] || 0) + 1); return ALLGROUPS.map(g => ({ id: g, name: g, count: counts[g] })) }
  const cats = await cached(kind === 'show' ? 'sc' : 'mc', () => api(kind === 'show' ? 'vod/show_cats' : 'vod/movie_cats'));
  return (cats || []).map(c => ({ id: c.id, name: c.name }));
}
function renderGroups(t) {
  const P = PICK[t.kind || 'live'], hid = P.set();
  if (!t.items) { tv.innerHTML = emptyHtml('Laden…'); pickItems(t.kind || 'live').then(x => { t.items = x; if (top_() === t) render() }).catch(() => { t.items = [] }); return }
  const items = t.items, shown = items.filter(i => !hid.has(i.id)).length;
  tv.innerHTML = `<div class="screen"><h1 style="margin:0 0 4px">${P.title}</h1>
   <div class="sec" style="font-size:15px;margin:0 4px 12px">Kies wat je wilt zien. Tip: eerst alles verbergen, dan zoeken (bijv. "NL") en die tonen. ${P.note} <span id="pcount">${shown} van ${items.length} ${P.what} zichtbaar</span>.</div>
   <div style="margin:0 0 10px;display:flex;gap:12px;align-items:center" data-region="gbtn"><div class="field" style="margin:0;width:300px"><input class="f" data-id="gsearch" placeholder="Zoeken, bijv. NL of Sport" value="${esc(t.q || '')}" autocomplete="off" spellcheck="false"></div><span class="btn f" data-id="gall" style="margin:0">Alles tonen</span><span class="btn f" data-id="gnone" style="margin:0">Alles verbergen</span><span class="btn f sel" data-id="gdone" style="margin:0">Klaar</span></div>
   <div class="scroll" data-region="glist" style="flex:1;min-height:0;overflow-y:auto;padding:6px"><div id="glist" style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-auto-rows:42px;gap:6px"></div></div></div>`;
  const list = document.getElementById('glist');
  const matches = () => { const q = (t.q || '').trim().toLowerCase(); return q ? items.filter(i => i.name.toLowerCase().includes(q)) : items };
  const count = () => { const n = items.filter(i => !hid.has(i.id)).length; document.getElementById('pcount').textContent = `${n} van ${items.length} ${P.what} zichtbaar` };
  const fill = () => { list.innerHTML = ''; list.parentElement.scrollTop = 0; chunked(list, matches(), i => `<div class="pill f" data-id="grp:${esc(i.id)}" style="display:flex;align-items:center;gap:8px;min-width:0"><span style="width:18px;color:#0A84FF;font-weight:800">${hid.has(i.id) ? '' : '✓'}</span><span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(i.name)}</span>${i.count ? `<span class="sec small">${i.count}</span>` : ''}</div>`,
    (el, i) => { el._ok = () => { hid.has(i.id) ? hid.delete(i.id) : hid.add(i.id); el.firstElementChild.textContent = hid.has(i.id) ? '' : '✓'; count() } }) };
  fill();
  const si = tv.querySelector('[data-id=gsearch]');
  si.addEventListener('input', () => { t.q = si.value; fill() });
  si._ok = () => move('down');
  const done = () => { persist(); if ((t.kind || 'live') === 'live') { applyHidden(); S.group = null } pop() };
  // Met een zoekterm gelden de knoppen alleen voor de gevonden groepen.
  tv.querySelector('[data-id=gall]')._ok = () => { matches().forEach(i => hid.delete(i.id)); render() };
  tv.querySelector('[data-id=gnone]')._ok = () => { matches().forEach(i => hid.add(i.id)); render() };
  tv.querySelector('[data-id=gdone]')._ok = done;
  t.onBack = done;
}

// ---------------------------------------------------------------- Instelscherm
function renderSetup(t) {
  if (t.xtream == null) {
    t.xtream = !!CONFIG.xtream; t.m3u = CONFIG.xtream ? '' : CONFIG.playlist_url || ''; t.epg = CONFIG.epg_url || '';
    t.server = t.user = t.pass = '';
    if (CONFIG.xtream) { try { const u = new URL(CONFIG.playlist_url); t.server = u.origin; t.user = u.searchParams.get('username') || ''; t.pass = u.searchParams.get('password') || '' } catch (e) { } }
  }
  const field = (id, label, val, ph, type = 'text') => `<div class="field"><label>${label}</label><input class="f" data-id="${id}" type="${type}" value="${esc(val)}" placeholder="${esc(ph)}" autocomplete="off" spellcheck="false"></div>`;
  tv.innerHTML = `<div class="screen scroll" style="align-items:center;overflow-y:auto"><div style="width:640px;padding-top:10px">
   <h1 style="margin:0 0 6px">${CONFIG.playlist_url ? 'Playlist en gids' : 'Welkom bij Kijk'}</h1>
   <div class="sec" style="font-size:15px;margin-bottom:18px">Voeg je playlist toe. Je kunt dit later wijzigen bij Instellingen.</div>
   <div style="margin-bottom:16px" data-region="mode"><span class="mode f${!t.xtream ? ' sel' : ''}" data-id="m3u">M3U-link</span><span class="mode f${t.xtream ? ' sel' : ''}" data-id="xt">Xtream-login</span></div>
   ${t.xtream ? field('server', 'Server', t.server, 'http://voorbeeld.tv:8080') + field('user', 'Gebruikersnaam', t.user, '') + field('pass', 'Wachtwoord', t.pass, '', 'password')
      : field('m3u', 'Playlist-link (M3U)', t.m3u, 'http://…/playlist.m3u')}
   ${field('epg', 'Gids-link (XMLTV, optioneel)', t.epg, t.xtream ? 'Leeg = gids van je Xtream-server' : 'Leeg = gids-link uit de playlist')}
   <div class="err" id="serr" style="font-size:15px;min-height:20px"></div>
   <div style="margin-top:10px"><span class="btn f" data-id="save">Opslaan en laden</span>${CONFIG.playlist_url ? '<span class="btn f" data-id="cancel">Annuleren</span>' : ''}</div>
  </div></div>`;
  const val = id => { const i = tv.querySelector(`input[data-id=${id}]`); return i ? i.value.trim() : '' };
  const keep = () => { ['m3u', 'server', 'user', 'pass', 'epg'].forEach(k => { const i = tv.querySelector(`input[data-id=${k}]`); if (i) t[k] = i.value }) };
  tv.querySelectorAll('input').forEach(i => i.addEventListener('input', keep));
  tv.querySelector('[data-id=m3u].mode')._ok = () => { keep(); t.xtream = false; render('m3u') };
  tv.querySelector('[data-id=xt]')._ok = () => { keep(); t.xtream = true; render('xt') };
  tv.querySelectorAll('input').forEach(i => { i._ok = () => move('down') });
  const c = tv.querySelector('[data-id=cancel]'); if (c) c._ok = () => pop();
  tv.querySelector('[data-id=save]')._ok = async () => {
    if (t.saving) return;
    let playlist, epg = val('epg');
    if (t.xtream) {
      let host = val('server'); if (!host || !val('user') || !val('pass')) { document.getElementById('serr').textContent = 'Vul server, gebruikersnaam en wachtwoord in.'; return }
      if (!/^https?:\/\//.test(host)) host = 'http://' + host; host = host.replace(/\/+$/, '');
      playlist = `${host}/get.php?username=${encodeURIComponent(val('user'))}&password=${encodeURIComponent(val('pass'))}&type=m3u_plus&output=ts`;
    } else {
      playlist = val('m3u'); if (!/^https?:\/\//.test(playlist)) { document.getElementById('serr').textContent = 'Een playlist-link begint met http:// of https://'; return }
    }
    t.saving = true; const sb = tv.querySelector('[data-id=save]'); sb.textContent = 'Bezig met laden…';
    await post('config', { playlist_url: playlist, epg_url: epg });
    CONFIG = await api('config'); CH = []; ALLCH = []; GROUPS = []; ALLGROUPS = []; NN = {}; for (const k in vodCache) delete vodCache[k];
    S.stack = [{ name: 'home' }]; S.tab = 0; render('tab0'); pollSoon();
  };
}

// ============================================================ video
const video = document.createElement('video');
video.className = 'vid'; video.playsInline = true; video.autoplay = true; video.crossOrigin = 'anonymous';
let engine = null, engineKind = null, playToken = 0;
function stopVideo() {
  playToken++;
  if (engine) { try { engineKind === 'hls' ? engine.destroy() : (engine.pause(), engine.unload(), engine.detachMediaElement(), engine.destroy()) } catch (e) { } }
  engine = null; engineKind = null;
  try { video.pause(); video.removeAttribute('src'); video.load() } catch (e) { }
}
function playUrl(src, { live, hls, onError }) {
  stopVideo();
  const tok = playToken;
  const fail = msg => { if (tok === playToken) onError && onError(msg) };
  const isHls = hls || (src.startsWith('/proxy') && /\.m3u8/i.test(decodeURIComponent(src)));
  if (isHls && window.Hls && Hls.isSupported()) {
    engine = new Hls({ enableWorker: true, renderTextTracksNatively: true, subtitlePreference: subPref() });
    engineKind = 'hls';
    engine.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) fail(d.details || 'HLS-fout') });
    engine.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => pickDefaultSubs());
    engine.loadSource(src); engine.attachMedia(video);
  } else if (live && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
    engine = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: location.origin + src }, { enableWorker: true, liveBufferLatencyChasing: false, enableStashBuffer: true, stashInitialSize: 384 * 1024 });
    engineKind = 'mpegts';
    engine.on(mpegts.Events.ERROR, (type, detail) => fail(detail || type));
    engine.attachMediaElement(video); engine.load();
  } else {
    video.src = src; engineKind = 'native';
  }
  video.onerror = () => fail(video.error ? 'Dit formaat kan de browser niet afspelen' : 'Fout');
  video.textTracks.onaddtrack = () => pickDefaultSubs();
  const p = video.play(); if (p) p.catch(() => { });
}
function subPref() { return S.sub.lang === 3 ? undefined : { lang: S.sub.lang === 1 ? 'en' : 'nl' } }
const langMatch = (l, want) => !!l && (l.toLowerCase().startsWith(want) || (want === 'nl' && /^(dut|nld)/i.test(l)) || (want === 'en' && /^eng/i.test(l)));
function pickDefaultSubs() {
  if (S.sub.lang === 3) { setSub(-1); return }
  const want = S.sub.lang === 1 ? 'en' : 'nl';
  const tracks = subTracks();
  let i = tracks.findIndex(t => langMatch(t.lang, want));
  if (i < 0 && S.sub.lang === 2 && tracks.length) i = 0;
  if (i >= 0) setSub(i);
}
function subTracks() {
  if (engineKind === 'hls' && engine && engine.subtitleTracks && engine.subtitleTracks.length)
    return engine.subtitleTracks.map((t, i) => ({ label: t.name || langName(t.lang) || `Spoor ${i + 1}`, lang: t.lang, idx: i, kind: 'hls', on: engine.subtitleTrack === i }));
  return [...video.textTracks].filter(t => t.kind === 'subtitles' || t.kind === 'captions').map((t, i) => ({ label: t.label || langName(t.language) || (t.kind === 'captions' ? 'Teletekst/CC' : `Spoor ${i + 1}`), lang: t.language, idx: i, kind: 'native', on: t.mode === 'showing' }));
}
function setSub(i) {
  if (engineKind === 'hls' && engine && engine.subtitleTracks && engine.subtitleTracks.length) { engine.subtitleTrack = i; engine.subtitleDisplay = i >= 0 }
  const native = [...video.textTracks].filter(t => t.kind === 'subtitles' || t.kind === 'captions');
  if (!(engineKind === 'hls' && engine && engine.subtitleTracks && engine.subtitleTracks.length)) native.forEach((t, j) => t.mode = j === i ? 'showing' : 'disabled');
  else if (i < 0) native.forEach(t => t.mode = 'disabled');
}
function audioTracks() {
  if (engineKind === 'hls' && engine && engine.audioTracks && engine.audioTracks.length > 1)
    return engine.audioTracks.map((t, i) => ({ label: t.name || langName(t.lang) || `Audio ${i + 1}`, idx: i, on: engine.audioTrack === i }));
  if (video.audioTracks && video.audioTracks.length > 1) return [...video.audioTracks].map((t, i) => ({ label: t.label || langName(t.language) || `Audio ${i + 1}`, idx: i, on: t.enabled }));
  return [];
}
function setAudio(i) {
  if (engineKind === 'hls' && engine && engine.audioTracks && engine.audioTracks.length > 1) engine.audioTrack = i;
  else if (video.audioTracks) [...video.audioTracks].forEach((t, j) => t.enabled = j === i);
}
function langName(code) { if (!code || code === 'und') return null; try { const n = new Intl.DisplayNames(['nl'], { type: 'language' }).of(code.slice(0, 2) === 'du' ? 'nl' : code); return n ? n[0].toUpperCase() + n.slice(1) : code } catch (e) { return code.toUpperCase() } }
function applyCueStyle() {
  const size = [2.6, 3.3, 4.1, 4.9][S.sub.size];
  const bg = S.sub.bg === 0 ? 'background:transparent;text-shadow:0 0 4px #000,0 0 4px #000,1px 1px 2px #000' : S.sub.bg === 1 ? 'background:rgba(0,0,0,.9)' : 'background:rgba(0,0,0,.55)';
  document.getElementById('cuestyle').textContent = `video.vid::cue{font-size:${size}vh;color:#fff;${bg}}`;
}
function positionCues(raised) {
  const line = raised ? -6 : (S.sub.pos ? -3 : -1);
  for (const t of video.textTracks) if (t.mode === 'showing' && t.activeCues) for (const c of t.activeCues) { try { if (c.line !== line) { c.snapToLines = true; c.line = line } } catch (e) { } }
}
video.addEventListener('timeupdate', () => positionCues(video._raised));
applyCueStyle();

function optionsHtml() {
  const subs = subTracks(), aud = audioTracks(), anyOn = subs.some(s => s.on);
  let h = `<h2>Ondertitels</h2><div class="opt f${!anyOn ? ' sel' : ''}" data-id="so-1"><span class="c">${!anyOn ? '✓' : ''}</span>Uit</div>`;
  h += subs.map(s => `<div class="opt f${s.on ? ' sel' : ''}" data-id="so${s.idx}"><span class="c">${s.on ? '✓' : ''}</span>${esc(s.label)}</div>`).join('');
  if (!subs.length) h += `<div class="sec" style="font-size:15px;padding:6px">Geen ondertitels die de browser kan tonen. DVB-ondertitels werken alleen in de Android-app.</div>`;
  const vt = top_(), vit = vt.name === 'vod' ? vt.items[vt.i] : null;
  if (vit && vit.subsQuery) {
    h += `<div class="opt f" data-id="osearch"><span class="c">🔍</span>Ondertitels zoeken (OpenSubtitles)</div>`;
    if (extTrack) { const o = (S.subChoice[vit.key] || {}).offset || 0; h += `<div class="opt f" data-id="oearl"><span class="c">−</span>Ondertitels eerder · nu ${fmtOff(o)}</div><div class="opt f" data-id="olate"><span class="c">+</span>Ondertitels later · nu ${fmtOff(o)}</div>` }
  }
  h += `<div class="opt f" data-id="os"><span class="c"></span>Grootte: ${SUB_SIZE[S.sub.size]}</div><div class="opt f" data-id="ob"><span class="c"></span>Stijl: ${SUB_BG[S.sub.bg]}</div><div class="opt f" data-id="op"><span class="c"></span>Positie: ${SUB_POS[S.sub.pos]}</div>`;
  if (aud.length) h += `<h2>Audio</h2>` + aud.map(a => `<div class="opt f${a.on ? ' sel' : ''}" data-id="ao${a.idx}"><span class="c">${a.on ? '✓' : ''}</span>${esc(a.label)}</div>`).join('');
  return h;
}
function wireOptions(panel) {
  panel.innerHTML = optionsHtml();
  panel.querySelectorAll('.opt').forEach(el => {
    const id = el.dataset.id;
    el._ok = () => {
      if (id.startsWith('so')) setSub(+id.slice(2));
      else if (id === 'os') { S.sub.size = (S.sub.size + 1) % 4; applyCueStyle() }
      else if (id === 'ob') { S.sub.bg = (S.sub.bg + 1) % 3; applyCueStyle() }
      else if (id === 'op') S.sub.pos = (S.sub.pos + 1) % 2;
      else if (id.startsWith('ao')) setAudio(+id.slice(2));
      else if (id === 'osearch') { const vt = top_(); return push({ name: 'subsearch', item: vt.items[vt.i], q: vt.items[vt.i].subsQuery.query, lang: S.sub.lang === 1 ? 'en' : 'nl' }) }
      else if (id === 'oearl' || id === 'olate') { const vt = top_(), k = vt.items[vt.i].key; const c = S.subChoice[k] || (S.subChoice[k] = {}); c.offset = Math.round(((c.offset || 0) + (id === 'olate' ? 0.5 : -0.5)) * 10) / 10; shiftExtSubs(c.offset) }
      persist(); setTimeout(() => { wireOptions(panel); restore(id) }, 60);
    };
  });
}

// ---------------------------------------------------------------- OpenSubtitles in de speler
let extTrack = null, extEl = null;
const fmtOff = o => (o > 0 ? '+' : '') + o.toFixed(1).replace('.', ',') + ' s';
function cleanTitle(n) {
  return String(n || '').replace(/^\W*[A-Z]{2,3}\s*[|:\-]\s*/, '').replace(/[\[(][^\])]*[\])]/g, ' ').replace(/\b(4K|UHD|FHD|HD|1080p|720p|MULTI.?SUB|NL.?SUB|DUBBED|NL|EN)\b/gi, ' ').replace(/\s+/g, ' ').trim();
}
function clearExtSubs() { if (extEl) { extEl.remove(); extEl = null } extTrack = null }
async function loadExtSubs(it, fileId, offset, announce) {
  const r = await fetch('/api/subs/get?file_id=' + fileId);
  if (!r.ok) { const e = await r.json().catch(() => ({})); toast(e.error || 'Ondertitel downloaden mislukt'); return false }
  const vtt = await r.text();
  if (top_().name !== 'vod' || top_().items[top_().i].key !== it.key) return false;
  clearExtSubs();
  extEl = document.createElement('track');
  extEl.kind = 'subtitles'; extEl.srclang = 'nl'; extEl.label = 'Nederlands (OpenSubtitles)';
  extEl.src = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }));
  video.appendChild(extEl);
  extTrack = extEl.track;
  [...video.textTracks].forEach(tt => tt.mode = tt === extTrack ? 'showing' : 'disabled');
  extEl.addEventListener('load', () => { const cues = [...(extTrack.cues || [])]; cues.forEach(c => { c._s = c.startTime; c._e = c.endTime }); shiftExtSubs(offset || 0) });
  S.subChoice[it.key] = { file_id: fileId, offset: offset || 0 }; persist();
  if (announce) toast('Ondertitels geladen van OpenSubtitles');
  return true;
}
function shiftExtSubs(off) {
  if (!extTrack || !extTrack.cues) return;
  for (const c of extTrack.cues) { if (c._s == null) { c._s = c.startTime; c._e = c.endTime } c.startTime = Math.max(0, c._s + off); c.endTime = Math.max(0, c._e + off) }
  persist();
}
async function autoSubs(t, it) {
  const prev = S.subChoice[it.key];
  if (prev && prev.file_id) return loadExtSubs(it, prev.file_id, prev.offset, false);  // eerder gekozen: gratis uit de opslag
  if (S.sub.lang === 3 || !CONFIG.os_key_set || CONFIG.auto_subs === false) return;
  await sleep(4000);  // eerst kijken of de film zelf ondertitels heeft
  if (top_() !== t || t.items[t.i].key !== it.key) return;
  if (subTracks().some(s => langMatch(s.lang, S.sub.lang === 1 ? 'en' : 'nl'))) return;
  let r; try { r = await api('subs/search?' + new URLSearchParams({ ...cleanQ(it.subsQuery), lang: S.sub.lang === 1 ? 'en' : 'nl' })) } catch (e) { return }
  const best = (r.results || []).find(x => !x.ai) || (r.results || [])[0];
  if (best) loadExtSubs(it, best.file_id, 0, true);
}
const cleanQ = q => Object.fromEntries(Object.entries(q || {}).filter(([k, v]) => v != null && v !== ''));
function renderSubSearch(t) {
  tv.innerHTML = `<div class="screen"><h1 style="margin:0 0 4px">Ondertitels zoeken</h1>
   <div class="sec" style="font-size:15px;margin:0 4px 12px">${esc(t.item.title)}${t.item.sub ? ' · ' + esc(t.item.sub) : ''} · bron: opensubtitles.com${t.remaining != null ? ` · nog ${t.remaining} downloads vandaag` : ''}</div>
   <div style="display:flex;gap:12px;align-items:center;margin-bottom:10px" data-region="stools"><div class="field" style="margin:0;width:340px"><input class="f" data-id="ssq" value="${esc(t.q || '')}" placeholder="Titel" autocomplete="off" spellcheck="false"></div>
   <span class="btn f" data-id="sslang" style="margin:0">Taal: ${t.lang === 'en' ? 'Engels' : 'Nederlands'}</span><span class="btn f sel" data-id="ssback" style="margin:0">Terug</span></div>
   <div class="col scroll" data-region="sres" id="sres" style="flex:1"><div class="sec" style="font-size:15px">Zoeken…</div></div></div>`;
  const box = document.getElementById('sres');
  const run = async useIds => {
    box.innerHTML = '<div class="sec" style="font-size:15px">Zoeken…</div>';
    const q = useIds ? cleanQ({ ...t.item.subsQuery, query: t.q }) : cleanQ({ query: t.q, season: t.item.subsQuery.season, episode: t.item.subsQuery.episode, year: t.item.subsQuery.year });
    let r; try { r = await api('subs/search?' + new URLSearchParams({ ...q, lang: t.lang })) } catch (e) { r = { error: 'Zoeken mislukt' } }
    if (top_() !== t) return;
    if (r.remaining != null) t.remaining = r.remaining;
    if (r.error) { box.innerHTML = `<div class="err" style="font-size:15px">${esc(r.error)}</div>`; return }
    if (!(r.results || []).length) { box.innerHTML = '<div class="sec" style="font-size:15px">Niets gevonden. Pas de titel aan (zonder jaartal of extra woorden) en druk op Enter.</div>'; return }
    box.innerHTML = '';
    chunked(box, r.results, x => `<div class="srow f" data-id="sr${x.file_id}"><b style="flex:2;font-size:15px">${esc(x.release || 'Ondertitel')}</b><span style="flex:1">${x.cached ? '✓ eerder gedownload · ' : ''}${x.ai ? 'automatisch vertaald · ' : ''}${x.hi ? 'voor doven · ' : ''}${x.downloads} keer gedownload</span></div>`,
      (el, x) => { el._ok = async () => { toast('Ondertitel downloaden…'); S.stack.pop(); render(null); await loadExtSubs(t.item, x.file_id, 0, true) } });
  };
  const si = tv.querySelector('[data-id=ssq]');
  si.addEventListener('input', () => { t.q = si.value });
  si._ok = () => run(false);
  tv.querySelector('[data-id=sslang]')._ok = () => { t.lang = t.lang === 'en' ? 'nl' : 'en'; render('sslang') };
  tv.querySelector('[data-id=ssback]')._ok = () => pop();
  run(true);
}
function renderSendTv(t) {
  const last = localStorage.getItem('kijkTv') || '';
  tv.innerHTML = `<div class="screen" style="align-items:center"><div style="width:640px;padding-top:10px">
   <h1 style="margin:0 0 6px">Naar tv sturen</h1>
   <div class="sec" style="font-size:15px;margin-bottom:16px">Open op de tv Kijk › Instellingen › Instellen via telefoon. Daar staan het adres en de code. Je playlist, gids-links en koppelingen, favorieten, verborgen groepen en categorieën, pincode, kinderslot, ondertitelstijl en OpenSubtitles-gegevens gaan dan naar de tv. Kijkgeschiedenis blijft hier.</div>
   <div class="field"><label>Adres van de tv</label><input class="f" data-id="tvaddr" value="${esc(last)}" placeholder="192.168.1.20:8080" autocomplete="off" spellcheck="false"></div>
   <div class="field"><label>Code (6 cijfers)</label><input class="f" data-id="tvcode" inputmode="numeric" autocomplete="off"></div>
   <div id="tvres" style="font-size:15px;min-height:22px;margin:4px 0 10px" class="sec">${esc(t.msg || '')}</div>
   <div><span class="btn f sel" data-id="tvsend">Versturen</span><span class="btn f" data-id="tvback">Terug</span></div></div></div>`;
  tv.querySelectorAll('input').forEach(i => { i._ok = () => move('down') });
  tv.querySelector('[data-id=tvback]')._ok = () => pop();
  tv.querySelector('[data-id=tvsend]')._ok = async () => {
    const v = id => tv.querySelector(`[data-id=${id}]`).value.trim();
    const address = v('tvaddr').replace(/^https?:\/\//, '').replace(/\/.*$/, ''), code = v('tvcode').replace(/\D/g, '');
    if (!address || code.length !== 6) { document.getElementById('tvres').textContent = 'Vul het adres en de code van 6 cijfers in.'; return }
    localStorage.setItem('kijkTv', address);
    document.getElementById('tvres').textContent = 'Versturen…';
    const client = { favs: [...S.favs], hidden: [...S.hidden], hiddenM: [...S.hiddenM], hiddenS: [...S.hiddenS], pinHash: S.pinHash || '', lockOn: S.lockOn, sub: S.sub, offset: S.offset };
    let r; try { r = await post('sendtv', { address, code, client }) } catch (e) { r = { ok: false, error: e.message } }
    t.msg = r && r.ok ? '✓ Verstuurd. De tv laadt de playlist en gids opnieuw.' : '✕ ' + ((r && r.error) || 'Mislukt');
    render('tvsend');
  };
}
function renderOsConfig(t) {
  tv.innerHTML = `<div class="screen" style="align-items:center"><div style="width:640px;padding-top:10px">
   <h1 style="margin:0 0 6px">OpenSubtitles</h1>
   <div class="sec" style="font-size:15px;margin-bottom:16px">Voor ondertitels bij films en series. Je gegevens blijven alleen op deze computer (in de map data). Een leeg wachtwoordveld laat het bewaarde wachtwoord staan.</div>
   <div class="field"><label>API-sleutel${CONFIG.os_key_hint ? ' · opgeslagen: ' + esc(CONFIG.os_key_hint) : ''}</label><input class="f" data-id="oskey" type="password" placeholder="${CONFIG.os_key_set ? 'ingesteld – leeg laten om te behouden' : 'je API-sleutel van opensubtitles.com'}" autocomplete="off"></div>
   <div class="field"><label>Gebruikersnaam</label><input class="f" data-id="osuser" value="${esc(CONFIG.os_user || '')}" autocomplete="off" spellcheck="false"></div>
   <div class="field"><label>Wachtwoord</label><input class="f" data-id="ospass" type="password" placeholder="${CONFIG.os_pass_set ? 'ingesteld – leeg laten om te behouden' : ''}" autocomplete="off"></div>
   <div id="osres" style="font-size:15px;min-height:22px;margin:4px 0 10px" class="sec">${esc(t.msg || '')}</div>
   <div><span class="btn f sel" data-id="ossave">Opslaan en testen</span><span class="btn f" data-id="osbrowser">Test via browser</span><span class="btn f" data-id="osback">Terug</span></div>
   <div id="osbres" class="sec" style="font-size:13px;margin-top:10px;white-space:pre-wrap">${esc(t.bmsg || '')}</div></div></div>`;
  tv.querySelectorAll('input').forEach(i => { i._ok = () => move('down') });
  tv.querySelector('[data-id=osback]')._ok = () => pop();
  // Dezelfde controle rechtstreeks vanuit de browser: laat zien of de blokkade bij Python zit.
  tv.querySelector('[data-id=osbrowser]')._ok = async () => {
    const key = tv.querySelector('[data-id=oskey]').value.trim();
    if (!key) { t.bmsg = 'Plak de API-sleutel in het veld hierboven en druk dan op Test via browser (die wordt niet opgeslagen).'; return render('osbrowser') }
    document.getElementById('osbres').textContent = 'Testen via browser…';
    try {
      const r = await fetch('https://api.opensubtitles.com/api/v1/infos/formats', { headers: { 'Api-Key': key, 'Accept': 'application/json' } });
      const body = (await r.text()).slice(0, 200);
      t.bmsg = `Browser: status ${r.status}${r.ok ? ' ✓ de sleutel werkt vanuit de browser' : ''}\n${body}`;
    } catch (e) { t.bmsg = 'Browser: verbinding geweigerd (' + e.message + '). OpenSubtitles staat directe toegang vanuit een webpagina waarschijnlijk niet toe; dat zegt niets over je sleutel.' }
    render('osbrowser');
  };
  tv.querySelector('[data-id=ossave]')._ok = async () => {
    const v = id => tv.querySelector(`[data-id=${id}]`).value.trim();
    const body = { os_user: v('osuser') }; if (v('oskey')) body.os_key = v('oskey'); if (v('ospass')) body.os_pass = v('ospass');
    document.getElementById('osres').textContent = 'Testen…';
    await post('subs/config', body);
    const r = await post('subs/test');
    CONFIG = await api('config');
    t.msg = r.ok ? (r.note ? '✓ ' + r.note : `✓ Verbonden${r.level ? ' (' + r.level + ')' : ''} · nog ${r.remaining ?? '?'} van ${r.allowed ?? '?'} downloads vandaag`) : '✕ ' + (r.error || 'Mislukt');
    render('ossave');
  };
}

// Geen geluid? Chrome telt gedecodeerde audio; blijft dat 0, dan is het formaat (meestal Dolby/AC3) niet ondersteund.
let audioWatch = null;
function watchAudio(key) {
  clearTimeout(audioWatch);
  if (video.muted || video.volume === 0) { video.muted = false; video.volume = 1 }
  audioWatch = setTimeout(() => {
    const t = top_();
    if (!['live', 'vod'].includes(t.name) || video.currentTime === 0 || (t.name === 'vod' && video.paused)) return;
    if (typeof video.webkitAudioDecodedByteCount === 'number' && video.webkitAudioDecodedByteCount === 0) {
      // Live zender zonder geluid: automatisch opnieuw via ffmpeg, en onthouden voor de volgende keer.
      const it = t.name === 'vod' ? t.items[t.i] : null;
      if (it && it.catchup && it.transcode && !it.usingT) { it.useT = true; S.needsT[it.catchup.ch.key] = 1; persist(); t.playing = null; toast('Geluid wordt omgezet…'); render(null); return }
      const ch = t.name === 'live' ? t.list[t.i] : null;
      if (ch && ch.key === key && ch.transcode && !t.transcoding) {
        S.needsT[key] = 1; persist(); t.playing = null; toast('Geluid wordt omgezet…'); render(null); return;
      }
      t.noAudioKey = key;
      t.noAudio = CONFIG.ffmpeg ? 'Geen geluid: deze zender stuurt geen geluid mee, of in een formaat dat ook ffmpeg niet kan omzetten.'
        : 'Geen geluid: dit geluidsformaat (meestal Dolby/AC3) kan de browser niet afspelen. Installeer ffmpeg (zie LEESMIJ) en start Kijk opnieuw.';
      t.draw ? t.draw() : drawLive(t);
    }
  }, 4500);
}

// ---------------------------------------------------------------- live speler
function openLive(list, i) { if (!list.length) return; push({ name: 'live', list, i, prev: i, info: true, opts: false }) }
function renderLive(t) {
  const ch = t.list[t.i];
  tv.innerHTML = `<div id="vbox" style="position:absolute;inset:0"></div><div id="liveui"></div><div class="panel${t.opts ? '' : ' off'}" data-layer id="panel"></div>`;
  document.getElementById('vbox').appendChild(video);
  if (t.playing !== ch.key) {
    t.playing = ch.key; t.err = null; t.buffering = true;
    S.lastCh = ch.key; S.recentCh = [ch.key, ...(S.recentCh || []).filter(k => k !== ch.key)].slice(0, 20); persist();
    t.transcoding = !!(S.needsT[ch.key] && ch.transcode);
    const start = () => playUrl(t.transcoding ? ch.transcode : ch.stream, { live: true, hls: !t.transcoding && ch.hls, onError: msg => { t.err = `Deze zender speelt niet af (${msg}). Opnieuw proberen…`; drawLive(t); clearTimeout(t.retry); t.retry = setTimeout(() => { if (top_() === t && t.playing === ch.key) start() }, 4000) } });
    start();
    watchAudio(ch.key);
  }
  video.onwaiting = () => { t.buffering = true; drawLive(t) };
  video.onplaying = () => { t.buffering = false; t.err = null; drawLive(t) };
  drawLive(t);
  wireOptions(document.getElementById('panel'));
  infoTimer(t);
  progsAhead(ch).then(() => drawLive(t));
}
// Infobalk (mini-gids): terwijl hij open is, blader je met ▲▼ door andere zenders en met ◀▶ door
// hun programma's, zonder dat het beeld wisselt. OK schakelt over naar de zender die je bekijkt.
const progCache = {};
async function progsAhead(ch) {
  const c = progCache[ch.key];
  if (c && Date.now() - c.t < 10 * MIN) return c.list;
  let list = [];
  try { list = await api(`programmes?key=${encodeURIComponent(ch.key)}&from=${Date.now()}&to=${Date.now() + 12 * HOUR}&offset=${S.offset}`) } catch (e) { }
  progCache[ch.key] = { t: Date.now(), list };
  return list;
}
function infoTimer(t) {
  stopTimers();
  if (t.info && !t.opts) timers.push(setTimeout(() => { t.info = false; t.browse = null; t.bOff = 0; drawLive(t) }, t.browse != null ? 10000 : 6000));
}
function drawLive(t) {
  const ui = document.getElementById('liveui'); if (!ui || top_() !== t) return;
  const browsing = t.browse != null && t.browse !== t.i;
  const ch = t.list[t.browse ?? t.i], now = Date.now();
  const { cur: liveCur, nx: liveNx } = liveNow(ch, now);
  const ahead = (progCache[ch.key] && progCache[ch.key].list.filter(p => p.stop > now)) || [liveCur, liveNx].filter(Boolean);
  const off = Math.min(t.bOff || 0, Math.max(0, ahead.length - 1));
  const cur = ahead[off] || null, nx = ahead[off + 1] || null;
  const isLive = cur && cur.start <= now && cur.stop > now;
  video._raised = t.info && !t.opts;
  const hint = t.infoMode !== 'browse' ? '▲▼ zappen&nbsp;&nbsp;&nbsp;OK = andere zenders bekijken&nbsp;&nbsp;&nbsp;◀ vorige&nbsp;&nbsp;&nbsp;▶ ondertitels &amp; audio&nbsp;&nbsp;&nbsp;G = gids' : t.browse != null
    ? (browsing ? 'OK = overschakelen naar deze zender' : 'OK = sluiten') + '&nbsp;&nbsp;&nbsp;▲▼ zenders&nbsp;&nbsp;&nbsp;◀▶ programma\'s&nbsp;&nbsp;&nbsp;G = gids'
    : '▲▼ andere zenders bekijken&nbsp;&nbsp;&nbsp;◀▶ programma\'s&nbsp;&nbsp;&nbsp;G = gids';
  ui.innerHTML = `${t.buffering || t.err ? `<div class="toast" style="position:absolute">${esc(t.err || 'Laden…')}</div>` : ''}
 ${t.noAudio && t.noAudioKey === t.list[t.i].key ? `<div class="toast" style="position:absolute;top:60px;transform:translateX(-50%);max-width:640px;text-align:center;font-size:15px">🔇 ${esc(t.noAudio)}</div>` : ''}
 <div class="info${t.info && !t.opts ? '' : ' hidden'}">
  ${browsing ? '<div style="font-size:13px;color:#0A84FF;font-weight:700;margin-bottom:6px">ANDERE ZENDER BEKIJKEN · het beeld blijft op ' + esc(t.list[t.i].name) + '</div>' : ''}
  <div style="display:flex;align-items:center">${logoHtml(ch, 96, 60, 17)}
   <div style="flex:1;margin-left:4px;min-width:0">
    <div style="font-size:22px"><span class="sec">${ch.number}</span>&nbsp;&nbsp;<b>${esc(ch.name)}</b>${S.favs.has(ch.key) ? ' <span class="star">★</span>' : ''}${ch.archive ? ' <span class="arch">↺</span>' : ''}</div>
    ${cur ? `<div style="display:flex;align-items:center;gap:8px;margin-top:4px">${isLive ? '<span class="live">NU</span>' : `<span class="sec" style="font-size:15px;font-weight:700">${hm(cur.start)}</span>`}<b style="font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(cur.title)}</b></div>` : `<div class="sec" style="font-size:15px;margin-top:4px">${ch.epg ? '' : 'Geen gidsinformatie'}</div>`}
   </div><div style="font-size:22px">${hm(now)}</div>
  </div>
  ${cur ? `<div style="display:flex;align-items:center;gap:10px;margin-top:10px"><span class="sec small">${hm(cur.start)}</span><div class="bar" style="flex:1;height:4px"><i style="width:${isLive ? (now - cur.start) / (cur.stop - cur.start) * 100 : 0}%"></i></div><span class="sec small">${hm(cur.stop)}</span></div>${cur.desc ? `<div style="font-size:15px;color:#D1D1D6;margin-top:8px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">${esc(cur.desc)}</div>` : ''}` : ''}
  <div style="display:flex;margin-top:10px;align-items:center"><span style="flex:1;font-size:15px" class="sec">${nx ? `Daarna ${hm(nx.start)} · ${esc(nx.title)}` : ''}</span><span class="sec small">${hint}</span><span class="liveact" style="margin-left:14px;font-size:13px" onclick="event.stopPropagation();liveAct('subs')" title="Ondertitels en audio (▶)">💬 Ondertitels</span></div>
 </div>`;
}
function liveAct(what) { const t = top_(); if (t.name === 'live' && what === 'subs') { t.opts = true; t.info = false; render(null); restore(null, '.opt.sel') } }
function liveKey(t, k) {
  if (t.opts) { if (k === 'back' || k === 'left') { t.opts = false; render(null); return true } return false }
  // Alleen bladeren als je de infobalk zelf met OK hebt geopend; na het zappen blijven ▲▼ gewoon zappen.
  if (t.info && t.infoMode === 'browse' && (k === 'up' || k === 'down')) {
    const b = t.browse ?? t.i; t.browse = (b + (k === 'up' ? -1 : 1) + t.list.length) % t.list.length; t.bOff = 0;
    drawLive(t); infoTimer(t);
    progsAhead(t.list[t.browse]).then(() => drawLive(t));
    return true;
  }
  if (t.info && t.infoMode === 'browse' && (k === 'left' || k === 'right')) {
    const ch = t.list[t.browse ?? t.i];
    t.bOff = Math.max(0, (t.bOff || 0) + (k === 'right' ? 1 : -1));
    if (t.browse == null) t.browse = t.i;
    progsAhead(ch).then(list => { t.bOff = Math.min(t.bOff, Math.max(0, list.filter(p => p.stop > Date.now()).length - 1)); drawLive(t) });
    drawLive(t); infoTimer(t);
    return true;
  }
  if (k === 'up' || k === 'down') { t.prev = t.i; t.i = (t.i + (k === 'up' ? -1 : 1) + t.list.length) % t.list.length; t.info = true; t.infoMode = 'zap'; t.browse = null; t.bOff = 0; render(null); return true }
  if (k === 'left') { if (t.prev !== t.i) { [t.i, t.prev] = [t.prev, t.i]; t.info = true; render(null) } return true }
  if (k === 'right') { t.opts = true; t.info = false; render(null); restore(null, '.opt.sel'); return true }
  if (k === 'ok') {
    if (t.info && t.browse != null && t.browse !== t.i) { t.prev = t.i; t.i = t.browse; t.browse = null; t.bOff = 0; render(null); return true }
    if (t.info && t.infoMode !== 'browse') t.infoMode = 'browse';   // balk staat al open na zappen: nu bladeren
    else { t.info = !t.info; t.infoMode = 'browse' }
    t.browse = null; t.bOff = 0; drawLive(t); infoTimer(t); return true;
  }
  if (k === 'guide') { openGuideAt(t.list[t.browse ?? t.i]); return true }
  if (k === 'fav') { toggleFav(t.list[t.browse ?? t.i]); drawLive(t); return true }
  if (k === 'back') { if (t.info) { t.info = false; t.browse = null; t.bOff = 0; drawLive(t); return true } stopVideo(); pop(); return true }
  return false;
}
// Vanuit het beeld naar de gids, met de zender in beeld geselecteerd.
function openGuideAt(ch) {
  stopVideo();
  S.stack = [{ name: 'home' }]; S.tab = 1;
  let list = liveList(); if (S.guideFav) list = CH.filter(c => S.favs.has(c.key)); if (S.guideArch) list = list.filter(c => c.archive);
  if (!list.some(c => c.key === ch.key)) { S.group = null; S.guideFav = false; S.guideArch = false }
  G.listKey = String(S.group) + '|' + CH.length + '|' + S.guideArch + '|' + S.guideFav;
  const l2 = S.guideFav ? CH.filter(c => S.favs.has(c.key)) : liveList();
  G.row = Math.max(0, (S.guideArch ? l2.filter(c => c.archive) : l2).findIndex(c => c.key === ch.key)); G.top = Math.max(0, G.row - 2); G.sel = null;
  G.viewStart = floor30(Date.now()); G.focusTime = Date.now();
  persist(); render('gp:x');
  setTimeout(() => { const el = document.querySelector(`#grows .gp[data-r="${G.row}"]`); if (el) { setFocus(el, true); el._onfocus() } }, 400);
}

// ---------------------------------------------------------------- detailpagina's
function resumeOf(key) { const p = S.progress[key]; return p && p.pos > 30000 && p.pos < p.dur * .95 ? p.pos : 0 }
function renderMovie(t) {
  const m = t.movie, key = 'm:' + m.id, res = resumeOf(key), info = t.info || {};
  if (t.info === undefined) { t.info = null; cached('mi:' + m.id, () => api('vod/movie_info?id=' + encodeURIComponent(m.id))).then(i => { t.info = i || {}; if (top_() === t) render() }).catch(() => { t.info = {} }) }
  const bd = info.backdrop || m.poster;
  const meta = [info.year || m.year, runtime(info.duration), m.rating && '★ ' + m.rating, info.genre].filter(Boolean).join('  ·  ');
  tv.innerHTML = `<div class="backdrop" style="background:${gradFor(m.name)}">${bd ? `<img src="${bd}" onerror="this.remove()">` : ''}</div><div class="shade"></div>
 <div class="screen" style="flex-direction:row;padding-top:47px;gap:36px">
  <div class="bigposter" style="width:210px;height:315px;border-radius:12px;background:${gradFor(m.name)};flex:none;display:flex;align-items:flex-end;padding:14px;font-weight:800;font-size:20px">${esc(m.name)}${m.poster ? `<img src="${m.poster}" onerror="this.remove()">` : ''}</div>
  <div style="flex:1;min-width:0">
   <h1 style="margin:0 0 8px">${esc(m.name)}</h1>
   <div class="meta">${esc(meta)}</div>
   ${info.plot ? `<div class="plot" style="display:-webkit-box;-webkit-line-clamp:7;-webkit-box-orient:vertical;overflow:hidden">${esc(info.plot)}</div>` : t.info === null ? '<div class="plot sec">Informatie laden…</div>' : ''}
   ${info.cast ? `<div class="sec small" style="margin-top:10px">Met: ${esc(info.cast)}</div>` : ''}
   ${info.director ? `<div class="sec small" style="margin-top:4px">Regie: ${esc(info.director)}</div>` : ''}
   <div style="margin-top:24px" data-region="btns">${res ? `<div class="btn f sel" data-id="play">▶&nbsp; Verder vanaf ${clock(res)}</div><div class="btn f" data-id="restart">Vanaf het begin</div>` : `<div class="btn f sel" data-id="play">▶&nbsp; Afspelen</div>`}</div>
  </div></div>`;
  const it = { key, title: m.name, sub: info.genre || '', stream: m.stream, subsQuery: { tmdb: info.tmdb, imdb: info.imdb, query: cleanTitle(m.name), year: info.year || m.year } };
  tv.querySelector('[data-id=play]')._ok = () => { addRecent('movie', m); push({ name: 'vod', items: [it], i: 0, start: res }) };
  const r = tv.querySelector('[data-id=restart]'); if (r) r._ok = () => { addRecent('movie', m); push({ name: 'vod', items: [it], i: 0, start: 0 }) };
}
function renderShow(t) {
  const sh = t.show;
  if (t.data === undefined) { t.data = null; cached('sd:' + sh.id, () => api('vod/show?id=' + encodeURIComponent(sh.id))).then(d => { t.data = d; if (top_() === t) render() }).catch(() => { t.data = { episodes: [], error: true } }) }
  const d = t.data || { episodes: [] }, eps = d.episodes || [], seasons = [...new Set(eps.map(e => e.season))];
  const last = eps.find(e => e.key === S.lastEp[sh.id]);
  if (t.season == null || !seasons.includes(t.season)) t.season = last ? last.season : seasons[0];
  const target = last || eps[0];
  const items = eps.map(e => ({ key: e.key, title: sh.name, sub: `S${e.season} · A${e.number} · ${e.title}`, stream: e.stream, subsQuery: { parent_tmdb: d.tmdb, query: cleanTitle(sh.name), season: e.season, episode: e.number } }));
  const bd = d.backdrop || sh.poster;
  tv.innerHTML = `<div class="backdrop" style="background:${gradFor(sh.name)}">${bd ? `<img src="${bd}" onerror="this.remove()">` : ''}</div><div class="shade"></div>
 <div class="screen scroll" id="showscroll" style="display:block;overflow-y:auto">
  <div style="display:flex;gap:30px;margin-top:12px">
   <div class="bigposter" style="width:150px;height:225px;border-radius:12px;background:${gradFor(sh.name)};flex:none;display:flex;align-items:flex-end;padding:12px;font-weight:800;font-size:17px">${esc(sh.name)}${sh.poster ? `<img src="${sh.poster}" onerror="this.remove()">` : ''}</div>
   <div style="flex:1;min-width:0"><h1 style="margin:0 0 6px">${esc(sh.name)}</h1>
    <div class="meta">${[sh.year, sh.rating && '★ ' + sh.rating, seasons.length ? (seasons.length === 1 ? '1 seizoen' : seasons.length + ' seizoenen') : null].filter(Boolean).join('  ·  ')}</div>
    ${(d.plot || sh.plot) ? `<div class="plot" style="max-width:600px;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden">${esc(d.plot || sh.plot)}</div>` : ''}
    <div style="margin-top:16px" data-region="sbtn">${t.data === null ? '<div class="sec" style="font-size:15px">Afleveringen laden…</div>' : target ? `<div class="btn f sel" data-id="cont">▶&nbsp; ${last ? 'Verder' : 'Afspelen'}: S${target.season} A${target.number}</div>` : '<div class="sec" style="font-size:15px">Geen afleveringen gevonden.</div>'}</div>
   </div></div>
  ${seasons.length > 1 ? `<div style="display:flex;gap:8px;margin:18px 4px 10px;flex-wrap:wrap" data-region="seasons">${seasons.map(s => `<div class="season f${s === t.season ? ' sel' : ''}" data-id="se${s}" data-s="${s}">Seizoen ${s}</div>`).join('')}</div>` : '<div style="height:18px"></div>'}
  <div style="display:flex;flex-direction:column;gap:8px;padding-bottom:30px" data-region="eps" id="eps"></div>
 </div>`;
  const play = e => { S.lastEp[sh.id] = e.key; addRecent('show', sh); persist(); push({ name: 'vod', items, i: eps.indexOf(e), start: resumeOf(e.key), showId: sh.id }) };
  const c = tv.querySelector('[data-id=cont]'); if (c) c._ok = () => play(target);
  const fillEps = () => {
    const box = document.getElementById('eps');
    box.innerHTML = eps.filter(e => e.season === t.season).map(e => {
      const p = S.progress[e.key]; const frac = p ? p.pos / p.dur : 0;
      return `<div class="ep f" data-id="${esc(e.key)}"><div class="still" style="background:${gradFor(e.title)}">${e.still ? `<img loading="lazy" src="${e.still}" onerror="this.remove()">` : ''}${frac > 0.02 && frac < .95 ? `<div class="pbar" style="left:6px;right:6px;bottom:6px"><i style="width:${frac * 100}%"></i></div>` : ''}</div><div style="flex:1;min-width:0"><b>${e.number}. ${esc(e.title)}</b>${frac >= .95 ? '<span class="sec small">&nbsp;&nbsp;✓ Bekeken</span>' : ''}${runtime(e.duration) ? `<div class="sec small">${runtime(e.duration)}</div>` : ''}${e.plot ? `<div class="d">${esc(e.plot)}</div>` : ''}</div></div>`;
    }).join('');
    box.querySelectorAll('.ep').forEach(el => { const e = eps.find(x => x.key === el.dataset.id); el._ok = () => play(e) });
  };
  tv.querySelectorAll('.season').forEach(el => { el._onfocus = () => { const s = +el.dataset.s; if (t.season !== s) { t.season = s; tv.querySelectorAll('.season').forEach(x => x.classList.toggle('sel', x === el)); fillEps() } }; el._ok = el._onfocus });
  fillEps();
  tv.querySelectorAll('.btn').forEach(el => { el._onfocus = () => { document.getElementById('showscroll').scrollTop = 0 } });
}

// ---------------------------------------------------------------- on-demand speler
function renderVod(t) {
  const it = t.items[t.i];
  const hasNext = t.i < t.items.length - 1;
  tv.innerHTML = `<div id="vbox" style="position:absolute;inset:0"></div><div id="vodstatus"></div>
   <div class="info vctl" id="vctl" data-layer>
    <div style="font-size:22px;font-weight:700">${esc(it.title)}</div><div style="font-size:15px;color:#D1D1D6">${esc(it.sub || '')}${it.catchup ? ' &nbsp;<span class="arch">↺ terugkijken</span>' : ''}</div>
    <div class="tl f" data-id="tl"><div class="tlbar"><i class="tlfill"></i><i class="tlprev"></i><span class="tlknob"></span><span class="tlbubble"></span></div></div>
    <div style="display:flex;margin-top:4px" class="sec small"><span id="tpos"></span><span style="flex:1"></span><span id="thint"></span><span style="flex:1"></span><span id="tdur"></span></div>
    <div class="vbtns" data-region="vbtns">
     <span class="vbtn f" data-id="vb-play"></span>
     <span class="vbtn f" data-id="vb-subs">💬 Ondertitels</span>
     <span class="vbtn f" data-id="vb-audio">🔊 Audio</span>
     ${hasNext ? '<span class="vbtn f" data-id="vb-next">⏭ Volgende aflevering</span>' : ''}
    </div>
   </div>
   <div class="panel${t.opts ? '' : ' off'}" data-layer id="panel"></div>`;
  document.getElementById('vbox').appendChild(video);
  if (t.playing !== it.key) {
    t.playing = it.key; t.err = null; t.cd = null; t.ctl = true; t.ctlAt = Date.now(); t.ended = false; t.scrub = null;
    const useT = it.catchup && it.transcode && (it.useT || S.needsT[it.catchup.ch.key]);
    playUrl(useT ? it.transcode : it.stream, { live: !!it.catchup && (useT || !it.hls), hls: !useT && it.hls, onError: msg => { t.err = `Afspelen mislukt: ${msg}`; t.draw && t.draw() } });
    it.usingT = !!useT;
    const start = it.catchup ? 0 : t.first === undefined ? t.start : resumeOf(it.key); t.first = false;
    if (start) video.addEventListener('loadedmetadata', () => { try { video.currentTime = start / 1000 } catch (e) { } }, { once: true });
    if (t.showId) { S.lastEp[t.showId] = it.key; persist() }
    t.noAudio = null; watchAudio(it.key);
    clearExtSubs(); if (it.subsQuery) autoSubs(t, it);
  }
  else if (video.paused && !t.userPaused && !t.ended && t.cd == null) { const pp = video.play(); if (pp) pp.catch(() => { }) }  // terug van een ander scherm
  video.onended = () => vodEnded(t);
  video.onwaiting = () => { t.buffering = true }; video.onplaying = () => { t.buffering = false; t.err = null };

  // knoppen
  const q = id => tv.querySelector(`[data-id=${id}]`);
  q('vb-play')._ok = () => { togglePlay(t); vodPoke(t) };
  q('vb-subs')._ok = () => openVodPanel(t, 'subs');
  q('vb-audio')._ok = () => openVodPanel(t, 'audio');
  if (hasNext) q('vb-next')._ok = () => nextEp(t);
  const tl = q('tl');
  tl._ok = () => { if (t.scrub != null) commitScrub(t); else { togglePlay(t); vodPoke(t) } };
  // muis: klik op de tijdlijn = daarheen springen
  tl.addEventListener('click', e => {
    const r = tl.querySelector('.tlbar').getBoundingClientRect(), [, dur] = vodPosDur(t);
    if (!dur) return;
    t.scrub = Math.max(0, Math.min(dur - 1000, (e.clientX - r.left) / r.width * dur)); commitScrub(t); e.stopPropagation();
  });
  t.draw = () => drawVod(t); drawVod(t);
  if (t.ctl && !t.opts) { const f = focusEl && focusEl.closest && focusEl.closest('#vctl') ? focusEl.dataset.id : 'tl'; restore(f) }
  wireOptions(document.getElementById('panel'));
  timers.push(setInterval(() => {
    if (t.ctl && !video.paused && t.scrub == null && Date.now() - t.ctlAt > 5000) { t.ctl = false }
    const dur = video.duration;
    if (!it.catchup && isFinite(dur) && dur > 0 && video.currentTime > 0) { S.progress[it.key] = { pos: video.currentTime * 1000, dur: dur * 1000 }; if (Math.random() < 0.03) persist() }
    drawVod(t);
  }, 500));
}
function vodPosDur(t) {
  const it = t.items[t.i];
  const pos = (it.catchup ? it.offsetMs || 0 : 0) + (video.currentTime * 1000 || 0);
  const dur = it.catchup ? it.durMs : isFinite(video.duration) ? video.duration * 1000 : 0;
  return [pos, dur];
}
function drawVod(t) {
  if (top_() !== t || !document.getElementById('vctl')) return;
  const it = t.items[t.i], [pos, dur] = vodPosDur(t);
  const show = t.ctl && t.cd == null && !t.opts;
  video._raised = show;
  const ctl = document.getElementById('vctl');
  ctl.classList.toggle('hidden', !show); ctl.classList.toggle('off', !show);
  const frac = dur ? Math.min(1, pos / dur) : 0, sfrac = t.scrub != null && dur ? Math.min(1, t.scrub / dur) : null;
  ctl.querySelector('.tlfill').style.width = frac * 100 + '%';
  const prev = ctl.querySelector('.tlprev'), knob = ctl.querySelector('.tlknob'), bub = ctl.querySelector('.tlbubble');
  const kf = sfrac != null ? sfrac : frac;
  knob.style.left = kf * 100 + '%';
  if (sfrac != null) {
    prev.style.left = Math.min(frac, sfrac) * 100 + '%'; prev.style.width = Math.abs(sfrac - frac) * 100 + '%'; prev.style.display = 'block';
    bub.style.left = sfrac * 100 + '%'; bub.style.display = 'block';
    const d = t.scrub - pos; bub.textContent = `${clock(t.scrub)}  (${d >= 0 ? '+' : '−'}${clock(Math.abs(d))})`;
  } else { prev.style.display = 'none'; bub.style.display = 'none' }
  document.getElementById('tpos').textContent = clock(pos);
  document.getElementById('tdur').textContent = dur ? '-' + clock(dur - pos) : '';
  const tlFocused = focusEl && focusEl.dataset && focusEl.dataset.id === 'tl';
  document.getElementById('thint').textContent = tlFocused ? (t.scrub != null ? 'OK = hierheen springen · Terug = annuleren' : `◀ ▶ spoelen${it.catchup ? ' (per minuut)' : ''} · OK = ${video.paused ? 'afspelen' : 'pauze'}`) : '';
  const pb = ctl.querySelector('[data-id=vb-play]'); const lbl = video.paused ? '▶ Afspelen' : '❚❚ Pauze'; if (pb.textContent !== lbl) pb.textContent = lbl;
  const st = document.getElementById('vodstatus');
  st.innerHTML = `${t.err ? `<div class="toast" style="position:absolute">${esc(t.err)}</div>` : t.buffering && !t.cd ? '<div class="toast" style="position:absolute">Laden…</div>' : ''}
   ${t.noAudio ? `<div class="toast" style="position:absolute;top:60px;transform:translateX(-50%);max-width:640px;text-align:center;font-size:15px">🔇 ${esc(t.noAudio.replace('Installeer ffmpeg (zie LEESMIJ) en start Kijk opnieuw.', 'Films met Dolby-geluid werken wel in de Android-app.'))}</div>` : ''}
   ${video.paused && !t.err && t.cd == null && !t.ended && !t.buffering && t.scrub == null ? '<div class="pause">❚❚</div>' : ''}
   ${t.cd != null ? `<div class="countdown"><b>Volgende aflevering over ${t.cd} s</b><span style="font-size:15px;color:#55555C">${esc(t.items[t.i + 1].sub)}</span><span>OK = nu afspelen · Terug = stoppen</span></div>` : ''}`;
}
function togglePlay(t) { if (video.paused) { video.play(); t.userPaused = false } else { video.pause(); t.userPaused = true } }
function vodPoke(t) {
  const wasHidden = !t.ctl;
  t.ctl = true; t.ctlAt = Date.now();
  drawVod(t);
  if (wasHidden || !(focusEl && focusEl.closest && focusEl.closest('#vctl'))) restore('tl');
}
// Spoelen op de tijdlijn: elke druk verschuift het doel; bij snel herhalen steeds grotere stappen.
let scrubTimer = null;
function scrubBy(t, dir) {
  const it = t.items[t.i], [pos, dur] = vodPosDur(t), now = Date.now();
  t.scrubN = now - (t.scrubAt || 0) < 450 ? (t.scrubN || 0) + 1 : 0; t.scrubAt = now;
  const n = t.scrubN;
  let step = it.catchup ? 60000 : n > 30 ? 300000 : n > 15 ? 60000 : n > 5 ? 30000 : 10000;
  if (it.catchup && n > 10) step = 300000;
  const base = t.scrub != null ? t.scrub : pos;
  t.scrub = Math.max(0, Math.min((dur || base + step) - 1000, base + dir * step));
  drawVod(t);
  clearTimeout(scrubTimer); scrubTimer = setTimeout(() => { if (top_() === t && t.scrub != null) commitScrub(t) }, 1400);
}
function commitScrub(t) {
  clearTimeout(scrubTimer);
  const it = t.items[t.i], target = t.scrub; t.scrub = null;
  if (target == null) return;
  if (it.catchup) { seekCatchupTo(t, target); return }
  try { video.currentTime = target / 1000 } catch (e) { }
  vodPoke(t);
}
function openVodPanel(t, which) {
  t.opts = true; t.ctl = false; render(null);
  if (which === 'audio') {
    if (!document.querySelector('#panel [data-id^=ao]')) toast('Deze film heeft maar één audiospoor die de browser kan kiezen');
    restore(null, '#panel .opt[data-id^=ao].sel') ;
  } else restore(null, '.opt.sel');
}
function vodEnded(t) {
  const it = t.items[t.i]; if (isFinite(video.duration)) S.progress[it.key] = { pos: video.duration * 1000, dur: video.duration * 1000 }; persist();
  t.ended = true;
  if (t.i >= t.items.length - 1) { stopVideo(); pop(); toast('Afgelopen'); return }
  t.cd = 8; const tick = () => { if (top_() !== t || t.cd == null) return; t.cd--; if (t.cd <= 0) nextEp(t); else { drawVod(t); setTimeout(tick, 1000) } }; setTimeout(tick, 1000);
}
function nextEp(t) { t.cd = null; t.i++; render(null) }
function vodKey(t, k) {
  if (t.opts) { if (k === 'back') { t.opts = false; t.ctl = true; t.ctlAt = Date.now(); render('vb-subs'); return true } return false }
  if (t.cd != null) { if (k === 'ok') { nextEp(t); return true } if (k === 'back') { t.cd = null; stopVideo(); pop(); return true } return true }
  // bediening verborgen: eerste toets toont de bediening
  if (!t.ctl) {
    if (k === 'back') { persist(); stopVideo(); pop(); return true }
    if (k === 'ok') { togglePlay(t); vodPoke(t); return true }
    if (k === 'left' || k === 'right') { vodPoke(t); scrubBy(t, k === 'left' ? -1 : 1); return true }
    if (k === 'up') { openVodPanel(t, 'subs'); return true }
    vodPoke(t); return true;
  }
  t.ctlAt = Date.now();
  if (k === 'back') { if (t.scrub != null) { t.scrub = null; clearTimeout(scrubTimer); drawVod(t); return true } t.ctl = false; drawVod(t); return true }
  const onTl = focusEl && focusEl.dataset && focusEl.dataset.id === 'tl';
  if (onTl && (k === 'left' || k === 'right')) { scrubBy(t, k === 'left' ? -1 : 1); return true }
  if (onTl && k === 'up') return true;
  if (onTl && k === 'down') { setFocus(document.querySelector('[data-id=vb-play]')); drawVod(t); return true }
  return false;  // knoppen: gewone navigatie en OK
}

// ============================================================ navigatie en invoer
function push(s) { if (focusEl) top_().focusId = focusEl.dataset.id; S.stack.push(s); render(null) }
function pop() { if (S.stack.length > 1) { S.stack.pop(); persist(); render(top_().focusId) } }
let toastT; function toast(msg) { let el = document.getElementById('toast'); if (!el) { el = document.createElement('div'); el.id = 'toast'; el.className = 'toast'; tv.appendChild(el) } el.textContent = msg; clearTimeout(toastT); toastT = setTimeout(() => el.remove(), 1800) }

function key(k) {
  const t = top_();
  if (t.name === 'live' && liveKey(t, k)) return;
  if (t.name === 'vod' && vodKey(t, k)) return;
  if (t.name === 'home' && S.tab === 1 && focusEl && focusEl.closest && focusEl.closest('#grows') && guideKey(k)) return;
  if (k === 'now') { if (t.name === 'home' && S.tab === 1) guideNow(); return }
  if (k === 'guide') { if (t.name === 'vod') return; S.stack = [{ name: 'home' }]; S.tab = 1; persist(); render('tab1'); return }
  if (['up', 'down', 'left', 'right'].includes(k)) return move(k);
  if (k === 'ok') { focusEl && focusEl._ok && focusEl._ok(); return }
  if (k === 'fav') { focusEl && focusEl._long && focusEl._long(); return }
  if (k === 'back') {
    if (t.name === 'home') { if (S.tab !== 0) { S.tab = 0; persist(); render('tab0') } return }
    if (t.name === 'setup' && !CONFIG.playlist_url) return;
    if (t.onBack) return t.onBack();
    pop();
  }
}
let holdT = null, held = false;
const KMAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Escape: 'back', Backspace: 'back', f: 'fav', F: 'fav', MediaPlayPause: 'ok', ' ': 'ok', n: 'now', N: 'now', g: 'guide', G: 'guide', m: 'menu', M: 'menu', ContextMenu: 'menu' };
document.addEventListener('keydown', e => {
  if (top_().name === 'pin') {
    if (/^[0-9]$/.test(e.key)) { e.preventDefault(); return pinKey(top_(), e.key) }
    if (e.key === 'Backspace') { e.preventDefault(); return pinKey(top_(), 'del') }
  }
  const typing = document.activeElement && document.activeElement.tagName === 'INPUT';
  if (typing && ['ArrowLeft', 'ArrowRight', 'Backspace', 'f', 'F', ' ', 'h', 'H'].includes(e.key)) return;
  if (typing && e.key.length === 1) return;
  if (e.key === 'h' || e.key === 'H') { help.classList.toggle('gone'); return }
  if (e.key === 'Enter') { e.preventDefault(); if (e.repeat) return; held = false; holdT = setTimeout(() => { held = true; key('fav') }, 650); return }
  if (/^[0-9]$/.test(e.key) && !e.ctrlKey && !e.altKey) { e.preventDefault(); return numKey(e.key) }
  const k = KMAP[e.key]; if (k) { e.preventDefault(); key(k) }
});
document.addEventListener('keyup', e => { if (e.key === 'Enter') { clearTimeout(holdT); if (!held) key('ok') } });
tv.addEventListener('click', e => { const el = e.target.closest('.f'); if (!el) { if (['live', 'vod'].includes(top_().name)) key('ok'); return } if (focusEl !== el) setFocus(el); if (el.tagName !== 'INPUT') el._ok && el._ok() });
tv.addEventListener('contextmenu', e => { const el = e.target.closest('.f'); e.preventDefault(); if (el) { setFocus(el); el._long && el._long() } });
document.querySelectorAll('#remote [data-k]').forEach(b => b.addEventListener('click', () => key(b.dataset.k)));
document.getElementById('rtoggle').addEventListener('click', () => document.getElementById('remote').classList.toggle('min'));
const help = document.getElementById('help'); setTimeout(() => help.classList.add('gone'), 12000);
function fit() { const sc = Math.min(innerWidth / 960, innerHeight / 540); tv.style.transform = `scale(${sc})` }
addEventListener('resize', fit); fit();

// ============================================================ gegevens laden
async function loadNowNext() { try { NN = await api('nownext?offset=' + S.offset) } catch (e) { } }
async function loadChannels() { ALLCH = await api('channels'); ALLGROUPS = [...new Set(ALLCH.map(c => c.group))]; migrateKeys(); applyHidden() }
// Oude favorieten (opgeslagen als plaats in de lijst, "c12") omzetten naar de vaste sleutel van die zender.
function migrateKeys() {
  // ook sleutels op stream-adres ("z…") omzetten naar het vaste Xtream-zendernummer ("s…")
  const known = new Set(ALLCH.map(c => c.key));
  const old = [...S.favs, ...Object.keys(S.needsT)].filter(k => !known.has(k) && /^[cz]/.test(k));
  if (!old.length) return;
  const map = {}; ALLCH.forEach(c => { if (c.oldKey) map[c.oldKey] = c.key; if (c.urlKey) map[c.urlKey] = c.key });
  S.favs = new Set([...S.favs].map(k => map[k] || k).filter(k => known.has(k)));
  const nt = {}; Object.keys(S.needsT).forEach(k => { const n = map[k] || k; if (known.has(n)) nt[n] = 1 }); S.needsT = nt;
  persist();
}
// Verborgen groepen (bijv. landen die je nooit kijkt) tellen nergens mee: niet in Live, niet in de gids, niet bij zappen.
function applyHidden() {
  const lockedOut = g => S.hidden.has(g) || (isLocked(g) && !S.unlocked);
  CH = ALLCH.filter(c => !lockedOut(c.group));
  GROUPS = ALLGROUPS.filter(g => !S.hidden.has(g));
  if (typeof guideReset === 'function') guideReset();
}
let pollTimer = null, lastSig = '';
function pollSoon() { clearTimeout(pollTimer); pollTimer = setTimeout(poll, 800) }
async function poll() {
  try {
    STATUS = await api('status');
    const sig = `${STATUS.channels}|${STATUS.matched}|${STATUS.programmes}|${STATUS.hasVod}`;
    if (sig !== lastSig) {
      const ls = lastSig.split('|');
      const chChanged = !lastSig || ls[0] !== String(STATUS.channels) || ls[1] !== String(STATUS.matched) || ls[2] !== String(STATUS.programmes);
      lastSig = sig;
      if (chChanged) { await loadChannels(); for (const k in vodCache) delete vodCache[k] }
      guideReset();
      await loadNowNext();
      if (top_().name === 'home') render();
    } else showStatus();
  } catch (e) { STATUS = { playlistError: 'Kan de Kijk-server niet bereiken. Draait kijk_server.py nog?' }; showStatus() }
  const busy = STATUS.playlist || STATUS.epgBusy;
  clearTimeout(pollTimer); pollTimer = setTimeout(poll, busy || STATUS.progress ? 600 : 30000);
}
setInterval(async () => { await loadNowNext(); const c = document.getElementById('clk'); if (c) c.textContent = hm(Date.now()); if (top_().name === 'home' && S.tab < 2 && !(focusEl && focusEl.tagName === 'INPUT')) render() }, 60000);

(async function start() {
  try { CONFIG = await api('config') } catch (e) { tv.innerHTML = emptyHtml('Kijk-server niet bereikbaar', 'Start kijk_server.py en ververs deze pagina.'); return }
  if (!CONFIG.playlist_url) { S.stack = [{ name: 'setup' }]; render(null); return }
  render('tab' + S.tab);
  poll();
})();
