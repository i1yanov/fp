/* Данные: категории, каталог (индекс категорий), годы выпуска, «Сохранённое» (IndexedDB). */
(function () {
    'use strict';
    var App = window.App;
    var P = function () { return App.parser; };

    // ---------- Категории ----------
    App.CATEGORIES = [
        { slug: 'skazki', title: 'Сказки', path: 'diafilmy/skazki/', count: 1360 },
        { slug: 'multfilmy', title: 'Мультфильмы', path: 'diafilmy/multfilmy/', count: 54 },
        { slug: 'stihi_basni', title: 'Стихи и басни', path: 'diafilmy/stihi_basni/', count: 349 },
        { slug: 'rasskazy', title: 'Повести и рассказы', path: 'diafilmy/rasskazy/', count: 931 },
        { slug: 'uchebnye', title: 'Образовательные и учебные', path: 'diafilmy/uchebnye/', count: 2255 },
        { slug: 'istoricheskie', title: 'Исторические и документальные', path: 'diafilmy/istoricheskie/', count: 1461 },
        { slug: 'diafilmy_sound', title: 'Озвученные диафильмы', path: 'diafilmy/diafilmy_sound/', count: 18 },
        { slug: 'drugie_yaziki', title: 'На других языках', path: 'diafilmy/drugie_yaziki/', count: 246 },
        { slug: 'defa', title: 'Студия DEFA', path: 'defa/', count: 5 }
    ];
    var byPath = {};
    App.CATEGORIES.forEach(function (c) { byPath[c.path.replace(/\/$/, '')] = c; });

    App.categoryBySlug = function (slug) {
        for (var i = 0; i < App.CATEGORIES.length; i++) if (App.CATEGORIES[i].slug === slug) return App.CATEGORIES[i];
        return null;
    };
    App.categoryByUrl = function (url) {
        var i = (url || '').indexOf('diafilmy.su/');
        if (i < 0) return null;
        var path = url.substring(i + 12).split('?')[0].split('#')[0].replace(/\/+$/, '');
        if (!path || path.indexOf('/page/') >= 0) return null;
        return byPath[path] || null;
    };

    // ---------- Каталог категорий ----------
    var PAGE = 21;
    var states = {};
    var listeners = {};
    var remoteTotals = {};
    var syncing = {};

    function pack(f) { return [f.id, f.title, f.thumb || '', f.added || 0, f.url]; }
    function unpack(a) { return { id: a[0], title: a[1], thumb: a[2] || null, added: a[3] || 0, url: a[4] }; }

    function state(slug) {
        if (!states[slug]) {
            var disk = App.store.get('idx_' + slug, null);
            states[slug] = {
                films: disk ? disk.films.map(unpack) : [],
                complete: disk ? !!disk.complete : false,
                totalPages: disk ? disk.totalPages : 0,
                loadedPages: disk && disk.complete ? disk.totalPages : 0,
                updated: disk ? disk.updated : 0,
                syncing: false,
                error: null
            };
        }
        return states[slug];
    }

    function emit(slug) { (listeners[slug] || []).forEach(function (f) { f(states[slug]); }); }

    function save(slug) {
        var s = states[slug];
        var ok = App.store.set('idx_' + slug, {
            films: s.films.map(pack), complete: s.complete, totalPages: s.totalPages, updated: s.updated
        });
        if (!ok) console.warn('Не удалось сохранить индекс ' + slug + ' (мало места)');
    }

    function pageUrl(cat, page) { return App.BASE + cat.path + (page > 1 ? 'page/' + page + '/' : ''); }

    App.catalog = {
        PAGE: PAGE,
        state: state,
        listen: function (slug, fn) {
            (listeners[slug] = listeners[slug] || []).push(fn);
            return function () { listeners[slug] = listeners[slug].filter(function (x) { return x !== fn; }); };
        },

        remotePage: function (cat, page) {
            return App.net.text(pageUrl(cat, page)).then(function (html) {
                var res = P().parseList(html, pageUrl(cat, page), false);
                if (res.totalPages >= page) remoteTotals[cat.slug] = Math.max(res.totalPages, remoteTotals[cat.slug] || 0);
                return res;
            });
        },

        remoteTotal: function (cat) {
            if (remoteTotals[cat.slug]) return Promise.resolve(remoteTotals[cat.slug]);
            var s = state(cat.slug);
            if (s.totalPages) return Promise.resolve(s.totalPages);
            return App.catalog.remotePage(cat, 1).then(function (r) { return r.totalPages; });
        },

        /** Фоновая сборка полного каталога: по одной странице (паузы выдерживает служба). */
        sync: function (cat, force) {
            var s = state(cat.slug);
            if (syncing[cat.slug]) return;
            var fresh = s.complete && Date.now() - s.updated < 24 * 3600000;
            if ((fresh && !force) || App.net.isBlocked()) return;
            syncing[cat.slug] = true;
            s.syncing = true; s.error = null; emit(cat.slug);

            var job = s.complete ? incremental(cat, s) : full(cat, s);
            job.catch(function (e) {
                s.error = e.message || 'Ошибка сети';
            }).then(function () {
                syncing[cat.slug] = false;
                s.syncing = false;
                emit(cat.slug);
            });
        },

        random: function (cat, exceptId) {
            var s = state(cat.slug);
            if (s.complete && s.films.length > 1) {
                var pool = s.films.filter(function (f) { return f.id !== exceptId; });
                return Promise.resolve(pool[Math.floor(Math.random() * pool.length)]);
            }
            return App.catalog.remoteTotal(cat).then(function (total) {
                var p = 1 + Math.floor(Math.random() * total);
                return App.catalog.remotePage(cat, p).then(function (r) {
                    var films = r.films.filter(function (f) { return f.id !== exceptId; });
                    return films.length ? films[Math.floor(Math.random() * films.length)] : null;
                });
            });
        },

        search: function (cat, query) {
            var q = P().normalize(query);
            if (!q) return Promise.resolve([]);
            var s = state(cat.slug);
            if (s.complete) {
                var tokens = q.split(' ').filter(Boolean);
                return Promise.resolve(s.films.filter(function (f) {
                    var t = P().normalize(f.title);
                    return tokens.every(function (tok) { return t.indexOf(tok) >= 0; });
                }));
            }
            // Каталог ещё не собран — поиск сайта (одна страница результатов)
            var form = { 'do': 'search', subaction: 'search', story: query, search_start: 1, full_search: 0, result_from: 1 };
            return App.net.text(App.BASE + 'index.php?do=search', { method: 'POST', form: form }).then(function (html) {
                return P().parseList(html, App.BASE, true).films.filter(function (f) {
                    return !f.cats.length || f.cats.indexOf(cat.slug) >= 0;
                });
            });
        },

        refreshCounts: function () {
            if (App.net.isBlocked()) return Promise.resolve(null);
            var cached = App.store.get('counts', null);
            if (cached && Date.now() - cached.t < 24 * 3600000) { applyCounts(cached.c); return Promise.resolve(cached.c); }
            return App.net.text(App.BASE).then(function (html) {
                var c = P().parseCounts(html);
                App.store.set('counts', { t: Date.now(), c: c });
                applyCounts(c);
                return c;
            }).catch(function () { return null; });
        }
    };

    function applyCounts(c) {
        App.CATEGORIES.forEach(function (cat) { if (c && c[cat.slug]) cat.count = c[cat.slug]; });
    }
    var cachedCounts = App.store.get('counts', null);
    if (cachedCounts) applyCounts(cachedCounts.c);

    function full(cat, s) {
        return App.catalog.remotePage(cat, 1).then(function (first) {
            var total = first.totalPages;
            var pages = [first.films];
            var failed = [];
            s.totalPages = total; s.loadedPages = 1; emit(cat.slug);
            var p = 2;
            function loadOne(page) {
                return App.catalog.remotePage(cat, page).then(function (r) {
                    pages[page - 1] = r.films;
                    s.loadedPages++; emit(cat.slug);
                }, function (e) {
                    if (e.blocked) throw e;
                    failed.push(page);
                });
            }
            function step() {
                if (p > total) return Promise.resolve();
                return loadOne(p++).then(step);
            }
            return step().then(function () {
                var retry = failed.splice(0, failed.length);
                return retry.reduce(function (pr, pg) { return pr.then(function () { return loadOne(pg); }); }, Promise.resolve());
            }).then(function () {
                var seen = {};
                var films = [];
                pages.forEach(function (list) {
                    (list || []).forEach(function (f) { if (!seen[f.id]) { seen[f.id] = true; films.push(f); } });
                });
                s.films = films;
                s.complete = failed.length === 0;
                s.updated = Date.now();
                if (!s.complete) s.error = 'Часть страниц не загрузилась';
                save(cat.slug);
            });
        });
    }

    function incremental(cat, s) {
        var known = {};
        s.films.forEach(function (f) { known[f.id] = true; });
        var fresh = [];
        var p = 1;
        function step() {
            if (p > 5) return Promise.resolve();
            var page = p++;
            return App.catalog.remotePage(cat, page).then(function (r) {
                if (page === 1) s.totalPages = r.totalPages;
                var news = r.films.filter(function (f) { return !known[f.id]; });
                fresh = fresh.concat(news);
                if (r.films.length && news.length === r.films.length) return step();
            });
        }
        return step().then(function () {
            s.films = fresh.concat(s.films);
            s.updated = Date.now();
            s.loadedPages = s.totalPages;
            save(cat.slug);
        });
    }

    // ---------- Годы выпуска ----------
    var years = App.store.get('years', { byId: {}, titles: {}, complete: false });
    var yearState = { building: false, error: null };
    var yearListeners = [];
    var yearSaveTimer = null;

    App.years = {
        state: function () { return { complete: years.complete, building: yearState.building, error: yearState.error }; },
        listen: function (fn) { yearListeners.push(fn); return function () { yearListeners = yearListeners.filter(function (x) { return x !== fn; }); }; },
        yearOf: function (id, title) {
            if (years.byId[id]) return years.byId[id];
            if (!title) return null;
            var list = years.titles[P().normalize(title)];
            return list && list.length === 1 ? list[0] : null;
        },
        put: function (id, year) {
            if (!year || years.byId[id] === year) return;
            years.byId[id] = year;
            clearTimeout(yearSaveTimer);
            yearSaveTimer = setTimeout(function () { App.store.set('years', years); }, 2000);
        },
        /** Один запрос к «Базе диафильмов». */
        build: function () {
            if (yearState.building) return;
            yearState.building = true; yearState.error = null; emitYears();
            App.net.text(App.BASE + 'base.php').then(function (html) {
                var titles = P().parseBase(html);
                if (!Object.keys(titles).length) throw new Error('Не удалось разобрать «Базу диафильмов»');
                years.titles = titles;
                years.complete = true;
                if (!App.store.set('years', years)) console.warn('years: мало места');
            }).catch(function (e) {
                yearState.error = e.message || 'Не удалось загрузить годы выпуска';
            }).then(function () {
                yearState.building = false;
                emitYears();
            });
        }
    };
    function emitYears() { yearListeners.forEach(function (f) { f(App.years.state()); }); }

    // ---------- Последний кадр ----------
    App.lastFrame = {
        get: function (id) { return App.store.get('lf_' + id, 0); },
        set: function (id, n) { if (n > 0) App.store.set('lf_' + id, n); else App.store.del('lf_' + id); }
    };

    // ---------- «Сохранённое» (IndexedDB) ----------
    var dbPromise = null;
    function db() {
        if (!dbPromise) {
            dbPromise = new Promise(function (resolve, reject) {
                var req = indexedDB.open('diafilmy', 1);
                req.onupgradeneeded = function () {
                    var d = req.result;
                    if (!d.objectStoreNames.contains('films')) d.createObjectStore('films', { keyPath: 'id' });
                    if (!d.objectStoreNames.contains('frames')) d.createObjectStore('frames');
                };
                req.onsuccess = function () { resolve(req.result); };
                req.onerror = function () { reject(req.error || new Error('IndexedDB недоступна')); };
            });
        }
        return dbPromise;
    }
    function tx(stores, mode, fn) {
        return db().then(function (d) {
            return new Promise(function (resolve, reject) {
                var t = d.transaction(stores, mode);
                var result;
                t.oncomplete = function () { resolve(result); };
                t.onerror = function () { reject(t.error); };
                t.onabort = function () { reject(t.error || new Error('Операция прервана')); };
                result = fn(t);
            });
        });
    }
    function reqP(r) { return new Promise(function (res, rej) { r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; }); }

    var savedList = [];
    var savedListeners = [];
    var downloads = {}; // id -> {title, done, total, error, cancel}

    function emitSaved() { savedListeners.forEach(function (f) { f(); }); }

    App.saved = {
        list: function () { return savedList; },
        downloads: function () { return downloads; },
        listen: function (fn) { savedListeners.push(fn); return function () { savedListeners = savedListeners.filter(function (x) { return x !== fn; }); }; },
        get: function (id) { for (var i = 0; i < savedList.length; i++) if (savedList[i].id === id) return savedList[i]; return null; },

        reload: function () {
            return db().then(function (d) {
                return reqP(d.transaction('films').objectStore('films').getAll());
            }).then(function (list) {
                savedList = (list || []).sort(function (a, b) { return b.savedAt - a.savedAt; });
                emitSaved();
                return savedList;
            }).catch(function (e) { console.warn('saved.reload', e); return savedList; });
        },

        /** URL-ы кадров сохранённого фильма (object URL; освобождать через releaseUrls). */
        frameUrls: function (film) {
            return db().then(function (d) {
                var store = d.transaction('frames').objectStore('frames');
                return Promise.all(film.frames.map(function (k) { return reqP(store.get(k)); }));
            }).then(function (blobs) {
                return blobs.map(function (b) { return b ? URL.createObjectURL(b) : ''; });
            });
        },
        releaseUrls: function (urls) { (urls || []).forEach(function (u) { if (u && u.indexOf('blob:') === 0) URL.revokeObjectURL(u); }); },

        coverUrl: function (film) {
            return db().then(function (d) {
                return reqP(d.transaction('frames').objectStore('frames').get(film.frames[0]));
            }).then(function (b) { return b ? URL.createObjectURL(b) : ''; });
        },

        download: function (detail) {
            if (downloads[detail.id] && !downloads[detail.id].error) return;
            if (App.saved.get(detail.id) || !detail.frames.length) return;
            var st = downloads[detail.id] = { title: detail.title, done: 0, total: detail.frames.length, error: null, cancelled: false };
            emitSaved();
            var keys = [];
            var size = 0;
            var i = 0;
            function step() {
                if (st.cancelled) throw new Error('cancelled');
                if (i >= detail.frames.length) return Promise.resolve();
                var n = i++;
                var key = detail.id + ':' + n;
                return App.net.blob(detail.frames[n]).then(function (blob) {
                    size += blob.size;
                    return tx(['frames'], 'readwrite', function (t) { t.objectStore('frames').put(blob, key); });
                }).then(function () {
                    keys.push(key);
                    st.done = keys.length;
                    emitSaved();
                    return step();
                });
            }
            step().then(function () {
                var film = {
                    id: detail.id, title: detail.title, url: detail.url, frames: keys,
                    meta: detail.meta, year: detail.year, savedAt: Date.now(), sizeBytes: size
                };
                return tx(['films'], 'readwrite', function (t) { t.objectStore('films').put(film); });
            }).then(function () {
                delete downloads[detail.id];
                return App.saved.reload();
            }).catch(function (e) {
                // убираем уже записанные кадры
                tx(['frames'], 'readwrite', function (t) { keys.forEach(function (k) { t.objectStore('frames').delete(k); }); }).catch(function () {});
                if (st.cancelled) { delete downloads[detail.id]; }
                else {
                    var quota = e && (e.name === 'QuotaExceededError' || /quota/i.test(e.message || ''));
                    st.error = quota ? 'Не хватает места на телевизоре' : (e.message || 'Ошибка загрузки');
                }
                emitSaved();
            });
        },

        cancel: function (id) { if (downloads[id]) { downloads[id].cancelled = true; delete downloads[id]; emitSaved(); } },
        dismissError: function (id) { delete downloads[id]; emitSaved(); },

        remove: function (id) {
            var film = App.saved.get(id);
            if (!film) return Promise.resolve();
            return tx(['films', 'frames'], 'readwrite', function (t) {
                t.objectStore('films').delete(id);
                film.frames.forEach(function (k) { t.objectStore('frames').delete(k); });
            }).then(function () { return App.saved.reload(); });
        }
    };
})();
