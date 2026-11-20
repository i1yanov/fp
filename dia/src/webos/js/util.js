/* Диафильмы для webOS — общие утилиты, хранилище и сеть (через фоновую службу). ES2015 для webOS 4. */
(function () {
    'use strict';
    var App = window.App = window.App || {};
    App.VERSION = '1.7.0';
    App.BASE = 'https://diafilmy.su/';
    App.SERVICE = 'luna://app.diafilmy.service/';

    // ---------- DOM ----------
    App.$ = function (sel, root) { return (root || document).querySelector(sel); };
    App.$$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

    /** h('div.card.f', {onclick: fn, text: '...'}, [children]) */
    App.h = function (spec, attrs, children) {
        var parts = spec.split('.');
        var el = document.createElement(parts[0] || 'div');
        if (parts.length > 1) el.className = parts.slice(1).join(' ');
        attrs = attrs || {};
        Object.keys(attrs).forEach(function (k) {
            var v = attrs[k];
            if (v === undefined || v === null) return;
            if (k === 'text') el.textContent = v;
            else if (k === 'html') el.innerHTML = v;
            else if (k.indexOf('on') === 0) el[k] = v;
            else if (k === 'style') el.style.cssText = v;
            else el.setAttribute(k, v);
        });
        (children || []).forEach(function (c) {
            if (c === null || c === undefined || c === false) return;
            el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
        });
        return el;
    };

    App.clear = function (el) { while (el.firstChild) el.removeChild(el.firstChild); };

    App.plural = function (n, one, few, many) {
        var m10 = n % 10, m100 = n % 100;
        if (m10 === 1 && m100 !== 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    };

    App.fmtDate = function (ms) {
        if (!ms) return null;
        var d = new Date(ms);
        return d.getDate() + '.' + ('0' + (d.getMonth() + 1)).slice(-2) + '.' + d.getFullYear();
    };

    App.fmtTime = function (ms) {
        var d = new Date(ms);
        return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    };

    App.fmtSize = function (b) {
        if (b >= 1073741824) return (b / 1073741824).toFixed(1).replace('.', ',') + ' ГБ';
        if (b >= 1048576) return (b / 1048576).toFixed(1).replace('.', ',') + ' МБ';
        return Math.round(b / 1024) + ' КБ';
    };

    App.delay = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

    // ---------- Хранилище (localStorage + JSON) ----------
    App.store = {
        get: function (k, def) {
            try {
                var v = localStorage.getItem(k);
                return v === null ? def : JSON.parse(v);
            } catch (e) { return def; }
        },
        set: function (k, v) {
            try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; }
        },
        del: function (k) { try { localStorage.removeItem(k); } catch (e) { /* ignore */ } }
    };

    // ---------- Сеть ----------
    var bridges = [];

    /** Вызов метода фоновой службы через шину webOS (как это делает webOSTV.js). */
    function luna(method, params) {
        return new Promise(function (resolve, reject) {
            if (typeof window.PalmServiceBridge === 'undefined') {
                reject(new Error('NO_BRIDGE'));
                return;
            }
            var bridge = new window.PalmServiceBridge();
            bridges.push(bridge);
            bridge.onservicecallback = function (msg) {
                var i = bridges.indexOf(bridge);
                if (i >= 0) bridges.splice(i, 1);
                var r;
                try { r = JSON.parse(msg); } catch (e) { reject(new Error('Плохой ответ службы')); return; }
                if (r.returnValue === false) reject(new Error(r.errorText || 'Служба не ответила'));
                else resolve(r);
            };
            bridge.call(App.SERVICE + method, JSON.stringify(params || {}));
        });
    }
    App.luna = luna;

    var htmlCache = {};
    var htmlOrder = [];
    var blockedUntil = App.store.get('blocked_until', 0);
    var blockedCode = App.store.get('blocked_code', 403);
    var blockListeners = [];

    App.net = {
        isBlocked: function () { return blockedUntil > Date.now(); },
        blockedUntil: function () { return blockedUntil; },
        onBlockChange: function (fn) { blockListeners.push(fn); },

        blockedError: function () {
            var e = new Error('Сайт временно ограничил доступ с вашего адреса (код ' + blockedCode + '). ' +
                'Чтобы не ухудшать ситуацию, приложение не обращается к сайту до ' + App.fmtTime(blockedUntil) +
                '. Сохранённые диафильмы доступны без сети.');
            e.blocked = true;
            return e;
        },

        markBlocked: function (code, until) {
            blockedCode = code || 403;
            blockedUntil = until || (Date.now() + 3600000);
            App.store.set('blocked_until', blockedUntil);
            App.store.set('blocked_code', blockedCode);
            blockListeners.forEach(function (f) { f(); });
        },

        /** Кнопка «Повторить»: снимаем паузу, дальше будет один запрос. */
        clearBlock: function () {
            blockedUntil = 0;
            App.store.del('blocked_until');
            luna('clearBlock', {}).catch(function () { /* служба могла быть выгружена */ });
            blockListeners.forEach(function (f) { f(); });
        },

        /** Запрос через службу. opts: {method, form, binary}. Возвращает {status, body}. */
        request: function (url, opts) {
            opts = opts || {};
            if (App.net.isBlocked()) return Promise.reject(App.net.blockedError());
            return luna('fetch', { url: url, method: opts.method || 'GET', form: opts.form, binary: !!opts.binary })
                .then(function (r) {
                    if (r.blocked) {
                        App.net.markBlocked(r.status, r.until);
                        throw App.net.blockedError();
                    }
                    return r;
                }, function (err) {
                    if (err.message !== 'NO_BRIDGE') throw err;
                    // Запуск в обычном браузере (отладка на компьютере): прямой запрос
                    return window.fetch(url, { method: opts.method || 'GET' }).then(function (resp) {
                        return (opts.binary ? resp.blob() : resp.text()).then(function (body) {
                            return { status: resp.status, body: body, direct: true };
                        });
                    });
                });
        },

        /** HTML-страница сайта. 404 — не ошибка (пустая страница DLE).
         *  GET-ответы 15 минут берутся из памяти: одна и та же страница не запрашивается повторно. */
        text: function (url, opts) {
            var isGet = !opts || !opts.method || opts.method === 'GET';
            var hit = isGet ? htmlCache[url] : null;
            if (hit && Date.now() - hit.t < 15 * 60000) return hit.p;
            var p = App.net.request(url, opts).then(function (r) {
                if ((r.status < 200 || r.status >= 300) && r.status !== 404) throw new Error('Сайт ответил ошибкой ' + r.status);
                return r.body;
            });
            if (isGet) {
                htmlCache[url] = { t: Date.now(), p: p };
                htmlOrder.push(url);
                if (htmlOrder.length > 40) delete htmlCache[htmlOrder.shift()];
                p.catch(function () { delete htmlCache[url]; });
            }
            return p;
        },

        /** Картинка как Blob (для «Сохранённого»). */
        blob: function (url) {
            return App.net.request(url, { binary: true }).then(function (r) {
                if (r.status < 200 || r.status >= 300) throw new Error('Кадр не загрузился (' + r.status + ')');
                if (r.direct) return r.body;
                var bin = atob(r.body);
                var arr = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                var type = /\.png$/i.test(url) ? 'image/png' : (/\.gif$/i.test(url) ? 'image/gif' : 'image/jpeg');
                return new Blob([arr], { type: type });
            });
        }
    };

    // ---------- Тосты ----------
    var toastTimer = null;
    App.toast = function (text, ms) {
        var t = App.$('#toast');
        if (!t) return;
        t.textContent = text;
        t.classList.remove('hidden');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { t.classList.add('hidden'); }, ms || 3500);
    };
})();
