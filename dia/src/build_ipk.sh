#!/bin/bash
# build_ipk.sh <src-dir> <out-dir>
# src-dir содержит webos/ (приложение) и service/ (фоновая служба Node.js)
set -e
SRC=$1; OUT=$2
ID=app.diafilmy
SVC=app.diafilmy.service
VER=$(python3 -c "import json;print(json.load(open('$SRC/webos/appinfo.json'))['version'])")
W=$(mktemp -d)
mkdir -p "$W/d/usr/palm/applications/$ID" "$W/d/usr/palm/services/$SVC" "$W/d/usr/palm/packages/$ID" "$W/c"
cp -r "$SRC/webos"/. "$W/d/usr/palm/applications/$ID/"
cp -r "$SRC/service"/. "$W/d/usr/palm/services/$SVC/"
printf '{"app":"%s","id":"%s","loc_name":"Диафильмы","package_format_version":2,"services":["%s"],"vendor":"Диафильмы","version":"%s"}\n' $ID $ID $SVC $VER > "$W/d/usr/palm/packages/$ID/packageinfo.json"
SIZE=$(du -sk "$W/d" | cut -f1)
cat > "$W/c/control" <<C
Package: $ID
Version: $VER
Section: misc
Priority: optional
Architecture: all
Installed-Size: $SIZE
Maintainer: Diafilmy <noreply@i1yanov.github.io>
Description: Diafilmy - viewer for diafilmy.su filmstrips
webOS-Package-Format-Version: 2
webOS-Packager-Version: 1.0.0
C
echo "2.0" > "$W/debian-binary"
(cd "$W/d" && tar --owner=0 --group=0 -czf ../data.tar.gz .)
(cd "$W/c" && tar --owner=0 --group=0 -czf ../control.tar.gz .)
F="$OUT/${ID}_${VER}_all.ipk"; rm -f "$F"
python3 "$(dirname "$0")/mkar.py" "$F" "$W/debian-binary" "$W/control.tar.gz" "$W/data.tar.gz"
rm -rf "$W"; echo "$F"
