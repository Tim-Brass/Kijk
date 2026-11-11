"use strict";
// Zoeken, zappen op zendernummer en "Verder kijken".

// ---------------------------------------------------------------- Verder kijken
function addRecent(kind, obj) {
  const id = kind + ':' + obj.id;
  S.recent = [{ id, kind, obj, ts: Date.now() }, ...(S.recent || []).filter(r => r.id !== id)].slice(0, 40);
  persist();
}
// Films die je half gezien hebt; series waar je mee bezig bent (nieuwste eerst).
function continueItems(series) {
  return (S.recent || []).filter(r => r.kind === (series ? 'show' : 'movie')).filter(r => {
    if (series) return !!S.lastEp[r.obj.id];
    const p = S.progress['m:' + r.obj.id];
    return p && p.pos > 30000 && p.pos < p.dur * 0.95;
  }).map(r => r.obj);
}

// ---------------------------------------------------------------- zappen op zendernummer
let numBuf = '', numTimer = null;
function numKey(d) {
  const t = top_();
  if (t.name === 'pin') return pinKey(t, d);
  if (!['home', 'live'].includes(t.name)) return;
  numBuf = (numBuf + d).slice(0, 5);
  let el = document.getElementById('numov');
  if (!el) { el = document.createElement('div'); el.id = 'numov'; el.className = 'numov'; tv.appendChild(el) }
  const ch = CH.find(c => String(c.number) === numBuf);
  el.innerHTML = `<b>${numBuf}</b><span>${ch ? esc(ch.name) : '…'}</span>`;
  clearTimeout(numTimer);
  numTimer = setTimeout(numCommit, numBuf.length >= 4 ? 300 : 1500);
}
function numCommit() {
  const n = numBuf; numBuf = '';
  const el = document.getElementById('numov'); if (el) el.remove();
  const idx = CH.findIndex(c => String(c.number) === n);
  if (idx < 0) return toast(`Zender ${n} niet gevonden`);
  const ch = CH[idx], t = top_();
  if (t.name === 'live') {
    // in beeld: overschakelen (binnen de huidige lijst als de zender daarin zit)
    const inList = t.list.findIndex(c => c.key === ch.key);
    t.prev = t.i;
    if (inList >= 0) t.i = inList; else { t.list = CH; t.i = idx }
    t.info = true; t.infoMode = 'zap'; t.browse = null; t.bOff = 0; render(null);
    return;
  }
  if (t.name === 'home' && S.tab === 1) {
    const r = GCH.findIndex(c => c.key === ch.key);
    if (r >= 0) { G.row = r; G.sel = null; drawGuide(); const f = document.querySelector(`#grows .gp[data-r="${r}"]`); if (f) { setFocus(f, true); f._onfocus() } return }
  }
  openLive(CH, idx);
}

// ---------------------------------------------------------------- zoeken
const SR = { q: '', res: null, token: 0 };
function renderSearch(body) {
  body.innerHTML = `<div style="flex:1;display:flex;flex-direction:column;min-width:0">
   <div style="display:flex;gap:12px;align-items:center;margin-bottom:10px" data-region="sbar"><div class="field" style="margin:0;width:460px"><input class="f" data-id="sq" value="${esc(SR.q)}" placeholder="Zoek een zender, programma, film of serie" autocomplete="off" spellcheck="false"></div><span class="sec" style="font-size:13px" id="sstat"></span></div>
   <div class="col scroll" data-region="sresults" id="sresults" style="flex:1"></div></div>`;
  const si = body.querySelector('[data-id=sq]');
  let tm; si.addEventListener('input', () => { SR.q = si.value; clearTimeout(tm); tm = setTimeout(runSearch, 350) });
  si._ok = () => move('down');
  if (SR.q) drawSearch(); else document.getElementById('sresults').innerHTML = emptyHtml('Zoeken', 'Typ minstens 2 letters. Kijk zoekt in je zichtbare zenders, de tv-gids (ook terugkijken), films en series.');
  if (SR.q && !SR.res) runSearch();
}
async function runSearch() {
  const tok = ++SR.token, q = SR.q.trim();
  if (q.length < 2) { SR.res = null; const r = document.getElementById('sresults'); if (r) r.innerHTML = emptyHtml('Zoeken', 'Typ minstens 2 letters.'); return }
  const st = document.getElementById('sstat'); if (st) st.textContent = 'Zoeken…';
  let res = { movies: [], shows: [], programmes: [] };
  try { res = await api('search?q=' + encodeURIComponent(q)) } catch (e) { }
  if (tok !== SR.token) return;
  SR.res = res; drawSearch();
}
function drawSearch() {
  const box = document.getElementById('sresults'); if (!box || !SR.res) return;
  const q = SR.q.trim().toLowerCase(), now = Date.now();
  const keyIdx = new Map(CH.map((c, i) => [c.key, i]));
  const chans = CH.filter(c => c.name.toLowerCase().includes(q)).sort((a, b) => (a.name.toLowerCase().startsWith(q) ? 0 : 1) - (b.name.toLowerCase().startsWith(q) ? 0 : 1)).slice(0, 40);
  // tv-programma's alleen op zichtbare zenders; geweest alleen als terugkijken kan
  const progs = (SR.res.programmes || []).filter(p => keyIdx.has(p.key)).filter(p => {
    const ch = CH[keyIdx.get(p.key)];
    return p.stop > now || (ch.archive && p.start > now - ch.archive * DAY);
  }).slice(0, 60);
  const hidM = S.hiddenM, hidS = S.hiddenS;
  const catNameM = id => ((vodCache.mc || []).find(c => c.id === id) || {}).name, catNameS = id => ((vodCache.sc || []).find(c => c.id === id) || {}).name;
  const movies = (SR.res.movies || []).filter(m => !hidM.has(m.cat) && (S.unlocked || !isLocked(catNameM(m.cat)) && !isLocked(m.name)));
  const shows = (SR.res.shows || []).filter(s => !hidS.has(s.cat) && (S.unlocked || !isLocked(catNameS(s.cat)) && !isLocked(s.name)));
  const st = document.getElementById('sstat');
  if (st) st.textContent = `${chans.length} zenders · ${progs.length} programma's · ${movies.length} films · ${shows.length} series`;
  if (!chans.length && !progs.length && !movies.length && !shows.length) { box.innerHTML = emptyHtml('Niets gevonden', 'Probeer een kortere of andere zoekterm.'); return }
  let h = '';
  const sec = (title, n) => `<div class="sect" style="font-size:15px;margin-top:12px">${title} <span class="sec small">(${n})</span></div>`;
  if (chans.length) h += sec('Zenders', chans.length) + chans.map(c => `<div class="srow f" data-id="sc:${c.key}" style="padding:9px 16px">${logoHtml(c, 44, 28, 10).replace('margin-right:14px', 'margin-right:4px')}<b style="flex:1.2">${esc(c.name)}${S.favs.has(c.key) ? ' <span class="star">★</span>' : ''}</b><span style="flex:1">${(liveNow(c).cur || {}).title ? 'Nu: ' + esc(liveNow(c).cur.title) : esc(c.group)}</span></div>`).join('');
  if (progs.length) h += sec('Op tv', progs.length) + progs.map((p, i) => {
    const ch = CH[keyIdx.get(p.key)], live = p.start <= now && p.stop > now, past = p.stop <= now;
    return `<div class="srow f" data-id="sp:${i}" style="padding:9px 16px"><b style="flex:1.4">${live ? '<span class="live">NU</span> ' : past ? '<span class="arch">↺</span> ' : ''}${esc(p.title)}</b><span style="flex:1.2">${esc(ch.name)} · ${dayLabel(p.start)} ${hm(p.start)}</span></div>`;
  }).join('');
  if (movies.length) h += sec('Films', movies.length) + movies.map(m => `<div class="srow f" data-id="sm:${esc(m.id)}" style="padding:9px 16px"><b style="flex:2">${esc(m.name)}</b><span style="flex:1">${[m.year, m.rating && '★ ' + m.rating].filter(Boolean).join(' · ')}</span></div>`).join('');
  if (shows.length) h += sec('Series', shows.length) + shows.map(s => `<div class="srow f" data-id="ss:${esc(s.id)}" style="padding:9px 16px"><b style="flex:2">${esc(s.name)}</b><span style="flex:1">${[s.year, s.rating && '★ ' + s.rating].filter(Boolean).join(' · ')}</span></div>`).join('');
  box.innerHTML = h;
  box.querySelectorAll('.srow').forEach(el => {
    const [kind, ...rest] = el.dataset.id.split(':'); const id = rest.join(':');
    if (kind === 'sc') { const i = keyIdx.get(id); el._ok = () => openLive(CH, i); el._long = () => toggleFav(CH[i]) }
    if (kind === 'sp') {
      const p = progs[+id], ch = CH[keyIdx.get(p.key)];
      el._ok = () => {
        const n = Date.now();
        if (p.start <= n && p.stop > n) return openLive(CH, keyIdx.get(p.key));
        if (p.stop <= n) return openCatchup(ch, p);
        toast(`Begint ${dayLabel(p.start).toLowerCase()} om ${hm(p.start)} op ${ch.name}`);
      };
    }
    if (kind === 'sm') { const m = movies.find(x => x.id === id); el._ok = () => push({ name: 'movie', movie: m }) }
    if (kind === 'ss') { const s = shows.find(x => x.id === id); el._ok = () => push({ name: 'show', show: s }) }
  });
}
