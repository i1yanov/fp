# fp
folderPlex 4 webos

Fast, lightweight Plex client for LG WebOs 4+ TVs with remote-friendly navigation: browse libraries by Plex metadata or real server folders, shuffle any folder or show, auto-play next with a countdown.

install through Homebrew.
1. add repo https://i1yanov.github.io/fp/a.json
2. install new app "FolderPlex"

---

# Архив Вещания (app.arhivveshchaniya)

Просмотр на LG webOS 4+ того, что Android-приложение «Архив Вещания» скачало на домашний сервер. Разделы: «Скачанное» и «История». С сайтом архива не соединяется ни приложение, ни помощник (у помощника связь с архивом выключена; включается только флагом `--allow-archive`). Работает в паре с домашним помощником на Linux-сервере с Samba. Подробная инструкция: https://i1yanov.github.io/fp/av/

## Порядок установки

1. **Помощник на сервер** (SSH на сервер, подставить свою папку «Архив Вещания» внутри SMB-шары и логин SMB):
   ```
   curl -fsSL https://i1yanov.github.io/fp/av/install.sh | sudo bash -s -- "/srv/media/Архив Вещания" ivan
   ```
   Путь шары: `testparm -s | grep -E '^\[|path ='`. Третий параметр — порт (по умолчанию 8765).
   Скрипт покажет адрес `http://IP:8765` — записать. Проверка: `http://IP:8765/api/status` в браузере → `"ok": true`.
2. **Приложение на телевизор:** Homebrew Channel → репозиторий `https://i1yanov.github.io/fp/a.json` (если ещё не добавлен) → «Архив Вещания» → Install.
3. **Связать:** в приложении «Настройки» → адрес помощника и имя телевизора → «Проверить» → «Сохранить».

## Обслуживание помощника

- статус: `systemctl status arhiv-veshchaniya`, журнал: `journalctl -u arhiv-veshchaniya -f`
- обновить: `sudo curl -fsSL https://i1yanov.github.io/fp/av/arhiv-server.py -o /opt/arhiv-veshchaniya/arhiv-server.py && sudo systemctl restart arhiv-veshchaniya`
- сменить папку/пользователя/порт: повторить команду из п. 1
- удалить: `sudo systemctl disable --now arhiv-veshchaniya; sudo rm /etc/systemd/system/arhiv-veshchaniya.service; sudo rm -r /opt/arhiv-veshchaniya`

## Файлы

- `av/` — помощник (`arhiv-server.py`, `install.sh`), страница инструкции, описание для Homebrew
- `av/src/webos/` — исходники приложения; `av/src/build_ipk.sh` — сборка ipk

---

# Архив Вещания для Windows

Программа для Windows 10/11 (64-бит), перенос Android-приложения «Архив Вещания»: каталог пяти разделов, онлайн-просмотр (одно соединение на раздел), скачивание в выбранную папку, домашний сервер (сетевая папка Windows), общая история и места остановки. Плеер VLC встроен.

- Установщик: https://github.com/i1yanov/fp/releases/latest/download/ArhivVeshchaniya.msi
- Инструкция: https://i1yanov.github.io/fp/windows/
- Исходники: `windows/` (Kotlin, Compose for Desktop, vlcj). Сборка — GitHub Actions (`.github/workflows/windows.yml`): проверка компиляции, самотест на виртуальном экране, сборка `.msi` с VLC 3.0.21 и публикация в Releases. Версия — `appVersion` в `windows/build.gradle.kts`.

---

# Диафильмы (app.diafilmy)

Просмотр диафильмов с сайта diafilmy.su на LG webOS 4+ — перенос Android-приложения «Диафильмы». Инструкция: https://i1yanov.github.io/fp/dia/

- Разделы сайта, сортировка по дате добавления и году выпуска, рубрикатор по страницам / буквам / годам, «Случайный», поиск.
- Просмотр: кадры протягиваются кнопками ▲ ▼, по OK — круговое меню (о диафильме, сохранить/удалить, случайный, сначала, выход).
- «Сохранённое» — кадры в IndexedDB телевизора для просмотра без интернета.
- Сайт не отдаёт CORS-заголовков, поэтому страницы читает фоновая служба `app.diafilmy.service` (Node.js): один запрос за раз, пауза 1,5 с, при 403/429/503 — пауза на час (или по Retry-After).

**Установка:** Homebrew Channel → репозиторий `https://i1yanov.github.io/fp/a.json` → «Диафильмы» → Install.

**Файлы:** `dia/src/webos/` — приложение, `dia/src/service/` — служба, `dia/src/build_ipk.sh` — сборка (`bash dia/src/build_ipk.sh dia/src .`), `dia/index.html` и `dia/d.html` — страницы описания. Версия — `dia/src/webos/appinfo.json` (сквозная нумерация с Android-версией: 1.5 — Android, 1.6.0 — первая версия для webOS, 1.7.0 — свежие корневые сертификаты в службе и выравнивание экрана).
