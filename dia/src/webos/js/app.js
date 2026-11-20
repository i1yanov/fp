/* Запуск, стек экранов и разбор нажатий пульта. */
(function () {
    'use strict';
    var App = window.App;
    var stack = [];
    var host = null;
    var okArmedFor = null;   // экран/окно, на котором началось нажатие OK
    var backArmedFor = null;

    App.router = {
        top: function () { return stack[stack.length - 1] || null; },
        push: function (screen) {
            var prev = App.router.top();
            if (prev) {
                prev.lastFocus = App.focus.current;
                prev.el.style.display = 'none';
            }
            stack.push(screen);
            host.appendChild(screen.el);
            show(screen, true);
        },
        pop: function () {
            if (stack.length <= 1) return;
            var s = stack.pop();
            if (s.onDestroy) s.onDestroy();
            if (s.el.parentNode) s.el.parentNode.removeChild(s.el);
            var prev = App.router.top();
            prev.el.style.display = '';
            show(prev, false);
        }
    };

    function show(screen, fresh) {
        App.focus.setRoot(screen.el);
        App.focus.current = null;
        if (screen.onShow) screen.onShow();
        if (App.focus.root === screen.el) {
            if (!fresh && screen.lastFocus && document.body.contains(screen.lastFocus)) App.focus.set(screen.lastFocus);
            else App.focus.first();
        }
    }

    function target() { return App.modal ? 'modal' : App.router.top(); }

    function handle(e, type) {
        var code = e.keyCode;
        var inInput = document.activeElement && document.activeElement.tagName === 'INPUT';
        // В поле ввода стрелки влево/вправо и печать — для текста
        if (inInput && code !== App.KEY.UP && code !== App.KEY.DOWN && !App.isBack(code) && code !== App.KEY.OK) {
            if (code === App.KEY.BACKSPACE) return;
            return;
        }
        var t = target();
        if (type === 'down') {
            if (code === App.KEY.OK && !e.repeat) okArmedFor = t;
            if (App.isBack(code) && !e.repeat) backArmedFor = t;
        }
        // Отпускание засчитывается только тому окну, где кнопка была нажата
        if (type === 'up' && code === App.KEY.OK && okArmedFor !== t) { e.preventDefault(); return; }
        if (type === 'up' && App.isBack(code) && backArmedFor !== t) { e.preventDefault(); return; }

        if (App.modal) {
            if (App.isBack(code)) { if (type === 'up') App.modal.close(); e.preventDefault(); return; }
        } else {
            var screen = App.router.top();
            if (screen && screen.onKey && screen.onKey(code, type, e)) { e.preventDefault(); return; }
        }
        // Обычная навигация по фокусу
        var dir = App.dirOf(code);
        if (dir && type === 'down') { App.focus.move(dir); e.preventDefault(); return; }
        if (code === App.KEY.OK) {
            if (type === 'up' && !(inInput)) App.focus.activate();
            if (!inInput) e.preventDefault();
        }
    }

    function init() {
        host = document.getElementById('app');
        document.addEventListener('keydown', function (e) { handle(e, 'down'); });
        document.addEventListener('keyup', function (e) { handle(e, 'up'); });
        // На webOS кнопка «Назад» иначе может закрыть приложение
        window.addEventListener('popstate', function () { history.pushState({}, ''); });
        try { history.pushState({}, ''); } catch (e) { /* ignore */ }
        App.saved.reload();
        App.router.push(new App.HomeScreen());
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
