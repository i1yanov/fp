#!/bin/bash
# Архив Вещания — установка домашнего помощника на Linux-сервер (Debian, Ubuntu, Raspberry Pi OS и т.п.)
#
#   curl -fsSL https://i1yanov.github.io/fp/av/install.sh | sudo bash -s -- "/путь/к/папке/Архив Вещания" пользователь [порт]
#
#   1-й параметр — папка «Архив Вещания» на диске сервера, та же, что видна по SMB
#                  (её создаёт Android-приложение; если её ещё нет — будет создана);
#   2-й параметр — пользователь Linux, от имени которого Samba пишет файлы
#                  (тот же логин, что в настройках Android-приложения);
#   3-й параметр — порт, по умолчанию 8765.
set -e

ROOT="$1"
RUN_USER="$2"
PORT="${3:-8765}"
BASE_URL="https://i1yanov.github.io/fp/av"
DIR="/opt/arhiv-veshchaniya"
UNIT="/etc/systemd/system/arhiv-veshchaniya.service"

say() { printf '\n\033[1;31m▶\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "запустите через sudo (см. команду выше)"
[ -n "$ROOT" ] && [ -n "$RUN_USER" ] || die "укажите папку и пользователя:  sudo bash install.sh \"/srv/share/Архив Вещания\" ivan"
id "$RUN_USER" >/dev/null 2>&1 || die "пользователь «$RUN_USER» не найден в системе"
command -v python3 >/dev/null || die "нужен python3:  sudo apt install python3"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)' || die "нужен Python 3.7 или новее"
command -v systemctl >/dev/null || die "не найден systemd"

say "Скачиваю помощник в $DIR"
mkdir -p "$DIR"
if command -v curl >/dev/null; then
  curl -fsSL "$BASE_URL/arhiv-server.py" -o "$DIR/arhiv-server.py"
else
  wget -qO "$DIR/arhiv-server.py" "$BASE_URL/arhiv-server.py"
fi
chmod 755 "$DIR/arhiv-server.py"

if [ ! -d "$ROOT" ]; then
  say "Создаю папку «$ROOT»"
  mkdir -p "$ROOT"
  chown "$RUN_USER": "$ROOT"
fi

say "Создаю службу arhiv-veshchaniya (пользователь $RUN_USER, порт $PORT)"
cat > "$UNIT" <<EOF
[Unit]
Description=Архив Вещания — домашний помощник для телевизоров LG
After=network-online.target
Wants=network-online.target

[Service]
User=$RUN_USER
UMask=0002
ExecStart=/usr/bin/env python3 $DIR/arhiv-server.py --root "$ROOT" --port $PORT
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable arhiv-veshchaniya >/dev/null 2>&1
systemctl restart arhiv-veshchaniya

if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  say "Открываю порт $PORT в брандмауэре ufw"
  ufw allow "$PORT"/tcp >/dev/null
fi

sleep 2
if systemctl is-active --quiet arhiv-veshchaniya; then
  say "Готово! Помощник работает."
  echo
  echo "  Введите на телевизоре в «Архив Вещания» → Настройки адрес:"
  for ip in $(hostname -I 2>/dev/null); do
    case "$ip" in *:*) ;; *) echo "      http://$ip:$PORT" ;; esac
  done
  echo
  echo "  Проверка в браузере на компьютере: тот же адрес + /api/status"
  echo "  Журнал работы:   journalctl -u arhiv-veshchaniya -f"
else
  die "служба не запустилась. Посмотрите журнал:  journalctl -u arhiv-veshchaniya -n 50"
fi
