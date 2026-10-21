#!/usr/bin/env bash
set -euo pipefail
# Validate and build the single Android app; market-data access is optional.
exec "$(dirname "$0")/gradle.sh" --no-daemon \
    testDebugUnitTest lintDebug assembleDebug "$@"
