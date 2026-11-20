/*
 * Диафильмы — фоновая служба webOS (Node.js, совместимо с Node 8 на webOS 4).
 *
 * Зачем: веб-приложение на webOS подчиняется CORS, а diafilmy.su не отдаёт
 * разрешающих заголовков. Служба читает страницы сайта сама и передаёт их приложению.
 *
 * Бережность к сайту (после того как массовое сканирование привело к блокировке):
 *  - строго один запрос за раз; между HTML-запросами пауза 1,5 с, между картинками 0,4 с;
 *  - при ответах 403/429/503 служба прекращает обращения на паузу (Retry-After или 1 час);
 *  - честный User-Agent.
 */
'use strict';

var Service = require('webos-service');
var https = require('https');
var http = require('http');
var urlm = require('url');

var VERSION = '1.6.0';
var UA = 'DiafilmApp/' + VERSION + ' (webOS; viewer for diafilmy.su)';
var HTML_GAP = 1500;
var BINARY_GAP = 400;
var DEFAULT_PAUSE = 60 * 60 * 1000;
var BLOCK_CODES = [403, 429, 503];

var service = new Service('app.diafilmy.service');

// ---------- Очередь запросов ----------

var queue = [];
var busy = false;
var lastAt = 0;
var lastGap = 0;
var blockedUntil = 0;
var blockedCode = 0;
var siteCharset = 'utf-8';

function enqueue(gap, job) {
    queue.push({ gap: gap, job: job });
    pump();
}

function pump() {
    if (busy || !queue.length) return;
    var next = queue[0];
    var wait = lastAt + Math.max(lastGap, next.gap) - Date.now();
    if (wait > 0) {
        setTimeout(pump, wait);
        return;
    }
    queue.shift();
    busy = true;
    next.job(function () {
        busy = false;
        lastAt = Date.now();
        lastGap = next.gap;
        pump();
    });
}

// ---------- Кодировка windows-1251 (на случай, если сайт отдаёт её) ----------

var CP1251_80 = 'ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏђ‘’“”•–—�™љ›њќћџ ЎўЈ¤Ґ¦§Ё©Є«¬­®Ї°±Ііґµ¶·ё№є»јЅѕї';

function decode1251(buf) {
    var out = '';
    for (var i = 0; i < buf.length; i++) {
        var b = buf[i];
        if (b < 0x80) out += String.fromCharCode(b);
        else if (b < 0xC0) out += CP1251_80.charAt(b - 0x80);
        else out += String.fromCharCode(0x410 + b - 0xC0);
    }
    return out;
}

function encode1251Component(str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) bytes.push(c);
        else if (c >= 0x410 && c <= 0x44F) bytes.push(c - 0x410 + 0xC0);
        else {
            var k = CP1251_80.indexOf(str.charAt(i));
            bytes.push(k >= 0 ? 0x80 + k : 0x3F);
        }
    }
    var s = '';
    for (var j = 0; j < bytes.length; j++) {
        var b = bytes[j];
        var ch = String.fromCharCode(b);
        if (/[A-Za-z0-9\-_.~]/.test(ch)) s += ch;
        else if (b === 0x20) s += '+';
        else s += '%' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
    }
    return s;
}

function detectCharset(contentType, buf) {
    var m = /charset=([\w-]+)/i.exec(contentType || '');
    if (!m) m = /<meta[^>]+charset=["']?([\w-]+)/i.exec(buf.slice(0, 2048).toString('latin1'));
    var cs = m ? m[1].toLowerCase() : 'utf-8';
    if (cs === 'cp1251' || cs === 'windows-1251') return 'windows-1251';
    return 'utf-8';
}

function encodeForm(form) {
    var parts = [];
    Object.keys(form || {}).forEach(function (k) {
        var v = String(form[k]);
        var enc = siteCharset === 'windows-1251'
            ? encode1251Component(v)
            : encodeURIComponent(v).replace(/%20/g, '+');
        parts.push(encodeURIComponent(k) + '=' + enc);
    });
    return parts.join('&');
}

// ---------- HTTP ----------

function request(opts, cb, redirects) {
    redirects = redirects || 0;
    var u = urlm.parse(opts.url);
    var lib = u.protocol === 'http:' ? http : https;
    var body = opts.method === 'POST' ? encodeForm(opts.form) : null;
    var headers = {
        'User-Agent': UA,
        'Accept-Language': 'ru-RU,ru;q=0.9',
        'Accept': opts.binary ? 'image/*,*/*;q=0.8' : 'text/html,*/*;q=0.8'
    };
    if (body !== null) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        headers['Content-Length'] = Buffer.byteLength(body);
    }
    var done = false;
    function finish(err, res) {
        if (done) return;
        done = true;
        cb(err, res);
    }
    var req = lib.request({
        protocol: u.protocol,
        hostname: u.hostname,
        port: u.port,
        path: u.path,
        method: opts.method || 'GET',
        headers: headers
    }, function (res) {
        var status = res.statusCode;
        if (status >= 300 && status < 400 && res.headers.location && redirects < 5) {
            res.resume();
            var next = urlm.resolve(opts.url, res.headers.location);
            var nextOpts = { url: next, method: status === 303 ? 'GET' : opts.method, form: opts.form, binary: opts.binary };
            if (status === 301 || status === 302) nextOpts.method = 'GET';
            return request(nextOpts, finish, redirects + 1);
        }
        var chunks = [];
        res.on('data', function (c) { chunks.push(c); });
        res.on('end', function () {
            finish(null, { status: status, headers: res.headers, body: Buffer.concat(chunks), url: opts.url });
        });
        res.on('error', finish);
    });
    req.setTimeout(30000, function () { req.abort(); finish(new Error('Сайт не отвечает (таймаут)')); });
    req.on('error', finish);
    if (body !== null) req.write(body);
    req.end();
}

function blockedReply(msg) {
    msg.respond({ returnValue: true, blocked: true, status: blockedCode, until: blockedUntil });
}

// ---------- Методы службы ----------

/**
 * fetch { url, method?: "GET"|"POST", form?: {}, binary?: bool }
 * -> { status, body (текст или base64), contentType } | { blocked, status, until }
 */
service.register('fetch', function (msg) {
    var p = msg.payload || {};
    if (!p.url || !/^https?:\/\/(www\.)?diafilmy\.su\//.test(p.url)) {
        return msg.respond({ returnValue: false, errorText: 'Разрешены только адреса diafilmy.su' });
    }
    if (blockedUntil > Date.now()) return blockedReply(msg);

    enqueue(p.binary ? BINARY_GAP : HTML_GAP, function (next) {
        if (blockedUntil > Date.now()) { next(); return blockedReply(msg); }
        request({ url: p.url, method: p.method || 'GET', form: p.form, binary: !!p.binary }, function (err, res) {
            next();
            if (err) return msg.respond({ returnValue: false, errorText: String(err.message || err) });
            if (BLOCK_CODES.indexOf(res.status) >= 0) {
                var ra = parseInt(res.headers['retry-after'], 10);
                var pause = isNaN(ra) ? DEFAULT_PAUSE : Math.min(Math.max(ra * 1000, 5 * 60 * 1000), 24 * 3600 * 1000);
                blockedUntil = Date.now() + pause;
                blockedCode = res.status;
                return blockedReply(msg);
            }
            var ct = res.headers['content-type'] || '';
            var out = { returnValue: true, status: res.status, contentType: ct, url: res.url };
            if (p.binary) {
                out.body = res.body.toString('base64');
            } else {
                siteCharset = detectCharset(ct, res.body);
                out.body = siteCharset === 'windows-1251' ? decode1251(res.body) : res.body.toString('utf8');
            }
            msg.respond(out);
        });
    });
});

/** Ручная проверка по кнопке «Повторить»: снимаем паузу. */
service.register('clearBlock', function (msg) {
    blockedUntil = 0;
    msg.respond({ returnValue: true });
});

/** Состояние: версия, пауза, длина очереди. */
service.register('status', function (msg) {
    msg.respond({ returnValue: true, version: VERSION, blockedUntil: blockedUntil, queue: queue.length });
});
