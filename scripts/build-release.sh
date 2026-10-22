#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"

if [[ "$#" -gt 1 || ( "$#" -eq 1 && "$1" != "--verify-only" ) ]]; then
    printf 'Uso: %s [--verify-only]\n' "$0" >&2
    exit 2
fi
# Never generate a replacement key: updates must retain the certificate of the installed APK.
MYFINANCEHUB_SIGNING_STORE="$ANDROID_USER_HOME/debug.keystore"
if [[ ! -f "$MYFINANCEHUB_SIGNING_STORE" ]]; then
    printf 'Chave anterior ausente em %s; restaure-a antes de gerar a atualização.\n' "$MYFINANCEHUB_SIGNING_STORE" >&2
    exit 1
fi
if [[ "$#" -eq 0 ]]; then
    "$(dirname "$0")/gradle.sh" --no-daemon assembleRelease
fi

MYFINANCEHUB_APK="$MYFINANCEHUB_PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
MYFINANCEHUB_PREVIOUS_APK="$MYFINANCEHUB_PROJECT_ROOT/artifacts/MyFinanceHub-0.5.1-debug.apk"
MYFINANCEHUB_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
if [[ ! -f "$MYFINANCEHUB_APK" || ! -f "$MYFINANCEHUB_PREVIOUS_APK" ]]; then
    printf 'APK de distribuição ou referência anterior ausente.\n' >&2
    exit 1
fi

"$MYFINANCEHUB_BUILD_TOOLS/zipalign" -c 4 "$MYFINANCEHUB_APK"
MYFINANCEHUB_SIGNER="$("$MYFINANCEHUB_BUILD_TOOLS/apksigner" verify --print-certs "$MYFINANCEHUB_APK")"
MYFINANCEHUB_PREVIOUS_SIGNER="$("$MYFINANCEHUB_BUILD_TOOLS/apksigner" verify --print-certs "$MYFINANCEHUB_PREVIOUS_APK")"
MYFINANCEHUB_CERT="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$MYFINANCEHUB_SIGNER")"
MYFINANCEHUB_PREVIOUS_CERT="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$MYFINANCEHUB_PREVIOUS_SIGNER")"
if [[ -z "$MYFINANCEHUB_CERT" || "$MYFINANCEHUB_CERT" != "$MYFINANCEHUB_PREVIOUS_CERT" ]]; then
    printf 'Certificado diferente da versão instalada; atualização interrompida.\n' >&2
    exit 1
fi
MYFINANCEHUB_DEBUGGABLE="$("$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest debuggable "$MYFINANCEHUB_APK")"
if [[ "$MYFINANCEHUB_DEBUGGABLE" != "false" ]]; then
    printf 'O APK de distribuição não pode permitir depuração.\n' >&2
    exit 1
fi
printf 'APK de distribuição validado: %s\n' "$MYFINANCEHUB_APK"
printf 'Depuração: desativada. Certificado preservado: %s\n' "$MYFINANCEHUB_CERT"
