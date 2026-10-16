#!/usr/bin/env bash
# Source this file to activate only this project's Android toolchain.
export MYFINANCEHUB_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export MYFINANCEHUB_TOOLS_DIR="${MYFINANCEHUB_TOOLS_DIR:-$(dirname "$MYFINANCEHUB_PROJECT_ROOT")/.myfinancehub-tools}"
export JAVA_HOME="$MYFINANCEHUB_TOOLS_DIR/jdk-21.0.9+10"
export ANDROID_HOME="$MYFINANCEHUB_TOOLS_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$MYFINANCEHUB_TOOLS_DIR/gradle-home"
export ANDROID_USER_HOME="$MYFINANCEHUB_TOOLS_DIR/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Java does not consume HTTPS_PROXY automatically. Read the route at runtime;
# no proxy credentials or environment-specific hostnames are saved in the repo.
if command -v python3 >/dev/null 2>&1; then
    MYFINANCEHUB_JAVA_NETWORK_OPTIONS="$(python3 - <<'PY'
import os
from urllib.parse import urlsplit
options = []
for protocol in ('http', 'https'):
    value = os.environ.get(protocol.upper() + '_PROXY') or os.environ.get(protocol + '_proxy')
    if value:
        proxy = urlsplit(value)
        if proxy.hostname:
            options += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
# Reuse the machine's trusted CA store, including managed egress certificates.
trust_store = '/etc/ssl/certs/java/cacerts'
if os.path.isfile(trust_store):
    options.append('-Djavax.net.ssl.trustStore=' + trust_store)
print(' '.join(options))
PY
)"
    export JAVA_OPTS="${JAVA_OPTS:-} $MYFINANCEHUB_JAVA_NETWORK_OPTIONS -Djava.util.prefs.userRoot=$MYFINANCEHUB_TOOLS_DIR/java-prefs"
fi
