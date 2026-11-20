/* Навигация пультом: коды кнопок, пространственный переход фокуса, прокрутка к элементу. */
(function () {
    'use strict';
    var App = window.App;

    App.KEY = {
        LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13,
        BACK: 461, BACKSPACE: 8, ESC: 27,
        PG_UP: 33, PG_DOWN: 34,
        PLAY: 415, PAUSE: 19, PLAY_PAUSE: 10252, STOP: 413, FF: 417, RW: 412,
        RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406
    };

    App.isBack = function (code) { return code === App.KEY.BACK || code === App.KEY.ESC || code === App.KEY.BACKSPACE; };
    App.dirOf = function (code) {
        switch (code) {
            case App.KEY.LEFT: return 'left';
            case App.KEY.RIGHT: return 'right';
            case App.KEY.UP: return 'up';
            case App.KEY.DOWN: return 'down';
            default: return null;
        }
    };

    function visible(el) {
        if (!el.offsetParent && getComputedStyle(el).position !== 'fixed') return false;
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
    }

    var focus = App.focus = {
        root: null,
        current: null,

        setRoot: function (root) { focus.root = root; },

        set: function (el, noScroll) {
            if (!el) return;
            if (focus.current && focus.current !== el) focus.current.classList.remove('focused');
            focus.current = el;
            el.classList.add('focused');
            if (!noScroll) focus.ensureVisible(el);
            if (el.tagName === 'INPUT') el.focus();
            else if (document.activeElement && document.activeElement.tagName === 'INPUT') document.activeElement.blur();
        },

        first: function () {
            if (!focus.root) return;
            var el = App.$('.f.default', focus.root) || App.$('.f', focus.root);
            if (el) focus.set(el);
        },

        /** Проверяет, что текущий фокус ещё в DOM; иначе ставит на первый элемент. */
        validate: function () {
            if (!focus.current || !focus.root || !focus.root.contains(focus.current) || !visible(focus.current)) focus.first();
        },

        /** Пространственный переход: ближайший элемент в нужной стороне. */
        move: function (dir) {
            if (!focus.root) return false;
            focus.validate();
            var cur = focus.current;
            if (!cur) return false;
            var a = cur.getBoundingClientRect();
            var ax = a.left + a.width / 2, ay = a.top + a.height / 2;
            var best = null, bestCost = Infinity;
            App.$$('.f', focus.root).forEach(function (el) {
                if (el === cur || !visible(el)) return;
                var b = el.getBoundingClientRect();
                var bx = b.left + b.width / 2, by = b.top + b.height / 2;
                var along, across;
                switch (dir) {
                    case 'right': if (b.left < a.right - 6 && bx <= ax + 4) return; along = Math.max(0, b.left - a.right); across = gap(a.top, a.bottom, b.top, b.bottom); break;
                    case 'left': if (b.right > a.left + 6 && bx >= ax - 4) return; along = Math.max(0, a.left - b.right); across = gap(a.top, a.bottom, b.top, b.bottom); break;
                    case 'down': if (b.top < a.bottom - 6 && by <= ay + 4) return; along = Math.max(0, b.top - a.bottom); across = gap(a.left, a.right, b.left, b.right); break;
                    case 'up': if (b.bottom > a.top + 6 && by >= ay - 4) return; along = Math.max(0, a.top - b.bottom); across = gap(a.left, a.right, b.left, b.right); break;
                }
                // для вертикали учитываем ещё и близость центров по горизонтали (сетки карточек)
                var centerShift = (dir === 'up' || dir === 'down') ? Math.abs(bx - ax) * 0.3 : Math.abs(by - ay) * 0.3;
                var cost = along + across * 3 + centerShift;
                if (cost < bestCost) { bestCost = cost; best = el; }
            });
            if (best) { focus.set(best); return true; }
            return false;
        },

        /** Прокручивает все прокручиваемые родители так, чтобы элемент был виден с запасом. */
        ensureVisible: function (el) {
            var p = el.parentElement;
            while (p && p !== document.body) {
                var cs = getComputedStyle(p);
                var r = el.getBoundingClientRect();
                var pr = p.getBoundingClientRect();
                if (/(auto|scroll)/.test(cs.overflowY) && p.scrollHeight > p.clientHeight) {
                    var m = 60;
                    if (r.top < pr.top + m) p.scrollTop -= (pr.top + m - r.top);
                    else if (r.bottom > pr.bottom - m) p.scrollTop += (r.bottom - (pr.bottom - m));
                }
                if (/(auto|scroll)/.test(cs.overflowX) && p.scrollWidth > p.clientWidth) {
                    var mx = 120;
                    r = el.getBoundingClientRect();
                    if (r.left < pr.left + mx) p.scrollLeft -= (pr.left + mx - r.left);
                    else if (r.right > pr.right - mx) p.scrollLeft += (r.right - (pr.right - mx));
                }
                p = p.parentElement;
            }
        },

        activate: function () {
            var el = focus.current;
            if (el && el.onactivate) el.onactivate();
        }
    };

    function gap(a1, a2, b1, b2) {
        if (b2 < a1) return a1 - b2;
        if (b1 > a2) return b1 - a2;
        return 0;
    }

    /** Делает элемент фокусируемым: OK/клик вызывает action, наведение указкой Magic Remote — фокус. */
    App.focusable = function (el, action) {
        el.classList.add('f');
        el.onactivate = action;
        el.addEventListener('mouseover', function () { if (focus.root && focus.root.contains(el)) focus.set(el, true); });
        el.addEventListener('click', function (e) { e.preventDefault(); focus.set(el, true); if (action) action(); });
        return el;
    };
})();
