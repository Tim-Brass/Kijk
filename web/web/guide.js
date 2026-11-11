"use strict";
// Tv-gids als tijdraster (zoals Ziggo, KPN en Odido): zenders onder elkaar, tijd van links naar rechts.
// ▲▼ andere zender (zelfde tijdstip) · ◀▶ vorig/volgend programma · OK kijken of terugkijken.

let GCH = [];
const G = { row: 0, top: 0, viewStart: null, focusTime: null, sel: null, cache: {}, loading: {} };
const GRID = { rowH: 52, gap: 4, chW: 196, span: 150 * MIN };
const BLOCK = 6 * HOUR;
const floor30 = t => Math.floor(t / (30 * MIN)) * 30 * MIN;
function gridWidth() { return 960 - 96 - GRID.chW - 8 }
const ppm = () => gridWidth() / GRID.span;
function guideReset() { G.cache = {}; G.loading = {} }

function dayLabel(t) {
  const d = Math.round((startOfDay(t) - startOfDay(Date.now())) / DAY);
  if (d === 0) return 'Vandaag';
  if (d === -1) return 'Gisteren';
  if (d === 1) return 'Morgen';
  const s = new Date(t).toLocaleDateString('nl-NL', { weekday: 'long', day: 'numeric', month: 'long' });
  return s[0].toUpperCase() + s.slice(1);
}

function progsFor(ch) {
  const c = G.cache[ch.key]; if (!c) return [];
  const seen = new Set(), out = [];
  Object.keys(c).sort((a, b) => a - b).forEach(b => c[b].forEach(p => { if (!seen.has(p.start)) { seen.add(p.start); out.push(p) } }));
  out.sort((a, b) => a.start - b.start);
  // dubbele/overlappende programma's uit de gids van de aanbieder weglaten
  const clean = [];
  for (const p of out) { const l = clean[clean.length - 1]; if (l && p.start < l.stop - 2 * MIN) continue; clean.push(p) }
  return clean;
}
function neededBlocks() {
  const out = [];
  for (let b = Math.floor((G.viewStart - HOUR) / BLOCK); b <= Math.floor((G.viewStart + GRID.span + HOUR) / BLOCK); b++) out.push(b);
  return out;
}
function rowLoaded(ch) { return neededBlocks().every(b => G.cache[ch.key] && G.cache[ch.key][b]) }

let gridTimer = null;
function ensureGridData() {
  clearTimeout(gridTimer);
  gridTimer = setTimeout(async () => {
    const n = visibleRows();
    const keys = GCH.slice(Math.max(0, G.top - 3), G.top + n + 8).filter(c => c.epg).map(c => c.key);
    for (const b of neededBlocks()) {
      const want = keys.filter(k => !(G.cache[k] && G.cache[k][b]) && !G.loading[k + ':' + b]);
      if (!want.length) continue;
      want.forEach(k => G.loading[k + ':' + b] = 1);
      try {
        const res = await api(`grid?keys=${want.map(encodeURIComponent).join(',')}&from=${b * BLOCK}&to=${(b + 1) * BLOCK}&offset=${S.offset}`);
        want.forEach(k => { (G.cache[k] = G.cache[k] || {})[b] = res[k] || []; delete G.loading[k + ':' + b] });
      } catch (e) { want.forEach(k => delete G.loading[k + ':' + b]) }
      if (top_().name === 'home' && S.tab === 1 && document.getElementById('grows')) drawGuide();
    }
  }, 60);
}

function visibleRows() {
  const el = document.getElementById('grows');
  return Math.max(3, Math.floor(((el && el.clientHeight) || 280) / (GRID.rowH + GRID.gap)));
}

function renderGuide(body) {
  const key = String(S.group) + '|' + CH.length + '|' + S.guideArch + '|' + S.guideFav;
  if (G.listKey !== key) { G.listKey = key; G.row = 0; G.top = 0; G.sel = null }
  // ★ Alleen favorieten: al je favorieten, ongeacht de gekozen groep. ↺ Alleen terugkijken: binnen de lijst.
  GCH = S.guideFav ? CH.filter(c => S.favs.has(c.key)) : liveList();
  if (S.guideArch) GCH = GCH.filter(c => c.archive);
  G.row = Math.max(0, Math.min(G.row, GCH.length - 1));
  if (!GCH.length && S.guideFav) { S.guideFav = false; persist(); toast(S.guideArch ? 'Geen favorieten om terug te kijken' : 'Nog geen favorieten. Houd OK ingedrukt op een zender (of druk F).'); return renderGuide(body) }
  if (!GCH.length && S.guideArch) { S.guideArch = false; persist(); toast('Geen zenders om terug te kijken in deze groep'); return renderGuide(body) }
  if (!GCH.length) { body.innerHTML = emptyHtml('Nog geen zenders', 'Controleer je playlist bij Instellingen.'); return }
  const now = Date.now();
  if (G.viewStart == null || G.viewStart + GRID.span < now - 7 * DAY) { G.viewStart = floor30(now); G.focusTime = now }
  G.row = Math.min(G.row, GCH.length - 1);
  body.innerHTML = `<div style="flex:1;display:flex;flex-direction:column;min-width:0">
    <div id="gdetail" style="height:80px;flex:none;padding:0 4px"></div>
    <div style="display:flex;gap:8px;align-items:center;height:30px;flex:none;margin-bottom:4px" data-region="gtools">
      <span class="season f" data-id="gfav" style="font-size:13px;padding:5px 14px${S.guideFav ? ';background:rgba(255,214,10,.35)' : ''}"><span class="star">★</span> Alleen favorieten${S.guideFav ? ' ✓' : ''}</span>
      <span class="season f" data-id="garch" style="font-size:13px;padding:5px 14px${S.guideArch ? ';background:rgba(10,132,255,.4)' : ''}"><span class="arch">↺</span> Alleen terugkijken${S.guideArch ? ' ✓' : ''}</span>
      <span class="sec" style="font-size:12px;margin-left:6px">${esc(S.guideFav ? '★ Favorieten' : S.group === null ? 'Alle zenders' : S.group === '★' ? '★ Favorieten' : S.group === '↺' ? '↺ Terugkijken' : S.group === '🕘' ? '🕘 Laatst bekeken' : S.group)} · ${GCH.length} zenders · N = nu · cijfers = zender</span>
    </div>
    <div id="gruler" style="height:24px;flex:none;position:relative"></div>
    <div id="grows" style="position:relative;flex:1;overflow:hidden"></div></div>`;
  body.querySelector('[data-id=garch]')._ok = () => { S.guideArch = !S.guideArch; persist(); if (S.guideArch) G.viewStart = floor30(Date.now() - 2 * HOUR); G.sel = null; renderGuide(body); restore('garch') };
  body.querySelector('[data-id=gfav]')._ok = () => { S.guideFav = !S.guideFav; persist(); G.sel = null; renderGuide(body); restore('gfav') };
  drawGuide(true);
}

function pickAt(ch, t) {
  const l = progsFor(ch);
  return l.find(p => p.start <= t && p.stop > t) || l.find(p => p.start > t) || l[l.length - 1] || null;
}

function drawGuide(initial) {
  const rowsEl = document.getElementById('grows'), ruler = document.getElementById('gruler');
  if (!rowsEl) return;
  const n = visibleRows();
  if (G.row < G.top) G.top = G.row;
  if (G.row >= G.top + n) G.top = G.row - n + 1;
  G.top = Math.max(0, Math.min(G.top, Math.max(0, GCH.length - n)));
  const vs = G.viewStart, ve = vs + GRID.span, k = ppm(), now = Date.now(), X = GRID.chW + 8;

  // tijdbalk
  let rh = `<div class="sec" style="position:absolute;left:4px;top:3px;font-size:13px;font-weight:600">${dayLabel(vs + 1)}</div>`;
  for (let t = vs; t < ve; t += 30 * MIN) rh += `<div class="sec" style="position:absolute;left:${X + (t - vs) * k}px;top:3px;font-size:13px;border-left:1px solid rgba(255,255,255,.25);padding-left:6px">${hm(t)}</div>`;
  if (now >= vs && now < ve) rh += `<div style="position:absolute;left:${X + (now - vs) * k - 4}px;bottom:-2px;width:0;height:0;border-left:5px solid transparent;border-right:5px solid transparent;border-top:7px solid #FF453A"></div>`;
  ruler.innerHTML = rh;

  // rijen
  let h = '';
  for (let r = G.top; r < Math.min(GCH.length, G.top + n); r++) {
    const ch = GCH[r], y = (r - G.top) * (GRID.rowH + GRID.gap);
    h += `<div style="position:absolute;left:0;right:0;top:${y}px;height:${GRID.rowH}px">
      <div class="gch${r === G.row ? ' cur' : ''}" style="width:${GRID.chW}px">
        <span class="num" style="width:30px">${ch.number}</span><span style="position:relative;flex:none">${logoHtml(ch, 44, 28, 10).replace('margin-right:14px', '')}${S.favs.has(ch.key) ? '<span class="gfav">★</span>' : ''}</span><span class="gname">${esc(ch.name.replace(/^\s*[A-Z]{2,3}\s*[:|]\s*/, ''))}</span>${ch.archive ? '<span class="arch" title="Terugkijken">↺</span>' : ''}
      </div>`;
    if (!ch.epg) {
      h += `<div class="gp f" data-id="gp:${ch.key}:none" data-r="${r}" style="left:${X}px;width:${gridWidth() - 4}px"><span class="gt sec">Geen gidsinformatie</span></div>`;
    } else if (!rowLoaded(ch) && !progsFor(ch).length) {
      h += `<div class="gp f" data-id="gp:${ch.key}:load" data-r="${r}" style="left:${X}px;width:${gridWidth() - 4}px"><span class="gt sec">Laden…</span></div>`;
    } else {
      const vis = progsFor(ch).filter(p => p.stop > vs && p.start < ve);
      if (!vis.length) h += `<div class="gp f" data-id="gp:${ch.key}:empty" data-r="${r}" style="left:${X}px;width:${gridWidth() - 4}px"><span class="gt sec">Geen programma-informatie</span></div>`;
      for (const p of vis) {
        const l = Math.max(0, (p.start - vs) * k), rr = Math.min(GRID.span, p.stop - vs) * k, w = rr - l - 4;
        if (w < 3) continue;
        const live = p.start <= now && p.stop > now, past = p.stop <= now;
        const canBack = past && ch.archive && p.start > now - ch.archive * DAY;
        h += `<div class="gp f${live ? ' glive' : ''}${past ? ' gpast' : ''}" data-id="gp:${ch.key}:${p.start}" data-r="${r}" data-s="${p.start}" style="left:${X + l}px;width:${w}px">
          ${w > 34 ? `<span class="gt">${canBack ? '<span class="arch">↺</span> ' : ''}${esc(p.title)}</span>${w > 90 ? `<span class="gtime">${hm(p.start)} – ${hm(p.stop)}</span>` : ''}` : ''}
          ${live ? `<i class="gprog" style="width:${(now - p.start) / (p.stop - p.start) * 100}%"></i>` : ''}</div>`;
      }
    }
    h += `</div>`;
  }
  if (now >= vs && now < ve) h += `<div style="position:absolute;left:${X + (now - vs) * k - 1}px;top:0;bottom:0;width:2px;background:#FF453A;opacity:.85;pointer-events:none;z-index:3"></div>`;
  rowsEl.innerHTML = h;

  // gedrag per blok
  rowsEl.querySelectorAll('.gp').forEach(el => {
    const r = +el.dataset.r, ch = GCH[r], start = el.dataset.s ? +el.dataset.s : null;
    el._onfocus = () => {
      const changedRow = G.row !== r; G.row = r;
      G.sel = { key: ch.key, start };
      if (start != null) G.focusTime = Math.max(start, G.viewStart);
      rowsEl.querySelectorAll('.gch').forEach((c, i) => c.classList.toggle('cur', G.top + i === r));
      drawDetail();
      if (changedRow) ensureGridData();
    };
    el._ok = () => guideOk();
    el._long = () => { toggleFav(ch) };
  });

  // focus herstellen
  const ch = GCH[G.row];
  let want = null;
  if (G.sel && G.sel.key === ch.key && G.sel.start != null) want = rowsEl.querySelector(`.gp[data-id="gp:${CSS.escape(ch.key)}:${G.sel.start}"]`);
  if (!want) { const p = pickAt(ch, Math.max(G.focusTime || now, vs)); if (p) want = rowsEl.querySelector(`.gp[data-id="gp:${CSS.escape(ch.key)}:${p.start}"]`) }
  if (!want) want = rowsEl.querySelector(`.gp[data-r="${G.row}"]`);
  const focusInGuide = !focusEl || !focusEl.isConnected || focusEl.closest('#grows');
  if (want && !initial && focusInGuide) { setFocus(want, true); want._onfocus() }
  else if (want && initial && focusEl && focusEl.dataset.id && focusEl.dataset.id.startsWith('gp:')) { setFocus(want, true); want._onfocus() }
  drawDetail();
  ensureGridData();
}

function selected() {
  const ch = GCH[G.row]; if (!ch) return {};
  const p = G.sel && G.sel.key === ch.key && G.sel.start != null ? progsFor(ch).find(x => x.start === G.sel.start) : null;
  return { ch, p };
}

function drawDetail() {
  const el = document.getElementById('gdetail'); if (!el) return;
  const { ch, p } = selected(), now = Date.now();
  if (!ch) { el.innerHTML = ''; return }
  if (!p) { el.innerHTML = `<div style="font-size:22px;font-weight:700">${esc(ch.name)}</div><div class="sec" style="font-size:15px;margin-top:4px">${ch.epg ? '' : 'Deze zender is niet aan de gids gekoppeld. Controleer de gids-link of de tvg-id in je playlist.'}</div>`; return }
  const live = p.start <= now && p.stop > now, past = p.stop <= now;
  const canBack = past && ch.archive && p.start > now - ch.archive * DAY;
  const when = `${dayLabel(p.start)} ${hm(p.start)} – ${hm(p.stop)}`;
  const extra = live ? minsLeft(p.stop, now) : !past ? `begint over ${Math.round((p.start - now) / MIN) >= 60 ? Math.floor((p.start - now) / HOUR) + ' u ' + Math.round(((p.start - now) % HOUR) / MIN) + ' min' : Math.round((p.start - now) / MIN) + ' min'}` : '';
  const action = live ? '▶ OK = kijken' : canBack ? '↺ OK = terugkijken' : past ? 'Terugkijken niet beschikbaar' : '';
  el.innerHTML = `<div style="display:flex;align-items:center;gap:10px">${live ? '<span class="live">NU</span>' : ''}<span style="font-size:22px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(p.title)}</span><span style="flex:1"></span><span class="sec" style="font-size:13px;white-space:nowrap">${action}</span></div>
   <div class="sec" style="font-size:15px;margin-top:2px">${esc(ch.name)}  ·  ${when}${extra ? '  ·  ' + extra : ''}</div>
   ${p.desc ? `<div style="font-size:14px;color:#D1D1D6;margin-top:4px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">${esc(p.desc)}</div>` : ''}`;
}

function guideOk() {
  const { ch, p } = selected(), now = Date.now();
  if (!ch) return;
  if (!p || (p.start <= now && p.stop > now)) return openLive(GCH, G.row);
  if (p.stop <= now) {
    if (ch.archive && p.start > now - ch.archive * DAY) return openCatchup(ch, p);
    return toast('Terugkijken is voor deze zender niet beschikbaar');
  }
  toast(`Begint ${dayLabel(p.start).toLowerCase()} om ${hm(p.start)}`);
}

function guideKey(k) {
  const ch = GCH[G.row]; if (!ch) return false;
  const list = progsFor(ch), cur = selected().p, now = Date.now();
  if (k === 'up') { if (G.row === 0) return false; G.row--; G.sel = null; drawGuide(); return true }
  if (k === 'down') { if (G.row >= GCH.length - 1) return true; G.row++; G.sel = null; drawGuide(); return true }
  if (k === 'left' || k === 'right') {
    const dir = k === 'left' ? -1 : 1;
    const idx = cur ? list.indexOf(cur) : -1;
    const nxt = idx >= 0 ? list[idx + dir] : null;
    const minStart = floor30(now - 7 * DAY), maxStart = floor30(now + 7 * DAY);
    if (nxt) {
      if (dir < 0) { while (nxt.stop <= G.viewStart + 15 * MIN && G.viewStart > minStart) G.viewStart -= 30 * MIN }
      else { while (nxt.start >= G.viewStart + GRID.span - 20 * MIN && G.viewStart < maxStart) G.viewStart += 30 * MIN }
      G.sel = { key: ch.key, start: nxt.start }; G.focusTime = Math.max(nxt.start, G.viewStart);
    } else {
      // geen programma meer in die richting (of nog niet geladen): tijd verschuiven
      G.viewStart = Math.max(minStart, Math.min(maxStart, G.viewStart + dir * 30 * MIN)); G.focusTime = G.viewStart + (dir > 0 ? GRID.span - 30 * MIN : 0); G.sel = null;
    }
    drawGuide(); return true;
  }
  return false;
}

// "Nu" in de gids: met de toets 0 of N terug naar het huidige tijdstip
function guideNow() { G.viewStart = floor30(Date.now()); G.focusTime = Date.now(); G.sel = null; drawGuide() }

// ---------------------------------------------------------------- terugkijken
async function openCatchup(ch, p, offsetMs = 0) {
  let r;
  try { r = await api(`catchup?key=${encodeURIComponent(ch.key)}&start=${p.start + offsetMs}&stop=${p.stop}&offset=${S.offset}`) } catch (e) { return toast('Terugkijken mislukt') }
  if (!r || !r.stream) return toast('Terugkijken is voor deze zender niet beschikbaar');
  const item = {
    key: 'cu:' + ch.key + ':' + p.start, title: p.title, sub: `${ch.name}  ·  ${dayLabel(p.start)} ${hm(p.start)} – ${hm(p.stop)}`,
    stream: r.stream, transcode: r.transcode, hls: r.hls, catchup: { ch, p }, durMs: p.stop - p.start, offsetMs,
  };
  push({ name: 'vod', items: [item], i: 0, start: 0 });
}
async function seekCatchupTo(t, targetMs) {
  const it = t.items[t.i], pos = (it.offsetMs || 0) + (video.currentTime || 0) * 1000;
  return seekCatchup(t, (targetMs - pos) / MIN);
}
async function seekCatchup(t, deltaMin) {
  const it = t.items[t.i], { ch, p } = it.catchup;
  const pos = (it.offsetMs || 0) + (video.currentTime || 0) * 1000;
  const target = Math.max(0, Math.min(it.durMs - MIN, Math.round((pos + deltaMin * MIN) / MIN) * MIN));
  let r; try { r = await api(`catchup?key=${encodeURIComponent(ch.key)}&start=${p.start + target}&stop=${p.stop}&offset=${S.offset}`) } catch (e) { return }
  if (!r || !r.stream || top_() !== t) return;
  Object.assign(it, { stream: r.stream, transcode: r.transcode, hls: r.hls, offsetMs: target });
  t.playing = null; render(null);
  toast(`${deltaMin > 0 ? '▶▶' : '◀◀'} ${clock(target)}`);
}

// ---------------------------------------------------------------- gids koppelen
// Voor zenders waarbij de gids niet (of niet goed) gevonden wordt: zelf de juiste gidszender kiezen.
function renderEpgLink(t) {
  const ch = t.ch;
  if (t.q == null) t.q = ch.name.replace(/^\W*[A-Za-z]{2}\W+/, '').replace(/\b(4K|UHD|FHD|HD|SD)\b/gi, '').replace(/[-|:]/g, ' ').replace(/\s+/g, ' ').trim();
  tv.innerHTML = `<div class="screen"><h1 style="margin:0 0 4px">Gids koppelen · ${esc(ch.name)}</h1>
   <div class="sec" style="font-size:15px;margin:0 4px 12px">Nu gekoppeld aan: <b style="color:#fff">${ch.epgCid ? esc(ch.epgCid) : 'geen gids'}</b>${ch.linked ? ' (door jou gekozen)' : ' (automatisch)'}. Zoek de juiste gidszender en druk op OK. Let op het land (.nl, .be, .uk) en kijk of het programma van nu klopt.</div>
   <div style="display:flex;gap:12px;align-items:center;margin-bottom:10px" data-region="ltools"><div class="field" style="margin:0;width:340px"><input class="f" data-id="lsearch" value="${esc(t.q)}" placeholder="Zoek gidszender" autocomplete="off" spellcheck="false"></div>
   <span class="btn f" data-id="lauto" style="margin:0">Automatisch</span><span class="btn f" data-id="lnone" style="margin:0">Geen gids</span><span class="btn f sel" data-id="lback" style="margin:0">Terug</span></div>
   <div class="col scroll" data-region="lres" id="lres" style="flex:1"></div></div>`;
  const res = document.getElementById('lres');
  let tok = 0;
  const link = async cid => {
    await post('epg/link', { name: ch.name, cid });
    await loadChannels(); await loadNowNext(); guideReset();
    toast(cid === null ? 'Automatisch gekoppeld' : cid === '' ? 'Geen gids voor deze zender' : 'Gekoppeld aan ' + cid);
    pop();
  };
  const search = async () => {
    const my = ++tok; res.innerHTML = '<div class="sec" style="font-size:15px">Zoeken…</div>';
    await sleep(250); if (my !== tok) return;
    let rows = []; try { rows = await api('epg/search?q=' + encodeURIComponent(t.q || '')) } catch (e) { }
    if (my !== tok || !document.getElementById('lres')) return;
    res.innerHTML = rows.length ? '' : '<div class="sec" style="font-size:15px">Niets gevonden. Probeer een kortere zoekterm, bijv. alleen "Discovery". Na deze update één keer "Gids nu bijwerken" doen, dan kun je ook op zendernaam zoeken.</div>';
    chunked(res, rows, r => `<div class="srow f${r.cid === ch.epgCid ? ' sel' : ''}" data-id="l:${esc(r.cid)}"><b style="flex:1.2">${esc(r.names[0] || r.cid)} <span class="sec small">${esc(r.cid)}</span></b><span>${r.now ? 'Nu: ' + esc(r.now) + ' · ' : ''}${r.count} programma's</span></div>`,
      (el, r) => { el._ok = () => link(r.cid) });
  };
  const si = tv.querySelector('[data-id=lsearch]');
  si.addEventListener('input', () => { t.q = si.value; search() });
  si._ok = () => move('down');
  tv.querySelector('[data-id=lauto]')._ok = () => link(null);
  tv.querySelector('[data-id=lnone]')._ok = () => link('');
  tv.querySelector('[data-id=lback]')._ok = () => pop();
  search();
}

// ---------------------------------------------------------------- gids koppelen: zender kiezen (vanuit Instellingen)
function renderEpgChans(t) {
  const q = (t.q || '').trim().toLowerCase();
  let list = CH.filter(c => !q || c.name.toLowerCase().includes(q) || (c.group || '').toLowerCase().includes(q));
  if (t.missing) list = list.filter(c => !c.epgCid);
  const withGuide = CH.filter(c => c.epgCid).length, own = CH.filter(c => c.linked).length;
  tv.innerHTML = `<div class="screen"><h1 style="margin:0 0 4px">Gids koppelen</h1>
   <div class="sec" style="font-size:15px;margin:0 4px 12px">${withGuide} van ${CH.length} zichtbare zenders hebben een gids${own ? ` · ${own} zelf gekoppeld` : ''}. Kies een zender om zijn gids te controleren of te wijzigen.</div>
   <div style="display:flex;gap:12px;align-items:center;margin-bottom:10px" data-region="ctools"><div class="field" style="margin:0;width:320px"><input class="f" data-id="csearch" value="${esc(t.q || '')}" placeholder="Zoek zender of groep" autocomplete="off" spellcheck="false"></div>
   <span class="btn f${t.missing ? ' sel' : ''}" data-id="cmiss" style="margin:0${t.missing ? ';background:rgba(10,132,255,.4)' : ''}">Alleen zonder gids${t.missing ? ' ✓' : ''}</span><span class="btn f" data-id="cback" style="margin:0">Klaar</span></div>
   <div class="col scroll" data-region="clist" id="clist" style="flex:1"></div></div>`;
  const box = document.getElementById('clist');
  if (!list.length) box.innerHTML = '<div class="sec" style="font-size:15px">Geen zenders gevonden.</div>';
  chunked(box, list, c => `<div class="srow f" data-id="ec:${c.key}" style="padding:9px 16px">${logoHtml(c, 44, 28, 10).replace('margin-right:14px', 'margin-right:4px')}<b style="flex:1.2">${esc(c.name)} <span class="sec small">${esc(c.group)}</span></b><span style="flex:1">${c.epgCid ? esc(c.epgCid) + (c.linked ? ' <span class="sec small">· zelf gekoppeld</span>' : '') : '<span class="err">geen gids</span>'}</span></div>`,
    (el, c) => { el._ok = () => push({ name: 'epglink', ch: c }) });
  const si = tv.querySelector('[data-id=csearch]');
  let tm; si.addEventListener('input', () => { t.q = si.value; clearTimeout(tm); tm = setTimeout(() => { render('csearch') }, 250) });
  si._ok = () => move('down');
  tv.querySelector('[data-id=cmiss]')._ok = () => { t.missing = !t.missing; render('cmiss') };
  tv.querySelector('[data-id=cback]')._ok = () => pop();
}
