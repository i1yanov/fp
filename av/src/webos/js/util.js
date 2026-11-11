/*
 * Общие помощники: разметка, форматирование, запросы, рисунки в советском стиле (SVG),
 * индикатор ожидания «3-2-1», всплывающие сообщения, окна с вопросами.
 */
var U = (function () {
    'use strict';

    function h(html) {
        var d = document.createElement('div');
        d.innerHTML = html.trim();
        return d.firstChild;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function pad(n) { return n < 10 ? '0' + n : '' + n; }

    function dur(sec) {
        sec = Math.max(0, Math.floor(sec || 0));
        var h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
        return h ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
    }

    function ms(v) { return dur((v || 0) / 1000); }

    function size(b) {
        if (b >= 1e9) return (b / 1e9).toFixed(1).replace('.', ',') + ' ГБ';
        if (b >= 1e6) return Math.round(b / 1e6) + ' МБ';
        return Math.round((b || 0) / 1e3) + ' КБ';
    }

    function count(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' '); }

    function plural(n, one, few, many) {
        var a = n % 10, b = n % 100;
        if (a === 1 && b !== 11) return one;
        if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return few;
        return many;
    }

    var MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

    function isoDate(s) {
        var p = (s || '').split('-');
        if (p.length !== 3) return s || '';
        return (+p[2]) + ' ' + MONTHS[+p[1] - 1] + ' ' + p[0];
    }

    function when(t) {
        var d = new Date(t), now = new Date();
        var hm = pad(d.getHours()) + ':' + pad(d.getMinutes());
        var day0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        if (t >= day0) return 'сегодня, ' + hm;
        if (t >= day0 - 864e5) return 'вчера, ' + hm;
        if (d.getFullYear() === now.getFullYear()) return d.getDate() + ' ' + MONTHS[d.getMonth()] + ', ' + hm;
        return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
    }

    function norm(s) { return String(s || '').toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ').trim(); }

    function uuid() {
        return 'tv-xxxxxxxx-xxxx'.replace(/x/g, function () { return (Math.random() * 16 | 0).toString(16); });
    }

    /** XHR → Promise с JSON. */
    function request(method, url, body, timeout) {
        return new Promise(function (resolve, reject) {
            var x = new XMLHttpRequest();
            x.open(method, url, true);
            x.timeout = timeout || 15000;
            if (body) x.setRequestHeader('Content-Type', 'application/json');
            x.onload = function () {
                if (x.status >= 200 && x.status < 300) {
                    try { resolve(x.responseText ? JSON.parse(x.responseText) : {}); } catch (e) { reject(new Error('Неверный ответ сервера')); }
                } else reject(new Error('Сервер ответил ' + x.status));
            };
            x.onerror = function () { reject(new Error('Нет связи с сервером')); };
            x.ontimeout = function () { reject(new Error('Сервер не отвечает')); };
            x.send(body ? JSON.stringify(body) : null);
        });
    }

    var toastTimer = null;
    function toast(text, timeout) {
        var t = document.getElementById('toast');
        t.textContent = text;
        t.classList.remove('hidden');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { t.classList.add('hidden'); }, timeout || 3000);
    }

    // ---------------------------------------------------------------- рисунки (SVG)
    var C = { paper: '#F3E6C8', paperDark: '#E6D3AA', red: '#9E2B25', redDark: '#6E1B17', ink: '#3B2A20', inkSoft: '#6B5444', mustard: '#D9A441', teal: '#2F5D62', film: '#2A1E17', wood: '#C8955A', steel: '#9A968C', steelLight: '#C9C4B6' };

    function reel(cx, cy, r, fg, bg) {
        var s = '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + fg + '"/>';
        for (var k = 0; k < 5; k++) {
            var a = (k * 72 - 90) * Math.PI / 180;
            s += '<circle cx="' + (cx + Math.cos(a) * r * 0.55).toFixed(1) + '" cy="' + (cy + Math.sin(a) * r * 0.55).toFixed(1) + '" r="' + (r * 0.22).toFixed(1) + '" fill="' + bg + '"/>';
        }
        return s + '<circle cx="' + cx + '" cy="' + cy + '" r="' + (r * 0.1).toFixed(1) + '" fill="' + bg + '"/>';
    }

    function perfs(y, w, h, color) {
        var s = '', step = w * 0.12, hw = w * 0.06, n = Math.floor((w * 0.94 - hw) / step) + 1;
        var start = (w - ((n - 1) * step + hw)) / 2;
        for (var i = 0; i < n; i++) s += '<rect x="' + (start + i * step).toFixed(1) + '" y="' + y + '" width="' + hw.toFixed(1) + '" height="' + h + '" fill="' + color + '"/>';
        return s;
    }

    /** Рисунок-заглушка: film, palette, camera, tv, record, radio, can, clock, gear. */
    function art(kind) {
        var W = 400, H = 300, b = '';
        var cx = W / 2, cy = H / 2;
        switch (kind) {
            case 'film':
                b = '<rect width="400" height="300" fill="' + C.film + '"/>' + perfs(12, W, 22, C.paperDark) + perfs(H - 34, W, 22, C.paperDark) +
                    '<rect x="32" y="54" width="336" height="192" fill="' + C.inkSoft + '"/>' + reel(cx, cy, 54, C.paper, C.inkSoft);
                break;
            case 'palette':
                b = '<ellipse cx="' + cx + '" cy="' + cy + '" rx="170" ry="115" fill="' + C.wood + '"/>' +
                    '<circle cx="262" cy="182" r="26" fill="' + C.paperDark + '"/>' +
                    '<circle cx="118" cy="122" r="24" fill="' + C.red + '"/><circle cx="168" cy="88" r="24" fill="' + C.mustard + '"/>' +
                    '<circle cx="226" cy="92" r="24" fill="' + C.teal + '"/><circle cx="112" cy="188" r="24" fill="' + C.paper + '"/>' +
                    '<circle cx="170" cy="212" r="24" fill="' + C.ink + '"/>' +
                    '<g transform="rotate(-35 220 150)"><line x1="190" y1="150" x2="330" y2="150" stroke="' + C.ink + '" stroke-width="12" stroke-linecap="round"/>' +
                    '<rect x="166" y="141" width="24" height="18" fill="' + C.steel + '"/><polygon points="166,140 130,150 166,160" fill="' + C.red + '"/></g>';
                break;
            case 'camera':
                b = reel(124, 92, 42, C.ink, C.paperDark) + reel(222, 92, 42, C.ink, C.paperDark) +
                    '<rect x="88" y="128" width="168" height="90" rx="12" fill="' + C.ink + '"/>' +
                    '<rect x="102" y="148" width="68" height="18" rx="5" fill="' + C.red + '"/><circle cx="224" cy="156" r="9" fill="' + C.mustard + '"/>' +
                    '<polygon points="256,158 314,134 314,212 256,188" fill="' + C.inkSoft + '"/>' +
                    '<line x1="172" y1="218" x2="130" y2="262" stroke="' + C.ink + '" stroke-width="8"/><line x1="172" y1="218" x2="214" y2="262" stroke="' + C.ink + '" stroke-width="8"/><line x1="172" y1="218" x2="172" y2="266" stroke="' + C.ink + '" stroke-width="8"/>';
                break;
            case 'tv':
                b = '<line x1="200" y1="78" x2="152" y2="30" stroke="' + C.ink + '" stroke-width="6"/><line x1="200" y1="78" x2="254" y2="34" stroke="' + C.ink + '" stroke-width="6"/>' +
                    '<line x1="128" y1="236" x2="114" y2="262" stroke="' + C.ink + '" stroke-width="8"/><line x1="272" y1="236" x2="286" y2="262" stroke="' + C.ink + '" stroke-width="8"/>' +
                    '<rect x="80" y="78" width="240" height="158" rx="16" fill="' + C.wood + '"/>' +
                    '<rect x="94" y="94" width="160" height="126" rx="20" fill="' + C.teal + '"/><rect x="106" y="104" width="44" height="22" rx="10" fill="' + C.paper + '" opacity=".35"/>' +
                    '<circle cx="288" cy="118" r="12" fill="' + C.ink + '"/><circle cx="288" cy="154" r="12" fill="' + C.ink + '"/>' +
                    '<line x1="270" y1="186" x2="306" y2="186" stroke="' + C.ink + '" stroke-width="4"/><line x1="270" y1="198" x2="306" y2="198" stroke="' + C.ink + '" stroke-width="4"/><line x1="270" y1="210" x2="306" y2="210" stroke="' + C.ink + '" stroke-width="4"/>' +
                    '<circle cx="200" cy="78" r="9" fill="' + C.ink + '"/>';
                break;
            case 'record':
                b = '<circle cx="200" cy="150" r="118" fill="' + C.film + '"/>';
                for (var k = 1; k <= 4; k++) b += '<circle cx="200" cy="150" r="' + (118 * (0.5 + k * 0.11)).toFixed(0) + '" fill="none" stroke="#4A3A30" stroke-width="2"/>';
                b += '<circle cx="200" cy="150" r="42" fill="' + C.red + '"/><circle cx="200" cy="150" r="6" fill="' + C.paper + '"/>' +
                    '<path d="M110 110 A100 100 0 0 1 150 70" fill="none" stroke="' + C.paper + '" stroke-opacity=".25" stroke-width="6"/>';
                break;
            case 'radio':
                b = '<rect x="88" y="104" width="224" height="150" rx="22" fill="' + C.teal + '"/><circle cx="160" cy="179" r="45" fill="' + C.paper + '"/>' +
                    '<line x1="226" y1="146" x2="290" y2="146" stroke="' + C.paper + '" stroke-width="7"/><line x1="226" y1="168" x2="290" y2="168" stroke="' + C.paper + '" stroke-width="7"/>' +
                    '<line x1="226" y1="190" x2="290" y2="190" stroke="' + C.paper + '" stroke-width="7"/><line x1="226" y1="212" x2="290" y2="212" stroke="' + C.paper + '" stroke-width="7"/>' +
                    '<line x1="155" y1="104" x2="115" y2="52" stroke="' + C.ink + '" stroke-width="6"/>' +
                    '<path d="M122 36 A22 22 0 0 1 140 52" fill="none" stroke="' + C.red + '" stroke-width="5"/><path d="M128 20 A40 40 0 0 1 156 48" fill="none" stroke="' + C.red + '" stroke-width="5"/>';
                break;
            case 'can':
                b = '<ellipse cx="200" cy="208" rx="140" ry="40" fill="' + C.steel + '"/><rect x="60" y="138" width="280" height="70" fill="' + C.steel + '"/>' +
                    '<rect x="60" y="156" width="280" height="30" fill="' + C.red + '"/><ellipse cx="200" cy="138" rx="140" ry="40" fill="' + C.steelLight + '"/>' +
                    '<ellipse cx="200" cy="138" rx="104" ry="29" fill="none" stroke="' + C.steel + '" stroke-width="4"/><ellipse cx="200" cy="138" rx="22" ry="8" fill="' + C.steel + '"/>' +
                    '<circle cx="290" cy="74" r="40" fill="' + C.teal + '"/><line x1="290" y1="52" x2="290" y2="86" stroke="' + C.paper + '" stroke-width="8" stroke-linecap="round"/>' +
                    '<polygon points="272,76 290,98 308,76" fill="' + C.paper + '"/>';
                break;
            case 'clock':
                b = '<path d="M106 92 A132 132 0 1 1 112 210" fill="none" stroke="' + C.red + '" stroke-width="12"/>' +
                    '<polygon points="88,78 124,96 98,118" fill="' + C.red + '"/>' +
                    '<circle cx="200" cy="150" r="88" fill="' + C.ink + '"/><circle cx="200" cy="150" r="76" fill="' + C.paper + '"/>';
                for (var t = 0; t < 12; t++) {
                    var an = t * 30 * Math.PI / 180, l = t % 3 === 0 ? 16 : 8;
                    b += '<line x1="' + (200 + Math.cos(an) * (68 - l)).toFixed(1) + '" y1="' + (150 + Math.sin(an) * (68 - l)).toFixed(1) + '" x2="' + (200 + Math.cos(an) * 68).toFixed(1) + '" y2="' + (150 + Math.sin(an) * 68).toFixed(1) + '" stroke="' + C.ink + '" stroke-width="4"/>';
                }
                b += '<line x1="200" y1="150" x2="200" y2="102" stroke="' + C.ink + '" stroke-width="8" stroke-linecap="round"/><line x1="200" y1="150" x2="164" y2="160" stroke="' + C.teal + '" stroke-width="6" stroke-linecap="round"/><circle cx="200" cy="150" r="7" fill="' + C.red + '"/>';
                break;
            case 'gear':
                b = '<g transform="translate(200 150)">';
                for (var g = 0; g < 8; g++) b += '<rect x="-16" y="-104" width="32" height="40" rx="5" fill="' + C.teal + '" transform="rotate(' + (g * 45) + ')"/>';
                b += '<circle r="74" fill="' + C.teal + '"/><circle r="32" fill="' + C.paperDark + '"/></g>' + reel(320, 230, 34, C.ink, C.paperDark);
                break;
        }
        return '<svg class="art" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">' +
            '<rect width="400" height="300" fill="' + C.paperDark + '"/>' + b + '</svg>';
    }

    /** Индикатор ожидания: отсчёт киноплёнки «3-2-1». */
    var LOADER = '<div class="leader"><svg viewBox="0 0 200 200"><rect width="200" height="200" fill="' + C.film + '"/>' +
        '<circle cx="100" cy="100" r="88" fill="' + C.paperDark + '"/>' +
        '<g class="sweep"><path d="M100 100 L100 12 A88 88 0 0 1 188 100 Z" fill="' + C.inkSoft + '" opacity=".55"/></g>' +
        '<line x1="0" y1="100" x2="200" y2="100" stroke="' + C.ink + '" stroke-width="3"/><line x1="100" y1="0" x2="100" y2="200" stroke="' + C.ink + '" stroke-width="3"/>' +
        '<circle cx="100" cy="100" r="88" fill="none" stroke="' + C.paper + '" stroke-width="5"/><circle cx="100" cy="100" r="66" fill="none" stroke="' + C.paper + '" stroke-width="3"/>' +
        '</svg><div class="num"><span>3</span><span>2</span><span>1</span></div><div class="cap">Заряжаем плёнку…</div></div>';

    /** Окно с вопросом: actions = [{label, primary, action}]. Первая кнопка получает фокус. */
    function confirm(title, text, actions) {
        var m = h('<div class="modal"><div class="box"><h2>' + esc(title) + '</h2><p>' + esc(text).replace(/\n/g, '<br>') + '</p><div class="acts"></div></div></div>');
        var acts = m.querySelector('.acts');
        function close() { if (m.parentNode) m.parentNode.removeChild(m); Nav.restore(); }
        actions.concat([{ label: 'Отмена', cancel: true }]).forEach(function (a) {
            var b = h('<div class="btn f' + (a.primary ? ' red' : a.cancel ? ' soft' : ' teal') + '">' + esc(a.label) + '</div>');
            b.addEventListener('click', function () { close(); if (a.action) a.action(); });
            acts.appendChild(b);
        });
        m.onBack = close;
        Nav.remember();
        document.body.appendChild(m);
        Nav.focus(acts.firstChild);
        return close;
    }

    return {
        h: h, esc: esc, dur: dur, ms: ms, size: size, count: count, plural: plural, isoDate: isoDate, when: when, norm: norm,
        uuid: uuid, request: request, toast: toast, art: art, LOADER: LOADER, confirm: confirm, C: C
    };
})();
