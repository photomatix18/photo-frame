#!/usr/bin/env bash
# Copies control.html and layout.js into Home Assistant's www folder (served at /local/), where
# the frame loads them from. No app rebuild needed: the frame picks up a new version when it
# next goes idle (if the page is over 30 min old), or at once on app start.
#
# Where to copy to comes from config.properties (deploy.host, deploy.key, deploy.dest).
# /local/ is served WITHOUT login, so these files must never contain a token or password.
set -euo pipefail
cd "$(dirname "$0")"
[ -f config.properties ] || { echo "config.properties missing - copy config.example.properties"; exit 1; }
[ -f layout.js ] || { echo "layout.js missing - copy layout.example.js and fill in your entities"; exit 1; }
prop() { grep -E "^$1=" config.properties | head -1 | cut -d= -f2-; }
HOST="$(prop deploy.host)"
KEY="$(prop deploy.key)"; KEY="${KEY/#\~/$HOME}"
DEST="$(prop deploy.dest)"
SSH=(ssh); SCP=(scp -q)
if [ -n "$KEY" ]; then SSH+=(-i "$KEY"); SCP+=(-i "$KEY"); fi
"${SSH[@]}" "$HOST" "mkdir -p '$DEST'"
"${SCP[@]}" control.html layout.js "$HOST:$DEST/"
echo "server:"; "${SSH[@]}" "$HOST" "cd '$DEST' && md5sum control.html layout.js"
echo "local:";  md5sum control.html layout.js
