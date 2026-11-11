/*
 * Навигация пультом LG: стрелки двигают выделение к ближайшему элементу в нужную сторону,
 * OK — нажатие, «Назад» — предыдущий экран. Фокусируемые элементы помечены классом "f".
 */
var KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 461, ESC: 27, BACKSPACE: 8,
    PLAY: 415, PAUSE: 19, PLAYPAUSE: 179, STOP: 413, FF: 417, RW: 412,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, CH_UP: 33, CH_DOWN: 34
};

var Screens = {};

var Router = (function () {
    'use strict';
    var root, stack = [];
    function top() { return stack[stack.length - 1]; }

    function show(scr, focusEl) {
        scr.el.style.display = '';
        if (scr.onShow) scr.onShow();
        if (focusEl && scr.el.contains(focusEl) && focusEl.offsetParent !== null) Nav.focus(focusEl);
        else if (!scr.el.contains(document.activeElement) || !document.activeElement.classList.contains('f')) Nav.focusFirst(scr.el);
    }

    function drop(s) {
        if (s.destroy) s.destroy();
        if (s.el.parentNode) s.el.parentNode.removeChild(s.el);
    }

    return {
        init: function (el) { root = el; },
        top: top,
        push: function (name, params) {
            var cur = top();
            if (cur) {
                cur.lastFocus = Nav.current();
                cur.el.style.display = 'none';
                if (cur.onHide) cur.onHide();
            }
            var scr = Screens[name](params || {});
            scr.name = name;
            stack.push(scr);
            root.appendChild(scr.el);
            show(scr);
        },
        /** Заменить верхний экран (например, следующий фильм в том же плеере). */
        replace: function (name, params) {
            var cur = stack.pop();
            if (cur) drop(cur);
            var scr = Screens[name](params || {});
            scr.name = name;
            stack.push(scr);
            root.appendChild(scr.el);
            show(scr);
        },
        home: function () {
            while (stack.length > 1) drop(stack.pop());
            show(top());
        },
        back: function () {
            var cur = top();
            if (!cur) return;
            if (cur.onBack && cur.onBack()) return;
            if (stack.length <= 1) { App.exitPrompt(); return; }
            drop(stack.pop());
            var prev = top();
            show(prev, prev.lastFocus);
        }
    };
})();

var Nav = (function () {
    'use strict';
    var cur = null, saved = null;

    function scope() {
        var m = document.querySelectorAll('.modal');
        if (m.length) return m[m.length - 1];
        var t = Router.top();
        return t ? t.el : document.body;
    }

    function visible(el) { return el.offsetParent !== null; }

    function focus(el) {
        if (!el) return;
        if (cur && cur !== el) cur.classList.remove('focus');
        cur = el;
        el.classList.add('focus');
        if (el.tagName === 'INPUT') el.focus(); else if (document.activeElement && document.activeElement.tagName === 'INPUT') document.activeElement.blur();
        if (el.scrollIntoViewIfNeeded) el.scrollIntoViewIfNeeded(false); else el.scrollIntoView(false);
    }

    function focusFirst(container) {
        var pref = container.querySelector('.f.autofocus');
        if (pref && visible(pref)) return focus(pref);
        var all = container.querySelectorAll('.f');
        for (var i = 0; i < all.length; i++) if (visible(all[i])) return focus(all[i]);
    }

    /** Ближайший элемент в направлении (dx, dy). */
    function move(dx, dy) {
        var sc = scope();
        if (!cur || !sc.contains(cur) || !visible(cur)) return focusFirst(sc);
        var r0 = cur.getBoundingClientRect();
        var cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
        var best = null, bestScore = Infinity;
        var all = sc.querySelectorAll('.f');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (el === cur || !visible(el)) continue;
            var r = el.getBoundingClientRect();
            var x = r.left + r.width / 2, y = r.top + r.height / 2;
            var primary, secondary, overlap;
            if (dx) {
                primary = (x - cx) * dx;
                if (primary <= 4 || (dx > 0 ? r.left < r0.right - 8 : r.right > r0.left + 8)) continue;
                overlap = Math.min(r.bottom, r0.bottom) - Math.max(r.top, r0.top) > 0;
                secondary = Math.abs(y - cy);
            } else {
                primary = (y - cy) * dy;
                if (primary <= 4 || (dy > 0 ? r.top < r0.bottom - 8 : r.bottom > r0.top + 8)) continue;
                overlap = Math.min(r.right, r0.right) - Math.max(r.left, r0.left) > 0;
                secondary = Math.abs(x - cx);
            }
            var score = primary + secondary * (overlap ? 0.3 : 2.5);
            if (score < bestScore) { bestScore = score; best = el; }
        }
        if (best) focus(best);
    }

    function onKey(e) {
        var k = e.keyCode;
        // окно поверх экрана
        var modals = document.querySelectorAll('.modal');
        var top = modals.length ? modals[modals.length - 1] : null;
        if (top && (k === KEY.BACK || k === KEY.ESC)) { e.preventDefault(); if (top.onBack) top.onBack(); return; }
        var scr = Router.top();
        if (!top && scr && scr.onKey && scr.onKey(e)) { e.preventDefault(); return; }
        var inInput = cur && cur.tagName === 'INPUT';
        switch (k) {
            case KEY.LEFT: if (inInput) return; move(-1, 0); break;
            case KEY.RIGHT: if (inInput) return; move(1, 0); break;
            case KEY.UP: move(0, -1); break;
            case KEY.DOWN: move(0, 1); break;
            case KEY.ENTER:
                if (inInput) return;
                if (cur) cur.click();
                break;
            case KEY.BACK: case KEY.ESC:
                Router.back();
                break;
            case KEY.BACKSPACE:
                if (inInput) return;
                Router.back();
                break;
            default: return;
        }
        e.preventDefault();
    }

    return {
        init: function () {
            document.addEventListener('keydown', onKey, true);
            // указка «волшебного» пульта LG: наведение = выделение
            document.addEventListener('mouseover', function (e) {
                var el = e.target.closest ? e.target.closest('.f') : null;
                if (el && scope().contains(el)) focus(el);
            });
        },
        focus: focus,
        focusFirst: focusFirst,
        current: function () { return cur; },
        remember: function () { saved = cur; },
        restore: function () { if (saved && saved.offsetParent !== null) focus(saved); else focusFirst(scope()); saved = null; }
    };
})();
