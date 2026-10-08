#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ -z "${JAVA_HOME:-}" && -x "$repo_root/../.jdk21/bin/javac" ]]; then
    export JAVA_HOME="$repo_root/../.jdk21"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

gradle_jvm_options="${GRADLE_OPTS:-}"
proxy_url="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [[ -n "$proxy_url" ]]; then
    proxy_authority="${proxy_url#*://}"
    proxy_authority="${proxy_authority##*@}"
    proxy_host="${proxy_authority%%:*}"
    proxy_port="${proxy_authority##*:}"
    gradle_jvm_options="${gradle_jvm_options:+$gradle_jvm_options }-Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port"
fi
if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    gradle_jvm_options="${gradle_jvm_options:+$gradle_jvm_options }-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
export GRADLE_OPTS="$gradle_jvm_options"

ui_java_options="-Dsun.java2d.uiScale=1.0 -Dide.ui.scale=1.0 -Djb.consents.confirmation.enabled=false -Dide.no.platform.update=true -Dide.show.tips.on.startup.default=false -Didea.trust.all.projects=true"
if [[ -n "$proxy_url" ]]; then
    ui_java_options+=" -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port -Dhttp.nonProxyHosts=localhost|127.*"
fi
if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    ui_java_options+=" -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }$ui_java_options"

if command -v Xvfb >/dev/null 2>&1; then
    xvfb_display="${XVFB_DISPLAY:-:99}"
    display_number="${xvfb_display#:}"
    if [[ -e "/tmp/.X${display_number}-lock" || -S "/tmp/.X11-unix/X${display_number}" ]]; then
        echo "X display $xvfb_display is already in use; set XVFB_DISPLAY to a free display." >&2
        exit 2
    fi

    Xvfb "$xvfb_display" -screen 0 1920x1080x24 -dpi 96 -nolisten tcp -ac &
    xvfb_pid=$!
    cleanup() {
        kill "$xvfb_pid" 2>/dev/null || true
        wait "$xvfb_pid" 2>/dev/null || true
    }
    trap cleanup EXIT

    export DISPLAY="$xvfb_display"
    for attempt in {1..30}; do
        if xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
            break
        fi
        if ! kill -0 "$xvfb_pid" 2>/dev/null; then
            echo "Xvfb exited before the display became ready." >&2
            exit 1
        fi
        sleep 1
    done
    if ! xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
        echo "Timed out waiting for Xvfb display $DISPLAY." >&2
        exit 1
    fi
elif [[ -z "${DISPLAY:-}" ]]; then
    echo "Xvfb is required for a headless run. Install Xvfb or provide a graphical DISPLAY." >&2
    exit 1
else
    echo "Using existing display $DISPLAY; install Xvfb for the deterministic 1920x1080 run." >&2
fi

./gradlew uiScreenshotTest "$@"
