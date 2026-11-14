/*
 * Плеер на встроенном <video> webOS (аппаратное декодирование: AVI/XviD, MPEG-2, MP3).
 * Источник — только файл на домашнем сервере. С сайтом архива плеер не соединяется.
 * Место остановки уходит на сервер каждые 10 с, при паузе и выходе — общее со всеми устройствами.
 * После последней серии — «Что дальше?» среди скачанного в том же разделе: отсчёт 10 с, «Сейчас», «Стоп», «По порядку», «Случайно».
 */
Screens.player = function (params) {
    'use strict';
    var esc = U.esc;
    var el = U.h('<div class="screen player"><div class="aud hidden"></div><video preload="auto"></video>' +
        '<div class="pload">' + U.LOADER + '<div class="pstat"></div></div>' +
        '<div class="pstate hidden">❚❚</div>' +
        '<div class="osd"><div class="top"><div class="ttl"></div><div class="sub"></div></div>' +
        '<div class="bottom"><div class="pbar"><i></i><b></b></div><div class="times"><span class="cur">0:00</span><span class="seek"></span><span class="dur">0:00</span></div>' +
        '<div class="keys">OK — пауза · ◀ ▶ — перемотка 10 с (удерживайте — быстрее) · CH ▲▼ — серии · «Назад» — выход</div><div class="msg"></div></div></div>' +
        '<div class="perr hidden"></div><div class="next hidden"></div></div>');
    var video = el.querySelector('video'), load = el.querySelector('.pload'), pstat = el.querySelector('.pstat');
    var osd = el.querySelector('.osd'), barI = osd.querySelector('.pbar i'), barB = osd.querySelector('.pbar b');
    var curEl = osd.querySelector('.cur'), durEl = osd.querySelector('.dur'), seekEl = osd.querySelector('.seek'), msgEl = osd.querySelector('.msg');
    var stateEl = el.querySelector('.pstate'), errEl = el.querySelector('.perr'), nextEl = el.querySelector('.next'), audEl = el.querySelector('.aud');

    var it = null, part = 0, resumeMs = 0, alive = true, started = false, finished = false;
    var osdTimer = null, saveTimer = null, cdTimer = null;
    var seekTarget = null, seekTimer = null, lastSeekKey = 0, seekStreak = 0;
    var next = null, nextRandom = false, secondsLeft = 10;

    function init() {
        it = Lib.get(params.sec, params.id);
        if (!it || !it.complete) return fail('Этого нет на домашнем сервере');
        if (!it.video) { audEl.innerHTML = U.art(Lib.art(it.sec)); audEl.classList.remove('hidden'); }
        start(params.part || 0, params.start || 0);
    }
    setTimeout(init, 0);

    function start(p, fromMs) {
        part = Math.max(0, Math.min(p, it.parts.length - 1));
        resumeMs = fromMs || 0;
        started = false;
        errEl.classList.add('hidden');
        load.classList.remove('hidden');
        osd.querySelector('.ttl').textContent = it.title;
        osd.querySelector('.sub').textContent = it.parts.length > 1
            ? (it.video ? 'Серия ' : 'Запись ') + (part + 1) + ' из ' + it.parts.length + (it.video ? '' : ': ' + Lib.trackTitle(it, it.parts[part]))
            : it.secTitle;
        var src = Server.partUrl(it.sec, it.id, it.parts[part].n);
        if (!src) return fail('Этой части нет на домашнем сервере');
        video.src = src;
        video.load();
        var pr = video.play();
        if (pr && pr.catch) pr.catch(function () { /* autoplay handled by events */ });
        showOsd();
    }

    // ---------------------------------------------------------- события видео
    video.addEventListener('loadedmetadata', function () {
        if (resumeMs > 0) {
            try { video.currentTime = resumeMs / 1000; } catch (e) { /* ignore */ }
            resumeMs = 0;
        }
    });
    video.addEventListener('playing', function () {
        load.classList.add('hidden');
        stateEl.classList.add('hidden');
        if (!started) { started = true; save(false); }
        showOsd();
    });
    video.addEventListener('waiting', function () { load.classList.remove('hidden'); });
    video.addEventListener('pause', function () { if (started && !video.ended) { stateEl.classList.remove('hidden'); save(false); showOsd(true); } });
    video.addEventListener('timeupdate', updateBar);
    video.addEventListener('ended', function () {
        if (part < it.parts.length - 1) { start(part + 1, 0); return; }
        finished = true;
        save(true);
        showNext();
    });
    video.addEventListener('error', function () {
        if (!alive || !it) return;
        load.classList.add('hidden');
        fail('Не удалось воспроизвести файл с домашнего сервера. Проверьте, что сервер включён.');
    });

    function fail(text) {
        load.classList.add('hidden');
        errEl.innerHTML = '<h2>' + esc(text) + '</h2><div class="btn red f autofocus" data-a="retry">Попробовать снова</div><div class="btn soft f" data-a="exit">Выйти</div>';
        errEl.classList.remove('hidden');
        errEl.querySelector('[data-a="retry"]').addEventListener('click', function () {
            if (!it || !it.complete) { errEl.classList.add('hidden'); load.classList.remove('hidden'); Server.refresh().then(init); }
            else start(part, Math.floor((video.currentTime || 0) * 1000));
        });
        errEl.querySelector('[data-a="exit"]').addEventListener('click', function () { Router.back(); });
        Nav.focusFirst(errEl);
    }

    function updateBar() {
        var d = video.duration || 0, t = seekTarget != null ? seekTarget : video.currentTime || 0;
        barI.style.width = d ? Math.min(100, t / d * 100) + '%' : '0';
        barB.style.left = d ? Math.min(100, t / d * 100) + '%' : '0';
        curEl.textContent = U.dur(t);
        durEl.textContent = U.dur(d);
    }

    function showOsd(stay) {
        osd.classList.remove('off');
        clearTimeout(osdTimer);
        if (!stay) osdTimer = setTimeout(function () { if (!video.paused) osd.classList.add('off'); }, 4000);
    }

    // ---------------------------------------------------------- история
    function save(done) {
        if (!it) return;
        var d = video.duration && isFinite(video.duration) ? video.duration * 1000 : 0;
        Server.saveHistory({
            s: it.sec, i: it.id, t: it.title, st: it.secTitle, v: it.video,
            p: done ? 0 : part, pc: it.parts.length, pos: done ? 0 : Math.floor((video.currentTime || 0) * 1000), dur: Math.floor(d), f: !!done
        });
    }
    saveTimer = setInterval(function () { if (started && !video.paused && !finished) save(false); }, 10000);

    // ---------------------------------------------------------- перемотка
    function seekBy(sec10) {
        var d = video.duration || 0;
        var now = Date.now();
        seekStreak = now - lastSeekKey < 450 ? seekStreak + 1 : 0;
        lastSeekKey = now;
        var step = 10 * (1 + Math.min(11, Math.floor(seekStreak / 4)));
        var base = seekTarget != null ? seekTarget : video.currentTime || 0;
        seekTarget = Math.max(0, Math.min(d ? d - 1 : base + step, base + (sec10 > 0 ? step : -step) * Math.abs(sec10)));
        seekEl.textContent = '→ ' + U.dur(seekTarget);
        updateBar();
        showOsd();
        clearTimeout(seekTimer);
        seekTimer = setTimeout(function () {
            try { video.currentTime = seekTarget; } catch (e) { /* ignore */ }
            seekTarget = null;
            seekEl.textContent = '';
        }, 600);
    }

    function toggle() {
        if (video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); } else video.pause();
        showOsd();
    }

    // ---------------------------------------------------------- «Что дальше?»
    function showNext() {
        clearInterval(cdTimer);
        osd.classList.add('off'); errEl.classList.add('hidden');
        next = Lib.nextAfter(it, nextRandom);
        if (!next) { Router.back(); return; }
        secondsLeft = 10;
        renderNext();
        cdTimer = setInterval(function () {
            secondsLeft -= 1;
            if (secondsLeft <= 0) { clearInterval(cdTimer); goNext(); } else {
                var s = nextEl.querySelector('.cd');
                if (s) s.textContent = 'Включится через ' + secondsLeft + ' с';
            }
        }, 1000);
    }

    function renderNext() {
        nextEl.innerHTML = '<div class="box"><h2>ЧТО ДАЛЬШЕ?</h2><div class="kind">' + (nextRandom ? 'Случайно из этого раздела:' : 'Следующий по порядку:') + '</div>' +
            '<div class="nt">' + esc(next.title) + '</div><div class="cd">Включится через ' + secondsLeft + ' с</div>' +
            '<div class="row1"><div class="btn red f autofocus" data-a="now">Сейчас</div><div class="btn soft f" data-a="stop">Стоп</div></div>' +
            '<div class="row2"><div class="btn f ' + (nextRandom ? 'off2' : 'teal') + '" data-a="order">По порядку</div><div class="btn f ' + (nextRandom ? 'teal' : 'off2') + '" data-a="random">Случайно</div></div></div>';
        nextEl.classList.remove('hidden');
        Array.prototype.forEach.call(nextEl.querySelectorAll('[data-a]'), function (b) {
            b.addEventListener('click', function () {
                var a = b.getAttribute('data-a');
                if (a === 'now') { clearInterval(cdTimer); goNext(); }
                else if (a === 'stop') { clearInterval(cdTimer); Router.back(); }
                else { nextRandom = a === 'random'; showNext(); Nav.focus(nextEl.querySelector('[data-a="' + a + '"]')); return; }
            });
        });
        Nav.focusFirst(nextEl);
    }

    function goNext() {
        var r = Server.hist(next.sec, next.id);
        var resume = Hist.canResume(r);
        Router.replace('player', { sec: next.sec, id: next.id, part: resume ? r.p : 0, start: resume ? r.pos : 0 });
    }

    // ---------------------------------------------------------- пульт
    function onKey(e) {
        var k = e.keyCode;
        var overlay = !nextEl.classList.contains('hidden') || !errEl.classList.contains('hidden');
        if (overlay) {
            if (k === KEY.BACK || k === KEY.ESC) { Router.back(); return true; }
            return false; // кнопки оверлея — обычная навигация
        }
        switch (k) {
            case KEY.ENTER: case KEY.PLAYPAUSE: toggle(); return true;
            case KEY.PLAY: if (video.paused) toggle(); return true;
            case KEY.PAUSE: if (!video.paused) toggle(); return true;
            case KEY.LEFT: seekBy(-1); return true;
            case KEY.RIGHT: seekBy(1); return true;
            case KEY.RW: seekBy(-3); return true;
            case KEY.FF: seekBy(3); return true;
            case KEY.UP: case KEY.DOWN: showOsd(true); return true;
            case KEY.CH_UP: if (part < it.parts.length - 1) { save(false); start(part + 1, 0); } return true;
            case KEY.CH_DOWN: if (part > 0) { save(false); start(part - 1, 0); } return true;
            case KEY.STOP: Router.back(); return true;
        }
        return false;
    }

    return {
        el: el,
        onKey: onKey,
        onBack: function () { return false; },
        destroy: function () {
            alive = false;
            if (started && !finished) save(false);
            clearInterval(saveTimer); clearInterval(cdTimer); clearTimeout(osdTimer); clearTimeout(seekTimer);
            try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) { /* ignore */ }
        }
    };
};
