/*
 * Данные: встроенный каталог архива и домашний сервер (помощник «Архив Вещания»).
 * На телевизоре ничего не хранится, кроме адреса сервера и имени устройства:
 * загруженное, история и места остановки — всё на сервере, общее с Android-устройствами.
 */
var Catalog = (function () {
    'use strict';
    var sections = {}, waiting = {};

    /** Элемент каталога из компактного вида [id,title,auto,dur,size,added,descr,parts]. */
    function item(a) {
        return {
            id: a[0], title: a[1], titleAuto: !!a[2], dur: a[3], size: a[4], added: a[5], descr: a[6],
            parts: a[7].map(function (p, i) { return { n: i, file: p[0], path: p[1], size: p[2], res: p[3], dur: p[4], group: p[5] }; }),
            key: U.norm(a[1])
        };
    }

    return {
        index: function () { return CATALOG_INDEX; },
        /** Загружает раздел (скрипт catalog/<id>.js). */
        load: function (id) {
            if (sections[id]) return Promise.resolve(sections[id]);
            return new Promise(function (resolve, reject) {
                (waiting[id] = waiting[id] || []).push(resolve);
                if (waiting[id].length > 1) return;
                var s = document.createElement('script');
                s.src = 'catalog/' + id + '.js';
                s.onerror = function () { reject(new Error('Не удалось открыть каталог')); };
                document.head.appendChild(s);
            });
        },
        loaded: function (d) {
            var sec = { id: d.id, title: d.title, host: d.host, kind: d.kind, random: d.random, items: d.items.map(item) };
            sec.byId = {};
            sec.items.forEach(function (it) { sec.byId[it.id] = it; });
            sections[d.id] = sec;
            (waiting[d.id] || []).forEach(function (r) { r(sec); });
            waiting[d.id] = [];
        },
        isVideo: function (sec) { return sec.kind !== 'audio'; },
        thumb: function (sec, it, n) {
            return sec.kind === 'audio' ? null : 'http://' + sec.host + '/ap/' + it.id + '/' + it.id + '.thumb' + (n || 1) + '.jpg';
        },
        image: function (sec, it, n) {
            return sec.kind === 'audio' ? null : 'http://' + sec.host + '/ap/' + it.id + '/' + it.id + '.image' + n + '.jpg';
        },
        remote: function (sec, part) { return 'http://' + sec.host + part.path; },
        nextAfter: function (sec, id, random) {
            var items = sec.items;
            if (!items.length) return null;
            if (random) {
                if (items.length === 1) return items[0];
                var r;
                do { r = items[Math.floor(Math.random() * items.length)]; } while (r.id === id);
                return r;
            }
            var i = -1;
            for (var k = 0; k < items.length; k++) if (items[k].id === id) { i = k; break; }
            return items[(i + 1) % items.length];
        },
        art: function (secId) {
            return { multiki: 'palette', film: 'camera', filmiki: 'tv', audio: 'record', radioteatr: 'radio' }[secId] || 'film';
        },
        thumbArt: function (sec) { return sec.kind !== 'audio' ? 'film' : sec.id === 'radioteatr' ? 'radio' : 'record'; },
        trackTitle: function (it, part) {
            if (it.parts.length === 1) return it.title;
            var stem = part.file.replace(/\.[^.]+$/, '');
            try { stem = decodeURIComponent(stem.replace(/\+/g, '%2B')); } catch (e) { /* ignore */ }
            return stem.replace(/_/g, ' ').trim() || ('Запись ' + (part.n + 1));
        }
    };
})();

var Server = (function () {
    'use strict';
    var url = '', deviceId = '', deviceName = 'Телевизор LG';
    var library = [], libByKey = {}, history = [], histByKey = {}, tasks = [];
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

        /** Обновить библиотеку, историю и очередь с сервера. */
        refresh: function () {
            return Promise.all([api('GET', '/api/library'), api('GET', '/api/history'), api('GET', '/api/downloads')]).then(function (r) {
                setLibrary(r[0].items); setHistory(r[1].records); tasks = r[2].tasks || [];
                online = true; lastError = null; emit();
            }, function (e) { online = false; lastError = e.message; emit(); });
        },
        refreshTasks: function () {
            return api('GET', '/api/downloads').then(function (r) { tasks = r.tasks || []; emit(); }, function () { /* ignore */ });
        },

        // ---------------------------------------------------------- library
        library: function () { return library; },
        libItem: function (secId, itemId) { return libByKey[secId + '|' + itemId] || null; },
        /** Готовая часть на сервере → адрес файла. */
        partUrl: function (secId, itemId, n) {
            var li = libByKey[secId + '|' + itemId];
            if (!li) return null;
            for (var i = 0; i < li.parts.length; i++) {
                var p = li.parts[i];
                if (p.part === n && p.done) return url + '/files/' + encodePath(li.folder + '/' + p.file);
            }
            return null;
        },
        tasks: function () { return tasks; },
        itemTasks: function (secId, itemId) { return tasks.filter(function (t) { return t.sectionId === secId && t.itemId === itemId && t.state !== 'done'; }); },
        download: function (sec, it) {
            var body = {
                sectionId: sec.id, itemId: it.id, title: it.title, sectionTitle: sec.title, isVideo: sec.kind !== 'audio',
                parts: it.parts.map(function (p) { return { part: p.n, file: p.file, url: Catalog.remote(sec, p), size: p.size }; })
            };
            var self = this;
            return api('POST', '/api/download', body).then(function () { return self.refresh(); });
        },
        cancel: function (secId, itemId) {
            var self = this;
            return api('POST', '/api/download/cancel', { sectionId: secId, itemId: itemId }).then(function () { return self.refresh(); });
        },
        remove: function (secId, itemId) {
            var self = this;
            return api('POST', '/api/library/delete', { sectionId: secId, itemId: itemId }).then(function () { return self.refresh(); });
        },

        /** Что играть: файл на сервере → архив через помощника → архив напрямую (без сервера). */
        playUrl: function (sec, it, n) {
            var f = this.partUrl(sec.id, it.id, n);
            if (f) return f;
            var remote = Catalog.remote(sec, it.parts[n]);
            if (url) return url + '/stream?lane=' + encodeURIComponent(sec.id) + '&url=' + encodeURIComponent(remote);
            return remote;
        },
        streamStatus: function (lane) {
            return api('GET', '/api/stream-status?lane=' + encodeURIComponent(lane), null, 5000).then(function (r) { return r.status; });
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
