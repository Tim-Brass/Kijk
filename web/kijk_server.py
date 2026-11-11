#!/usr/bin/env python3
"""
Kijk – webversie. Start met:  python kijk_server.py
Open daarna http://localhost:8765 in Chrome of Edge.

Dit programma draait alleen op je eigen computer. Het haalt je playlist, de gids
en de streams op en geeft ze door aan de browser (die mag IPTV-servers niet
rechtstreeks benaderen). Alleen de Python-standaardbibliotheek is nodig.
"""
import base64
import gzip
from concurrent.futures import ThreadPoolExecutor
import hashlib
import socket
import io
import json
import os
import re
import shutil
import subprocess
import sqlite3
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import xml.etree.ElementTree as ET
from calendar import timegm
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("KIJK_PORT", "8765"))
HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")
DATA = os.path.join(HERE, "data")
os.makedirs(DATA, exist_ok=True)
CONFIG_FILE = os.path.join(DATA, "config.json")
IMG_DIR = os.path.join(DATA, "logos")
os.makedirs(IMG_DIR, exist_ok=True)
IMG_SEM = threading.Semaphore(6)
EPG_DB = os.path.join(DATA, "epg.sqlite")
USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
MIN, HOUR, DAY = 60_000, 3_600_000, 86_400_000


def find_ffmpeg():
    """ffmpeg in het PATH, of naast dit programma (ffmpeg.exe of ffmpeg/bin/ffmpeg.exe)."""
    for c in ("ffmpeg.exe", "ffmpeg", os.path.join("ffmpeg", "bin", "ffmpeg.exe"), os.path.join("ffmpeg", "bin", "ffmpeg")):
        p = os.path.join(HERE, c)
        if os.path.isfile(p):
            return p
    return shutil.which("ffmpeg")


FFMPEG = find_ffmpeg()


def now_ms():
    return int(time.time() * 1000)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------ netwerk

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_opener = urllib.request.build_opener(NoRedirect)


def http_open(url, headers=None, timeout=20):
    """Opent een URL en volgt zelf doorverwijzingen (ook http <-> https). Geeft (response, eind-URL)."""
    for _ in range(8):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
        try:
            resp = _opener.open(req, timeout=timeout)
            return resp, url
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                url = urllib.parse.urljoin(url, e.headers["Location"])
                continue
            raise
    raise IOError("Te veel doorverwijzingen")


def fetch_stream(url, progress=None):
    """Bestand-achtig object, automatisch uitgepakt als het gzip is. progress(gedaan, totaal) in bytes."""
    resp, _ = http_open(url, timeout=60)
    total = _int(resp.headers.get("Content-Length"))
    head = resp.read(2)
    rest = io.BufferedReader(_Prefixed(head, resp, progress, total), 1 << 16)
    if head == b"\x1f\x8b":
        return gzip.GzipFile(fileobj=rest)
    return rest


class _Prefixed(io.RawIOBase):
    def __init__(self, prefix, f, progress=None, total=0):
        self.prefix, self.f, self.progress, self.total, self.done = prefix, f, progress, total, len(prefix)

    def readable(self):
        return True

    def readinto(self, b):
        if self.prefix:
            n = min(len(b), len(self.prefix))
            b[:n] = self.prefix[:n]
            self.prefix = self.prefix[n:]
            return n
        data = self.f.read(len(b))
        b[: len(data)] = data
        self.done += len(data)
        if self.progress:
            self.progress(self.done, self.total)
        return len(data)


def fetch_text(url, progress=None):
    with fetch_stream(url, progress) as f:
        return f.read().decode("utf-8", "replace")


# ------------------------------------------------------------------ M3U

ATTR = re.compile(r'([\w-]+)="([^"]*)"')
VOD_EXT = (".mp4", ".mkv", ".avi", ".mov", ".m4v")


def vod_kind(url):
    u = url.lower().split("?")[0]
    if "/series/" in u:
        return "series"
    if "/movie/" in u or u.endswith(VOD_EXT):
        return "movie"
    return None


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def parse_m3u(text, keep_vod=True):
    channels, vod, epg_url, info, extgrp = [], [], None, None, None
    seen = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTM3U"):
            a = dict((k.lower(), v) for k, v in ATTR.findall(line))
            epg_url = a.get("url-tvg") or a.get("x-tvg-url") or a.get("tvg-url") or None
        elif line.startswith("#EXTINF"):
            info = line
        elif line.startswith("#EXTGRP:"):
            extgrp = line.split(":", 1)[1].strip()
        elif line.startswith("#"):
            continue
        else:
            url, inf, grp0 = line, info, extgrp
            info = extgrp = None
            kind = vod_kind(url)
            if kind and not keep_vod:
                continue
            a = dict((k.lower(), v) for k, v in ATTR.findall(inf or ""))
            after = inf[inf.rfind('"') + 1:] if inf and '"' in inf else (inf or "")
            name = after.split(",", 1)[1].strip() if "," in after else ""
            name = name or a.get("tvg-name") or url.rsplit("/", 1)[-1]
            logo = a.get("tvg-logo") or None
            group = a.get("group-title") or grp0 or "Overig"
            if kind:
                vod.append({"name": name, "logo": logo, "group": group, "url": url, "series": kind == "series"})
                continue
            if url in seen:
                continue
            seen.add(url)
            try:
                number = int(a.get("tvg-chno", ""))
            except ValueError:
                number = len(channels) + 1
            channels.append({
                # vaste sleutel op basis van het stream-adres, zodat favorieten kloppen als de volgorde verandert
                "key": "z" + hashlib.sha1(url.encode()).hexdigest()[:12], "oldKey": "c%d" % len(channels), "number": number, "name": name, "logo": logo, "group": group,
                "tvgId": a.get("tvg-id") or None, "tvgName": a.get("tvg-name") or None, "url": url,
                "catchup": (a.get("catchup") or a.get("catchup-type") or ("default" if a.get("catchup-source") else None)),
                "catchupSource": a.get("catchup-source") or None,
                "catchupDays": _int(a.get("catchup-days") or a.get("tvg-rec") or a.get("timeshift")),
            })
    return channels, vod, epg_url


# ------------------------------------------------------------------ gids (XMLTV)

NOISE = {"hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265", "raw", "backup", "nl", "be", "vl", "tv",
         "uk", "gb", "de", "fr", "es", "pt", "tr", "pl", "se", "dk", "fi", "ch", "ca", "au", "ie", "gr", "ro", "ru"}


COUNTRIES = {"nl", "be", "uk", "gb", "de", "fr", "us", "es", "it", "pt", "tr", "pl", "se", "no", "dk", "fi", "ch", "at",
             "ca", "au", "ie", "gr", "ro", "ru", "al", "ar", "in", "pk", "br", "mx", "hu", "cz", "sk", "hr", "rs", "bg", "ex"}


def _cc(c):
    c = c.lower()
    return "uk" if c == "gb" else c if c in COUNTRIES else None


def country_of(name):
    """Land uit een naam als "NL: NPO 1", "NL | RTL 4", "NL 4K-Discovery", "|NL| ..." of een id als "Discovery.nl"."""
    if not name:
        return None
    m = re.match(r"^\W*([A-Za-z]{2})(?:\W|$)", name.strip())
    if m and _cc(m.group(1)):
        return _cc(m.group(1))
    m = re.search(r"\.([A-Za-z]{2})$", name.strip())
    return _cc(m.group(1)) if m else None


def normalize(s):
    s = re.sub(r"[(\[{].*?[)\]}]", " ", s.lower())
    s = re.sub(r"^\s*[a-z]{2,3}\s*[:|]\s*", " ", s)
    toks = [t for t in re.split(r"[^\w+]+", s) if t and t not in NOISE and t != "_"]
    return "".join(toks) or re.sub(r"\W", "", s)


def parse_time(s):
    if not s or len(s) < 12:
        return None
    try:
        t = timegm((int(s[0:4]), int(s[4:6]), int(s[6:8]), int(s[8:10]), int(s[10:12]),
                    int(s[12:14]) if len(s) >= 14 and s[12].isdigit() else 0, 0, 0, 0)) * 1000
        tz = s[14:].strip()
        if len(tz) >= 5 and tz[0] in "+-":
            off = (int(tz[1:3]) * 60 + int(tz[3:5])) * MIN
            t = t - off if tz[0] == "+" else t + off
        return t
    except ValueError:
        return None


def dedupe(progs):
    """Veel gidsen bevatten hetzelfde programma twee keer, met een paar minuten verschil.
    Houd per tijdvak één programma over (bij twijfel het programma met een beschrijving)."""
    out = []
    for p in sorted(progs, key=lambda p: (p["start"], -(len(p.get("desc") or "")))):
        if out and p["start"] < out[-1]["stop"] - 2 * MIN:
            last = out[-1]
            same = last["title"].strip().lower() == p["title"].strip().lower()
            if same or p["stop"] <= last["stop"] + 2 * MIN:
                if not last.get("desc") and p.get("desc"):
                    last["desc"] = p["desc"]
                continue
            # verschillend programma dat later eindigt: laat het na het vorige beginnen
            p = {**p, "start": last["stop"]}
        out.append(p)
    return out


def dedupe_simple(progs):
    seen, out = set(), []
    for p in progs:
        k = (p["cid"], p["start"] // (5 * MIN), p["title"].lower())
        if k not in seen:
            seen.add(k)
            out.append(p)
    return out


class Epg:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        db = self.db()
        db.executescript("""
            CREATE TABLE IF NOT EXISTS prog (cid TEXT, start INTEGER, stop INTEGER, title TEXT, descr TEXT);
            CREATE INDEX IF NOT EXISTS prog_cid ON prog (cid, start);
            CREATE TABLE IF NOT EXISTS chan (cid TEXT, name TEXT);
            CREATE TABLE IF NOT EXISTS disp (cid TEXT, name TEXT);
            CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
        """)
        db.commit()
        db.close()

    def db(self):
        c = sqlite3.connect(self.path, timeout=60)
        c.execute("PRAGMA journal_mode=WAL")
        return c

    def refresh(self, urls, progress_cb):
        def progress(msg, done=None, total=None):
            progress_cb(msg, done, total)
        now = now_ms()
        keep_from, keep_until = now - 8 * DAY, now + 8 * DAY
        with self.lock:
            db = self.db()
            try:
                db.execute("BEGIN")
                db.execute("DELETE FROM prog")
                db.execute("DELETE FROM chan")
                db.execute("DELETE FROM disp")
                count = 0
                for i, url in enumerate(urls):
                    progress("Gids %s downloaden…" % ("%d van %d " % (i + 1, len(urls)) if len(urls) > 1 else ""))
                    with fetch_stream(url, lambda d, t: progress(None, d, t)) as f:
                        batch = []
                        for _, el in ET.iterparse(f, events=("end",)):
                            tag = el.tag
                            if tag == "channel":
                                cid = el.get("id")
                                if cid:
                                    names = [cid] + [d.text or "" for d in el.findall("display-name")]
                                    db.executemany("INSERT INTO chan VALUES (?, ?)",
                                                   [(cid, normalize(n)) for n in names if n.strip()])
                                    db.executemany("INSERT INTO disp VALUES (?, ?)",
                                                   [(cid, n.strip()) for n in names[1:] if n.strip()])
                                el.clear()
                            elif tag == "programme":
                                cid = el.get("channel")
                                st = parse_time(el.get("start"))
                                sp = parse_time(el.get("stop")) or (st + 30 * MIN if st else None)
                                title = el.findtext("title")
                                if cid and st and title and sp > keep_from and st < keep_until:
                                    batch.append((cid, st, sp, title.strip(), (el.findtext("desc") or "").strip() or None))
                                    count += 1
                                    if len(batch) >= 5000:
                                        db.executemany("INSERT INTO prog VALUES (?, ?, ?, ?, ?)", batch)
                                        batch.clear()
                                        progress("Gids verwerken… %d programma's" % count)
                                el.clear()
                        if batch:
                            db.executemany("INSERT INTO prog VALUES (?, ?, ?, ?, ?)", batch)
                db.execute("INSERT OR REPLACE INTO meta VALUES ('updated', ?)", (str(now_ms()),))
                db.commit()
                return count
            except Exception:
                db.rollback()
                raise
            finally:
                db.close()

    def updated(self):
        db = self.db()
        try:
            r = db.execute("SELECT v FROM meta WHERE k='updated'").fetchone()
            return int(r[0]) if r else 0
        finally:
            db.close()

    def stats(self):
        db = self.db()
        try:
            return db.execute("SELECT COUNT(*), COUNT(DISTINCT cid) FROM prog").fetchone()
        finally:
            db.close()

    def match(self, channels, links=None):
        """Koppelt zenders aan de gids. Volgorde: eigen koppeling > tvg-id > naam (met hetzelfde land)."""
        links = links or {}
        db = self.db()
        try:
            ids = {r[0] for r in db.execute("SELECT DISTINCT cid FROM prog")}
            lower = {i.lower(): i for i in ids}
            by_name = {}
            for cid, name in db.execute("SELECT cid, name FROM chan"):
                if cid in ids:
                    lst = by_name.setdefault(name, [])
                    if cid not in lst:
                        lst.append(cid)
            disp_country = {}
            for cid, name in db.execute("SELECT cid, name FROM disp"):
                c = country_of(name)
                if c:
                    disp_country.setdefault(cid, c)
        finally:
            db.close()

        def cid_country(cid):
            m = re.search(r"\.([a-z]{2})$", cid.lower())
            return _cc(m.group(1)) if m else disp_country.get(cid) or country_of(cid)

        def by_name_for(name, want):
            cands = by_name.get(normalize(name)) or []
            if not cands:
                return None
            if want:
                same = [c for c in cands if cid_country(c) == want]
                if same:
                    return same[0]
                neutral = [c for c in cands if not cid_country(c)]
                return neutral[0] if neutral else None  # liever geen gids dan die van een ander land
            neutral = [c for c in cands if not cid_country(c)]
            return (neutral or cands)[0]

        out = {}
        for ch in channels:
            if ch["name"] in links:
                if links[ch["name"]] == "":
                    continue  # bewust geen gids
                if links[ch["name"]] in ids:
                    out[ch["key"]] = links[ch["name"]]
                    continue
                # gekozen gidszender bestaat niet meer: automatisch koppelen
            want = country_of(ch["name"]) or country_of(ch.get("group") or "")
            t = ch.get("tvgId")
            cid = (t if t in ids else lower.get(t.lower())) if t else None
            cid = cid or (by_name_for(t, want) if t else None) or by_name_for(ch["name"], want) \
                or (by_name_for(ch["tvgName"], want) if ch.get("tvgName") else None)
            if cid:
                out[ch["key"]] = cid
        return out

    def find(self, q, frm, to, limit=400):
        db = self.db()
        try:
            return [{"cid": c, "start": a, "stop": b, "title": t, "desc": d} for c, a, b, t, d in db.execute(
                "SELECT cid, start, stop, title, descr FROM prog WHERE title LIKE ? AND stop > ? AND start < ? ORDER BY start LIMIT ?",
                ("%" + q + "%", frm, to, limit))]
        finally:
            db.close()

    def earliest(self, cid):
        db = self.db()
        try:
            r = db.execute("SELECT MIN(start) FROM prog WHERE cid = ?", (cid,)).fetchone()
            return r[0] if r else None
        finally:
            db.close()

    def search(self, q, limit=60):
        """Gidszenders zoeken op naam of id, voor handmatig koppelen."""
        db = self.db()
        try:
            like = "%" + q.strip() + "%"
            rows = db.execute("SELECT DISTINCT cid FROM disp WHERE name LIKE ? UNION SELECT DISTINCT cid FROM prog WHERE cid LIKE ? LIMIT ?",
                              (like, like, limit)).fetchall()
            out = []
            for (cid,) in rows:
                names = [r[0] for r in db.execute("SELECT DISTINCT name FROM disp WHERE cid = ? LIMIT 3", (cid,))]
                n, = db.execute("SELECT COUNT(*) FROM prog WHERE cid = ?", (cid,)).fetchone()
                now_t = db.execute("SELECT title FROM prog WHERE cid = ? AND start <= ? AND stop > ? LIMIT 1", (cid, now_ms(), now_ms())).fetchone()
                out.append({"cid": cid, "names": names, "count": n, "now": now_t[0] if now_t else None})
            out.sort(key=lambda r: -r["count"])
            return out
        finally:
            db.close()

    def now_next(self, at):
        db = self.db()
        raw = {}
        try:
            for cid, st, sp, title, desc in db.execute(
                    "SELECT cid, start, stop, title, descr FROM prog WHERE stop > ? AND start < ? ORDER BY cid, start",
                    (at - 3 * HOUR, at + 6 * HOUR)):
                raw.setdefault(cid, []).append({"start": st, "stop": sp, "title": title, "desc": desc})
        finally:
            db.close()
        return {cid: [p for p in dedupe(lst) if p["stop"] > at][:2] for cid, lst in raw.items()}

    def programmes(self, cid, frm, to):
        db = self.db()
        try:
            return dedupe([{"start": a, "stop": b, "title": c, "desc": d} for a, b, c, d in db.execute(
                "SELECT start, stop, title, descr FROM prog WHERE cid = ? AND stop > ? AND start < ? ORDER BY start",
                (cid, frm - 3 * HOUR, to))])
        finally:
            db.close()


# ------------------------------------------------------------------ films en series

class Xtream:
    def __init__(self, host, user, pw):
        self.host, self.user, self.pw = host, user, pw

    @staticmethod
    def from_playlist(url):
        if "get.php" not in (url or ""):
            return None
        u = urllib.parse.urlparse(url)
        q = urllib.parse.parse_qs(u.query)
        if "username" not in q or "password" not in q:
            return None
        return Xtream("%s://%s" % (u.scheme, u.netloc), q["username"][0], q["password"][0])

    def archive_info(self):
        """{stream_id: dagen terugkijken} en het tijdsverschil van de server (in seconden t.o.v. UTC)."""
        days = {}
        for o in self.api("get_live_streams") or []:
            if isinstance(o, dict) and str(o.get("tv_archive")) == "1":
                days[str(o.get("stream_id"))] = _int(o.get("tv_archive_duration")) or 1
        offset = 0
        try:
            info = (self.api("") or {}).get("server_info") or {}
            ts = _int(info.get("timestamp_now"))
            local = time.strptime(info.get("time_now", ""), "%Y-%m-%d %H:%M:%S")
            if ts:
                offset = round((timegm(local) - ts) / 900) * 900
        except (ValueError, AttributeError):
            pass
        return days, offset

    def archive_epg(self, stream_id):
        """Volledige terugkijkgids van één zender (vaak 7 dagen, ook als de XMLTV-gids maar 1 dag terug gaat)."""
        data = self.api("get_simple_data_table", stream_id=stream_id) or {}
        out = []
        for e in (data.get("epg_listings") or []) if isinstance(data, dict) else []:
            def dec(v):
                try:
                    return base64.b64decode(v or "").decode("utf-8", "replace").strip()
                except Exception:
                    return (v or "").strip()
            st, sp = _int(e.get("start_timestamp")) * 1000, _int(e.get("stop_timestamp")) * 1000
            title = dec(e.get("title"))
            if st and sp > st and title:
                out.append({"start": st, "stop": sp, "title": title, "desc": dec(e.get("description")) or None})
        return out

    def timeshift_url(self, stream_id, start_ms, minutes, offset):
        t = time.gmtime(start_ms / 1000 + offset)
        return "%s/timeshift/%s/%s/%d/%s/%s.ts" % (self.host, self._u(self.user), self._u(self.pw), minutes,
                                                 time.strftime("%Y-%m-%d:%H-%M", t), stream_id)

    def api(self, action, **extra):
        params = {"username": self.user, "password": self.pw, **({"action": action} if action else {}), **extra}
        txt = fetch_text("%s/player_api.php?%s" % (self.host, urllib.parse.urlencode(params)))
        try:
            return json.loads(txt)
        except ValueError:
            return None

    def _cats(self, action):
        data = self.api(action) or []
        return [{"id": str(c.get("category_id")), "name": c.get("category_name") or "Overig"}
                for c in data if isinstance(c, dict) and c.get("category_id") is not None]

    def movie_cats(self):
        return self._cats("get_vod_categories")

    def show_cats(self):
        return self._cats("get_series_categories")

    def _u(self, s):
        return urllib.parse.quote(s, safe="")

    def movies(self, cat):
        data = (self.api("get_vod_streams", category_id=cat) if cat is not None else self.api("get_vod_streams")) or []
        out = []
        for o in data:
            if not isinstance(o, dict) or o.get("stream_id") is None:
                continue
            sid, ext = o["stream_id"], o.get("container_extension") or "mp4"
            out.append({"id": str(sid), "name": o.get("name") or "Film", "poster": o.get("stream_icon") or None,
                        "url": "%s/movie/%s/%s/%s.%s" % (self.host, self._u(self.user), self._u(self.pw), sid, ext),
                        "rating": (str(o.get("rating")) if o.get("rating") not in (None, "", 0, "0") else None),
                        "year": o.get("year") or None, "cat": str(o.get("category_id") if cat is None else cat)})
        return out

    def movie_info(self, mid):
        root = self.api("get_vod_info", vod_id=mid) or {}
        info = root.get("info") if isinstance(root, dict) else None
        if not isinstance(info, dict):
            return None
        bd = info.get("backdrop_path")
        bd = bd[0] if isinstance(bd, list) and bd else None
        return {"plot": info.get("plot") or info.get("description"), "genre": info.get("genre"),
                "duration": info.get("duration"), "cast": info.get("cast") or info.get("actors"),
                "director": info.get("director"), "backdrop": bd or info.get("movie_image"),
                "year": (info.get("releasedate") or "")[:4] or None,
                "tmdb": str(info.get("tmdb_id") or info.get("tmdb") or "") or None,
                "imdb": str(info.get("imdb_id") or "") or None}

    def shows(self, cat):
        data = (self.api("get_series", category_id=cat) if cat is not None else self.api("get_series")) or []
        return [{"id": str(o["series_id"]), "name": o.get("name") or "Serie", "poster": o.get("cover") or None,
                 "plot": o.get("plot"), "rating": (str(o.get("rating")) if o.get("rating") not in (None, "", 0, "0") else None),
                 "year": (o.get("releaseDate") or o.get("release_date") or "")[:4] or None,
                 "cat": str(o.get("category_id") if cat is None else cat)}
                for o in data if isinstance(o, dict) and o.get("series_id") is not None]

    def show(self, sid):
        root = self.api("get_series_info", series_id=sid) or {}
        info = root.get("info") or {}
        eps = []
        by = root.get("episodes") or {}
        if isinstance(by, dict):
            for k, arr in by.items():
                for i, e in enumerate(arr or []):
                    if not isinstance(e, dict) or e.get("id") is None:
                        continue
                    ei = e.get("info") if isinstance(e.get("info"), dict) else {}
                    ext = e.get("container_extension") or "mp4"
                    try:
                        season = int(e.get("season") or k)
                    except ValueError:
                        season = 1
                    try:
                        num = int(e.get("episode_num") or i + 1)
                    except ValueError:
                        num = i + 1
                    eps.append({"key": "x%s" % e["id"], "season": season, "number": num,
                                "title": e.get("title") or "Aflevering %d" % num,
                                "url": "%s/series/%s/%s/%s.%s" % (self.host, self._u(self.user), self._u(self.pw), e["id"], ext),
                                "plot": ei.get("plot"), "duration": ei.get("duration"), "still": ei.get("movie_image")})
        eps.sort(key=lambda e: (e["season"], e["number"]))
        bd = info.get("backdrop_path")
        return {"plot": info.get("plot"), "backdrop": bd[0] if isinstance(bd, list) and bd else None, "episodes": eps,
                "tmdb": str(info.get("tmdb") or info.get("tmdb_id") or "") or None}


EP_RE = re.compile(r"^(.*?)[\s._-]*[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})(.*)$")


class M3uVod:
    def __init__(self, entries):
        self.movies_ = [e for e in entries if not e["series"]]
        self.eps = []
        for e in entries:
            if not e["series"]:
                continue
            m = EP_RE.match(e["name"])
            if m:
                self.eps.append((m.group(1).strip() or e["name"], e["group"], int(m.group(2)), int(m.group(3)), e,
                                 m.group(4).strip(" -.:")))
            else:
                self.eps.append((e["group"], e["group"], 1, 0, e, e["name"]))

    def movie_cats(self):
        return [{"id": g, "name": g} for g in dict.fromkeys(e["group"] for e in self.movies_)]

    def show_cats(self):
        return [{"id": g, "name": g} for g in dict.fromkeys(e[1] for e in self.eps)]

    def movies(self, cat):
        out = []
        for e in self.movies_:
            if cat is None or e["group"] == cat:
                y = re.search(r"\((\d{4})\)", e["name"])
                out.append({"id": e["url"], "name": e["name"], "poster": e["logo"], "url": e["url"], "rating": None,
                            "year": y.group(1) if y else None, "cat": e["group"]})
        return out

    def movie_info(self, mid):
        return None

    def shows(self, cat):
        seen = {}
        for show, grp, *_rest in self.eps:
            if (cat is None or grp == cat) and (grp, show) not in seen:
                logo = next((x[4]["logo"] for x in self.eps if x[0] == show and x[1] == grp and x[4]["logo"]), None)
                seen[(grp, show)] = {"id": "%s|%s" % (grp, show), "name": show, "poster": logo, "plot": None, "rating": None, "year": None, "cat": grp}
        return list(seen.values())

    def show(self, sid):
        eps = []
        for n, (show, grp, s, num, e, rest) in enumerate(x for x in self.eps if "%s|%s" % (x[1], x[0]) == sid):
            nr = num if num > 0 else n + 1
            eps.append({"key": e["url"], "season": s, "number": nr, "title": rest or "Aflevering %d" % nr,
                        "url": e["url"], "plot": None, "duration": None, "still": e["logo"]})
        eps.sort(key=lambda e: (e["season"], e["number"]))
        return {"plot": None, "backdrop": None, "episodes": eps}


# ------------------------------------------------------------------ OpenSubtitles

OS_API = os.environ.get("KIJK_OS_API", "https://api.opensubtitles.com/api/v1")
SUB_DIR = os.path.join(DATA, "subs")
os.makedirs(SUB_DIR, exist_ok=True)


class OpenSubtitles:
    """Ondertitels zoeken en downloaden via opensubtitles.com (API-sleutel + account van de gebruiker)."""

    def __init__(self, app):
        self.app, self.token, self.token_time, self.base = app, None, 0, OS_API
        self.remaining = None

    def cfg(self):
        c = self.app.cfg
        return c.get("os_key", ""), c.get("os_user", ""), c.get("os_pass", "")

    def _req(self, method, path, body=None, auth=False, base=None):
        key = self.cfg()[0]
        if not key:
            raise IOError("Geen OpenSubtitles API-sleutel ingesteld (Instellingen › Ondertitels)")
        headers = {"Api-Key": key, "User-Agent": "Kijk v0.1.0", "Accept": "application/json", "Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.login()
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request((base or self.base) + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode("utf-8", "replace") or "{}")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            msg = None
            try:
                j = json.loads(raw)
                msg = j.get("message") or j.get("error") or (j.get("errors") or [None])[0]
            except Exception:
                if "cloudflare" in raw.lower() or e.headers.get("cf-ray"):
                    msg = "geblokkeerd door de beveiliging van OpenSubtitles (Cloudflare). Gebruik je een VPN? Probeer het zonder."
            detail = "server=%s cf-ray=%s body=%s" % (e.headers.get("server"), e.headers.get("cf-ray"), re.sub(r"\s+", " ", raw[:160]))
            log("OpenSubtitles %s %s → %d %s | %s" % (method, path.split("?")[0], e.code, msg or "", detail))
            if not msg:
                msg = "geen uitleg van OpenSubtitles (%s)" % detail
            if e.code == 401:
                self.token = None
            err = IOError("OpenSubtitles gaf fout %d%s" % (e.code, ": " + str(msg) if msg else ""))
            err.code = e.code
            raise err

    def login(self):
        if self.token and time.time() - self.token_time < 20 * 3600:
            return self.token
        key, user, pw = self.cfg()
        if not user or not pw:
            raise IOError("Vul je OpenSubtitles-gebruikersnaam en wachtwoord in (Instellingen › Ondertitels)")
        r = self._req("POST", "/login", {"username": user, "password": pw}, base=OS_API)
        self.token, self.token_time = r.get("token"), time.time()
        if r.get("base_url"):
            b = r["base_url"]
            self.base = (b if b.startswith("http") else "https://" + b).rstrip("/") + ("" if b.rstrip("/").endswith("/api/v1") else "/api/v1")
        user_info = r.get("user") or {}
        self.remaining = user_info.get("allowed_downloads")
        return self.token

    def test(self):
        """Stap 1: API-sleutel (zonder inloggen). Stap 2: inloggen met gebruikersnaam en wachtwoord."""
        self.token = None
        self.base = OS_API
        try:
            self._req("GET", "/infos/formats")
        except IOError as e:
            return {"ok": False, "error": "Stap 1 (API-sleutel) mislukt: %s. Controleer de API-sleutel op opensubtitles.com › profiel › API consumers." % e}
        if not self.cfg()[1] or not self.cfg()[2]:
            return {"ok": True, "level": "alleen API-sleutel", "remaining": None, "allowed": None,
                    "note": "API-sleutel werkt. Vul ook gebruikersnaam en wachtwoord in voor meer downloads per dag."}
        try:
            self.login()
        except IOError as e:
            return {"ok": False, "keyOk": True, "error": "API-sleutel werkt, maar stap 2 (inloggen) mislukt: %s. Gebruik je gebruikersnaam (niet je e-mailadres) en het wachtwoord van opensubtitles.com (niet .org)." % e}
        try:
            info = self._req("GET", "/infos/user", auth=True).get("data") or {}
        except IOError:
            info = {}
        self.remaining = info.get("remaining_downloads", self.remaining)
        return {"ok": True, "level": info.get("level"), "remaining": self.remaining, "allowed": info.get("allowed_downloads")}

    def search(self, q):
        params = {"languages": q.get("lang") or "nl"}
        for k_in, k_out in (("tmdb", "tmdb_id"), ("imdb", "imdb_id"), ("parent_tmdb", "parent_tmdb_id"),
                            ("season", "season_number"), ("episode", "episode_number"), ("year", "year")):
            if q.get(k_in):
                params[k_out] = str(q[k_in]).replace("tt", "") if k_in == "imdb" else q[k_in]
        if not any(k in params for k in ("tmdb_id", "imdb_id", "parent_tmdb_id")) and q.get("query"):
            params["query"] = q["query"]
        elif q.get("query") and q.get("season"):
            params.setdefault("query", q["query"])
        r = self._req("GET", "/subtitles?" + urllib.parse.urlencode(sorted(params.items())))
        out = []
        for d in r.get("data") or []:
            a = d.get("attributes") or {}
            files = a.get("files") or []
            if not files:
                continue
            out.append({"file_id": files[0].get("file_id"), "release": a.get("release") or files[0].get("file_name") or "",
                        "lang": a.get("language"), "downloads": a.get("download_count", 0),
                        "hi": bool(a.get("hearing_impaired")), "ai": bool(a.get("ai_translated") or a.get("machine_translated")),
                        "cached": os.path.isfile(os.path.join(SUB_DIR, "%s.vtt" % files[0].get("file_id")))})
        # beste eerst: eerder gedownload, door mensen vertaald, veel gedownload
        out.sort(key=lambda x: (not x["cached"], x["ai"], -(x["downloads"] or 0)))
        return out

    def get(self, file_id):
        path = os.path.join(SUB_DIR, "%s.vtt" % int(file_id))
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                return f.read()
        try:
            r = self._req("POST", "/download", {"file_id": int(file_id), "sub_format": "webvtt"}, auth=bool(self.cfg()[1] and self.cfg()[2]))
        except IOError as e:
            if getattr(e, "code", 0) in (401, 403) and self.cfg()[1]:
                log("Downloaden met account mislukt, opnieuw met alleen de API-sleutel")
                self.token = None
                r = self._req("POST", "/download", {"file_id": int(file_id), "sub_format": "webvtt"}, base=OS_API)
            else:
                raise
        self.remaining = r.get("remaining", self.remaining)
        link = r.get("link")
        if not link:
            raise IOError(r.get("message") or "Geen downloadlink ontvangen")
        resp, _ = http_open(link, timeout=30)
        raw = resp.read()
        text = None
        for enc in ("utf-8", "cp1252", "latin-1"):
            try:
                text = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if not text.lstrip("\ufeff").startswith("WEBVTT"):
            text = srt_to_vtt(text)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        log("Ondertitel gedownload (nog %s downloads vandaag)" % self.remaining)
        return text


def srt_to_vtt(text):
    text = text.replace("\r\n", "\n").lstrip("\ufeff")
    text = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text)
    return "WEBVTT\n\n" + text


# ------------------------------------------------------------------ toestand

class App:
    def __init__(self):
        self.cfg = {"playlist_url": "", "epg_url": ""}
        try:
            with open(CONFIG_FILE, encoding="utf-8") as f:
                self.cfg.update(json.load(f))
        except (OSError, ValueError):
            pass
        self.epg = Epg(EPG_DB)
        self.channels, self.vod, self.playlist_epg = [], None, None
        self.match = {}
        self.server_offset = 0
        self.by_key = {}
        self.arch_cache = {}
        self.load_lock = threading.Lock()
        self.subs = OpenSubtitles(self)
        self.status = {"progress": None, "playlist": None, "playlistError": None, "epg": None, "epgBusy": False}
        self.cache = {}
        self.lock = threading.Lock()
        if self.cfg["playlist_url"]:
            threading.Thread(target=self.load, kwargs={"startup": True}, daemon=True).start()
        threading.Thread(target=self.auto_refresh_loop, daemon=True).start()

    def save_cfg(self):
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(self.cfg, f, indent=2)

    # ---- automatisch vernieuwen ----
    def _day(self, t):
        return time.strftime("%Y%m%d", time.localtime(t / 1000)) if t else ""

    def playlist_due(self, startup=False):
        policy = self.cfg.get("refresh_playlist", "daily")
        last = self.cfg.get("playlist_updated", 0)
        if not os.path.isfile(os.path.join(DATA, "playlist.m3u")) or not last:
            return True
        if policy == "start":
            return startup
        if policy == "daily":
            return self._day(last) != self._day(now_ms())
        if policy == "weekly":
            return now_ms() - last > 7 * DAY
        return False  # handmatig

    def epg_due(self, startup=False):
        policy = self.cfg.get("refresh_epg", "twice")
        last = self.epg.updated()
        if not last:
            return True
        if policy == "start":
            return startup
        if policy == "twice":
            return now_ms() - last > 12 * HOUR
        if policy == "daily":
            return self._day(last) != self._day(now_ms())
        return False

    def auto_refresh_loop(self):
        """Blijft Kijk de hele dag open staan, dan toch op tijd vernieuwen."""
        while True:
            time.sleep(1800)
            try:
                if not self.cfg.get("playlist_url"):
                    continue
                if self.playlist_due():
                    log("Playlist automatisch vernieuwen")
                    self.load()
                elif self.epg_due():
                    log("Gids automatisch vernieuwen")
                    self.refresh_epg()
            except Exception as e:
                log("Automatisch vernieuwen mislukt:", e)

    def load(self, force_epg=False, force_playlist=False, startup=False):
        if not self.load_lock.acquire(blocking=False):
            # al bezig: daarna nog één keer laden (bijv. na een gewijzigde playlist-link)
            self.pending = max(getattr(self, "pending", 0), 2 if force_epg else 1)
            return
        try:
            self._load(force_epg, force_playlist, startup)
            while getattr(self, "pending", 0):
                again, self.pending = self.pending, 0
                self._load(again == 2, True)
        finally:
            self.load_lock.release()

    def _load(self, force_epg=False, force_playlist=False, startup=False):
        st = self.status
        st["playlist"], st["playlistError"] = "Playlist laden…", None
        url = self.cfg["playlist_url"]
        xt = Xtream.from_playlist(url)
        cache_file = os.path.join(DATA, "playlist.m3u")
        arch_file = os.path.join(DATA, "archive.json")
        download = force_playlist or self.playlist_due(startup)
        text = None
        if not download:
            try:
                st["progress"] = {"label": "Opgeslagen playlist openen", "done": 0, "total": 0}
                with open(cache_file, encoding="utf-8") as f:
                    text = f.read()
                log("Opgeslagen playlist gebruikt (vernieuwen: %s)" % self.cfg.get("refresh_playlist", "daily"))
            except OSError:
                download = True
        try:
            if text is not None:
                raise StopIteration

            st["progress"] = {"label": "Playlist downloaden", "done": 0, "total": 0}
            text = fetch_text(url, lambda d, t: st.__setitem__("progress", {"label": "Playlist downloaden", "done": d, "total": t}))
            with open(cache_file, "w", encoding="utf-8") as f:
                f.write(text)
            self.cfg["playlist_updated"] = now_ms()
            self.save_cfg()
        except StopIteration:
            pass
        except Exception as e:
            log("Playlist laden mislukt:", e)
            st["playlistError"] = "Playlist laden mislukt: %s" % e
            try:
                with open(cache_file, encoding="utf-8") as f:
                    text = f.read()
                st["playlistError"] += " (opgeslagen versie gebruikt)"
            except OSError:
                st["playlist"] = None
                st["progress"] = None
                return
        st["progress"] = {"label": "Zenders verwerken", "done": 0, "total": 0}
        channels, vod, epg_url = parse_m3u(text, keep_vod=xt is None)
        archive, server_offset = {}, 0
        if xt:
            cached = None
            if not download:
                try:
                    with open(arch_file, encoding="utf-8") as f:
                        cached = json.load(f)
                except (OSError, ValueError):
                    cached = None
            if cached:
                archive, server_offset = cached.get("archive", {}), cached.get("offset", 0)
            else:
                try:
                    st["progress"] = {"label": "Terugkijk-informatie ophalen", "done": 0, "total": 0}
                    archive, server_offset = xt.archive_info()
                    with open(arch_file, "w", encoding="utf-8") as f:
                        json.dump({"archive": archive, "offset": server_offset}, f)
                except Exception as e:
                    log("Terugkijk-informatie ophalen mislukt:", e)
        for c in channels:
            sid = re.search(r"/(\d+)(?:\.\w+)?$", c["url"].split("?")[0])
            c["streamId"] = sid.group(1) if sid else None
            if xt and c["streamId"]:
                # Xtream: vaste sleutel op zendernummer (blijft gelijk als je wachtwoord verandert)
                c["urlKey"], c["key"] = c["key"], "s" + c["streamId"]
            if xt and c["streamId"] in archive:
                c["archiveDays"] = archive[c["streamId"]]
            elif c.get("catchup") or c.get("catchupDays"):
                c["archiveDays"] = c.get("catchupDays") or 7
            else:
                c["archiveDays"] = 0
        n_arch = sum(1 for c in channels if c["archiveDays"])
        if n_arch:
            log("Terugkijken mogelijk op %d zenders" % n_arch)
        st["progress"] = {"label": "Zenders aan de gids koppelen", "done": 0, "total": 0}
        new_match = self.epg.match(channels, self.cfg.get("epg_links"))
        with self.lock:
            self.server_offset = server_offset
            self.match = new_match  # zenders en gidskoppeling tegelijk vervangen
            self.by_key = {c["key"]: c for c in channels}
            self.arch_cache = {}
            self.channels, self.playlist_epg = channels, epg_url
            self.vod = xt or (M3uVod(vod) if vod else None)
            self.cache.clear()
        st["playlist"] = None
        st["progress"] = None
        log("Playlist: %d zenders, %d films/afleveringen%s" % (len(channels), len(vod), " (Xtream)" if xt else ""))
        if force_epg or self.epg_due(startup):
            self.refresh_epg()

    def epg_urls(self):
        raw = self.cfg.get("epg_url") or self.playlist_epg or ""
        if not raw:
            xt = Xtream.from_playlist(self.cfg["playlist_url"])
            if xt:
                raw = "%s/xmltv.php?%s" % (xt.host, urllib.parse.urlencode({"username": xt.user, "password": xt.pw}))
        return [u.strip() for u in raw.split(",") if u.strip().startswith("http")]

    def refresh_epg(self):
        st = self.status
        if st["epgBusy"]:
            return
        urls = self.epg_urls()
        if not urls:
            st["epg"] = "Geen gids-link. Vul er een in bij Instellingen."
            return
        st["epgBusy"] = True
        try:
            def cb(msg, done=None, total=None):
                if msg:
                    st["epg"] = msg
                    prev = st.get("progress") or {}
                    keep = "verwerken" in msg
                    st["progress"] = {"label": msg.rstrip("…"), "done": prev.get("done", 0) if keep else 0, "total": prev.get("total", 0) if keep else 0}
                elif st.get("progress"):
                    st["progress"] = {**st["progress"], "done": done, "total": total}
            n = self.epg.refresh(urls, cb)
            st["epg"] = None
            st["progress"] = None
            log("Gids: %d programma's" % n)
        except Exception as e:
            log("Gids mislukt:", e)
            st["progress"] = None
            st["epg"] = "Gids bijwerken mislukt: %s" % e
        finally:
            st["epgBusy"] = False
        self.match = self.epg.match(self.channels, self.cfg.get("epg_links"))

    def archive_for(self, ch):
        """Terugkijkgids per zender, 30 minuten bewaard."""
        sid = ch.get("streamId")
        hit = self.arch_cache.get(sid)
        if hit and time.time() - hit[0] < 1800:
            return hit[1]
        xt = Xtream.from_playlist(self.cfg["playlist_url"])
        try:
            lst = xt.archive_epg(sid) if xt else []
        except Exception as e:
            log("Terugkijkgids ophalen mislukt:", e)
            lst = []
        self.arch_cache[sid] = (time.time(), lst)
        return lst

    def programmes_for(self, key, frm, to):
        """Gids uit XMLTV, aangevuld met de terugkijkgids van de server voor oudere programma's."""
        cid = self.match.get(key)
        lst = self.epg.programmes(cid, frm, to) if cid else []
        ch = self.by_key.get(key)
        if ch and ch.get("archiveDays") and ch.get("streamId") and frm < now_ms() and Xtream.from_playlist(self.cfg["playlist_url"]):
            earliest = min((p["start"] for p in lst), default=None) if lst else (self.epg.earliest(cid) if cid else None)
            if cid and earliest is None:
                earliest = self.epg.earliest(cid)
            if earliest is None or frm < earliest:
                older = [p for p in self.archive_for(ch) if p["stop"] > frm and p["start"] < to and (earliest is None or p["stop"] <= earliest + 5 * MIN)]
                lst = dedupe(older + lst)
        return lst

    def search(self, q):
        """Zoeken in films, series en de tv-gids (zenders zoekt de browser zelf)."""
        ql = q.lower().strip()
        out = {"movies": [], "shows": [], "programmes": []}
        if len(ql) < 2:
            return out

        def rank(name):
            n = name.lower()
            return (0 if n.startswith(ql) else 1 if (" " + ql) in n else 2, len(n))
        if self.vod is not None:
            for kind, fn in (("movies", "movies"), ("shows", "shows")):
                try:
                    allv = self.vod_call(fn, None) or []
                except Exception as e:
                    log("Zoeken in %s mislukt: %s" % (kind, e))
                    allv = []
                hits = [v for v in allv if ql in (v.get("name") or "").lower()]
                hits.sort(key=lambda v: rank(v["name"]))
                out[kind] = hits[:60]
        now = now_ms()
        rev = {}
        for key, cid in self.match.items():
            rev.setdefault(cid, []).append(key)
        progs = []
        for p in dedupe_simple(self.epg.find(q.strip(), now - 7 * DAY, now + 2 * DAY)):
            for key in rev.get(p["cid"], []):
                progs.append({"key": key, "start": p["start"], "stop": p["stop"], "title": p["title"], "desc": p["desc"]})
        # eerst wat nu loopt, dan wat straks komt, dan wat geweest is
        progs.sort(key=lambda p: (0 if p["start"] <= now < p["stop"] else 1 if p["start"] > now else 2,
                                  abs(p["start"] - now)))
        out["programmes"] = progs[:300]
        return out

    def catchup_url(self, ch, start, stop):
        minutes = max(1, int((stop - start) / MIN) + 1)
        xt = Xtream.from_playlist(self.cfg["playlist_url"])
        if xt and ch.get("streamId") and not ch.get("catchupSource"):
            return xt.timeshift_url(ch["streamId"], start, minutes, self.server_offset)
        mode = (ch.get("catchup") or "default").lower()
        url = ch["url"]
        s_, e_, n_ = int(start / 1000), int(stop / 1000), int(time.time())
        if mode == "shift" or (mode in ("default", "append") and not ch.get("catchupSource")):
            return url + ("&" if "?" in url else "?") + "utc=%d&lutc=%d" % (s_, n_)
        if mode in ("flussonic", "flussonic-hls", "flussonic-ts", "fs"):
            base = url.rsplit("/", 1)[0]
            if ".m3u8" in url:
                return "%s/archive-%d-%d.m3u8" % (base, s_, e_ - s_)
            return "%s/timeshift_abs-%d.ts" % (base, s_)
        src = ch.get("catchupSource") or ""
        g = time.gmtime(s_)
        subs = {"{utc}": s_, "${start}": s_, "{start}": s_, "{utcend}": e_, "${end}": e_, "{end}": e_,
                "{lutc}": n_, "${now}": n_, "${timestamp}": n_, "{timestamp}": n_, "{duration}": e_ - s_,
                "${duration}": e_ - s_, "{duration:60}": minutes, "{offset}": n_ - s_, "${offset}": n_ - s_,
                "{Y}": time.strftime("%Y", g), "{m}": time.strftime("%m", g), "{d}": time.strftime("%d", g),
                "{H}": time.strftime("%H", g), "{M}": time.strftime("%M", g), "{S}": time.strftime("%S", g)}
        for k, v in subs.items():
            src = src.replace(k, str(v))
        src = re.sub(r"\{utc:([^}]+)\}", lambda m: time.strftime(m.group(1).replace("Y", "%Y").replace("m", "%m").replace("d", "%d").replace("H", "%H").replace("M", "%M").replace("S", "%S"), g), src)
        if mode == "append" or not src.startswith("http"):
            return url + src
        return src

    def vod_call(self, name, *args):
        src = self.vod
        if src is None:
            return None
        key = (name,) + args
        if key in self.cache:
            return self.cache[key]
        val = getattr(src, name)(*args)
        if val is not None:
            self.cache[key] = val
        return val


APP = None

# ------------------------------------------------------------------ HTTP

MIME = {".html": "text/html; charset=utf-8", ".js": "application/javascript", ".css": "text/css",
        ".svg": "image/svg+xml", ".png": "image/png", ".json": "application/json", ".ico": "image/x-icon"}
HOP = {"server", "date", "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization",
       "proxy-authenticate", "content-encoding"}


def proxied(url):
    return "/proxy?u=" + urllib.parse.quote(url, safe="") if url else None


def img(url):
    return "/proxy?img=1&u=" + urllib.parse.quote(url, safe="") if url else None


def live_url(url):
    """Met ffmpeg: geluid omzetten naar AAC (Dolby/AC3 kan de browser niet afspelen)."""
    if FFMPEG:
        return "/live?u=" + urllib.parse.quote(url, safe="")
    return proxied(url)


def rewrite_m3u8(text, base):
    """Alle adressen in een HLS-playlist via de proxy laten lopen."""
    def fix(u):
        return proxied(urllib.parse.urljoin(base, u.strip()))
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            out.append(fix(s))
        elif 'URI="' in s:
            out.append(re.sub(r'URI="([^"]+)"', lambda m: 'URI="%s"' % fix(m.group(1)), line))
        else:
            out.append(line)
    return "\n".join(out) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Kijk"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}") if n else {}
        if u.path == "/api/config":
            APP.cfg["playlist_url"] = (body.get("playlist_url") or "").strip()
            APP.cfg["epg_url"] = (body.get("epg_url") or "").strip()
            APP.save_cfg()
            threading.Thread(target=APP.load, kwargs={"force_epg": True, "force_playlist": True}, daemon=True).start()
            return self.send_json({"ok": True})
        if u.path == "/api/subs/config":
            for k in ("os_key", "os_user", "os_pass"):
                if k in body and (k != "os_pass" or body[k]):
                    v = (body[k] or "").strip()
                    if k == "os_key":
                        v = re.sub(r"^api[-_ ]?key\s*[:=]\s*", "", v, flags=re.I).strip().strip("'\"").replace(" ", "")
                    APP.cfg[k] = v
            if "auto_subs" in body:
                APP.cfg["auto_subs"] = bool(body["auto_subs"])
            APP.save_cfg()
            APP.subs.token = None
            return self.send_json({"ok": True})
        if u.path == "/api/subs/test":
            try:
                return self.send_json(APP.subs.test())
            except Exception as e:
                return self.send_json({"ok": False, "error": str(e)})
        if u.path == "/api/settings":
            for k, allowed in (("refresh_playlist", ("start", "daily", "weekly", "manual")), ("refresh_epg", ("start", "twice", "daily", "manual"))):
                if body.get(k) in allowed:
                    APP.cfg[k] = body[k]
            APP.save_cfg()
            return self.send_json({"ok": True})
        if u.path == "/api/sendtv":
            return self.send_json(send_to_tv(body))
        if u.path == "/api/reload":
            threading.Thread(target=APP.load, kwargs={"force_playlist": True}, daemon=True).start()
            return self.send_json({"ok": True})
        if u.path == "/api/epg/link":
            links = APP.cfg.setdefault("epg_links", {})
            if body.get("cid") is None:
                links.pop(body.get("name", ""), None)
            else:
                links[body.get("name", "")] = body["cid"]
            APP.save_cfg()
            APP.match = APP.epg.match(APP.channels, links)
            return self.send_json({"ok": True})
        if u.path == "/api/epg/refresh":
            threading.Thread(target=APP.refresh_epg, daemon=True).start()
            return self.send_json({"ok": True})
        self.send_json({"error": "onbekend"}, 404)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            if u.path == "/proxy":
                return self.proxy(q.get("u", ""), img=bool(q.get("img")))
            if u.path == "/live":
                return self.live(q.get("u", ""))
            if u.path.startswith("/api/"):
                return self.api(u.path[5:], q)
            return self.static(u.path)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # de browser heeft de verbinding gesloten (bijv. bij zappen): normaal
        except (TimeoutError, socket.timeout, urllib.error.URLError, OSError) as e:
            if u.path == "/proxy" and q.get("img"):
                pass  # een logo dat niet laadt is geen probleem
            else:
                log("Geen antwoord van %s: %s" % (urllib.parse.urlparse(q.get("u", "") or u.path).netloc or u.path, getattr(e, "reason", e)))
            try:
                self.send_json({"error": "timeout"}, 504)
            except Exception:
                pass
        except Exception as e:
            traceback.print_exc()
            try:
                self.send_json({"error": str(e)}, 500)
            except Exception:
                pass

    def api(self, name, q):
        A = APP
        if name == "config":
            cfg = dict(A.cfg)
            return self.send_json({"playlist_url": cfg["playlist_url"], "epg_url": cfg["epg_url"], "ffmpeg": bool(FFMPEG),
                                   "refresh_playlist": cfg.get("refresh_playlist", "daily"), "refresh_epg": cfg.get("refresh_epg", "twice"),
                                   "os_key_set": bool(cfg.get("os_key")),
                                   "os_key_hint": (cfg.get("os_key", "")[:3] + "…" + cfg.get("os_key", "")[-3:] + " (%d tekens)" % len(cfg.get("os_key", ""))) if cfg.get("os_key") else "", "os_user": cfg.get("os_user", ""), "os_pass_set": bool(cfg.get("os_pass")),
                                   "auto_subs": cfg.get("auto_subs", True),
                                   "playlist_updated": cfg.get("playlist_updated", 0),
                                   "xtream": Xtream.from_playlist(cfg["playlist_url"]) is not None})
        if name == "status":
            progs, chans = A.epg.stats()
            return self.send_json({**A.status, "channels": len(A.channels), "programmes": progs,
                                   "matched": len(A.match), "epgUpdated": A.epg.updated(),
                                   "hasVod": A.vod is not None, "configured": bool(A.cfg["playlist_url"])})
        if name == "channels":
            return self.send_json([{**{k: c.get(k) for k in ("key", "oldKey", "urlKey", "number", "name", "group")},
                                    "logo": img(c["logo"]), "epg": c["key"] in A.match or bool(c.get("archiveDays") and c.get("streamId") and Xtream.from_playlist(A.cfg["playlist_url"])),
                                    "stream": proxied(c["url"]), "hls": ".m3u8" in c["url"].lower(),
                                    "transcode": live_url(c["url"]) if FFMPEG else None,
                                    "archive": c.get("archiveDays", 0), "epgCid": A.match.get(c["key"]),
                                    "linked": c["name"] in (A.cfg.get("epg_links") or {})}
                                   for c in A.channels])
        if name == "nownext":
            at = now_ms() - int(q.get("offset", 0)) * MIN
            nn = A.epg.now_next(at)
            off = int(q.get("offset", 0)) * MIN
            out = {}
            for key, cid in A.match.items():
                if cid in nn:
                    out[key] = [{**p, "start": p["start"] + off, "stop": p["stop"] + off} for p in nn[cid]]
            return self.send_json(out)
        if name == "search":
            res = A.search(q.get("q", ""))
            res["movies"] = [{**m, "poster": img(m.get("poster")), "stream": proxied(m.get("url"))} for m in res["movies"]]
            res["shows"] = [{**sh, "poster": img(sh.get("poster"))} for sh in res["shows"]]
            return self.send_json(res)
        if name == "subs/search":
            try:
                return self.send_json({"results": A.subs.search(q), "remaining": A.subs.remaining})
            except Exception as e:
                return self.send_json({"results": [], "error": str(e)})
        if name == "subs/get":
            try:
                text = A.subs.get(q.get("file_id", "0")).encode("utf-8")
            except Exception as e:
                return self.send_json({"error": str(e)}, 502)
            self.send_response(200)
            self.send_header("Content-Type", "text/vtt; charset=utf-8")
            self.send_header("Content-Length", str(len(text)))
            self.end_headers()
            self.wfile.write(text)
            return
        if name == "epg/search":
            return self.send_json(A.epg.search(q.get("q", "")))
        if name == "grid":
            off = int(q.get("offset", 0)) * MIN
            frm, to = int(q["from"]) - off, int(q["to"]) - off
            keys = [k for k in (q.get("keys") or "").split(",") if k]
            with ThreadPoolExecutor(6) as ex:
                lists = list(ex.map(lambda k: A.programmes_for(k, frm, to), keys))
            out = {k: [{**p, "start": p["start"] + off, "stop": p["stop"] + off} for p in lst] for k, lst in zip(keys, lists)}
            return self.send_json(out)
        if name == "catchup":
            ch = next((c for c in A.channels if c["key"] == q.get("key")), None)
            if not ch:
                return self.send_json({"error": "onbekende zender"}, 404)
            off = int(q.get("offset", 0)) * MIN
            url = A.catchup_url(ch, int(q["start"]) - off, int(q["stop"]) - off)
            log("Terugkijken:", re.sub(r"(/timeshift/)[^/]+/[^/]+/", r"\1•••/•••/", url))
            return self.send_json({"stream": proxied(url), "transcode": live_url(url) if FFMPEG else None,
                                   "hls": ".m3u8" in url.lower()})
        if name == "programmes":
            off = int(q.get("offset", 0)) * MIN
            lst = A.programmes_for(q.get("key"), int(q["from"]) - off, int(q["to"]) - off)
            return self.send_json([{**p, "start": p["start"] + off, "stop": p["stop"] + off} for p in lst])
        if name.startswith("vod/"):
            what = name[4:]
            if what == "movie_cats":
                return self.send_json(A.vod_call("movie_cats") or [])
            if what == "show_cats":
                return self.send_json(A.vod_call("show_cats") or [])
            if what == "movies":
                lst = A.vod_call("movies", q.get("cat", "")) or []
                return self.send_json([{**m, "poster": img(m["poster"]), "stream": proxied(m["url"])} for m in lst])
            if what == "movie_info":
                info = A.vod_call("movie_info", q.get("id", ""))
                if info and info.get("backdrop"):
                    info = {**info, "backdrop": img(info["backdrop"])}
                return self.send_json(info)
            if what == "shows":
                lst = A.vod_call("shows", q.get("cat", "")) or []
                return self.send_json([{**s, "poster": img(s["poster"])} for s in lst])
            if what == "show":
                d = A.vod_call("show", q.get("id", "")) or {"episodes": []}
                return self.send_json({**d, "backdrop": img(d.get("backdrop")),
                                       "episodes": [{**e, "still": img(e["still"]), "stream": proxied(e["url"])}
                                                    for e in d["episodes"]]})
        self.send_json({"error": "onbekend"}, 404)

    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        full = os.path.normpath(os.path.join(WEB, path.lstrip("/")))
        if not full.startswith(WEB) or not os.path.isfile(full):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with open(full, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", MIME.get(os.path.splitext(full)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def live(self, url):
        """Live stream via ffmpeg: beeld ongewijzigd, geluid omgezet naar stereo AAC."""
        if not url.startswith(("http://", "https://")) or not FFMPEG:
            return self.send_json({"error": "ongeldige url"}, 400)
        cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-user_agent", USER_AGENT,
               "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
               "-fflags", "+genpts+discardcorrupt", "-probesize", "1500000", "-analyzeduration", "1500000", "-i", url,
               "-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-flush_packets", "1", "-muxdelay", "0",
               "-f", "mpegts", "pipe:1"]
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL, creationflags=flags)
        threading.Thread(target=lambda: [log("ffmpeg:", l.decode("utf-8", "replace").strip()) for l in proc.stderr], daemon=True).start()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            while True:
                chunk = proc.stdout.read1(64 * 1024) if hasattr(proc.stdout, "read1") else proc.stdout.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
        finally:
            proc.kill()
            proc.wait()

    def image(self, url):
        """Logo's en posters: op schijf bewaard en maximaal 6 tegelijk opgehaald, zodat ze de streams niet verdringen."""
        path = os.path.join(IMG_DIR, hashlib.sha1(url.encode()).hexdigest())
        if not os.path.isfile(path):
            if os.path.isfile(path + ".fail") and time.time() - os.path.getmtime(path + ".fail") < 6 * 3600:
                return self.send_json({"error": "geen logo"}, 404)
            with IMG_SEM:
                if not os.path.isfile(path):
                    try:
                        resp, _ = http_open(url, timeout=8)
                        data = resp.read(3 * 1024 * 1024)
                        ctype = resp.headers.get("Content-Type", "image/png")
                    except Exception:
                        open(path + ".fail", "w").close()
                        return self.send_json({"error": "geen logo"}, 404)
                    with open(path, "wb") as f:
                        f.write(ctype.encode()[:100].ljust(100) + data)
        with open(path, "rb") as f:
            raw = f.read()
        body = raw[100:]
        self.send_response(200)
        self.send_header("Content-Type", raw[:100].decode(errors="ignore").strip() or "image/png")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=604800")
        self.end_headers()
        self.wfile.write(body)

    def proxy(self, url, img=False):
        if not url.startswith(("http://", "https://")):
            return self.send_json({"error": "ongeldige url"}, 400)
        if img:
            return self.image(url)
        headers = {}
        if self.headers.get("Range"):
            headers["Range"] = self.headers["Range"]
        try:
            resp, final = http_open(url, headers, timeout=30)
            code = resp.status
        except urllib.error.HTTPError as e:
            resp, final, code = e, url, e.code
        if code >= 400:
            log("Server gaf fout %d voor %s" % (code, urllib.parse.urlparse(url).netloc))
        ctype = resp.headers.get("Content-Type", "")
        is_m3u8 = "mpegurl" in ctype.lower() or final.lower().split("?")[0].endswith(".m3u8")
        if is_m3u8 and code == 200:
            text = rewrite_m3u8(resp.read().decode("utf-8", "replace"), final).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(text)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(text)
            return
        self.send_response(code)
        length = None
        for k, v in resp.headers.items():
            if k.lower() in HOP:
                continue
            if k.lower() == "content-length":
                length = v
            self.send_header(k, v)
        if length is None:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        try:
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
        finally:
            resp.close()  # verbinding met de IPTV-server direct vrijgeven (belangrijk bij 1 verbinding per abonnement)


def send_to_tv(body):
    """Instellingen naar de Kijk-app op de Android TV sturen (zelfde veldnamen als de app)."""
    addr = re.sub(r"^https?://", "", str(body.get("address") or "")).strip().strip("/")
    if not re.match(r"^[\w.\-]+(:\d+)?$", addr):
        return {"ok": False, "error": "Ongeldig adres"}
    if ":" not in addr:
        addr += ":8080"
    c = APP.cfg
    data = dict(body.get("client") or {})
    data["code"] = str(body.get("code") or "")
    for k in ("playlist_url", "epg_url", "refresh_playlist", "refresh_epg", "os_key", "os_user", "os_pass"):
        if c.get(k):
            data[k] = c[k]
    data["epg_links"] = c.get("epg_links", {})
    if "auto_subs" in c:
        data["auto_subs"] = bool(c["auto_subs"])
    req = urllib.request.Request(f"http://{addr}/import", data=json.dumps(data).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return {"ok": False, "error": json.loads(e.read()).get("error", f"HTTP {e.code}")}
        except Exception:
            return {"ok": False, "error": f"HTTP {e.code}"}
    except Exception as e:
        return {"ok": False, "error": f"Geen verbinding met de tv ({e.__class__.__name__}). Staat Kijk open op de tv en zit je in hetzelfde netwerk?"}


class QuietServer(ThreadingHTTPServer):
    """Geen foutmelding als de browser een verbinding sluit; dat is normaal (bijv. bij zappen)."""
    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionAbortedError, ConnectionResetError, BrokenPipeError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def main():
    global APP
    APP = App()
    srv = QuietServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    url = "http://localhost:%d" % PORT
    log("Kijk draait op %s  (stoppen: Ctrl+C)" % url)
    if FFMPEG:
        log("ffmpeg gevonden: geluid van live zenders wordt omgezet naar AAC (%s)" % FFMPEG)
    else:
        log("ffmpeg niet gevonden: zenders met Dolby/AC3-geluid hebben in de browser geen geluid. Zie LEESMIJ.txt.")
    if "--no-browser" not in sys.argv:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("Gestopt.")


if __name__ == "__main__":
    main()
