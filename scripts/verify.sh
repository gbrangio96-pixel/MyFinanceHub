#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
if [[ ! -f "$ANDROID_USER_HOME/debug.keystore" ]]; then
    printf 'Chave anterior ausente; restaure-a antes de validar a atualização.\n' >&2
    exit 1
fi
# Unit tests share the app implementation. Check lint and packaging for both build types.
"$(dirname "$0")/gradle.sh" --no-daemon \
    testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease "$@"
exec "$(dirname "$0")/build-release.sh" --verify-only
