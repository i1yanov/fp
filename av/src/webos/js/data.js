/*
 * Данные: только домашний сервер (помощник «Архив Вещания»).
 * С сайтом архива приложение не соединяется никогда: ни каталога, ни картинок, ни онлайн-просмотра.
 * Смотрится только то, что Android-устройства уже скачали на сервер.
 * На телевизоре хранится лишь адрес сервера и имя устройства; история и места остановки — на сервере.
 */
var Lib = {
    /** Элемент для экранов из записи библиотеки сервера (info.json). */
    item: function (li) {
        if (!li) return null;
        var parts = li.parts.slice().sort(function (a, b) { return a.part - b.part; }).map(function (p) {
            return { n: p.part, file: p.file, size: p.size || 0, done: !!p.done };
        });
        return { sec: li.sectionId, id: li.itemId, title: li.title || li.itemId, secTitle: li.sectionTitle || '', video: li.isVideo !== false,
            parts: parts, complete: parts.length > 0 && parts.every(function (p) { return p.done; }),
            size: parts.reduce(function (a, p) { return a + (p.done ? p.size : 0); }, 0) };
    },
    get: function (secId, itemId) { return this.item(Server.libItem(secId, itemId)); },
    /** Готовые к просмотру элементы раздела (по названию). */
    ready: function (secId) {
        return Server.library().map(Lib.item).filter(function (i) { return i.complete && (!secId || i.sec === secId); });
    },
    nextAfter: function (it, random) {
        var items = this.ready(it.sec).filter(function (i) { return i.id !== it.id; });
        if (!items.length) return null;
        if (random) return items[Math.floor(Math.random() * items.length)];
        var all = this.ready(it.sec), i = -1;
        for (var k = 0; k < all.length; k++) if (all[k].id === it.id) { i = k; break; }
        return all[(i + 1) % all.length].id === it.id ? null : all[(i + 1) % all.length];
    },
    art: function (secId) { return secId === 'audio' ? 'record' : secId === 'radioteatr' ? 'radio' : 'film'; },
    trackTitle: function (it, part) {
        if (it.parts.length === 1) return it.title;
        var stem = part.file.replace(/\.[^.]+$/, '');
        try { stem = decodeURIComponent(stem.replace(/\+/g, '%2B')); } catch (e) { /* ignore */ }
        return stem.replace(/_/g, ' ').trim() || ('Запись ' + (part.n + 1));
    }
};

var Server = (function () {
    'use strict';
    var url = '', deviceId = '', deviceName = 'Телевизор LG';
    var library = [], libByKey = {}, history = [], histByKey = {};
    var listeners = [], online = false, lastError = null;

    function emit() { listeners.forEach(function (f) { try { f(); } catch (e) { /* ignore */ } }); }

    function api(method, path, body, timeout) {
        if (!url) return Promise.reject(new Error('Домашний сервер не настроен'));
        return U.request(method, url + path, body, timeout);
    }

    function setLibrary(items) {
        library = items || [];
        libByKey = {};
        library.forEach(function (i) { libByKey[i.sectionId + '|' + i.itemId] = i; });
    }

    function setHistory(recs) {
        history = recs || [];
        histByKey = {};
        history.forEach(function (r) { histByKey[r.s + '|' + r.i] = r; });
    }

    function encodePath(p) { return p.split('/').map(encodeURIComponent).join('/'); }

    return {
        init: function () {
            try {
                url = localStorage.getItem('server') || '';
                deviceId = localStorage.getItem('deviceId') || '';
                if (!deviceId) { deviceId = U.uuid(); localStorage.setItem('deviceId', deviceId); }
                deviceName = localStorage.getItem('deviceName') || deviceName;
            } catch (e) { /* ignore */ }
            if (url) this.refresh();
            var self = this;
            setInterval(function () { if (url) self.refresh(); }, 30000);
        },
        url: function () { return url; },
        configured: function () { return !!url; },
        online: function () { return online; },
        error: function () { return lastError; },
        deviceName: function () { return deviceName; },
        onChange: function (f) { listeners.push(f); return function () { listeners = listeners.filter(function (x) { return x !== f; }); }; },

        normalizeUrl: function (s) {
            s = (s || '').trim();
            if (!s) return '';
            if (!/^https?:\/\//.test(s)) s = 'http://' + s;
            if (!/:\d+(\/|$)/.test(s.replace(/^https?:\/\//, ''))) s = s.replace(/\/$/, '') + ':8765';
            return s.replace(/\/$/, '');
        },
        test: function (u) { return U.request('GET', u + '/api/status', null, 8000); },
        save: function (u, name) {
            url = u;
            if (name) deviceName = name;
            try { localStorage.setItem('server', url); localStorage.setItem('deviceName', deviceName); } catch (e) { /* ignore */ }
            setLibrary([]); setHistory([]);
            return this.refresh();
        },

        /** Обновить библиотеку и историю с сервера. */
        refresh: function () {
            return Promise.all([api('GET', '/api/library'), api('GET', '/api/history')]).then(function (r) {
                setLibrary(r[0].items); setHistory(r[1].records);
                online = true; lastError = null; emit();
            }, function (e) { online = false; lastError = e.message; emit(); });
        },

        // ---------------------------------------------------------- library
        library: function () { return library; },
        libItem: function (secId, itemId) { return libByKey[secId + '|' + itemId] || null; },
        /** Адрес готовой части на сервере; null — части нет (тогда смотреть нечего). */
        partUrl: function (secId, itemId, n) {
            var li = libByKey[secId + '|' + itemId];
            if (!li) return null;
            for (var i = 0; i < li.parts.length; i++) {
                var p = li.parts[i];
                if (p.part === n && p.done) return url + '/files/' + encodePath(li.folder + '/' + p.file);
            }
            return null;
        },
        remove: function (secId, itemId) {
            var self = this;
            return api('POST', '/api/library/delete', { sectionId: secId, itemId: itemId }).then(function () { return self.refresh(); });
        },

        // ---------------------------------------------------------- history (формат Android)
        history: function () { return history; },
        hist: function (secId, itemId) { return histByKey[secId + '|' + itemId] || null; },
        saveHistory: function (rec) {
            rec.u = Date.now();
            rec.dev = deviceName;
            rec.did = deviceId;
            rec.del = !!rec.del;
            histByKey[rec.s + '|' + rec.i] = rec;
            history = [rec].concat(history.filter(function (r) { return !(r.s === rec.s && r.i === rec.i); }));
            if (rec.del) history = history.filter(function (r) { return !r.del; });
            emit();
            if (!url) return Promise.resolve();
            return api('POST', '/api/history', { deviceId: deviceId, deviceName: deviceName, record: rec }).catch(function () { /* ignore */ });
        },
        removeHistory: function (r) {
            var rec = {}; for (var k in r) rec[k] = r[k];
            rec.del = true;
            return this.saveHistory(rec);
        },
        isMine: function (r) { return r.did === deviceId; }
    };
})();

/** Помощники для записей истории (как HistoryEntry в Android). */
var Hist = {
    canResume: function (r) { return r && !r.f && (r.pos >= 10000 || r.p > 0); },
    progress: function (r) {
        if (!r) return 0;
        if (r.f) return 1;
        var inPart = r.dur > 0 ? Math.min(1, r.pos / r.dur) : 0;
        return Math.min(1, (r.p + inPart) / Math.max(1, r.pc));
    },
    line: function (r, isVideo) {
        var where = !Server.isMine(r) && r.dev ? ' · на «' + r.dev + '»' : '';
        if (r.f) return (isVideo ? 'Просмотрено' : 'Прослушано') + ' · ' + U.when(r.u) + where;
        var unit = isVideo ? 'серия' : 'запись';
        var part = r.pc > 1 ? unit + ' ' + (r.p + 1) + ' из ' + r.pc + ', ' : '';
        return 'Остановились: ' + part + U.ms(r.pos) + ' · ' + U.when(r.u) + where;
    }
};
