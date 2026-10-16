#!/usr/bin/env bash
set -euo pipefail
# Validate both modes; produce both explicitly named APKs.
exec "$(dirname "$0")/gradle.sh" --no-daemon \
    testLocalDebugUnitTest testNotificationsDebugUnitTest \
    lintLocalDebug lintNotificationsDebug assembleLocalDebug assembleNotificationsDebug "$@"
