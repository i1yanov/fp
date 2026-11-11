/* Запуск приложения. */
var App = (function () {
    'use strict';
    var exitArmed = 0;
    return {
        start: function () {
            Router.init(document.getElementById('app'));
            Nav.init();
            Server.init();
            Router.push('home');
        },
        /** «Назад» на главной: первое нажатие — подсказка, второе в течение 2 с — выход. */
        exitPrompt: function () {
            var now = Date.now();
            if (now - exitArmed < 2000) {
                try { if (window.webOS && webOS.platformBack) { webOS.platformBack(); return; } } catch (e) { /* ignore */ }
                window.close();
                return;
            }
            exitArmed = now;
            U.toast('Нажмите «Назад» ещё раз, чтобы выйти', 2000);
        }
    };
})();

App.start();
