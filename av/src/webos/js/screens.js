/* Экраны: главная, «Скачанное», «История», карточка, настройки. Всё — только с домашнего сервера. */
(function () {
    'use strict';
    var esc = U.esc;

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
            radioteatr: ['спектакль', 'спектакля', 'спектаклей'], rec: ['запись', 'записи', 'записей'], title: ['название', 'названия', 'названий'] }[id] || ['фильм', 'фильма', 'фильмов'];
        return U.count(n) + ' ' + U.plural(n, w[0], w[1], w[2]);
    }

    function loading() { return '<div class="center">' + U.LOADER + '</div>'; }

    // ================================================================== главная
    Screens.home = function () {
        var el = U.h('<div class="screen home">' + header('Архив Вещания', [['settings', 'Настройки']]) +
            '<div class="body"><div class="notice hidden"></div><div class="tiles"></div>' +
            '<div class="credit"><h3>ИСТОЧНИК</h3><p>Фильмы, мультфильмы, сказки и радиоспектакли — из архива «Мультики by ArjLover». ' +
            'Телевизор показывает только то, что уже скачано на домашний сервер с Android-устройств; с сайтом архива приложение не соединяется. ' +
            'Права на произведения принадлежат их правообладателям.</p></div></div></div>');
        bindHeader(el);
        var tilesEl = el.querySelector('.tiles'), notice = el.querySelector('.notice');

        function render() {
            var ready = Lib.ready().length, hist = Server.history().length;
            var tiles = [
                { id: 'downloads', title: 'Скачанное', sub: ready ? countLabel('title', ready) + ' на сервере' : 'на домашнем сервере', art: 'can' },
                { id: 'history', title: 'История', sub: hist ? countLabel('rec', hist) : 'начатое и просмотренное', art: 'clock' }
            ];
            var focusedId = Nav.current() && Nav.current().getAttribute('data-id');
            tilesEl.innerHTML = tiles.map(function (t, i) {
                return '<div class="tile f' + (i === 0 ? ' autofocus' : '') + '" data-id="' + t.id + '">' + U.art(t.art) + '<div class="lbl"><b>' + esc(t.title) + '</b><span>' + esc(t.sub) + '</span></div></div>';
            }).join('');
            Array.prototype.forEach.call(tilesEl.children, function (c) {
                c.addEventListener('click', function () { Router.push(c.getAttribute('data-id')); });
                if (c.getAttribute('data-id') === focusedId) Nav.focus(c);
            });
            if (!Server.configured()) {
                notice.innerHTML = 'Домашний сервер не подключён — без него смотреть нечего. Откройте «Настройки».';
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

    // ================================================================== карточка (только для скачанного)
    Screens.details = function (params) {
        var el = U.h('<div class="screen details">' + header('', [['back', '← Назад'], ['home', 'Главная']]) + '<div class="body">' + loading() + '</div></div>');
        bindHeader(el);
        var body = el.querySelector('.body'), it = null;

        function render(first) {
            it = Lib.get(params.sec, params.id);
            if (!it || !it.complete) {
                el.querySelector('.hdr h1').textContent = it ? it.secTitle : '';
                body.innerHTML = '<div class="center err">' + (Server.online() ? 'Этого нет на домашнем сервере' : 'Нет связи с домашним сервером') + '</div>';
                return;
            }
            el.querySelector('.hdr h1').textContent = it.secTitle;
            var video = it.video, r = Server.hist(it.sec, it.id);
            var verb = video ? 'Смотреть' : 'Слушать';
            var facts = [['Раздел', it.secTitle], ['Размер', it.size ? U.size(it.size) : ''], [video ? 'Серий' : 'Записей', it.parts.length > 1 ? String(it.parts.length) : '']]
                .filter(function (f) { return f[1]; }).map(function (f) { return '<div><b>' + f[0] + ':</b> ' + esc(f[1]) + '</div>'; }).join('');
            var btns = Hist.canResume(r)
                ? '<div class="btn red f autofocus" data-a="resume">Продолжить с ' + U.ms(r.pos) + '</div><div class="btn soft f" data-a="start">Сначала</div>'
                : '<div class="btn red f autofocus" data-a="start">' + (r && r.f ? verb + ' снова' : verb) + '</div>';
            var parts = '';
            if (it.parts.length > 1) {
                parts = '<h3>' + (video ? 'СЕРИИ' : 'ЗАПИСИ') + '</h3>' + it.parts.map(function (p, i) {
                    var here = Hist.canResume(r) && r.p === i;
                    var meta = [here ? 'остановились на ' + U.ms(r.pos) : '', p.size ? U.size(p.size) : ''].filter(Boolean).join(' · ');
                    return '<div class="part f" data-i="' + i + '"><span>' + esc(video ? 'Серия ' + (i + 1) : Lib.trackTitle(it, p)) + '</span><em>' + esc(meta) + '</em></div>';
                }).join('');
            }
            body.innerHTML = '<div class="cols"><div class="left"><div class="big' + (video ? '' : ' sq') + '">' + U.art(Lib.art(it.sec)) + '</div></div><div class="right">' +
                '<h2 class="title">' + esc(it.title) + '</h2><div class="facts">' + facts + '</div>' +
                (r ? '<div class="resume">' + esc(Hist.line(r, video)) + '</div>' + progress(Hist.progress(r)) : '') +
                '<div class="btns">' + btns + '</div><div class="srv"><div class="srvline ok">✓ На домашнем сервере</div>' +
                '<div class="btn soft f" data-a="del">Удалить с сервера</div></div>' + parts + '</div></div>';
            Array.prototype.forEach.call(body.querySelectorAll('[data-a]'), function (b) {
                b.addEventListener('click', function () { act(b.getAttribute('data-a'), r); });
            });
            Array.prototype.forEach.call(body.querySelectorAll('.part'), function (b) {
                b.addEventListener('click', function () {
                    var i = +b.getAttribute('data-i');
                    play(i, Hist.canResume(r) && r.p === i ? r.pos : 0);
                });
            });
            if (first) Nav.focusFirst(body);
        }

        function play(part, startMs) { Router.push('player', { sec: it.sec, id: it.id, part: part, start: startMs || 0 }); }

        function act(a, r) {
            if (a === 'resume') play(r.p, r.pos);
            else if (a === 'start') play(0, 0);
            else if (a === 'del') U.confirm('Удалить с сервера?', '«' + it.title + '» будет удалён с домашнего сервера — для всех устройств.', [
                { label: 'Удалить', primary: true, action: function () { Server.remove(it.sec, it.id).then(function () { Router.back(); }); } }]);
        }

        render(true);
        var off = Server.onChange(function () {
            if (el.style.display === 'none') return;
            var f = Nav.current(), a = f && f.getAttribute('data-a'), i = f && f.getAttribute('data-i');
            render(false);
            var again = a ? body.querySelector('[data-a="' + a + '"]') : i ? body.querySelector('[data-i="' + i + '"]') : null;
            Nav.focus(again || body.querySelector('.f'));
        });
        return { el: el, destroy: off, onShow: function () { render(false); Nav.focusFirst(body); } };
    };

    // ================================================================== списки: история и скачанное
    function rowHtml(o, i) {
        return '<div class="row"><div class="rcard f' + (i === 0 ? ' autofocus' : '') + (o.dim ? ' dim' : '') + '" data-k="' + esc(o.key) + '"><div class="rpic">' + U.art(o.art) + '</div>' +
            '<div class="rtext"><b>' + esc(o.title) + '</b><span>' + esc(o.sub) + '</span><em class="' + (o.err ? 'err' : '') + '">' + esc(o.status) + '</em>' + progress(o.progress) + '</div></div>' +
            o.buttons.map(function (b) { return '<div class="rbtn f ' + b[1] + '" data-k="' + esc(o.key) + '" data-b="' + b[0] + '">' + b[2] + '</div>'; }).join('') + '</div>';
    }

    var ICON = { play: '▶', del: '✕', again: '↺' };

    /** Играть только то, что есть на сервере. */
    function playItem(secId, itemId) {
        var it = Lib.get(secId, itemId);
        if (!it || !it.complete) { U.toast('Этого нет на домашнем сервере. Скачайте на сервер с Android-устройства.'); return; }
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
                var it = Lib.get(r.s, r.i), here = it && it.complete;
                return rowHtml({ key: r.s + '|' + r.i, art: Lib.art(r.s), title: r.t, sub: r.st, dim: !here,
                    status: here ? Hist.line(r, r.v) : 'Нет на домашнем сервере · ' + Hist.line(r, r.v), progress: Hist.progress(r),
                    buttons: here ? [['play', 'red', Hist.canResume(r) ? ICON.play : ICON.again], ['del', 'soft small', ICON.del]] : [['del', 'soft small', ICON.del]] }, i);
            }).join('');
            bindRows(rows, function (k, b) {
                var p = k.split('|'), r = Server.hist(p[0], p[1]);
                if (b === 'play') playItem(p[0], p[1]);
                else if (b === 'del' && r) Server.removeHistory(r);
                else if (Lib.get(p[0], p[1]) && Lib.get(p[0], p[1]).complete) Router.push('details', { sec: p[0], id: p[1] });
                else U.toast('Этого нет на домашнем сервере. Скачайте на сервер с Android-устройства.');
            });
        }
        render();
        Server.refresh();
        var off = Server.onChange(function () { if (el.style.display !== 'none') { var f = keyOf(Nav.current()); render(); refocus(rows, f); } });
        return { el: el, destroy: off, onShow: function () { Server.refresh(); } };
    };

    Screens.downloads = function () {
        var el = U.h('<div class="screen list">' + header('Скачанное', [['settings', 'Настройки'], ['home', 'Главная']]) +
            '<div class="body"><div class="sub"></div><div class="rows"></div></div></div>');
        bindHeader(el);
        var rows = el.querySelector('.rows'), sub = el.querySelector('.sub');

        function render() {
            if (!Server.configured()) {
                sub.textContent = '';
                rows.innerHTML = '<div class="empty">' + U.art('can') + '<p>«Скачанное» хранится на домашнем сервере. Подключите его в «Настройках».</p></div>';
                return;
            }
            sub.textContent = Server.online() ? Server.url() + ' · общий для всех устройств' : 'Нет связи с сервером: ' + (Server.error() || '');
            var items = Server.library().map(Lib.item).map(function (it) {
                var r = Server.hist(it.sec, it.id);
                var done = it.parts.filter(function (p) { return p.done; }).length;
                return { key: it.sec + '|' + it.id, art: Lib.art(it.sec), title: it.title, sub: it.secTitle, dim: !it.complete,
                    status: it.complete ? (r ? Hist.line(r, it.video) : U.size(it.size)) : 'Ещё скачивается на сервер: ' + done + ' из ' + it.parts.length,
                    progress: it.complete ? Hist.progress(r) : done / Math.max(1, it.parts.length),
                    buttons: it.complete ? [['play', 'red', Hist.canResume(r) ? ICON.play : ICON.again], ['del', 'soft small', ICON.del]] : [] };
            });
            if (!items.length) {
                rows.innerHTML = '<div class="empty">' + U.art('can') + '<p>На сервере пока ничего нет. Скачайте мультфильмы и сказки на сервер с телефона или планшета — они появятся здесь.</p></div>';
                return;
            }
            rows.innerHTML = items.map(rowHtml).join('');
            bindRows(rows, function (k, b) {
                var p = k.split('|');
                var title = (items.filter(function (i) { return i.key === k; })[0] || {}).title;
                if (b === 'play') playItem(p[0], p[1]);
                else if (b === 'del') U.confirm('Удалить с сервера?', '«' + title + '» будет удалён с домашнего сервера — для всех устройств.', [
                    { label: 'Удалить', primary: true, action: function () { Server.remove(p[0], p[1]); } }]);
                else if (Lib.get(p[0], p[1]).complete) Router.push('details', { sec: p[0], id: p[1] });
                else U.toast('Ещё скачивается на сервер');
            });
        }
        render();
        Server.refresh();
        var off = Server.onChange(function () { if (el.style.display !== 'none') { var f = keyOf(Nav.current()); render(); refocus(rows, f); } });
        return { el: el, destroy: off, onShow: function () { Server.refresh(); } };
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
            '<p>Адрес помощника «Архив Вещания» на домашнем сервере. Через него телевизор видит «Скачанное» — то, что скачали на сервер ' +
            'Android-устройства, — и общие места остановки (остановились на планшете — продолжайте здесь). С сайтом архива телевизор не соединяется.</p>' +
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
