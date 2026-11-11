#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Архив Вещания — домашний помощник.

Небольшая служба для домашнего Linux-сервера с Samba. Отдаёт телевизорам LG (webOS)
по HTTP ту же папку «Архив Вещания», в которую Android-приложение пишет по SMB:

  * библиотеку загруженного (папки <Раздел>/<Название> с info.json — формат Android);
  * сами файлы, с перемоткой (HTTP Range);
  * общую историю просмотров (папка .история — тот же формат, что у Android);
  * онлайн-просмотр архива через себя: не больше ОДНОГО соединения с каждым разделом
    архива, просмотренное складывается в кэш (.кэш) — перемотка назад без интернета;
  * загрузку из архива прямо в папку по просьбе телевизора (с докачкой).

Только стандартная библиотека Python 3.7+, без установки пакетов.

Запуск вручную:
    python3 arhiv-server.py --root "/srv/storage/SrvAV/Архив Вещания" --port 8765
"""
import argparse
import hashlib
import json
import mimetypes
import os
import re
import shutil
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.2"
# Наборы заголовков. Первый — как у Android-приложения (с ним архив точно работает),
# второй — как у обычного браузера, на случай если Cloudflare перед архивом отвергнет первый (403).
HEADER_SETS = [
    {"User-Agent": "Mozilla/5.0 (Linux; Android) MultikiFamily/0.3", "Accept": "*/*"},
    {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
     "Accept": "*/*", "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8"},
]
HEADER_IDX = [0]
DEVICE_NAME = "Домашний сервер"
HISTORY_DIR = ".история"
CACHE_DIR = ".кэш"
QUEUE_FILE = ".очередь.json"
AHEAD = 2 * 1024 * 1024          # ждать, если нужный байт приедет в ближайшие 2 МБ
MIN_GAP = 0.8                    # пауза между соединениями с одним разделом, с
CHUNK = 64 * 1024

ROOT = ""
CACHE_LIMIT = 5 * 1024 ** 3


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def safe_name(s):
    """Как в Android-приложении: одинаковые имена папок на всех устройствах."""
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", s).strip().rstrip(".")
    return s[:90] or "file"


def decode_name(f):
    try:
        return urllib.parse.unquote(f.replace("+", "%2B"))
    except Exception:
        return f


def atomic_write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def inside_root(rel):
    """Абсолютный путь внутри ROOT или None (защита от ../)."""
    rel = rel.replace("\\", "/").lstrip("/")
    full = os.path.realpath(os.path.join(ROOT, rel))
    root = os.path.realpath(ROOT)
    if full != root and not full.startswith(root + os.sep):
        return None
    return full


# ====================================================================== library

def read_info(folder):
    try:
        with open(os.path.join(folder, "info.json"), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def write_info(folder, info):
    atomic_write(os.path.join(folder, "info.json"), json.dumps(info, ensure_ascii=False, indent=1))


def scan_library():
    items = []
    if not os.path.isdir(ROOT):
        return items
    for sec in sorted(os.listdir(ROOT)):
        sp = os.path.join(ROOT, sec)
        if sec.startswith(".") or not os.path.isdir(sp):
            continue
        for it in sorted(os.listdir(sp)):
            ip = os.path.join(sp, it)
            if it.startswith(".") or not os.path.isdir(ip):
                continue
            info = read_info(ip)
            if not info or "sectionId" not in info:
                continue
            rel = os.path.relpath(ip, ROOT).replace(os.sep, "/")
            info["folder"] = rel
            items.append(info)
    items.sort(key=lambda i: (i.get("title") or "").lower())
    return items


def delete_item(section_id, item_id):
    for info in scan_library():
        if info.get("sectionId") == section_id and info.get("itemId") == item_id:
            p = inside_root(info["folder"])
            if p and os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
                return True
    return False


# ====================================================================== history

def history_records():
    """Все записи всех устройств; для каждого фильма — самая свежая."""
    d = os.path.join(ROOT, HISTORY_DIR)
    best = {}
    if os.path.isdir(d):
        for name in os.listdir(d):
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(d, name), encoding="utf-8") as f:
                    recs = json.load(f)
            except Exception:
                continue
            for r in recs if isinstance(recs, list) else []:
                k = "%s|%s" % (r.get("s"), r.get("i"))
                if k not in best or r.get("u", 0) > best[k].get("u", 0):
                    best[k] = r
    out = [r for r in best.values() if not r.get("del")]
    out.sort(key=lambda r: -r.get("u", 0))
    return out


_history_lock = threading.Lock()


def history_put(device_id, device_name, rec):
    """Запись одного устройства (телевизора) в его собственный файл — как делает Android."""
    device_id = re.sub(r"[^A-Za-z0-9_.-]", "_", device_id or "tv")[:64]
    path = os.path.join(ROOT, HISTORY_DIR, device_id + ".json")
    with _history_lock:
        try:
            with open(path, encoding="utf-8") as f:
                recs = json.load(f)
        except Exception:
            recs = []
        k = "%s|%s" % (rec.get("s"), rec.get("i"))
        rec = dict(rec)
        rec["did"] = device_id
        rec["dev"] = device_name or rec.get("dev") or "Телевизор"
        rec.setdefault("u", int(time.time() * 1000))
        recs = [r for r in recs if "%s|%s" % (r.get("s"), r.get("i")) != k]
        recs.insert(0, rec)
        atomic_write(path, json.dumps(recs[:600], ensure_ascii=False))


# ====================================================================== connection gate

class Gate:
    """Одно соединение с каждым разделом архива. Просмотр вытесняет загрузку, не наоборот."""

    def __init__(self):
        self.cv = threading.Condition()
        self.holders = {}
        self.last_release = {}

    def acquire(self, lane, owner, demand=True, timeout=None):
        t0 = time.time()
        with self.cv:
            while True:
                cur = self.holders.get(lane)
                if cur is None or cur is owner:
                    break
                if not demand:
                    return False
                cur.preempt()
                self.cv.wait(0.2)
                if timeout and time.time() - t0 > timeout:
                    return False
            self.holders[lane] = owner
            wait = self.last_release.get(lane, 0) + MIN_GAP - time.time()
        if wait > 0:
            time.sleep(wait)
        return True

    def release(self, lane, owner):
        with self.cv:
            if self.holders.get(lane) is owner:
                del self.holders[lane]
                self.last_release[lane] = time.time()
            self.cv.notify_all()

    def busy(self, lane):
        with self.cv:
            return lane in self.holders


GATE = Gate()


class Busy(Exception):
    pass


class Denied(Exception):
    """Архив отказал в доступе (401/403): вероятно, адрес временно заблокирован. Долгая пауза, без частых повторов."""
    def __init__(self, code):
        Exception.__init__(self, "HTTP %d" % code)
        self.code = code


DENIED_PAUSE = 30 * 60
DENIED_UNTIL = [0.0]          # общая пауза для всех разделов: бан — на весь адрес


def denied_left():
    return max(0, int(DENIED_UNTIL[0] - time.time()))


def denied_message():
    return "Архив отказал в доступе — похоже, адрес временно заблокирован. Следующая попытка через %d мин" % max(1, (denied_left() + 59) // 60)


def _quote_url(url):
    """Кириллица и пробелы в пути → %XX (уже закодированное не трогаем)."""
    u = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((u.scheme, u.netloc, urllib.parse.quote(u.path, safe="/%:@!$&'()*+,;=~-._"), u.query, ""))


def describe_http_error(e):
    if isinstance(e, urllib.error.HTTPError):
        return "HTTP %d" % e.code
    if isinstance(e, urllib.error.URLError):
        return "сеть: %s" % (e.reason,)
    return e.__class__.__name__


def _request(url, start, timeout, idx):
    req = urllib.request.Request(_quote_url(url), headers=dict(HEADER_SETS[idx]))
    if start > 0:
        req.add_header("Range", "bytes=%d-" % start)
    return urllib.request.urlopen(req, timeout=timeout)


def open_remote(url, start=0, timeout=25):
    if denied_left():
        raise Denied(401)
    idx = HEADER_IDX[0]
    try:
        try:
            resp = _request(url, start, timeout, idx)
        except urllib.error.HTTPError as e:
            if e.code != 403:
                raise
            alt = (idx + 1) % len(HEADER_SETS)
            log("архив ответил 403 на %s — пробую другие заголовки" % url)
            resp = _request(url, start, timeout, alt)
            HEADER_IDX[0] = alt
    except urllib.error.HTTPError as e:
        log("архив: %s на %s" % (describe_http_error(e), url))
        if e.code in (503, 429):
            raise Busy()
        if e.code in (401, 403):
            DENIED_UNTIL[0] = time.time() + DENIED_PAUSE
            raise Denied(e.code)
        if e.code == 416:
            return None, start, start
        raise
    except Exception as e:
        log("архив: %s на %s" % (describe_http_error(e), url))
        raise
    total = None
    pos = 0
    cr = resp.headers.get("Content-Range")
    if resp.status == 206 and cr:
        m = re.match(r"bytes\s+(\d+)-(\d+)/(\d+)", cr)
        if m:
            pos, total = int(m.group(1)), int(m.group(3))
    else:
        cl = resp.headers.get("Content-Length")
        total = int(cl) if cl else None
    return resp, pos, total


# ====================================================================== stream cache

class StreamSource:
    """
    Файл архива, который сейчас смотрят. Один фоновый поток качает его ОДНИМ соединением в кэш;
    телевизор читает любые места; если нужно далеко от текущей загрузки — соединение закрывается
    и открывается заново там (никогда не два сразу). Скачанное запоминается.
    """

    def __init__(self, lane, url):
        self.lane, self.url = lane, url
        key = hashlib.sha1(("%s|%s" % (lane, url)).encode()).hexdigest()[:24]
        d = os.path.join(ROOT, CACHE_DIR)
        os.makedirs(d, exist_ok=True)
        self.path = os.path.join(d, key + ".bin")
        self.rpath = os.path.join(d, key + ".ranges")
        self.cv = threading.Condition()
        self.ranges = []           # [[start, end)]
        self.total = -1
        self.wanted = -1
        self.up = -1               # где сейчас идёт загрузка
        self.last_pos = 0
        self.preempted = False
        self.closed = False
        self.message = None
        self.last_used = time.time()
        self.resp = None
        if not os.path.exists(self.path):
            open(self.path, "wb").close()
        self._load()
        self.fh = open(self.path, "r+b")
        self.worker = threading.Thread(target=self._run, daemon=True)
        self.worker.start()

    # ---- ranges
    def _covered_end(self, pos):
        for s, e in self.ranges:
            if s <= pos < e:
                return e
        return -1

    def _add(self, s, e):
        rs = sorted(self.ranges + [[s, e]])
        out = []
        for a, b in rs:
            if out and a <= out[-1][1]:
                out[-1][1] = max(out[-1][1], b)
            else:
                out.append([a, b])
        self.ranges = out

    def complete(self):
        return self.total > 0 and len(self.ranges) == 1 and self.ranges[0][0] == 0 and self.ranges[0][1] >= self.total

    def downloaded(self):
        return sum(e - s for s, e in self.ranges)

    def _load(self):
        try:
            with open(self.rpath) as f:
                lines = f.read().split("\n")
            t = int(lines[0])
            size = os.path.getsize(self.path)
            if t > 0 and size >= t:
                self.total = t
            for ln in lines[1:]:
                p = ln.split()
                if len(p) == 2 and int(p[1]) <= size:
                    self._add(int(p[0]), int(p[1]))
        except Exception:
            pass

    def _save(self):
        try:
            with open(self.rpath, "w") as f:
                f.write("%d\n" % self.total + "".join("%d %d\n" % (s, e) for s, e in self.ranges))
        except Exception:
            pass

    # ---- readers
    def await_total(self, timeout=45):
        t0 = time.time()
        with self.cv:
            while self.total < 0 and not self.closed:
                if self.wanted < 0 and self.up < 0:
                    self.wanted = 0
                    self.cv.notify_all()
                left = timeout - (time.time() - t0)
                if left <= 0:
                    break
                self.cv.wait(min(left, 0.5))
            return self.total

    def read(self, pos, n):
        self.last_used = time.time()
        with self.cv:
            while True:
                if self.closed:
                    raise IOError("closed")
                if 0 <= self.total <= pos:
                    return b""
                end = self._covered_end(pos)
                if end > pos:
                    n = min(n, end - pos)
                    break
                soon = 0 <= self.up <= pos <= self.up + AHEAD
                if not soon and self.wanted != pos:
                    self.wanted = pos
                    self._abort_upstream()
                    self.cv.notify_all()
                self.cv.wait(0.5)
        return os.pread(self.fh.fileno(), n, pos)

    def preempt(self):
        self.preempted = True
        self._abort_upstream()

    def _abort_upstream(self):
        r = self.resp
        if r is not None:
            try:
                r.fp.raw._sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass

    def close(self):
        self.closed = True
        self._abort_upstream()
        with self.cv:
            self.cv.notify_all()
        self._save()

    # ---- worker
    def _next_start(self):
        demand = False
        if self.wanted >= 0:
            w, self.wanted = self.wanted, -1
            self.preempted = False
            demand = True
            e = self._covered_end(w)
            if e > w:
                return (None if 0 <= self.total <= e else e), demand
            return w, demand
        if self.preempted or self.total < 0 or self.complete():
            return None, False
        for start in (self.last_pos, 0):
            pos = start
            while True:
                e = self._covered_end(pos)
                if e > pos:
                    pos = e
                else:
                    break
            if not (0 <= self.total <= pos):
                return pos, False
        return None, False

    def _run(self):
        backoff = 0
        while not self.closed:
            with self.cv:
                start, demand = self._next_start()
                while start is None and not self.closed:
                    self.cv.wait(1.0)
                    start, demand = self._next_start()
                if self.closed:
                    return
            sleep = 0
            self.preempted = False
            if not GATE.acquire(self.lane, self, demand=demand):
                time.sleep(5)
                continue
            try:
                self._download(start)
                backoff = 0
                self.message = None
            except Denied:
                sleep = max(60, denied_left())
                self.message = denied_message()
            except Busy:
                backoff = min(60, backoff * 2 if backoff else 5)
                sleep = backoff
                self.message = "Сервер архива занят (возможно, архив открыт на другом устройстве дома). Повтор через %d с" % backoff
            except Exception as e:
                if not (self.closed or self.preempted or self.wanted >= 0):
                    backoff = min(30, backoff * 2 if backoff else 3)
                    sleep = backoff
                    self.message = "Нет связи с архивом (%s). Повтор через %d с" % (describe_http_error(e), backoff)
            finally:
                self.resp = None
                self.up = -1
                GATE.release(self.lane, self)
                with self.cv:
                    self.cv.notify_all()
                self._save()
            t_end = time.time() + sleep
            while sleep and time.time() < t_end and not self.closed:
                time.sleep(0.25)

    def _download(self, start):
        resp, pos, total = open_remote(self.url, start)
        if resp is None:
            return
        self.resp = resp
        with resp:
            if total and total > 0 and self.total != total:
                with self.cv:
                    self.total = total
                    if os.path.getsize(self.path) != total:
                        self.fh.truncate(total)
                    self.cv.notify_all()
            self.up = pos
            saved_at = pos
            while not (self.closed or self.preempted):
                with self.cv:
                    w = self.wanted
                    if (w >= 0 and (w < pos or w > pos + AHEAD)) or self._covered_end(pos) > pos:
                        break
                buf = resp.read(CHUNK)
                if not buf:
                    break
                os.pwrite(self.fh.fileno(), buf, pos)
                with self.cv:
                    self._add(pos, pos + len(buf))
                    pos += len(buf)
                    self.up = self.last_pos = pos
                    if 0 <= self.wanted < pos and self._covered_end(self.wanted) > self.wanted:
                        self.wanted = -1
                    self.cv.notify_all()
                if pos - saved_at > 8 * 1024 * 1024:
                    saved_at = pos
                    self._save()


class Streams:
    def __init__(self):
        self.lock = threading.Lock()
        self.open = {}

    def get(self, lane, url):
        k = lane + "|" + url
        with self.lock:
            s = self.open.get(k)
            if s and not s.closed:
                return s
            # не больше 4 открытых файлов на раздел
            same = sorted([x for x in self.open.values() if x.lane == lane and not x.closed], key=lambda x: x.last_used)
            for old in same[:max(0, len(same) - 3)]:
                old.close()
            self.open = {kk: v for kk, v in self.open.items() if not v.closed}
            self._trim()
            s = StreamSource(lane, url)
            self.open[k] = s
            return s

    def lane_active(self, lane):
        now = time.time()
        with self.lock:
            return any(s.lane == lane and not s.closed and not s.complete() and now - s.last_used < 20
                       for s in self.open.values())

    def complete_cache_file(self, lane, url):
        key = hashlib.sha1(("%s|%s" % (lane, url)).encode()).hexdigest()[:24]
        d = os.path.join(ROOT, CACHE_DIR)
        b, r = os.path.join(d, key + ".bin"), os.path.join(d, key + ".ranges")
        try:
            lines = open(r).read().split("\n")
            t = int(lines[0])
            full = any(len(ln.split()) == 2 and ln.split()[0] == "0" and int(ln.split()[1]) >= t for ln in lines[1:])
            if t > 0 and full and os.path.getsize(b) >= t:
                return b
        except Exception:
            pass
        return None

    def status(self, lane):
        with self.lock:
            src = [s for s in self.open.values() if s.lane == lane and not s.closed]
        if not src:
            return None
        s = max(src, key=lambda x: x.last_used)
        return {"downloaded": s.downloaded(), "total": s.total, "message": s.message}

    def _trim(self):
        d = os.path.join(ROOT, CACHE_DIR)
        if not os.path.isdir(d):
            return
        files = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".bin")]
        size = sum(os.path.getsize(f) for f in files)
        busy = {s.path for s in self.open.values() if not s.closed}
        for f in sorted(files, key=os.path.getmtime):
            if size <= CACHE_LIMIT:
                break
            if f in busy:
                continue
            size -= os.path.getsize(f)
            os.remove(f)
            try:
                os.remove(f[:-4] + ".ranges")
            except OSError:
                pass


STREAMS = Streams()


# ====================================================================== downloads (into the share)

class Downloads:
    """Очередь загрузки в папку сервера. Один файл на раздел за раз, просмотр имеет приоритет."""

    def __init__(self):
        self.lock = threading.Lock()
        self.tasks = []
        self.workers = {}
        self.path = None

    def init(self):
        self.path = os.path.join(ROOT, QUEUE_FILE)
        try:
            with open(self.path, encoding="utf-8") as f:
                self.tasks = json.load(f)
        except Exception:
            self.tasks = []
        for t in self.tasks:
            if t["state"] in ("running", "waiting"):
                t["state"] = "queued"
        self._kick()

    def save(self):
        try:
            atomic_write(self.path, json.dumps(self.tasks, ensure_ascii=False))
        except Exception:
            pass

    def add(self, req):
        sec, sid, iid = req["sectionTitle"], req["sectionId"], req["itemId"]
        folder = os.path.join(ROOT, safe_name(sec), safe_name(req["title"]))
        with self.lock:
            for p in req["parts"]:
                tid = "%s|%s|%d" % (sid, iid, p["part"])
                if any(t["id"] == tid and t["state"] != "error" for t in self.tasks):
                    continue
                self.tasks = [t for t in self.tasks if t["id"] != tid]
                self.tasks.append({
                    "id": tid, "sectionId": sid, "itemId": iid, "title": req["title"], "sectionTitle": sec,
                    "isVideo": bool(req.get("isVideo", True)), "part": p["part"], "partsCount": len(req["parts"]),
                    "url": p["url"], "file": safe_name(decode_name(p["file"])), "folder": folder,
                    "total": int(p.get("size") or 0), "done": 0, "state": "queued", "message": None,
                    "added": time.time(),
                })
            self.save()
        self._ensure_info(req, folder)
        self._kick()

    def cancel(self, section_id, item_id):
        with self.lock:
            for t in self.tasks:
                if t["sectionId"] == section_id and t["itemId"] == item_id and t["state"] != "done":
                    t["state"] = "cancelled"
            self.tasks = [t for t in self.tasks if t["state"] != "cancelled"]
            self.save()

    def snapshot(self):
        with self.lock:
            return [dict(t) for t in self.tasks if t["state"] != "done" or time.time() - t.get("finished", 0) < 600]

    def _ensure_info(self, req, folder):
        os.makedirs(folder, exist_ok=True)
        info = read_info(folder) or {
            "app": "Архив Вещания", "sectionId": req["sectionId"], "itemId": req["itemId"], "title": req["title"],
            "sectionTitle": req["sectionTitle"], "isVideo": bool(req.get("isVideo", True)),
            "busyDevice": None, "busyAt": 0, "parts": [],
        }
        have = {p["part"] for p in info.get("parts", [])}
        for p in req["parts"]:
            if p["part"] not in have:
                info["parts"].append({"part": p["part"], "file": safe_name(decode_name(p["file"])),
                                      "size": int(p.get("size") or 0), "done": False})
        info["parts"].sort(key=lambda p: p["part"])
        write_info(folder, info)

    def _mark(self, t, done=False, size=0, busy=True):
        info = read_info(t["folder"])
        if not info:
            return
        for p in info["parts"]:
            if p["part"] == t["part"] and done:
                p["done"], p["size"] = True, size
        last = all(p.get("done") for p in info["parts"])
        info["busyDevice"] = None if (last or not busy) else DEVICE_NAME
        info["busyAt"] = int(time.time() * 1000)
        write_info(t["folder"], info)

    def _kick(self):
        with self.lock:
            lanes = {t["sectionId"] for t in self.tasks if t["state"] in ("queued", "waiting", "running")}
            for lane in lanes:
                w = self.workers.get(lane)
                if not w or not w.is_alive():
                    w = threading.Thread(target=self._run, args=(lane,), daemon=True)
                    self.workers[lane] = w
                    w.start()

    def _next(self, lane):
        with self.lock:
            c = [t for t in self.tasks if t["sectionId"] == lane and t["state"] in ("queued", "waiting", "running")]
            return min(c, key=lambda t: t["added"]) if c else None

    def _run(self, lane):
        owner = _Owner()
        backoff = 0
        while True:
            t = self._next(lane)
            if not t:
                return
            target = os.path.join(t["folder"], t["file"])
            info = read_info(t["folder"]) or {}
            part = next((p for p in info.get("parts", []) if p["part"] == t["part"]), None)
            if part and part.get("done") and os.path.exists(target):
                self._finish(t, os.path.getsize(target))
                continue
            busy_dev = info.get("busyDevice")
            if busy_dev and busy_dev != DEVICE_NAME and time.time() * 1000 - info.get("busyAt", 0) < 180000:
                t["state"], t["message"] = "waiting", "Загружается на устройстве «%s»" % busy_dev
                time.sleep(30)
                continue
            if STREAMS.lane_active(lane):
                t["state"], t["message"] = "waiting", "Ждёт: в этом разделе сейчас идёт просмотр"
                time.sleep(5)
                continue
            cached = STREAMS.complete_cache_file(lane, t["url"])
            if cached:
                t["state"], t["message"] = "running", "Копируется из кэша просмотренного"
                shutil.copyfile(cached, target)
                self._finish(t, os.path.getsize(target))
                continue
            if not GATE.acquire(lane, owner, demand=False):
                t["state"], t["message"] = "waiting", "Ждёт, пока освободится соединение с разделом"
                time.sleep(5)
                continue
            sleep = 0
            owner.preempted = False
            try:
                self._download(t, target, owner)
                backoff = 0
            except Denied:
                sleep = max(60, denied_left())
                t["state"], t["message"] = "waiting", denied_message()
            except Busy:
                backoff = min(120, backoff * 2 if backoff else 10)
                sleep = backoff
                t["state"], t["message"] = "waiting", "Сервер архива занят. Повтор через %d с" % backoff
            except Exception as e:
                if t["state"] == "cancelled":
                    pass
                elif owner.preempted:
                    t["state"], t["message"] = "waiting", "Приостановлено на время просмотра"
                else:
                    backoff = min(120, backoff * 2 if backoff else 5)
                    sleep = backoff
                    t["state"], t["message"] = "waiting", "Нет связи с архивом (%s). Повтор через %d с" % (describe_http_error(e), backoff)
            finally:
                owner.resp = None
                GATE.release(lane, owner)
                self.save()
            t_end = time.time() + sleep
            while sleep and time.time() < t_end and t["state"] != "cancelled":
                time.sleep(0.5)

    def _download(self, t, target, owner):
        part = target + ".part"
        start = os.path.getsize(part) if os.path.exists(part) else 0
        resp, pos, total = open_remote(t["url"], start)
        if resp is None:   # 416: .part уже полный
            os.replace(part, target)
            self._finish(t, os.path.getsize(target))
            return
        owner.resp = resp
        with resp:
            if total:
                t["total"] = total
            mode = "r+b" if (pos > 0 and os.path.exists(part)) else "wb"
            t["state"], t["message"], t["done"] = "running", None, pos
            last_beat = time.time()
            speed_t, speed_b = time.time(), 0
            with open(part, mode) as f:
                f.seek(pos)
                while True:
                    if owner.preempted or t["state"] == "cancelled":
                        raise IOError("stopped")
                    buf = resp.read(CHUNK * 4)
                    if not buf:
                        break
                    f.write(buf)
                    t["done"] += len(buf)
                    speed_b += len(buf)
                    now = time.time()
                    if now - speed_t >= 1:
                        t["message"] = "%.1f МБ/с" % (speed_b / 1e6 / (now - speed_t))
                        speed_t, speed_b = now, 0
                    if now - last_beat > 60:
                        last_beat = now
                        self._mark(t)
                        self.save()
        os.replace(part, target)
        self._finish(t, os.path.getsize(target))

    def _finish(self, t, size):
        t["state"], t["done"], t["message"], t["finished"] = "done", size, None, time.time()
        t["total"] = max(size, t.get("total") or 0)
        self._mark(t, done=True, size=size)
        self.save()


class _Owner:
    def __init__(self):
        self.preempted = False
        self.resp = None

    def preempt(self):
        self.preempted = True
        r = self.resp
        if r is not None:
            try:
                r.fp.raw._sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass


DOWNLOADS = Downloads()


# ====================================================================== HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "ArhivVeshchaniya/" + VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if "/api/" in self.path and not self.path.startswith("/api/history"):
            log(self.address_string(), fmt % args)

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Range")
        self.send_header("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")

    def _json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head=False):
        u = urllib.parse.urlsplit(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        try:
            if u.path == "/api/status":
                return self._json({"ok": True, "app": "Архив Вещания", "version": VERSION, "root": ROOT,
                                   "rootExists": os.path.isdir(ROOT)})
            if u.path == "/api/library":
                return self._json({"items": scan_library()})
            if u.path == "/api/history":
                return self._json({"records": history_records()})
            if u.path == "/api/downloads":
                return self._json({"tasks": DOWNLOADS.snapshot()})
            if u.path == "/api/stream-status":
                return self._json({"status": STREAMS.status(q.get("lane", ""))})
            if u.path.startswith("/files/"):
                full = inside_root(urllib.parse.unquote(u.path[len("/files/"):]))
                if not full or not os.path.isfile(full):
                    return self._json({"error": "not found"}, 404)
                size = os.path.getsize(full)
                with open(full, "rb") as f:
                    return self._serve(size, lambda pos, n: os.pread(f.fileno(), n, pos), full, head)
            if u.path == "/stream":
                lane, url = q.get("lane", ""), q.get("url", "")
                if not lane or not url.startswith("http"):
                    return self._json({"error": "bad request"}, 400)
                src = STREAMS.get(lane, url)
                total = src.await_total()
                if total <= 0:
                    return self._json({"error": "archive unavailable", "message": src.message}, 503)
                return self._serve(total, src.read, url, head)
            return self._json({"error": "not found"}, 404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        u = urllib.parse.urlsplit(self.path)
        try:
            b = self._body()
            if u.path == "/api/history":
                history_put(b.get("deviceId"), b.get("deviceName"), b["record"])
                return self._json({"ok": True})
            if u.path == "/api/download":
                DOWNLOADS.add(b)
                return self._json({"ok": True})
            if u.path == "/api/download/cancel":
                DOWNLOADS.cancel(b["sectionId"], b["itemId"])
                return self._json({"ok": True})
            if u.path == "/api/library/delete":
                DOWNLOADS.cancel(b["sectionId"], b["itemId"])
                return self._json({"ok": delete_item(b["sectionId"], b["itemId"])})
            return self._json({"error": "not found"}, 404)
        except Exception as e:
            return self._json({"error": str(e)}, 400)

    def _serve(self, total, reader, name, head):
        start, end = 0, total - 1
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
        if m:
            a, b = m.group(1), m.group(2)
            if a:
                start = int(a)
                if b:
                    end = min(int(b), total - 1)
            elif b:
                start = max(0, total - int(b))
        if start >= total:
            self.send_response(416)
            self._cors()
            self.send_header("Content-Range", "bytes */%d" % total)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(206 if m else 200)
        self._cors()
        if m:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, total))
        ext = name.rsplit(".", 1)[-1].lower()
        mime = {"avi": "video/x-msvideo", "mpg": "video/mpeg", "mpeg": "video/mpeg", "mp3": "audio/mpeg",
                "mp4": "video/mp4"}.get(ext) or mimetypes.guess_type(name)[0] or "application/octet-stream"
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        if head:
            return
        pos = start
        while pos <= end:
            data = reader(pos, min(CHUNK * 4, end - pos + 1))
            if not data:
                break
            self.wfile.write(data)
            pos += len(data)


TEST_URLS = [
    "http://multiki.arjlover.net/multiki/13.reis.avi",
    "http://audio.arjlover.net/audio/Baba_yaga/chitaet_Larisa_Luzhina_-_Baba_Yaga.mp3",
]


def self_test(urls):
    """python3 arhiv-server.py --test : проверка, отвечает ли архив этому серверу."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    ok_all = True
    for url in urls:
        print("\n=== " + url)
        for i, hs in enumerate(HEADER_SETS):
            cur, hops, result = url, 0, None
            while hops < 5:
                req = urllib.request.Request(_quote_url(cur), headers=dict(hs))
                req.add_header("Range", "bytes=0-1023")
                try:
                    r = opener.open(req, timeout=20)
                    result = "HTTP %d, получено %d байт" % (r.status, len(r.read(1024)))
                    break
                except urllib.error.HTTPError as e:
                    if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                        cur = urllib.parse.urljoin(cur, e.headers["Location"])
                        print("   заголовки %d: %d → %s" % (i + 1, e.code, cur))
                        hops += 1
                        continue
                    srv = e.headers.get("Server", "")
                    result = "HTTP %d%s" % (e.code, (" (сервер: %s)" % srv) if srv else "")
                    break
                except Exception as e:
                    result = "ошибка: %s" % describe_http_error(e)
                    break
            good = result.startswith("HTTP 20")
            ok_all = ok_all and (good or i > 0)
            print("   заголовки %d: %s %s" % (i + 1, result, "✓" if good else "✗"))
    print()
    return 0


def main():
    global ROOT, CACHE_LIMIT
    if "--test" in sys.argv:
        extra = [a for a in sys.argv[1:] if a.startswith("http")]
        sys.exit(self_test(extra or TEST_URLS))
    ap = argparse.ArgumentParser(description="Архив Вещания — домашний помощник для телевизоров LG")
    ap.add_argument("--root", required=True, help="папка «Архив Вещания» на диске сервера (та же, что в Samba)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--cache-gb", type=float, default=5, help="размер кэша онлайн-просмотра, ГБ")
    a = ap.parse_args()
    ROOT = os.path.abspath(a.root)
    CACHE_LIMIT = int(a.cache_gb * 1024 ** 3)
    os.makedirs(ROOT, exist_ok=True)
    os.umask(0o002)
    DOWNLOADS.init()
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    srv.daemon_threads = True
    log("Архив Вещания — помощник %s. Папка: %s. Порт: %d" % (VERSION, ROOT, a.port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
