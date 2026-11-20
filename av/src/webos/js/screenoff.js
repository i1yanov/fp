/*
 * «Выключить экран» для аудио: звук идёт, экран гаснет.
 * Сначала — штатное выключение экрана webOS (как в меню телевизора «Выключить экран»): панель действительно гаснет,
 * любая кнопка пульта включает её обратно. Если телевизор не разрешает это приложению — просто чёрный экран.
 */
var ScreenOff = (function () {
    'use strict';
    var active = false, mode = null, overlay = null, sub = null, sawOff = false, bridges = [];
    var SVC = 'luna://com.webos.service.tvpower/power/';

    function luna(method, params, cb) {
        if (typeof PalmServiceBridge === 'undefined') { if (cb) cb(null); return null; }
        try {
            var b = new PalmServiceBridge();
            bridges.push(b); // держим ссылку, иначе ответ может не прийти
            if (bridges.length > 20) bridges.shift();
            b.onservicecallback = function (msg) {
                var r = null;
                try { r = JSON.parse(msg); } catch (e) { /* ignore */ }
                if (cb) cb(r);
            };
            b.call(SVC + method, JSON.stringify(params || {}));
            return b;
        } catch (e) { if (cb) cb(null); return null; }
    }

    function black() {
        mode = 'black';
        overlay = U.h('<div class="scroff"></div>');
        document.body.appendChild(overlay);
    }

    /** Экран включили кнопкой пульта сами (телевизор) — просто забываем, что он был выключен. */
    function watch() {
        sawOff = false;
        sub = luna('getPowerState', { subscribe: true }, function (r) {
            if (!r || !r.state || !active) return;
            if (r.state !== 'Active') sawOff = true;
            else if (sawOff) reset();
        });
    }

    function reset() {
        active = false; mode = null; sawOff = false;
        if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
        overlay = null;
        if (sub) { try { sub.cancel(); } catch (e) { /* ignore */ } sub = null; }
    }

    function on() {
        if (active) return;
        active = true;
        var done = false;
        var timer = setTimeout(function () { if (!done) { done = true; black(); } }, 1500);
        function ok() { done = true; clearTimeout(timer); mode = 'luna'; watch(); }
        luna('turnOffScreen', {}, function (r) {
            if (done) return;
            if (r && r.returnValue) { ok(); return; }
            // новые webOS просят указать режим
            luna('turnOffScreen', { standbyMode: 'active' }, function (r2) {
                if (done) return;
                if (r2 && r2.returnValue) ok();
                else { done = true; clearTimeout(timer); black(); }
            });
        });
    }

    function wake() {
        if (!active) return;
        if (mode === 'luna') {
            luna('turnOnScreen', {}, function (r) {
                if (!(r && r.returnValue)) luna('turnOnScreen', { standbyMode: 'active' });
            });
        }
        reset();
    }

    return { on: on, wake: wake, isActive: function () { return active; } };
})();
