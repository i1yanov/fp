/* Экраны: главная, категория (сортировки, рубрикатор, поиск, «Случайный»), «Сохранённое», диалог подтверждения. */
(function () {
    'use strict';
    var App = window.App;
    var h = App.h;
    var collator = window.Intl ? new Intl.Collator('ru', { sensitivity: 'base' }) : null;
    function byTitle(a, b) { return collator ? collator.compare(a.title, b.title) : (a.title < b.title ? -1 : 1); }

    /** Перерисовка части экрана с сохранением фокуса (по data-key). */
    function rerender(section, build) {
        var key = null;
        var cur = App.focus.current;
        if (cur && section.contains(cur)) key = cur.getAttribute('data-key');
        App.clear(section);
        build(section);
        if (key !== null) {
            var el = section.querySelector('[data-key="' + key.replace(/"/g, '\\"') + '"]');
            if (el) App.focus.set(el);
        }
    }

    // =====================================================================
    // Главная
    // =====================================================================
    function HomeScreen() {
        var self = this;
        this.el = h('div.screen.home');
        var unlisten = [];

        function render() {
            var key = App.focus.current && self.el.contains(App.focus.current) ? App.focus.current.getAttribute('data-key') : null;
            App.clear(self.el);
            var header = h('div.home-header', {}, [
                h('img.home-logo', { src: 'img/largeIcon.png', alt: '' }),
                h('div', {}, [
                    h('div.home-title', { text: 'Диафильмы' }),
                    h('div.home-sub', { text: 'по материалам diafilmy.su · версия ' + App.VERSION })
                ])
            ]);
            self.el.appendChild(header);

            if (App.net.isBlocked()) {
                self.el.appendChild(h('div.banner', {
                    text: 'Сайт временно ограничил доступ с вашего адреса. Приложение сделает паузу до ' +
                        App.fmtTime(App.net.blockedUntil()) + ', чтобы не продлевать ограничение. «Сохранённое» работает без сети.'
                }));
            }

            var grid = h('div.tiles');
            var saved = App.saved.list();
            var active = Object.keys(App.saved.downloads()).filter(function (k) { return !App.saved.downloads()[k].error; }).length;
            var savedSub = saved.length
                ? saved.length + ' ' + App.plural(saved.length, 'диафильм', 'диафильма', 'диафильмов')
                : 'пока пусто — сохраняйте диафильмы для просмотра без интернета';
            if (active) savedSub += ' · загружается ' + active;
            grid.appendChild(tile('saved', 'Сохранённое', savedSub, 'folder', true, function () {
                App.router.push(new SavedScreen());
            }));
            grid.appendChild(h('div.section-title', { text: 'Диафильмы' }));
            var cats = h('div.tiles3');
            grid.appendChild(cats);
            App.CATEGORIES.forEach(function (c) {
                cats.appendChild(tile(c.slug, c.title, c.count + ' шт.', 'film', false, function () {
                    App.router.push(new CategoryScreen(c));
                }));
            });
            self.el.appendChild(grid);
            App.fitAll(App.$$('.tile-title', self.el), 22);

            var el = key ? self.el.querySelector('[data-key="' + key + '"]') : null;
            if (el) App.focus.set(el, true);
        }

        function tile(key, title, sub, icon, wide, action) {
            var el = h('div.tile' + (wide ? '.wide.default' : ''), { 'data-key': key }, [
                h('div.tile-inner', {}, [
                    App.icon(icon, wide ? 'red' : 'amber'),
                    h('div.tile-text', {}, [h('div.tile-title', { text: title }), h('div.tile-sub', { text: sub })])
                ])
            ]);
            return App.focusable(el, action);
        }

        this.onShow = function () {
            render();
            if (!unlisten.length) {
                unlisten.push(App.saved.listen(render));
                App.net.onBlockChange(render);
                App.catalog.refreshCounts().then(function (c) { if (c) render(); });
            }
        };
        this.onKey = function (code, type) {
            if (App.isBack(code) && type === 'up') {
                App.confirm('Закрыть приложение?', 'Закрыть', function () { window.close(); });
                return true;
            }
            return false;
        };
        render();
    }

    // =====================================================================
    // Категория
    // =====================================================================
    var SORTS = [
        { id: 'NEW', label: 'Сначала новые' }, { id: 'OLD', label: 'Сначала старые' },
        { id: 'YEAR_DESC', label: 'Год выпуска ↓' }, { id: 'YEAR_ASC', label: 'Год выпуска ↑' }
    ];
    var RUBRICS = [{ id: 'PAGES', label: 'По страницам' }, { id: 'LETTERS', label: 'По буквам' }];
    var NO_YEAR = '?';

    function letterOf(title) {
        var t = String(title || '').replace(/^[^0-9A-Za-zА-Яа-яЁёÀ-ɏ]+/, '');
        var c = t.charAt(0).toUpperCase();
        if (!c) return '#';
        if (c === 'Ё') return 'Е';
        if (/[A-ZА-ЯÀ-ɏ]/.test(c)) return c;
        return '#';
    }
    function letterRank(s) {
        if (/[А-Я]/.test(s)) return 0;
        if (/[A-Z]/.test(s)) return 1;
        if (s === '#') return 3;
        return 2;
    }

    function CategoryScreen(cat) {
        var self = this;
        var PAGE = App.catalog.PAGE;
        var prefs = App.store.get('prefs_' + cat.slug, { sort: 'NEW', rubric: 'PAGES' });
        var S = {
            sort: prefs.sort, rubric: prefs.rubric, chips: [], selected: null, items: [],
            loading: false, error: null, blocker: null,
            searchOpen: false, query: '', results: null, searching: false, searchError: null,
            randomBusy: false
        };
        var token = 0;
        var searchTimer = null;
        var lastComplete = App.catalog.state(cat.slug).complete;
        var lastYears = App.years.state().complete;
        var byYear = function () { return S.sort === 'YEAR_DESC' || S.sort === 'YEAR_ASC'; };

        this.el = h('div.screen.category');
        var top = h('div.topbar');
        var controls = h('div.controls');
        var status = h('div.status');
        var chipsRow = h('div.chips-row');
        var content = h('div.content');
        this.el.appendChild(top);
        this.el.appendChild(controls);
        this.el.appendChild(status);
        this.el.appendChild(chipsRow);
        this.el.appendChild(content);

        // ---------- Верхняя панель ----------
        function renderTop() {
            rerender(top, function (t) {
                if (S.searchOpen) {
                    var input = h('input.search-input', { type: 'text', placeholder: 'Название диафильма', 'data-key': 'search-input' });
                    input.value = S.query;
                    input.addEventListener('input', function () { onQuery(input.value); });
                    App.focusable(input, function () { input.focus(); });
                    t.appendChild(input);
                    t.appendChild(App.focusable(h('div.btn', { 'data-key': 'search-close' }, [App.icon('close'), h('span', { text: 'Закрыть поиск' })]), closeSearch));
                } else {
                    t.appendChild(h('div.page-title', { text: cat.title }));
                    t.appendChild(App.focusable(h('div.btn', { 'data-key': 'search' }, [App.icon('search'), h('span', { text: 'Поиск' })]), openSearch));
                    t.appendChild(App.focusable(h('div.btn.red', { 'data-key': 'random' }, [App.icon('dice'), h('span', { text: S.randomBusy ? 'Выбираем…' : 'Случайный' })]), random));
                }
            });
        }

        // ---------- Сортировка и вид рубрикатора ----------
        function renderControls() {
            rerender(controls, function (c) {
                if (S.searchOpen) { c.style.display = 'none'; return; }
                c.style.display = '';
                var row1 = h('div.row');
                SORTS.forEach(function (s) {
                    var chip = App.chip(s.label, S.sort === s.id, null, function () { setSort(s.id); });
                    chip.setAttribute('data-key', 'sort-' + s.id);
                    if (S.sort === s.id) chip.classList.add('default');
                    row1.appendChild(chip);
                });
                c.appendChild(row1);
                if (!byYear()) {
                    var row2 = h('div.row');
                    RUBRICS.forEach(function (r) {
                        var chip = App.chip(r.label, S.rubric === r.id, null, function () { setRubric(r.id); }, true);
                        chip.setAttribute('data-key', 'rub-' + r.id);
                        row2.appendChild(chip);
                    });
                    c.appendChild(row2);
                }
            });
        }

        function renderStatus() {
            var st = App.catalog.state(cat.slug);
            App.clear(status);
            if (!S.searchOpen && st.syncing && !st.complete && st.totalPages > 0 && !S.blocker) {
                status.appendChild(h('div.status-text', {
                    text: 'Составляем каталог: ' + st.loadedPages + ' из ' + st.totalPages +
                        ' стр. — потом заработают буквы, годы и мгновенный поиск'
                }));
                status.appendChild(h('div.lbar', {}, [h('div.lbar-fill', { style: 'width:' + Math.round(st.loadedPages / st.totalPages * 100) + '%' })]));
            }
        }

        function renderChips() {
            rerender(chipsRow, function (r) {
                if (S.searchOpen || !S.chips.length) { r.style.display = 'none'; return; }
                r.style.display = '';
                S.chips.forEach(function (c) {
                    var chip = App.chip(c.label, c.key === S.selected, c.count, function () { select(c.key); }, true);
                    chip.setAttribute('data-key', 'chip-' + c.key);
                    r.appendChild(chip);
                });
                var sel = r.querySelector('.chip.sel');
                if (sel) r.scrollLeft = Math.max(0, sel.offsetLeft - 300);
            });
        }

        // ---------- Содержимое ----------
        function renderContent() {
            rerender(content, function (c) {
                var st = App.catalog.state(cat.slug);
                var ys = App.years.state();
                if (S.searchOpen) { renderSearch(c); return; }
                if (S.blocker === 'index') {
                    if (st.error && !st.syncing) c.appendChild(App.errorBox('Каталог не загрузился: ' + st.error, retry));
                    else c.appendChild(App.loader('Составляем каталог «' + cat.title + '»',
                        st.totalPages ? 'страница ' + st.loadedPages + ' из ' + st.totalPages : null,
                        st.totalPages ? st.loadedPages / st.totalPages : undefined));
                    return;
                }
                if (S.blocker === 'years') {
                    if (ys.error && !ys.building) c.appendChild(App.errorBox(ys.error, function () { App.net.clearBlock(); App.years.build(); }));
                    else c.appendChild(App.loader('Загружаем «Базу диафильмов» с годами выпуска (один раз)'));
                    return;
                }
                if (S.error) { c.appendChild(App.errorBox(S.error, retry)); return; }
                if (S.loading && !S.items.length) { c.appendChild(App.loader('Листаем каталог…')); return; }
                renderGrid(c, S.items);
                if (S.chips.length > 1) {
                    var idx = indexOfSelected();
                    var pager = h('div.pager');
                    if (idx > 0) pager.appendChild(App.focusable(h('div.btn', { text: '← Назад', 'data-key': 'prev' }), function () { step(-1); }));
                    pager.appendChild(h('div.pager-pos', { text: (idx + 1) + ' из ' + S.chips.length }));
                    if (idx < S.chips.length - 1) pager.appendChild(App.focusable(h('div.btn', { text: 'Дальше →', 'data-key': 'next' }), function () { step(1); }));
                    c.appendChild(pager);
                }
                c.scrollTop = 0;
            });
        }

        function renderGrid(c, films) {
            if (!films.length) { c.appendChild(h('div.empty', { text: 'Здесь пока пусто' })); return; }
            var grid = h('div.grid');
            films.forEach(function (f) {
                var card = App.card({
                    title: f.title, thumb: f.thumb,
                    subtitle: App.filmSubtitle(App.years.yearOf(f.id, f.title), f.added),
                    onOpen: function () { open(f); }
                });
                card.setAttribute('data-key', 'film-' + f.id);
                grid.appendChild(card);
            });
            c.appendChild(grid);
            App.fitAll(App.$$('.card-title', grid), 18);
        }

        function renderSearch(c) {
            var st = App.catalog.state(cat.slug);
            c.appendChild(h('div.hint', { text: st.complete ? 'Поиск по каталогу категории' : 'Каталог ещё составляется — ищем на сайте' }));
            if (S.searching) { c.appendChild(App.loader('Ищем «' + S.query + '»')); return; }
            if (S.searchError) { c.appendChild(App.errorBox(S.searchError, function () { onQuery(S.query, true); })); return; }
            if (S.results === null) { c.appendChild(h('div.empty', { text: 'Нажмите OK на поле поиска и введите хотя бы ' + (st.complete ? 2 : 3) + ' буквы названия' })); return; }
            if (!S.results.length) { c.appendChild(h('div.empty', { text: 'Ничего не найдено' })); return; }
            c.appendChild(h('div.hint', { text: 'Найдено: ' + S.results.length }));
            renderGrid(c, S.results);
        }

        function renderAll() { renderTop(); renderControls(); renderStatus(); renderChips(); renderContent(); }

        // ---------- Логика (перенос CategoryViewModel) ----------
        function indexOfSelected() {
            for (var i = 0; i < S.chips.length; i++) if (S.chips[i].key === S.selected) return i;
            return -1;
        }
        function yearKey(f) { var y = App.years.yearOf(f.id, f.title); return y ? String(y) : NO_YEAR; }

        function letterChips(films) {
            var counts = {};
            films.forEach(function (f) { var l = letterOf(f.title); counts[l] = (counts[l] || 0) + 1; });
            return Object.keys(counts).sort(function (a, b) { return letterRank(a) - letterRank(b) || (a < b ? -1 : 1); })
                .map(function (l) { return { key: l, label: l, count: counts[l] }; });
        }
        function yearChips(films) {
            var counts = {};
            films.forEach(function (f) { var k = yearKey(f); counts[k] = (counts[k] || 0) + 1; });
            var ys = Object.keys(counts).filter(function (k) { return k !== NO_YEAR; }).sort(function (a, b) { return a - b; });
            if (S.sort === 'YEAR_DESC') ys.reverse();
            var out = ys.map(function (y) { return { key: y, label: y, count: counts[y] }; });
            if (counts[NO_YEAR]) out.push({ key: NO_YEAR, label: 'Без года', count: counts[NO_YEAR] });
            return out;
        }

        function rebuild(keep) {
            var my = ++token;
            S.error = null;
            var st = App.catalog.state(cat.slug);
            var chipsP;
            if (byYear() && !st.complete) { S.blocker = 'index'; chipsP = Promise.resolve([]); }
            else if (byYear() && !App.years.state().complete) { S.blocker = 'years'; App.years.build(); chipsP = Promise.resolve([]); }
            else if (byYear()) { S.blocker = null; chipsP = Promise.resolve(yearChips(st.films)); }
            else if (S.rubric === 'LETTERS' && !st.complete) { S.blocker = 'index'; chipsP = Promise.resolve([]); }
            else if (S.rubric === 'LETTERS') { S.blocker = null; chipsP = Promise.resolve(letterChips(st.films)); }
            else {
                S.blocker = null;
                if (st.complete) chipsP = Promise.resolve(pagesChips(Math.ceil(st.films.length / PAGE)));
                else { S.loading = true; renderContent(); chipsP = App.catalog.remoteTotal(cat).then(pagesChips); }
            }
            chipsP.then(function (chips) {
                if (my !== token) return;
                S.chips = chips;
                if (!chips.length) { S.items = []; S.loading = false; renderAll(); return; }
                var keepSel = keep && chips.some(function (c) { return c.key === S.selected; });
                S.selected = keepSel ? S.selected : chips[0].key;
                renderChips();
                load(my);
            }, function (e) {
                if (my !== token) return;
                S.loading = false; S.error = e.message || 'Нет соединения с сайтом';
                renderAll();
            });
            renderControls(); renderStatus();
        }

        function pagesChips(n) {
            var out = [];
            for (var i = 1; i <= Math.max(1, n); i++) out.push({ key: String(i), label: String(i) });
            return out;
        }

        function load(my) {
            my = my || ++token;
            var key = S.selected;
            var st = App.catalog.state(cat.slug);
            S.error = null;
            var p;
            if (byYear()) p = Promise.resolve(st.films.filter(function (f) { return yearKey(f) === key; }).sort(byTitle));
            else if (S.rubric === 'LETTERS') p = Promise.resolve(st.films.filter(function (f) { return letterOf(f.title) === key; }).sort(byTitle));
            else if (st.complete) {
                var pg = +key;
                var list = S.sort === 'OLD' ? st.films.slice().reverse() : st.films;
                p = Promise.resolve(list.slice((pg - 1) * PAGE, pg * PAGE));
            } else {
                S.loading = true; renderContent();
                p = App.catalog.remoteTotal(cat).then(function (total) {
                    var sitePage = S.sort === 'OLD' ? Math.max(1, total - (+key) + 1) : +key;
                    return App.catalog.remotePage(cat, sitePage).then(function (r) {
                        return S.sort === 'OLD' ? r.films.slice().reverse() : r.films;
                    });
                });
            }
            p.then(function (items) {
                if (my !== token) return;
                S.items = items; S.loading = false;
                renderContent();
            }, function (e) {
                if (my !== token) return;
                S.loading = false; S.error = e.message || 'Нет соединения с сайтом';
                renderContent();
            });
        }

        function setSort(id) {
            if (S.sort === id) return;
            S.sort = id; prefs.sort = id; App.store.set('prefs_' + cat.slug, prefs);
            rebuild(false);
        }
        function setRubric(id) {
            if (S.rubric === id) return;
            S.rubric = id; prefs.rubric = id; App.store.set('prefs_' + cat.slug, prefs);
            rebuild(false);
        }
        function select(key) {
            if (key === S.selected && S.items.length) return;
            S.selected = key;
            renderChips();
            load();
        }
        function step(d) {
            var i = indexOfSelected() + d;
            if (S.chips[i]) {
                select(S.chips[i].key);
                var first = content.querySelector('.card');
                if (first) App.focus.set(first);
            }
        }
        function retry() {
            App.net.clearBlock();
            if (App.catalog.state(cat.slug).error) App.catalog.sync(cat, true);
            rebuild(true);
        }
        function open(f) { App.router.push(new App.ViewerScreen({ url: f.url, cat: cat })); }

        function random() {
            if (S.randomBusy) return;
            S.randomBusy = true; renderTop();
            App.catalog.random(cat).then(function (f) {
                if (f) open(f); else App.toast('Не удалось выбрать диафильм');
            }, function (e) {
                App.toast(e.blocked ? e.message : 'Нет соединения: ' + e.message, 6000);
            }).then(function () { S.randomBusy = false; renderTop(); });
        }

        // ---------- Поиск ----------
        function openSearch() {
            S.searchOpen = true; S.query = ''; S.results = null;
            renderAll();
            var inp = App.$('.search-input', top);
            if (inp) App.focus.set(inp);
        }
        function closeSearch() {
            S.searchOpen = false; S.results = null; S.searching = false; clearTimeout(searchTimer);
            renderAll();
            var b = App.$('[data-key="search"]', top);
            if (b) App.focus.set(b);
        }
        function onQuery(q, now) {
            S.query = q;
            clearTimeout(searchTimer);
            var local = App.catalog.state(cat.slug).complete;
            if (App.parser.normalize(q).length < (local ? 2 : 3)) { S.results = null; S.searching = false; renderContent(); return; }
            searchTimer = setTimeout(function () {
                S.searching = true; S.searchError = null; renderContent();
                var my = q;
                App.catalog.search(cat, q).then(function (res) {
                    if (S.query !== my) return;
                    S.results = res;
                }, function (e) {
                    if (S.query !== my) return;
                    S.searchError = e.message || 'Ошибка поиска';
                }).then(function () {
                    if (S.query !== my) return;
                    S.searching = false; renderContent();
                });
            }, now ? 0 : (local ? 200 : 900));
        }

        // ---------- Подписки ----------
        var unIndex = App.catalog.listen(cat.slug, function (st) {
            renderStatus();
            if (st.complete !== lastComplete) { lastComplete = st.complete; rebuild(true); }
            else if (S.blocker === 'index') renderContent();
        });
        var unYears = App.years.listen(function (ys) {
            if (ys.complete !== lastYears) { lastYears = ys.complete; rebuild(true); }
            else if (S.blocker === 'years') renderContent();
        });

        this.onKey = function (code, type) {
            if (App.isBack(code) && type === 'up') {
                if (S.searchOpen) { closeSearch(); return true; }
                App.router.pop();
                return true;
            }
            return false;
        };
        this.onDestroy = function () { unIndex(); unYears(); token++; clearTimeout(searchTimer); };

        renderAll();
        App.catalog.sync(cat);
        rebuild(true);
    }

    // =====================================================================
    // Сохранённое
    // =====================================================================
    function SavedScreen() {
        var self = this;
        this.el = h('div.screen.saved');
        var urls = [];
        var un = App.saved.listen(function () { render(); });

        function render() {
            var key = App.focus.current && self.el.contains(App.focus.current) ? App.focus.current.getAttribute('data-key') : null;
            App.saved.releaseUrls(urls); urls = [];
            App.clear(self.el);
            var list = App.saved.list();
            var total = list.reduce(function (s, f) { return s + (f.sizeBytes || 0); }, 0);
            self.el.appendChild(h('div.topbar', {}, [
                h('div', {}, [
                    h('div.page-title', { text: 'Сохранённое' }),
                    h('div.page-sub', { text: list.length + ' шт. · ' + App.fmtSize(total) + ' в памяти телевизора' })
                ])
            ]));
            var body = h('div.content');
            var dls = App.saved.downloads();
            Object.keys(dls).forEach(function (id) {
                var d = dls[id];
                var row = h('div.dl-row', { 'data-key': 'dl-' + id }, [
                    h('div.dl-title', { text: d.title }),
                    d.error ? h('div.dl-err', { text: 'Ошибка: ' + d.error })
                        : h('div.lbar', {}, [h('div.lbar-fill', { style: 'width:' + Math.round(d.done / d.total * 100) + '%' })]),
                    h('div.dl-sub', { text: d.error ? 'OK — скрыть' : 'кадр ' + d.done + ' из ' + d.total + ' · OK — отменить' })
                ]);
                App.focusable(row, function () { if (d.error) App.saved.dismissError(+id); else App.saved.cancel(+id); });
                body.appendChild(row);
            });
            if (!list.length && !Object.keys(dls).length) {
                body.appendChild(h('div.empty', {
                    text: 'Здесь пока пусто. Откройте диафильм, нажмите OK и выберите в круговом меню «Сохранить» — ' +
                        'он появится здесь и будет доступен без интернета.'
                }));
            }
            var grid = h('div.grid');
            list.forEach(function (f) {
                var card = App.card({
                    title: App.parser.cleanTitle(f.title), thumb: null,
                    subtitle: [f.year ? f.year + ' г.' : null, f.frames.length + ' кадр.', App.fmtSize(f.sizeBytes || 0)].filter(Boolean).join(' · '),
                    badge: 'офлайн',
                    onOpen: function () { App.router.push(new App.ViewerScreen({ url: f.url, savedId: f.id })); }
                });
                card.setAttribute('data-key', 'saved-' + f.id);
                card.setAttribute('data-id', f.id);
                grid.appendChild(card);
                App.saved.coverUrl(f).then(function (u) {
                    if (!u) return;
                    urls.push(u);
                    var img = card.querySelector('img.thumb');
                    img.src = u; img.classList.add('loaded');
                });
            });
            body.appendChild(grid);
            if (list.length) body.appendChild(h('div.hint', { text: 'Удалить: красная кнопка на карточке или «Удалить» в круговом меню просмотра' }));
            self.el.appendChild(body);
            App.fitAll(App.$$('.card-title', grid), 18);
            var el = key ? self.el.querySelector('[data-key="' + key + '"]') : null;
            if (el) App.focus.set(el, true); else if (App.router.top() === self) App.focus.first();
        }

        this.onKey = function (code, type) {
            if (App.isBack(code) && type === 'up') { App.router.pop(); return true; }
            if (code === App.KEY.RED && type === 'up') {
                var cur = App.focus.current;
                var id = cur && cur.getAttribute('data-id');
                if (id) {
                    var f = App.saved.get(+id);
                    if (f) App.confirm('Удалить «' + App.parser.cleanTitle(f.title) + '»? Освободится ' + App.fmtSize(f.sizeBytes || 0) + '.',
                        'Удалить', function () { App.saved.remove(+id); });
                }
                return true;
            }
            return false;
        };
        this.onShow = render;
        this.onDestroy = function () { un(); App.saved.releaseUrls(urls); };
        render();
    }

    // =====================================================================
    // Подтверждение (модальное окно поверх экрана)
    // =====================================================================
    App.confirm = function (text, okLabel, onOk) {
        var prevRoot = App.focus.root, prevFocus = App.focus.current;
        var overlay = h('div.modal');
        var box = h('div.modal-box', {}, [h('div.modal-text', { text: text })]);
        var row = h('div.modal-row');
        function close() {
            App.modal = null;
            overlay.parentNode && overlay.parentNode.removeChild(overlay);
            App.focus.setRoot(prevRoot);
            if (prevFocus && document.body.contains(prevFocus)) App.focus.set(prevFocus, true); else App.focus.first();
        }
        var cancel = App.focusable(h('div.btn.default', { text: 'Отмена' }), close);
        var ok = App.focusable(h('div.btn.red', { text: okLabel }), function () { close(); onOk(); });
        row.appendChild(cancel); row.appendChild(ok);
        box.appendChild(row);
        overlay.appendChild(box);
        document.body.appendChild(overlay);
        App.focus.setRoot(overlay);
        App.focus.set(cancel);
        App.modal = { close: close };
    };

    App.HomeScreen = HomeScreen;
    App.CategoryScreen = CategoryScreen;
    App.SavedScreen = SavedScreen;
})();
