/* Разбор HTML diafilmy.su (DataLife Engine). Перенос SiteParser.kt из Android-версии. */
(function () {
    'use strict';
    var App = window.App;

    var FILM_RE = /^https?:\/\/(?:www\.)?diafilmy\.su\/(\d+)-[^\/?#]+\.html$/;
    var PAGE_RE = /\/page\/(\d+)\/?/;
    var DATE_RE = /Дата:\s*(\d{1,2})\.(\d{1,2})\.(\d{4})/;
    var FRAMES_RE = /Кадров:\s*(\d+)/;
    var XF_RE = /\/xfsearch\/([^\/]+)\/([^\/]+)\/?/;
    var COUNT_RE = /\[(\d+)]/;
    var TITLE_PREFIX = /^\s*смотреть\s+(онлайн\s+)?диафильм(ы)?(\s+онлайн)?\s*[:\-–—]?\s*/i;
    var TITLE_SUFFIX = /\s*[\-–—|]\s*(смотреть|диафильм|онлайн).*$/i;
    var IMG_EXT = /\.(jpe?g|png|gif|webp)$/i;

    var XF_LABELS = {
        'year': 'Год выпуска', 'studio': 'Студия', 'author': 'Автор', 'artist': 'Художник',
        'graphic-designer': 'Художник-оформитель', 'editor': 'Редактор',
        'art-editor': 'Художественный редактор', 'photographer': 'Фотограф',
        'color': 'Цветность', 'doll': 'Вид диафильма'
    };

    function parse(html) { return new DOMParser().parseFromString(html, 'text/html'); }

    function abs(el, attr, base) {
        var v = el.getAttribute(attr);
        if (!v || v.indexOf('data:') === 0) return '';
        try { return new URL(v, base).href; } catch (e) { return ''; }
    }

    function txt(el) { return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }

    function filmId(url) {
        var m = FILM_RE.exec(url || '');
        return m ? parseInt(m[1], 10) : null;
    }

    function cleanTitle(raw) {
        var t = String(raw || '').replace(/ /g, ' ').trim();
        var stripped = t.replace(TITLE_PREFIX, '');
        if (stripped.trim()) t = stripped;
        t = t.replace(TITLE_SUFFIX, '').trim();
        if (t.length > 2 && '«"'.indexOf(t.charAt(0)) >= 0 && '»"'.indexOf(t.charAt(t.length - 1)) >= 0) {
            t = t.substring(1, t.length - 1).trim();
        }
        t = t.replace(/\s+/g, ' ');
        return t || String(raw || '').trim();
    }

    function normalize(s) {
        return String(s || '').toLowerCase().replace(/ё/g, 'е')
            .replace(/[^0-9a-zÀ-ɏЀ-ӿ]+/g, ' ').trim();
    }

    function imgSrc(img, base) {
        var attrs = ['data-src', 'data-original', 'data-lazy-src', 'src'];
        for (var i = 0; i < attrs.length; i++) {
            var u = abs(img, attrs[i], base);
            if (u) return u;
        }
        return '';
    }

    // ---------- Списки ----------

    function findCard(link, root, base) {
        var el = link;
        for (;;) {
            var p = el.parentElement;
            if (!p || p === root) break;
            var ids = {};
            var n = 0;
            var links = p.querySelectorAll('a[href]');
            for (var i = 0; i < links.length && n < 2; i++) {
                var id = filmId(abs(links[i], 'href', base));
                if (id !== null && !ids[id]) { ids[id] = true; n++; }
            }
            if (n > 1) break;
            el = p;
        }
        return el;
    }

    function buildFilm(id, links, card, base) {
        var h = card.querySelector('h1, h2, h3, h4');
        var title = txt(h);
        if (!title) {
            var best = '';
            links.forEach(function (a) {
                var t = txt(a);
                if (t && !/^\d+$/.test(t) && t.length > best.length) best = t;
            });
            title = best;
        }
        if (!title) {
            var im = card.querySelector('img[alt]');
            title = im ? im.getAttribute('alt').trim() : '';
        }
        if (!title) title = 'Диафильм №' + id;

        var thumb = null;
        var imgs = card.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
            var s = imgSrc(imgs[i], base);
            if (s.indexOf('/uploads/') >= 0 && s.indexOf('/fotos/') < 0) { thumb = s; break; }
        }
        var text = txt(card);
        var added = 0;
        var m = DATE_RE.exec(text);
        if (m) added = new Date(+m[3], +m[2] - 1, +m[1]).getTime();
        var cats = [];
        card.querySelectorAll('a[href]').forEach(function (a) {
            var c = App.categoryByUrl(abs(a, 'href', base));
            if (c && cats.indexOf(c.slug) < 0) cats.push(c.slug);
        });
        return { id: id, title: cleanTitle(title), url: abs(links[0], 'href', base), thumb: thumb, added: added, cats: cats, _text: text };
    }

    function totalPages(doc) {
        var max = 1;
        doc.querySelectorAll('a[href*="/page/"]').forEach(function (a) {
            var m = PAGE_RE.exec(a.getAttribute('href'));
            if (m && +m[1] > max) max = +m[1];
        });
        return max;
    }

    function parseList(html, base, strict) {
        var doc = parse(html);
        var root = doc.getElementById('dle-content') || doc.body;
        var order = [];
        var byId = {};
        root.querySelectorAll('a[href]').forEach(function (a) {
            var id = filmId(abs(a, 'href', base));
            if (id === null) return;
            if (!byId[id]) { byId[id] = []; order.push(id); }
            byId[id].push(a);
        });
        var all = order.map(function (id) {
            return buildFilm(id, byId[id], findCard(byId[id][0], root, base), base);
        });
        var strictCards = all.filter(function (f) { return f._text.indexOf('Дата') >= 0 || f._text.indexOf('Просмотр') >= 0; });
        var films = strict ? strictCards : (strictCards.length ? strictCards : all.filter(function (f) { return f.thumb; }));
        films.forEach(function (f) { delete f._text; });
        return { films: films, totalPages: totalPages(doc) };
    }

    // ---------- Страница диафильма ----------

    function parseDetail(html, url) {
        var doc = parse(html);
        var id = filmId(url) || 0;
        var title = cleanTitle(txt(doc.querySelector('h1')) || doc.title);
        var meta = [];
        var year = null;
        var seen = {};
        doc.querySelectorAll('a[href*="/xfsearch/"]').forEach(function (a) {
            var m = XF_RE.exec(a.getAttribute('href'));
            if (!m) return;
            var label = XF_LABELS[m[1]];
            if (!label) return;
            var value = txt(a);
            if (!value) { try { value = decodeURIComponent(m[2]); } catch (e) { value = m[2]; } }
            if (m[1] === 'year') { var y = parseInt(value.replace(/\D/g, '').slice(0, 4), 10); if (y) year = y; }
            if (!seen[m[1] + '|' + value]) { seen[m[1] + '|' + value] = true; meta.push({ label: label, value: value }); }
        });
        var content = doc.getElementById('dle-content') || doc.body;
        var links = content.querySelectorAll('a[href]');
        for (var i = 0; i < links.length; i++) {
            var c = App.categoryByUrl(abs(links[i], 'href', url));
            if (c) { meta.push({ label: 'Категория', value: c.title }); break; }
        }
        var fm = FRAMES_RE.exec(txt(doc.body));
        if (fm) meta.push({ label: 'Кадров', value: fm[1] });
        // Объединяем повторяющиеся поля
        var merged = [];
        var idx = {};
        meta.forEach(function (it) {
            if (idx[it.label] === undefined) { idx[it.label] = merged.length; merged.push({ label: it.label, value: it.value }); }
            else merged[idx[it.label]].value += ', ' + it.value;
        });
        return { id: id, title: title, url: url, frames: extractFrames(doc, url), meta: merged, year: year };
    }

    function extractFrames(doc, base) {
        var out = [];
        var seen = {};
        var scope = doc.querySelector('.full-story, .fstory, .full-text, .full_story, .full .text, #dle-content') || doc.body;
        scope.querySelectorAll('img').forEach(function (img) {
            var src = imgSrc(img, base);
            if (src.indexOf('/uploads/') < 0 || src.indexOf('/fotos/') >= 0 || src.indexOf('/emoticons/') >= 0) return;
            var a = img.closest ? img.closest('a') : null;
            if (a) {
                var href = abs(a, 'href', base);
                if (filmId(href) !== null) return; // превью другого фильма («Смотрите также»)
                if (IMG_EXT.test(href)) src = href;
            }
            if (!seen[src]) { seen[src] = true; out.push(src); }
        });
        return out;
    }

    // ---------- Счётчики в меню ----------

    function parseCounts(html) {
        var doc = parse(html);
        var out = {};
        doc.querySelectorAll('a[href]').forEach(function (a) {
            var c = App.categoryByUrl(abs(a, 'href', App.BASE));
            if (!c || out[c.slug]) return;
            var text = txt(a) + ' ' + txt(a.nextElementSibling);
            var m = COUNT_RE.exec(text);
            if (m) out[c.slug] = +m[1];
        });
        return out;
    }

    // ---------- «База диафильмов» (base.php): название -> годы ----------

    function parseBase(html) {
        var doc = parse(html);
        var tables = doc.querySelectorAll('table');
        var table = null;
        var maxRows = 0;
        tables.forEach(function (t) { var n = t.querySelectorAll('tr').length; if (n > maxRows) { maxRows = n; table = t; } });
        if (!table) return {};
        var rows = table.querySelectorAll('tr');
        var titleIdx = -1, yearIdx = -1, start = 0;
        for (var i = 0; i < rows.length && i <= 5; i++) {
            var cells = Array.prototype.map.call(rows[i].querySelectorAll('th, td'), function (c) { return txt(c).toLowerCase(); });
            var t = -1, y = -1;
            cells.forEach(function (c, k) { if (t < 0 && c.indexOf('назван') === 0) t = k; if (y < 0 && c.indexOf('год') === 0) y = k; });
            if (t >= 0 && y >= 0) { titleIdx = t; yearIdx = y; start = i + 1; break; }
        }
        if (titleIdx < 0) { titleIdx = 1; yearIdx = 3; }
        var out = {};
        for (var r = start; r < rows.length; r++) {
            var tds = rows[r].querySelectorAll('td');
            if (tds.length <= Math.max(titleIdx, yearIdx)) continue;
            var title = normalize(cleanTitle(txt(tds[titleIdx])));
            var ym = /(19|20)\d{2}/.exec(txt(tds[yearIdx]));
            if (!title || !ym) continue;
            var yv = +ym[0];
            if (!out[title]) out[title] = [];
            if (out[title].indexOf(yv) < 0) out[title].push(yv);
        }
        return out;
    }

    App.parser = {
        filmId: filmId, cleanTitle: cleanTitle, normalize: normalize,
        parseList: parseList, parseDetail: parseDetail, parseCounts: parseCounts, parseBase: parseBase
    };
})();
