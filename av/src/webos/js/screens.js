/* Экраны: главная, раздел (рубрикатор), карточка, история, загруженное (сервер), настройки. */
(function () {
    'use strict';
    var esc = U.esc;
    var PAGE = 20;

    function header(title, buttons) {
        return '<div class="hdr"><div class="strip"></div><div class="bar"><h1>' + esc(title) + '</h1><div class="hbtns">' +
            (buttons || []).map(function (b) { return '<div class="btn dark f" data-act="' + b[0] + '">' + esc(b[1]) + '</div>'; }).join('') +
            '</div></div></div>';
    }

    function bindHeader(el) {
        Array.prototype.forEach.call(el.querySelectorAll('.hbtns .btn'), function (b) {
            b.addEventListener('click', function () {
                var a = b.getAttribute('data-act');
                if (a === 'home') Router.home();
                else if (a === 'back') Router.back();
                else Router.push(a);
            });
        });
    }

    function progress(p, cls) {
        return '<div class="prog ' + (cls || '') + '"><i class="' + (p >= 1 ? 'full' : '') + '" style="width:' + Math.round(p * 100) + '%"></i></div>';
    }

    function countLabel(id, n) {
        var w = { multiki: ['мультфильм', 'мультфильма', 'мультфильмов'], audio: ['сказка', 'сказки', 'сказок'],
            radioteatr: ['спектакль', 'спектакля', 'спектаклей'], rec: ['запись', 'записи', 'записей'] }[id] || ['фильм', 'фильма', 'фильмов'];
        return U.count(n) + ' ' + U.plural(n, w[0], w[1], w[2]);
    }

    function loading() { return '<div class="center">' + U.LOADER + '</div>'; }

    // ================================================================== главная
    Screens.home = function () {
        var el = U.h('<div class="screen home">' + header('Архив Вещания', [['settings', 'Настройки']]) +
            '<div class="body"><div class="notice hidden"></div><div class="tiles"></div>' +
            '<div class="credit"><h3>ИСТОЧНИК</h3><p>Все фильмы, мультфильмы, сказки и радиоспектакли — из архива «Мультики by ArjLover» ' +
            '(multiki.arjlover.net). Приложение лишь открывает файлы этого архива; права на произведения принадлежат их правообладателям.</p></div></div></div>');
        bindHeader(el);
        var tilesEl = el.querySelector('.tiles'), notice = el.querySelector('.notice');

        function render() {
            var tiles = Catalog.index().map(function (s) { return { id: s.id, title: s.title, sub: countLabel(s.id, s.count), art: Catalog.art(s.id) }; });
            var hist = Server.history().length, lib = Server.library().length;
            var active = Server.tasks().filter(function (t) { return t.state !== 'done'; }).length;
            tiles.push({ id: 'history', title: 'История', sub: hist ? countLabel('rec', hist) : 'начатое и просмотренное', art: 'clock' });
            tiles.push({ id: 'downloads', title: 'Загруженное', sub: active ? 'загружается: ' + active : lib ? countLabel('rec', lib) + ' на сервере' : 'на домашнем сервере', art: 'can' });
            var focusedId = Nav.current() && Nav.current().getAttribute('data-id');
            tilesEl.innerHTML = tiles.map(function (t, i) {
                return '<div class="tile f' + (i === 0 ? ' autofocus' : '') + '" data-id="' + t.id + '">' + U.art(t.art) + '<div class="lbl"><b>' + esc(t.title) + '</b><span>' + esc(t.sub) + '</span></div></div>';
            }).join('');
            Array.prototype.forEach.call(tilesEl.children, function (c) {
                c.addEventListener('click', function () {
                    var id = c.getAttribute('data-id');
                    if (id === 'history' || id === 'downloads') Router.push(id);
                    else Router.push('section', { id: id });
                });
                if (c.getAttribute('data-id') === focusedId) Nav.focus(c);
            });
            if (!Server.configured()) {
                notice.innerHTML = 'Домашний сервер не подключён: «Загруженное» и общие места остановки работают только через него. Откройте «Настройки».';
                notice.classList.remove('hidden');
            } else if (!Server.online() && Server.error()) {
                notice.innerHTML = 'Нет связи с домашним сервером (' + esc(Server.error()) + '). Проверьте, что он включён.';
                notice.classList.remove('hidden');
            } else notice.classList.add('hidden');
        }
        render();
        var off = Server.onChange(function () { if (el.style.display !== 'none') render(); });
        return { el: el, destroy: off, onShow: render };
    };

    // ================================================================== раздел
    Screens.section = function (params) {
        var el = U.h('<div class="screen section">' + header('', [['home', 'Главная']]) + '<div class="body">' + loading() + '</div></div>');
        bindHeader(el);
        var body = el.querySelector('.body'), sec = null, query = '', page = 0, filtered = [];

        Catalog.load(params.id).then(function (s) {
            sec = s;
            el.querySelector('.hdr h1').textContent = s.title;
            body.innerHTML = '<div class="tools"><input class="search f" type="text" placeholder="Поиск по названию"><div class="btn teal f rnd">' +
                esc(s.random) + '</div><span class="cnt"></span></div><div class="grid"></div><div class="pager"></div>';
            var input = body.querySelector('.search');
            input.addEventListener('input', function () { query = input.value; page = 0; render(); });
            input.addEventListener('keydown', function (e) { if (e.keyCode === KEY.ENTER) { input.blur(); } });
            body.querySelector('.rnd').addEventListener('click', function () {
                var pool = filtered.length ? filtered : sec.items;
                Router.push('details', { sec: sec.id, id: pool[Math.floor(Math.random() * pool.length)].id });
            });
            render();
            var first = body.querySelector('.card');
            if (first) Nav.focus(first);
        }, function (e) { body.innerHTML = '<div class="center err">' + esc(e.message) + '</div>'; });

        function render() {
            var q = U.norm(query);
            filtered = q ? sec.items.filter(function (i) { return i.key.indexOf(q) >= 0; }) : sec.items;
            var pages = Math.max(1, Math.ceil(filtered.length / PAGE));
            page = Math.min(page, pages - 1);
            body.querySelector('.cnt').textContent = (q ? 'Найдено: ' : 'Всего: ') + U.count(filtered.length);
            var grid = body.querySelector('.grid');
            var slice = filtered.slice(page * PAGE, page * PAGE + PAGE);
            if (!slice.length) {
                grid.innerHTML = '<div class="empty">Ничего не нашлось. Попробуйте написать название короче.</div>';
            } else {
                grid.innerHTML = slice.map(function (it) {
                    var thumb = Catalog.thumb(sec, it);
                    var r = Server.hist(sec.id, it.id), li = Server.libItem(sec.id, it.id);
                    var onServer = li && li.parts.every(function (p) { return p.done; });
                    var n = it.parts.length;
                    var cap = [n > 1 ? n + ' ' + U.plural(n, sec.kind === 'audio' ? 'запись' : 'серия', sec.kind === 'audio' ? 'записи' : 'серии', sec.kind === 'audio' ? 'записей' : 'серий') : '', it.dur ? U.dur(it.dur) : ''].filter(Boolean).join(' · ');
                    return '<div class="card f" data-id="' + esc(it.id) + '"><div class="pic">' + U.art(Catalog.thumbArt(sec)) +
                        (thumb ? '<img src="' + esc(thumb) + '" onerror="this.style.display=\'none\'">' : '') +
                        (onServer ? '<span class="badge srv">⌂</span>' : '') + (r && r.f ? '<span class="badge done">✓</span>' : '') +
                        (r ? progress(Hist.progress(r), 'on-pic') : '') + '</div>' +
                        '<div class="t">' + esc(it.title) + '</div><div class="c">' + esc(cap || ' ') + '</div></div>';
                }).join('');
                Array.prototype.forEach.call(grid.children, function (c) {
                    c.addEventListener('click', function () { Router.push('details', { sec: sec.id, id: c.getAttribute('data-id') }); });
                });
            }
            var pg = body.querySelector('.pager');
            pg.innerHTML = '<div class="btn f" data-p="first">«</div><div class="btn f" data-p="prev">‹ Назад</div><span>Страница ' + (page + 1) + ' из ' + pages +
                '</span><div class="btn f" data-p="next">Вперёд ›</div><div class="btn f" data-p="last">»</div>';
            Array.prototype.forEach.call(pg.querySelectorAll('.btn'), function (b) {
                var p = b.getAttribute('data-p');
                var target = p === 'first' ? 0 : p === 'prev' ? page - 1 : p === 'next' ? page + 1 : pages - 1;
                if (target < 0 || target >= pages || target === page) b.classList.add('off');
                b.addEventListener('click', function () {
                    if (target < 0 || target >= pages || target === page) return;
                    page = target;
                    render();
                    var keep = pg.querySelector('[data-p="' + p + '"]');
                    if (keep && !keep.classList.contains('off')) Nav.focus(keep); else Nav.focus(grid.querySelector('.card') || pg.querySelector('.btn'));
                });
            });
            fitTitles(grid);
        }

        var off = Server.onChange(function () { if (sec && el.style.display !== 'none') { var f = Nav.current(); render(); restoreFocus(f); } });
        function restoreFocus(f) {
            if (!f) return;
            var id = f.getAttribute && f.getAttribute('data-id');
            var same = id && el.querySelector('.card[data-id="' + id.replace(/"/g, '\\"') + '"]');
            if (same) Nav.focus(same);
        }
        return { el: el, destroy: off, onShow: function () { if (sec) { var f = Nav.current(); render(); restoreFocus(f); } } };
    };

    /** Подписи без разрыва слов: уменьшаем шрифт, пока слово не поместится (до 70%). */
    function fitTitles(root) {
        Array.prototype.forEach.call(root.querySelectorAll('.t'), function (t) {
            var size = 100;
            t.style.fontSize = '';
            while (size > 70 && (t.scrollWidth > t.clientWidth + 1 || t.scrollHeight > t.clientHeight + 1)) {
                size -= 6;
                t.style.fontSize = size + '%';
            }
        });
    }

    // ================================================================== карточка
    Screens.details = function (params) {
        var el = U.h('<div class="screen details">' + header('', [['back', '← Назад'], ['home', 'Главная']]) + '<div class="body">' + loading() + '</div></div>');
        bindHeader(el);
        var body = el.querySelector('.body'), sec = null, it = null;

        Catalog.load(params.sec).then(function (s) {
            sec = s;
            it = s.byId[params.id];
            el.querySelector('.hdr h1').textContent = s.title;
            if (!it) { body.innerHTML = '<div class="center err">Не найдено</div>'; return; }
            render(true);
        });

        function render(first) {
            var video = sec.kind !== 'audio';
            var r = Server.hist(sec.id, it.id), li = Server.libItem(sec.id, it.id);
            var onServer = li && li.parts.length && li.parts.every(function (p) { return p.done; });
            var tasks = Server.itemTasks(sec.id, it.id);
            var verb = video ? 'Смотреть' : 'Слушать';

            var pics = video
                ? '<div class="big">' + U.art('film') + '<img src="' + esc(Catalog.image(sec, it, 1)) + '" onerror="this.style.display=\'none\'"></div><div class="frames">' +
                  [1, 2, 3, 4, 5, 6].map(function (k) { return '<div class="fr f" data-k="' + k + '">' + U.art('film') + '<img src="' + esc(Catalog.thumb(sec, it, k)) + '" onerror="this.style.display=\'none\'"></div>'; }).join('') + '</div>'
                : '<div class="big sq">' + U.art(Catalog.thumbArt(sec)) + '</div>';

            var facts = [['Длительность', it.dur ? U.dur(it.dur) : ''], ['Размер', it.size ? U.size(it.size) : ''],
                ['Изображение', it.parts[0] && it.parts[0].res], ['Добавлено в архив', U.isoDate(it.added)]]
                .filter(function (f) { return f[1]; }).map(function (f) { return '<div><b>' + f[0] + ':</b> ' + esc(f[1]) + '</div>'; }).join('');

            var btns = Hist.canResume(r)
                ? '<div class="btn red f autofocus" data-a="resume">Продолжить с ' + U.ms(r.pos) + '</div><div class="btn soft f" data-a="start">Сначала</div>'
                : '<div class="btn red f autofocus" data-a="start">' + (r && r.f ? verb + ' снова' : verb) + '</div>';

            var srv;
            if (!Server.configured()) srv = '<div class="srvline">Чтобы сохранять на домашний сервер, подключите его в «Настройках».</div>';
            else if (onServer) srv = '<div class="srvline ok">✓ На домашнем сервере — смотрится на всех устройствах</div><div class="btn soft f" data-a="del">Удалить с сервера</div>';
            else if (tasks.length) {
                var done = tasks.reduce(function (a, t) { return a + (t.done || 0); }, 0), total = tasks.reduce(function (a, t) { return a + (t.total || 0); }, 0);
                var cur = tasks.filter(function (t) { return t.message; })[0];
                srv = '<div class="srvline">Загружается на сервер' + (total ? ': ' + Math.floor(done * 100 / total) + '%' : '') + (cur ? ' · ' + esc(cur.message) : '') + '</div><div class="btn soft f" data-a="cancel">Отменить загрузку</div>';
            } else if (li && li.busyDevice) srv = '<div class="srvline">Загружается на сервер с устройства «' + esc(li.busyDevice) + '»</div>';
            else srv = '<div class="btn teal f" data-a="dl">Скачать на сервер</div>';

            var parts = '';
            if (it.parts.length > 1) {
                var last = null;
                parts = '<h3>' + (video ? 'СЕРИИ' : 'ЗАПИСИ') + '</h3>' + it.parts.map(function (p, i) {
                    var g = '';
                    if (!video && p.group && p.group !== last) { last = p.group; g = '<div class="grp">' + esc(p.group) + '</div>'; }
                    var here = Hist.canResume(r) && r.p === i;
                    var meta = [here ? 'остановились на ' + U.ms(r.pos) : '', p.dur ? U.dur(p.dur) : '', p.size ? U.size(p.size) : ''].filter(Boolean).join(' · ');
                    return g + '<div class="part f" data-i="' + i + '"><span>' + esc(video ? 'Серия ' + (i + 1) : Catalog.trackTitle(it, p)) + '</span><em>' + esc(meta) + '</em></div>';
                }).join('');
            }

            body.innerHTML = '<div class="cols"><div class="left">' + pics + '</div><div class="right">' +
                '<h2 class="title">' + esc(it.title) + '</h2>' + (it.titleAuto ? '<div class="note">Название восстановлено по имени файла и может быть неточным</div>' : '') +
                '<div class="facts">' + facts + '</div>' +
                (r ? '<div class="resume">' + esc(Hist.line(r, video)) + '</div>' + progress(Hist.progress(r)) : '') +
                '<div class="btns">' + btns + '</div><div class="srv">' + srv + '</div>' + parts +
                (it.descr ? '<h3>С КОНВЕРТА ПЛАСТИНКИ</h3><div class="descr">' + esc(it.descr).replace(/\n/g, '<br>') + '</div>' : '') +
                '</div></div>';

            Array.prototype.forEach.call(body.querySelectorAll('[data-a]'), function (b) {
                b.addEventListener('click', function () { act(b.getAttribute('data-a'), r); });
            });
            Array.prototype.forEach.call(body.querySelectorAll('.part'), function (b) {
                b.addEventListener('click', function () {
                    var i = +b.getAttribute('data-i');
                    var here = Hist.canResume(r) && r.p === i;
                    play(i, here ? r.pos : 0);
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll('.fr'), function (f) {
                f.addEventListener('click', function () {
                    var img = body.querySelector('.big img');
                    img.style.display = '';
                    img.src = Catalog.image(sec, it, +f.getAttribute('data-k'));
                });
            });
            if (first) Nav.focusFirst(body);
        }

        function play(part, startMs) { Router.push('player', { sec: sec.id, id: it.id, part: part, start: startMs || 0 }); }

        function act(a, r) {
            if (a === 'resume') play(r.p, r.pos);
            else if (a === 'start') play(0, 0);
            else if (a === 'dl') Server.download(sec, it).then(function () { U.toast('Поставлено в очередь загрузки на сервер'); }, function (e) { U.toast(e.message); });
            else if (a === 'cancel') Server.cancel(sec.id, it.id);
            else if (a === 'del') U.confirm('Удалить с сервера?', '«' + it.title + '» будет удалён с домашнего сервера — для всех устройств.', [
                { label: 'Удалить', primary: true, action: function () { Server.remove(sec.id, it.id); } }]);
        }

        var timer = setInterval(function () { if (it && Server.itemTasks(sec.id, it.id).length) Server.refreshTasks(); }, 3000);
        var off = Server.onChange(function () {
            if (!it || el.style.display === 'none') return;
            var f = Nav.current(), a = f && f.getAttribute('data-a'), i = f && f.getAttribute('data-i');
            render(false);
            var again = a ? body.querySelector('[data-a="' + a + '"]') : i ? body.querySelector('[data-i="' + i + '"]') : null;
            Nav.focus(again || body.querySelector('.f'));
        });
        return { el: el, destroy: function () { off(); clearInterval(timer); }, onShow: function () { if (it) { render(false); Nav.focusFirst(body); } } };
    };

    // ================================================================== списки: история и загруженное
    function rowHtml(o, i) {
        return '<div class="row"><div class="rcard f' + (i === 0 ? ' autofocus' : '') + '" data-k="' + esc(o.key) + '"><div class="rpic">' + U.art(o.art) +
            (o.thumb ? '<img src="' + esc(o.thumb) + '" onerror="this.style.display=\'none\'">' : '') + '</div>' +
            '<div class="rtext"><b>' + esc(o.title) + '</b><span>' + esc(o.sub) + '</span><em class="' + (o.err ? 'err' : '') + '">' + esc(o.status) + '</em>' + progress(o.progress) + '</div></div>' +
            o.buttons.map(function (b) { return '<div class="rbtn f ' + b[1] + '" data-k="' + esc(o.key) + '" data-b="' + b[0] + '">' + b[2] + '</div>'; }).join('') + '</div>';
    }

    var ICON = { play: '▶', del: '✕', stop: '■', again: '↺' };

    function thumbFor(secId, itemId) {
        var host = { multiki: 'multiki.arjlover.net', film: 'film.arjlover.net', filmiki: 'filmiki.arjlover.net' }[secId];
        return host ? 'http://' + host + '/ap/' + itemId + '/' + itemId + '.thumb1.jpg' : null;
    }

    function artFor(secId) { return secId === 'audio' ? 'record' : secId === 'radioteatr' ? 'radio' : 'film'; }

    function playFromHistory(secId, itemId) {
        var r = Server.hist(secId, itemId);
        if (Hist.canResume(r)) Router.push('player', { sec: secId, id: itemId, part: r.p, start: r.pos });
        else Router.push('player', { sec: secId, id: itemId, part: 0, start: 0 });
    }

    Screens.history = function () {
        var el = U.h('<div class="screen list">' + header('История просмотров', [['home', 'Главная']]) + '<div class="body"><div class="rows"></div></div></div>');
        bindHeader(el);
        var rows = el.querySelector('.rows');
        var clearBtn = U.h('<div class="btn dark f" data-act="clear">Очистить</div>');
        el.querySelector('.hbtns').insertBefore(clearBtn, el.querySelector('.hbtns').firstChild);
        clearBtn.addEventListener('click', function () {
            U.confirm('Очистить историю?', 'Список и все места остановки будут удалены — на всех устройствах.', [{ label: 'Очистить', primary: true, action: function () {
                Server.history().slice().forEach(function (r) { Server.removeHistory(r); });
            } }]);
        });

        function render() {
            var list = Server.history();
            if (!Server.configured()) { rows.innerHTML = '<div class="empty">История хранится на домашнем сервере. Подключите его в «Настройках».</div>'; return; }
            if (!list.length) { rows.innerHTML = '<div class="empty">' + U.art('clock') + '<p>Здесь появятся фильмы, мультфильмы и сказки, которые вы начали смотреть или слушать — на любом устройстве дома.</p></div>'; return; }
            rows.innerHTML = list.map(function (r, i) {
                return rowHtml({ key: r.s + '|' + r.i, art: artFor(r.s), thumb: r.v ? thumbFor(r.s, r.i) : null, title: r.t, sub: r.st,
                    status: Hist.line(r, r.v), progress: Hist.progress(r),
                    buttons: [['play', 'red', Hist.canResume(r) ? ICON.play : ICON.again], ['del', 'soft small', ICON.del]] }, i);
            }).join('');
            bindRows(rows, function (k, b) {
                var p = k.split('|'), r = Server.hist(p[0], p[1]);
                if (b === 'play') playFromHistory(p[0], p[1]);
                else if (b === 'del' && r) Server.removeHistory(r);
                else Router.push('details', { sec: p[0], id: p[1] });
            });
        }
        render();
        Server.refresh();
        var off = Server.onChange(function () { if (el.style.display !== 'none') { var f = keyOf(Nav.current()); render(); refocus(rows, f); } });
        return { el: el, destroy: off, onShow: function () { Server.refresh(); } };
    };

    Screens.downloads = function () {
        var el = U.h('<div class="screen list">' + header('Загруженное', [['settings', 'Настройки'], ['home', 'Главная']]) +
            '<div class="body"><div class="sub"></div><div class="rows"></div></div></div>');
        bindHeader(el);
        var rows = el.querySelector('.rows'), sub = el.querySelector('.sub');

        function render() {
            if (!Server.configured()) {
                sub.textContent = '';
                rows.innerHTML = '<div class="empty">' + U.art('can') + '<p>«Загруженное» хранится на домашнем сервере и общее для всех телевизоров, планшетов и телефонов. Подключите сервер в «Настройках».</p></div>';
                return;
            }
            sub.textContent = Server.online() ? Server.url() + ' · общий для всех устройств' : 'Нет связи с сервером: ' + (Server.error() || '');
            var tasks = Server.tasks().filter(function (t) { return t.state !== 'done'; });
            var byItem = {};
            tasks.forEach(function (t) { (byItem[t.sectionId + '|' + t.itemId] = byItem[t.sectionId + '|' + t.itemId] || []).push(t); });
            var lib = Server.library();
            var keys = {};
            var items = [];
            Object.keys(byItem).forEach(function (k) {
                var ts = byItem[k], f = ts[0];
                keys[k] = 1;
                var done = ts.reduce(function (a, t) { return a + (t.done || 0); }, 0), total = ts.reduce(function (a, t) { return a + (t.total || 0); }, 0);
                var msg = ts.filter(function (t) { return t.message; })[0];
                items.push({ key: k, art: artFor(f.sectionId), thumb: f.isVideo ? thumbFor(f.sectionId, f.itemId) : null, title: f.title, sub: f.sectionTitle,
                    status: 'Загружается на сервер' + (total ? ': ' + Math.floor(done * 100 / total) + '% · ' + U.size(done) + ' из ' + U.size(total) : '') + (msg ? ' · ' + msg.message : ''),
                    progress: total ? done / total : 0, buttons: [['cancel', 'soft', ICON.stop]] });
            });
            lib.forEach(function (l) {
                var k = l.sectionId + '|' + l.itemId;
                if (keys[k]) return;
                var doneParts = l.parts.filter(function (p) { return p.done; });
                var complete = l.parts.length && doneParts.length === l.parts.length;
                var bytes = doneParts.reduce(function (a, p) { return a + (p.size || 0); }, 0);
                var busy = l.busyDevice && Date.now() - (l.busyAt || 0) < 180000;
                items.push({ key: k, art: artFor(l.sectionId), thumb: l.isVideo ? thumbFor(l.sectionId, l.itemId) : null, title: l.title, sub: l.sectionTitle,
                    status: complete ? '✓ На сервере · ' + U.size(bytes) + ' · смотрится на всех устройствах'
                        : busy ? 'Загружается с устройства «' + l.busyDevice + '» · ' + doneParts.length + ' из ' + l.parts.length
                        : 'Загружено ' + doneParts.length + ' из ' + l.parts.length + ' — докачать можно в карточке',
                    progress: l.parts.length ? doneParts.length / l.parts.length : 0,
                    buttons: complete ? [['play', 'red', ICON.play], ['del', 'soft small', ICON.del]] : [['del', 'soft small', ICON.del]] });
            });
            if (!items.length) {
                rows.innerHTML = '<div class="empty">' + U.art('can') + '<p>На сервере пока ничего нет. Откройте карточку и нажмите «Скачать на сервер».</p></div>';
                return;
            }
            rows.innerHTML = items.map(rowHtml).join('');
            bindRows(rows, function (k, b) {
                var p = k.split('|');
                var title = (items.filter(function (i) { return i.key === k; })[0] || {}).title;
                if (b === 'play') playFromHistory(p[0], p[1]);
                else if (b === 'cancel') Server.cancel(p[0], p[1]);
                else if (b === 'del') U.confirm('Удалить с сервера?', '«' + title + '» будет удалён с домашнего сервера — для всех устройств.', [
                    { label: 'Удалить', primary: true, action: function () { Server.remove(p[0], p[1]); } }]);
                else Router.push('details', { sec: p[0], id: p[1] });
            });
        }
        render();
        Server.refresh();
        var timer = setInterval(function () { if (el.style.display !== 'none') Server.refreshTasks(); }, 3000);
        var off = Server.onChange(function () { if (el.style.display !== 'none') { var f = keyOf(Nav.current()); render(); refocus(rows, f); } });
        return { el: el, destroy: function () { off(); clearInterval(timer); }, onShow: function () { Server.refresh(); } };
    };

    function bindRows(rows, handler) {
        Array.prototype.forEach.call(rows.querySelectorAll('[data-k]'), function (b) {
            b.addEventListener('click', function () { handler(b.getAttribute('data-k'), b.getAttribute('data-b')); });
        });
    }

    function keyOf(f) { return f ? { k: f.getAttribute('data-k'), b: f.getAttribute('data-b') } : null; }
    function refocus(rows, f) {
        if (!f || !f.k) return;
        var all = rows.querySelectorAll('[data-k]');
        for (var i = 0; i < all.length; i++) {
            if (all[i].getAttribute('data-k') === f.k && all[i].getAttribute('data-b') === f.b) return Nav.focus(all[i]);
        }
        Nav.focusFirst(rows);
    }

    // ================================================================== настройки
    Screens.settings = function () {
        var el = U.h('<div class="screen settings">' + header('Настройки', [['back', '← Назад'], ['home', 'Главная']]) +
            '<div class="body"><div class="form"><h3>ДОМАШНИЙ СЕРВЕР</h3>' +
            '<p>Адрес помощника «Архив Вещания» на домашнем сервере. Через него телевизор видит общее «Загруженное», места остановки ' +
            '(остановились на планшете — продолжайте здесь) и смотрит архив онлайн с соблюдением правила «одно соединение на раздел».</p>' +
            '<label>Адрес сервера</label><input class="f inp-url autofocus" type="text" placeholder="например, 192.168.1.50:8765">' +
            '<label>Имя этого телевизора</label><input class="f inp-name" type="text" placeholder="например, Телевизор в зале">' +
            '<div class="btns"><div class="btn teal f" data-a="test">Проверить</div><div class="btn red f" data-a="save">Сохранить</div></div>' +
            '<div class="status"></div>' +
            '<div class="help"><b>Как установить помощника на сервер</b><br>' +
            '1. На Linux-сервере с Samba выполните одну команду (подставьте свою папку «Архив Вещания» и пользователя Samba):<br>' +
            '<code>curl -fsSL https://i1yanov.github.io/fp/av/install.sh | sudo bash -s -- "/путь/к/Архив Вещания" пользователь</code><br>' +
            '2. Установщик покажет адрес вида <code>http://192.168.1.50:8765</code> — введите его выше и нажмите «Сохранить».<br>' +
            'Подробная инструкция: <code>i1yanov.github.io/fp/av</code></div></div></div></div>');
        bindHeader(el);
        var inUrl = el.querySelector('.inp-url'), inName = el.querySelector('.inp-name'), status = el.querySelector('.status');
        inUrl.value = Server.url().replace(/^http:\/\//, '');
        inName.value = Server.deviceName();
        [inUrl, inName].forEach(function (i) { i.addEventListener('keydown', function (e) { if (e.keyCode === KEY.ENTER) i.blur(); }); });

        function show(text, ok) { status.textContent = text; status.className = 'status ' + (ok ? 'ok' : 'err'); }

        el.querySelector('[data-a="test"]').addEventListener('click', function () {
            var u = Server.normalizeUrl(inUrl.value);
            if (!u) return show('Введите адрес сервера', false);
            show('Проверяю…', true);
            Server.test(u).then(function (r) {
                show(r.rootExists ? 'Помощник работает (версия ' + r.version + '). Папка: ' + r.root : 'Помощник отвечает, но папки «' + r.root + '» нет на сервере', !!r.rootExists);
            }, function (e) { show(e.message + '. Проверьте адрес и что помощник запущен.', false); });
        });
        el.querySelector('[data-a="save"]').addEventListener('click', function () {
            var u = Server.normalizeUrl(inUrl.value);
            Server.save(u, inName.value.trim() || 'Телевизор LG');
            show(u ? 'Сохранено: ' + u : 'Сервер отключён', true);
        });
        return { el: el };
    };
})();
