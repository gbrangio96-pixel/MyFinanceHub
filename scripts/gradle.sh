#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'Toolchain ausente. Execute: ./scripts/setup-android.sh\n' >&2
    exit 1
fi
cd "$MYFINANCEHUB_PROJECT_ROOT"
exec ./gradlew "$@"
