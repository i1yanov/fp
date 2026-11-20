/* Просмотр диафильма: вертикальная «протяжка плёнки» кнопками ▲/▼, круговое меню по OK. */
(function () {
    'use strict';
    var App = window.App;
    var h = App.h;
    var EASE = 'cubic-bezier(.33,0,.2,1)';

    function tY(d) { return 'translate3d(0,' + (d * 100) + '%,0)'; }

    function ViewerScreen(opts) {
        var self = this;
        this.el = h('div.screen.viewer');
        var stage = h('div.stage');
        var top = h('div.vbar.top');
        var bottom = h('div.vbar.bottom');
        var counter = h('div.counter');
        this.el.appendChild(stage);
        this.el.appendChild(top);
        this.el.appendChild(bottom);
        this.el.appendChild(counter);

        var C = null;            // содержимое: {id, title, url, frames, meta, offline, detail}
        var cur = 0;
        var slots = {};
        var cleanupTimer = null, barTimer = null, counterTimer = null;
        var lastMoveAt = 0;
        var okArmed = false;
        var menuOpen = false, menuSel = -1, menuEl = null, menuItems = [];
        var infoEl = null;
        var blobUrls = [];
        var randomBusy = false;
        var loadToken = 0;

        // ---------- Загрузка ----------
        function load(url, savedId) {
            var my = ++loadToken;
            closeMenu(); closeInfo();
            App.saved.releaseUrls(blobUrls); blobUrls = [];
            C = null; slots = {}; App.clear(stage);
            stage.appendChild(h('div.center', {}, [App.loader('Заряжаем плёнку…')]));
            hideBars();

            var id = savedId || App.parser.filmId(url);
            var saved = id ? App.saved.get(id) : null;
            var p = saved
                ? App.saved.frameUrls(saved).then(function (urls) {
                    blobUrls = urls;
                    return { id: saved.id, title: App.parser.cleanTitle(saved.title), url: saved.url, frames: urls, meta: saved.meta || [], offline: true, detail: null };
                })
                : App.net.text(url).then(function (html) {
                    var d = App.parser.parseDetail(html, url);
                    if (!d.frames.length) throw new Error('На странице не найдено кадров');
                    App.years.put(d.id, d.year);
                    return { id: d.id, title: d.title, url: d.url, frames: d.frames, meta: d.meta, offline: false, detail: d };
                });
            p.then(function (content) {
                if (my !== loadToken) return;
                C = content;
                var last = App.lastFrame.get(C.id);
                cur = last > 0 && last < C.frames.length ? last : 0;
                App.clear(stage);
                buildAround(cur);
                onFrameChanged();
                showBars();
            }, function (e) {
                if (my !== loadToken) return;
                App.clear(stage);
                var box = App.errorBox(e.message || 'Не удалось открыть диафильм', function () { App.net.clearBlock(); load(url, savedId); });
                stage.appendChild(h('div.center', {}, [box]));
                App.focus.setRoot(stage);
                App.focus.first();
            });
        }

        // ---------- Слоты кадров ----------
        function makeSlot(i) {
            var el = h('div.slot', {}, [h('div.perf.l'), h('div.perf.r')]);
            if (i < C.frames.length) {
                var ld = h('div.slot-loader', {}, [App.loader(null)]);
                var img = h('img.frame', { alt: '' });
                img.onload = function () { if (ld.parentNode) ld.parentNode.removeChild(ld); img.classList.add('loaded'); };
                img.onerror = function () { App.clear(ld); ld.appendChild(h('div.frame-err', { text: 'Кадр не загрузился — проверьте соединение' })); };
                img.src = C.frames[i];
                el.appendChild(ld);
                el.appendChild(img);
            } else {
                el.appendChild(h('div.end-card', {}, [
                    h('div.end-title', { text: 'КОНЕЦ' }),
                    h('div.end-hint', { text: 'OK — меню: смотреть сначала, случайный диафильм, выход' }),
                    h('div.end-hint', { text: '▲ — к последнему кадру' })
                ]));
            }
            return el;
        }

        function last() { return C.frames.length; } // индекс кадра «Конец»

        function buildAround(n) {
            Object.keys(slots).forEach(function (k) { stage.removeChild(slots[k]); });
            slots = {};
            for (var i = n - 1; i <= n + 1; i++) {
                if (i < 0 || i > last()) continue;
                var s = makeSlot(i);
                s.style.transform = tY(i - n);
                stage.appendChild(s);
                slots[i] = s;
            }
        }

        /** Плавная протяжка: соседние кадры едут вверх/вниз; быстрые нажатия — короче и без остановок. */
        function moveTo(n) {
            var old = cur;
            if (n === old) return;
            cur = n;
            var now = Date.now();
            var dur = now - lastMoveAt < 450 ? 300 : 520;
            lastMoveAt = now;
            for (var i = n - 1; i <= n + 1; i++) {
                if (i < 0 || i > last() || slots[i]) continue;
                var s = makeSlot(i);
                s.style.transition = 'none';
                s.style.transform = tY(i - old);
                stage.appendChild(s);
                slots[i] = s;
            }
            void stage.offsetHeight; // применить стартовые позиции до анимации
            Object.keys(slots).forEach(function (k) {
                var el = slots[k];
                el.style.transition = 'transform ' + dur + 'ms ' + EASE;
                el.style.transform = tY(+k - n);
            });
            clearTimeout(cleanupTimer);
            cleanupTimer = setTimeout(function () {
                Object.keys(slots).forEach(function (k) {
                    if (Math.abs(+k - cur) > 1) { stage.removeChild(slots[k]); delete slots[k]; }
                });
            }, dur + 80);
            onFrameChanged();
        }

        function go(d) {
            if (!C) return;
            var n = Math.max(0, Math.min(last(), cur + d));
            moveTo(n);
        }

        function jump(n) {
            if (!C) return;
            cur = Math.max(0, Math.min(last(), n));
            buildAround(cur);
            onFrameChanged();
        }

        function onFrameChanged() {
            App.lastFrame.set(C.id, cur >= last() ? 0 : cur);
            for (var i = 1; i <= 2; i++) {
                var u = C.frames[cur + i];
                if (u && u.indexOf('blob:') !== 0) { var im = new Image(); im.src = u; }
            }
            counter.textContent = cur < last() ? (cur + 1) + ' / ' + last() : 'конец';
            counter.classList.add('show');
            clearTimeout(counterTimer);
            counterTimer = setTimeout(function () { counter.classList.remove('show'); }, 1500);
            if (top.classList.contains('show')) renderBars();
        }

        // ---------- Информационные полосы ----------
        function status() {
            var d = App.saved.downloads()[C.id];
            if (d && !d.error) return ' · сохраняется ' + Math.round(d.done / d.total * 100) + '%';
            if (d && d.error) return ' · не удалось сохранить: ' + d.error;
            if (C.offline || App.saved.get(C.id)) return ' · сохранён на телевизоре';
            return '';
        }
        function renderBars() {
            if (!C) return;
            App.clear(top); App.clear(bottom);
            top.appendChild(h('div.vbar-title', { text: C.title }));
            top.appendChild(h('div.vbar-sub', { text: (cur < last() ? 'кадр ' + (cur + 1) + ' из ' + last() : 'конец') + status() }));
            bottom.appendChild(h('div.lbar', {}, [h('div.lbar-fill', { style: 'width:' + Math.round(Math.min(cur + 1, last()) / last() * 100) + '%' })]));
            bottom.appendChild(h('div.vbar-hint', { text: '▲ ▼ — листать кадры   ·   OK — меню   ·   Назад — выход' }));
            App.fitText(top.querySelector('.vbar-title'), 26);
        }
        function showBars() {
            renderBars();
            top.classList.add('show'); bottom.classList.add('show');
            clearTimeout(barTimer);
            barTimer = setTimeout(hideBars, 3500);
        }
        function hideBars() { top.classList.remove('show'); bottom.classList.remove('show'); }

        // ---------- Круговое меню ----------
        function buildItems() {
            var items = [{ id: 'info', label: 'О диафильме', icon: 'info' }];
            var d = App.saved.downloads()[C.id];
            if (d && !d.error) items.push({ id: 'dl', label: 'Сохраняется… ' + Math.round(d.done / d.total * 100) + '%', icon: 'save', disabled: true });
            else if (C.offline || App.saved.get(C.id)) items.push({ id: 'delete', label: 'Удалить с телевизора', icon: 'del' });
            else items.push({ id: 'save', label: 'Сохранить для просмотра без интернета', icon: 'save' });
            if (opts.cat) items.push({ id: 'random', label: randomBusy ? 'Выбираем…' : 'Случайный диафильм', icon: 'dice' });
            items.push({ id: 'restart', label: 'Смотреть сначала', icon: 'restart' });
            items.push({ id: 'exit', label: 'Выйти к списку', icon: 'exit' });
            return items;
        }

        function pos(n, i) {
            if (i < 0) return { x: 0, y: 0 };
            var a = (-90 + 360 * i / n) * Math.PI / 180;
            return { x: Math.cos(a), y: Math.sin(a) };
        }

        /** Переход крестовиной к ближайшему пункту в нажатой стороне. */
        function radialMove(dx, dy) {
            var n = menuItems.length;
            var from = pos(n, menuSel);
            var best = menuSel, bestCost = Infinity;
            for (var i = -1; i < n; i++) {
                if (i === menuSel) continue;
                var p = pos(n, i);
                var vx = p.x - from.x, vy = p.y - from.y;
                var along = vx * dx + vy * dy;
                if (along <= 0.05) continue;
                var cost = along + Math.abs(vx * dy - vy * dx) * 2.5;
                if (cost < bestCost) { bestCost = cost; best = i; }
            }
            menuSel = best;
            renderMenu();
        }

        function renderMenu() {
            if (!menuEl) return;
            App.clear(menuEl);
            menuItems = buildItems();
            var R = 220, ITEM = 132, CENTER = 150;
            var ring = h('div.ring', {}, [h('div.ring-line')]);
            menuItems.forEach(function (it, i) {
                var p = pos(menuItems.length, i);
                var b = h('div.rbtn' + (i === menuSel ? '.sel' : '') + (it.disabled ? '.dis' : ''), {
                    style: 'width:' + ITEM + 'px;height:' + ITEM + 'px;left:' + (300 + R * p.x - ITEM / 2) + 'px;top:' + (300 + R * p.y - ITEM / 2) + 'px'
                }, [App.icon(it.icon)]);
                b.addEventListener('mouseover', function () { if (menuSel !== i) { menuSel = i; renderMenu(); } });
                b.addEventListener('click', function () { menuSel = i; activate(); });
                ring.appendChild(b);
            });
            var c = h('div.rbtn.center' + (menuSel === -1 ? '.sel' : ''), {
                style: 'width:' + CENTER + 'px;height:' + CENTER + 'px;left:' + (300 - CENTER / 2) + 'px;top:' + (300 - CENTER / 2) + 'px'
            }, [App.icon('close')]);
            c.addEventListener('mouseover', function () { if (menuSel !== -1) { menuSel = -1; renderMenu(); } });
            c.addEventListener('click', function () { menuSel = -1; activate(); });
            ring.appendChild(c);

            var label = menuSel === -1 ? 'Закрыть меню' : menuItems[menuSel].label;
            menuEl.appendChild(h('div.menu-title', { text: C.title }));
            menuEl.appendChild(h('div.menu-sub', { text: (cur < last() ? 'кадр ' + (cur + 1) + ' из ' + last() : 'конец диафильма') + status() }));
            menuEl.appendChild(ring);
            menuEl.appendChild(h('div.menu-label', { text: label }));
            menuEl.appendChild(h('div.menu-hint', { text: '◀ ▲ ▼ ▶ — выбор   ·   OK — выполнить   ·   Назад — закрыть' }));
            App.fitText(menuEl.querySelector('.menu-title'), 28);
        }

        function openMenu() {
            if (!C) return;
            hideBars();
            menuOpen = true; menuSel = -1;
            menuEl = h('div.radial');
            self.el.appendChild(menuEl);
            renderMenu();
        }
        function closeMenu() {
            menuOpen = false;
            if (menuEl && menuEl.parentNode) menuEl.parentNode.removeChild(menuEl);
            menuEl = null;
        }

        function activate() {
            var it = menuSel >= 0 ? menuItems[menuSel] : null;
            if (it && it.disabled) return;
            closeMenu();
            if (!it) return;
            switch (it.id) {
                case 'info': openInfo(); break;
                case 'save': if (C.detail) App.saved.download(C.detail); App.toast('Сохраняем «' + C.title + '» на телевизор…'); showBars(); break;
                case 'delete':
                    App.confirm('Удалить «' + C.title + '» с телевизора? Онлайн-просмотр останется доступен.', 'Удалить', function () {
                        App.saved.remove(C.id).then(function () { App.router.pop(); });
                    });
                    break;
                case 'random': random(); break;
                case 'restart': jump(0); break;
                case 'exit': App.router.pop(); break;
            }
        }

        function random() {
            if (!opts.cat || randomBusy) return;
            randomBusy = true;
            App.toast('Выбираем случайный диафильм…', 2000);
            App.catalog.random(opts.cat, C ? C.id : null).then(function (f) {
                if (f) load(f.url, null); else App.toast('Не удалось выбрать диафильм');
            }, function (e) { App.toast(e.message, 6000); }).then(function () { randomBusy = false; });
        }

        // ---------- О диафильме ----------
        function openInfo() {
            var rows = (C.meta || []).map(function (m) {
                return h('div.info-row', {}, [h('span.info-label', { text: m.label + ': ' }), h('span', { text: m.value })]);
            });
            var closeBtn = h('div.btn.amber.sel-ring', { text: 'Закрыть' });
            closeBtn.addEventListener('click', closeInfo);
            infoEl = h('div.info-overlay', {}, [
                h('div.info-box', {}, [
                    h('div.info-title', { text: C.title }),
                    h('div.info-rows', {}, rows),
                    h('div.info-sub', { text: 'Кадров в просмотре: ' + C.frames.length + (C.offline ? ' · сохранён на телевизоре' : '') }),
                    h('div.info-actions', {}, [h('span.info-hint', { text: 'OK — закрыть' }), closeBtn])
                ])
            ]);
            self.el.appendChild(infoEl);
        }
        function closeInfo() {
            if (infoEl && infoEl.parentNode) infoEl.parentNode.removeChild(infoEl);
            infoEl = null;
        }

        // ---------- Кнопки пульта ----------
        // OK срабатывает по ОТПУСКАНИЮ и только если нажатие началось в том же режиме —
        // иначе «хвост» нажатия закрыл бы только что открытую панель.
        this.onKey = function (code, type, e) {
            var isOk = code === App.KEY.OK;
            var first = type === 'down' && !e.repeat;
            var back = App.isBack(code);

            if (!C) { // загрузка или ошибка: работает обычная навигация по кнопке «Повторить»
                if (back && type === 'up') { App.router.pop(); return true; }
                return false;
            }
            if (infoEl) {
                if (isOk) {
                    if (first) okArmed = true;
                    if (type === 'up' && okArmed) { okArmed = false; closeInfo(); }
                } else if (back && type === 'up') closeInfo();
                return true;
            }
            if (menuOpen) {
                if (isOk) {
                    if (first) okArmed = true;
                    if (type === 'up' && okArmed) { okArmed = false; activate(); }
                    return true;
                }
                if (back && type === 'up') { closeMenu(); return true; }
                if (type === 'down') {
                    if (code === App.KEY.UP) radialMove(0, -1);
                    else if (code === App.KEY.DOWN) radialMove(0, 1);
                    else if (code === App.KEY.LEFT) radialMove(-1, 0);
                    else if (code === App.KEY.RIGHT) radialMove(1, 0);
                }
                return true;
            }
            if (isOk) {
                if (first) { okArmed = false; openMenu(); }
                return true;
            }
            if (back) { if (type === 'up') App.router.pop(); return true; }
            if (type === 'down') {
                switch (code) {
                    case App.KEY.DOWN: case App.KEY.PG_DOWN: case App.KEY.FF: go(1); break;
                    case App.KEY.UP: case App.KEY.PG_UP: case App.KEY.RW: go(-1); break;
                    case App.KEY.GREEN: if (top.classList.contains('show')) hideBars(); else showBars(); break;
                }
            }
            return true; // влево/вправо на ТВ ничего не делают
        };

        // Указка Magic Remote: колесо листает, щелчок открывает меню
        this.el.addEventListener('wheel', function (e) {
            if (!C || menuOpen || infoEl) return;
            e.preventDefault();
            go(e.deltaY > 0 ? 1 : -1);
        });
        stage.addEventListener('click', function () { if (C && !menuOpen && !infoEl) openMenu(); });

        var unSaved = App.saved.listen(function () {
            if (!C) return;
            if (menuOpen) renderMenu();
            if (top.classList.contains('show')) renderBars();
        });

        this.onShow = function () { App.focus.setRoot(null); };
        this.onDestroy = function () {
            loadToken++;
            unSaved();
            clearTimeout(cleanupTimer); clearTimeout(barTimer); clearTimeout(counterTimer);
            App.saved.releaseUrls(blobUrls);
        };

        load(opts.url, opts.savedId);
    }

    App.ViewerScreen = ViewerScreen;
})();
