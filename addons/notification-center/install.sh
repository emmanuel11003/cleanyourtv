#!/usr/bin/env bash
# Build Notification Center, install it on the TV, grant notification listener access, and verify.
#   TV_HOST=<tv ip> addons/notification-center/install.sh
# To uninstall:
#   TV_HOST=<tv ip> addons/notification-center/install.sh --uninstall
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.emmanuel11003.tvnotifications
SVC=$PKG/.Notifications
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

if [ "${1:-}" = "--uninstall" ]; then
    echo "Uninstalling Notification Center..."
    tv "cmd notification disallow_listener $SVC 2>/dev/null || true; pm uninstall $PKG; echo uninstalled"
    echo "Uninstalled Notification Center and revoked notification listener."
    exit 0
fi

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/tvnotifications.apk
OUT=$(tv 'pm install -r /data/local/tmp/tvnotifications.apk; rm -f /data/local/tmp/tvnotifications.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# Allow the NotificationListenerService so it can cache active notifications
tv "cmd notification allow_listener $SVC; echo notification listener granted"

# Verify that the listener is active
LISTENERS=$(tv "settings get secure enabled_notification_listeners")
case "$LISTENERS" in
    *$PKG*) echo "Verified: notification listener is enabled ($SVC)" ;;
    *) echo "WARNING: notification listener not yet active in secure settings" ;;
esac

echo "Notification Center installed. Open Quick Settings -> Notifications on your TV to view."
