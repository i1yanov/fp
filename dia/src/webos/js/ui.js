/* Общие элементы интерфейса: значки, индикатор-плёнка, карточки, чипы, подгонка текста, ленивые превью. */
(function () {
    'use strict';
    var App = window.App;
    var h = App.h;

    // ---------- Значки (SVG, 24x24) ----------
    var P = {
        info: 'M11,7h2v2h-2zM11,11h2v6h-2zM12,2a10,10 0 1 0 0.01,0zM12,4a8,8 0 1 1 -0.01,0z',
        save: 'M11,3h2v9.2l3.3,-3.3 1.4,1.4L12,16 6.3,10.3l1.4,-1.4L11,12.2zM4,18h16v2H4z',
        del: 'M9,3h6l1,2h4v2H4V5h4zM6,8h12l-1,13H7z',
        dice: 'M6,3h12a3,3 0 0 1 3,3v12a3,3 0 0 1 -3,3H6a3,3 0 0 1 -3,-3V6a3,3 0 0 1 3,-3zM8.5,7a1.5,1.5 0 1 0 0.01,0zM15.5,7a1.5,1.5 0 1 0 0.01,0zM12,10.5a1.5,1.5 0 1 0 0.01,0zM8.5,14a1.5,1.5 0 1 0 0.01,0zM15.5,14a1.5,1.5 0 1 0 0.01,0z',
        restart: 'M12,5V2L7,6l5,4V7a5,5 0 1 1 -5,5H5a7,7 0 1 0 7,-7z',
        exit: 'M20,11H7.8l5.6,-5.6L12,4l-8,8 8,8 1.4,-1.4L7.8,13H20z',
        close: 'M6.4,5L12,10.6 17.6,5 19,6.4 13.4,12 19,17.6 17.6,19 12,13.4 6.4,19 5,17.6 10.6,12 5,6.4z',
        search: 'M10,3a7,7 0 0 1 5.6,11.2l5.1,5.1 -1.4,1.4 -5.1,-5.1A7,7 0 1 1 10,3zM10,5a5,5 0 1 0 0.01,0z',
        folder: 'M3,5a2,2 0 0 1 2,-2h5l2,2h7a2,2 0 0 1 2,2v11a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2z',
        film: 'M4,3h16v18H4zM5.5,4.5v2h2v-2zM5.5,8.5v2h2v-2zM5.5,12.5v2h2v-2zM5.5,16.5v2h2v-2zM16.5,4.5v2h2v-2zM16.5,8.5v2h2v-2zM16.5,12.5v2h2v-2zM16.5,16.5v2h2v-2zM9,5v6h6V5zM9,13v6h6v-6z'
    };
    App.icon = function (name, cls) {
        var ns = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('class', 'ico ' + (cls || ''));
        var path = document.createElementNS(ns, 'path');
        path.setAttribute('d', P[name] || '');
        path.setAttribute('fill-rule', 'evenodd');
        svg.appendChild(path);
        return svg;
    };

    // ---------- Индикатор загрузки: прокручивающаяся плёнка ----------
    App.loader = function (caption, progressText, progress) {
        var bar = null;
        if (typeof progress === 'number') {
            bar = h('div.lbar', {}, [h('div.lbar-fill', { style: 'width:' + Math.round(progress * 100) + '%' })]);
        }
        return h('div.loader', {}, [
            h('div.film', {}, [
                h('div.film-move', {}, [h('div.holes.top'), h('div.frames'), h('div.holes.bottom')]),
                h('div.film-fade')
            ]),
            caption ? h('div.lcap', { text: caption }) : null,
            bar,
            progressText ? h('div.lsub', { text: progressText }) : null
        ]);
    };

    App.errorBox = function (message, onRetry) {
        var box = h('div.error', {}, [
            h('div.error-title', { text: 'Плёнку заело…' }),
            h('div.error-text', { text: message })
        ]);
        if (onRetry) {
            var b = App.focusable(h('div.btn.default', { text: 'Повторить' }), onRetry);
            box.appendChild(b);
        }
        return box;
    };

    // ---------- Подгонка текста: переносы только по словам, при нехватке места шрифт меньше ----------
    App.fitText = function (el, minPx) {
        var size = parseFloat(getComputedStyle(el).fontSize);
        var min = minPx || 16;
        for (var i = 0; i < 8; i++) {
            var overflow = el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
            if (!overflow || size <= min) break;
            size = Math.max(min, size * 0.9);
            el.style.fontSize = size + 'px';
        }
    };
    /** Подгонять порциями, чтобы не подвешивать ТВ на больших списках. */
    App.fitAll = function (els, minPx) {
        var list = Array.prototype.slice.call(els);
        function chunk() {
            var part = list.splice(0, 12);
            part.forEach(function (e) { App.fitText(e, minPx); });
            if (list.length) requestAnimationFrame(chunk);
        }
        requestAnimationFrame(chunk);
    };

    // ---------- Ленивые превью: грузим только видимые, не больше двух одновременно ----------
    var imgQueue = [];
    var imgActive = 0;
    function pumpImages() {
        while (imgActive < 2 && imgQueue.length) {
            var img = imgQueue.shift();
            if (!document.body.contains(img)) continue;
            imgActive++;
            img.onload = img.onerror = function () {
                imgActive--;
                this.classList.add('loaded');
                pumpImages();
            };
            img.src = img.getAttribute('data-src');
        }
    }
    var io = window.IntersectionObserver ? new IntersectionObserver(function (entries) {
        entries.forEach(function (e) {
            if (e.isIntersecting) { io.unobserve(e.target); imgQueue.push(e.target); }
        });
        pumpImages();
    }, { rootMargin: '300px' }) : null;

    App.lazyImg = function (src) {
        var img = h('img.thumb', { alt: '' });
        img.addEventListener('error', function () { img.style.visibility = 'hidden'; });
        if (!src) return img;
        if (src.indexOf('blob:') === 0) { img.src = src; img.classList.add('loaded'); return img; }
        img.setAttribute('data-src', src);
        if (io) io.observe(img); else { imgQueue.push(img); setTimeout(pumpImages, 0); }
        return img;
    };

    // ---------- Карточка диафильма ----------
    App.card = function (opts) {
        var thumbBox = h('div.thumb-box', {}, [h('div.perf-ph'), App.lazyImg(opts.thumb)]);
        if (opts.badge) thumbBox.appendChild(h('div.badge', { text: opts.badge }));
        var el = h('div.card', {}, [
            thumbBox,
            h('div.card-title', { text: opts.title }),
            opts.subtitle ? h('div.card-sub', { text: opts.subtitle }) : null
        ]);
        return App.focusable(el, opts.onOpen);
    };

    App.chip = function (label, selected, count, onClick, small) {
        var el = h('div.chip' + (selected ? '.sel' : '') + (small ? '.small' : ''), {}, [
            h('span', { text: label }),
            count !== undefined && count !== null ? h('span.chip-count', { text: String(count) }) : null
        ]);
        return App.focusable(el, onClick);
    };

    App.filmSubtitle = function (year, added) {
        if (year) return year + ' г.';
        var d = App.fmtDate(added);
        return d ? 'добавлен ' + d : null;
    };
})();
