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

- Установщик: https://github.com/i1yanov/fp/releases/download/windows-v1.0.0/ArhivVeshchaniya-1.0.0.msi
- Инструкция: https://i1yanov.github.io/fp/windows/
- Исходники: `windows/` (Kotlin, Compose for Desktop, vlcj). Сборка — GitHub Actions (`.github/workflows/windows.yml`): проверка компиляции, самотест на виртуальном экране, сборка `.msi` с VLC 3.0.21 и публикация в Releases. Версия — `appVersion` в `windows/build.gradle.kts`.
