#!/usr/bin/env bash
# Diagnostico local: apenas consultas e atualizacao com adb install -r.
set -u

mfh_script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P) || exit 2
mfh_apk_name=MyFinanceHub-0.2.0-debug.apk
mfh_expected_sha=e1318bf0ea1c329b145239dfba6b7b9860a97fffbcd7de23c27e5afcf33cbdf0
mfh_log=$(mktemp "${TMPDIR:-/tmp}/MyFinanceHub-instalacao.XXXXXX" 2>/dev/null) || mfh_log=''

mfh_report() {
    printf '%s\n' "$1"
    if [[ -n "$mfh_log" ]]; then printf '%s\n' "$1" >> "$mfh_log"; fi
}

mfh_stop() {
    mfh_report "$1"
    if [[ -n "$mfh_log" ]]; then printf 'Relatorio local: %s\n' "$mfh_log"; fi
    exit 2
}

if [[ $# -gt 1 ]]; then mfh_stop 'Uso: bash diagnosticar-instalacao.sh [caminho-do-APK]'; fi

mfh_apk=${1:-}
if [[ -z "$mfh_apk" ]]; then
    for mfh_candidate in "$mfh_script_dir/$mfh_apk_name" "$mfh_script_dir/../artifacts/$mfh_apk_name" "$mfh_script_dir/../$mfh_apk_name"; do
        if [[ -f "$mfh_candidate" ]]; then mfh_apk=$mfh_candidate; break; fi
    done
fi
if [[ -z "$mfh_apk" || ! -f "$mfh_apk" || ! -r "$mfh_apk" ]]; then
    mfh_stop 'APK nao encontrado ou sem leitura. Extraia todo o ZIP ou informe o caminho do APK como primeiro argumento.'
fi
mfh_apk_dir=$(CDPATH= cd -- "$(dirname -- "$mfh_apk")" && pwd -P) || mfh_stop 'Nao foi possivel acessar a pasta do APK.'
mfh_apk="$mfh_apk_dir/$(basename -- "$mfh_apk")"
if command -v sha256sum >/dev/null 2>&1; then
    mfh_hash_output=$(sha256sum -- "$mfh_apk" 2>/dev/null) || mfh_stop 'Nao foi possivel verificar o SHA256 do APK.'
elif command -v shasum >/dev/null 2>&1; then
    mfh_hash_output=$(shasum -a 256 -- "$mfh_apk" 2>/dev/null) || mfh_stop 'Nao foi possivel verificar o SHA256 do APK.'
else
    mfh_stop 'SHA256 indisponivel. Instale sha256sum (Linux) ou use shasum (macOS) antes de continuar.'
fi
mfh_actual_sha=${mfh_hash_output%% *}
if [[ "$mfh_actual_sha" != "$mfh_expected_sha" ]]; then
    mfh_stop 'SHA256 diferente do APK oficial 0.2.0. Nada foi instalado. Baixe e extraia novamente o pacote enviado.'
fi
mfh_report 'APK MyFinanceHub 0.2.0: SHA256 conferido.'

mfh_adb=''
if command -v adb >/dev/null 2>&1; then
    mfh_adb=$(command -v adb)
else
    for mfh_candidate in "$mfh_script_dir/platform-tools/adb" "$mfh_script_dir/../platform-tools/adb"; do
        if [[ -x "$mfh_candidate" ]]; then mfh_adb=$mfh_candidate; break; fi
    done
fi
if [[ -z "$mfh_adb" ]]; then
    mfh_stop 'ADB nao encontrado. Baixe Android SDK Platform-Tools em https://developer.android.com/tools/releases/platform-tools e extraia a pasta platform-tools ao lado deste script, ou adicione adb ao PATH.'
fi

mfh_devices=$("$mfh_adb" devices 2>&1)
mfh_devices_code=$?
if [[ $mfh_devices_code -ne 0 ]]; then
    mfh_stop 'Nao foi possivel consultar o ADB. Confira a instalacao do Platform-Tools e a conexao USB. Nenhum APK foi instalado.'
fi
mfh_count=0
mfh_header=0
mfh_serial=''
mfh_state=''
while IFS= read -r mfh_line; do
    mfh_line=${mfh_line%$'\r'}
    if [[ "$mfh_line" == 'List of devices attached'* ]]; then mfh_header=1; continue; fi
    if [[ $mfh_header -ne 1 || -z "$mfh_line" ]]; then continue; fi
    read -r mfh_row_serial mfh_row_state mfh_unused <<< "$mfh_line"
    if [[ -z "${mfh_row_state:-}" ]]; then continue; fi
    mfh_count=$((mfh_count + 1))
    mfh_serial=$mfh_row_serial
    mfh_state=$mfh_row_state
done <<< "$mfh_devices"
if [[ $mfh_count -eq 0 ]]; then
    mfh_stop 'Nenhum dispositivo encontrado. Conecte somente o celular por cabo de dados, ative a depuracao USB e mantenha a tela desbloqueada. No Linux, confira tambem as permissoes USB/udev; no Windows, o driver USB.'
fi
if [[ $mfh_count -ne 1 ]]; then
    mfh_stop 'Mais de um dispositivo/emulador encontrado. Desconecte os demais e execute novamente com somente o celular conectado.'
fi
if [[ "$mfh_state" == unauthorized ]]; then
    mfh_stop 'Celular nao autorizado. Desbloqueie a tela e aceite no celular a solicitacao de depuracao USB deste computador; execute novamente.'
fi
if [[ "$mfh_state" != device ]]; then
    mfh_stop 'Celular indisponivel no ADB (offline, permissao USB ou outro modo). Reconecte o cabo de dados, mantenha o celular desbloqueado e confira a autorizacao/permissao USB; execute novamente.'
fi

mfh_release=$("$mfh_adb" -s "$mfh_serial" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n')
mfh_api=$("$mfh_adb" -s "$mfh_serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')
if [[ ! "$mfh_release" =~ ^[A-Za-z0-9._-]{1,40}$ ]]; then mfh_release=indisponivel; fi
if [[ ! "$mfh_api" =~ ^[0-9]{1,3}$ ]]; then mfh_api=indisponivel; fi
mfh_report "Android: $mfh_release; API: $mfh_api"
mfh_package=$("$mfh_adb" -s "$mfh_serial" shell dumpsys package br.com.myfinancehub 2>/dev/null)
mfh_version_code=$(printf '%s\n' "$mfh_package" | sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
mfh_version_name=$(printf '%s\n' "$mfh_package" | sed -n 's/^[[:space:]]*versionName=\([A-Za-z0-9._+-]*\)[[:space:]]*$/\1/p' | head -n 1)
if [[ -n "$mfh_version_code" ]]; then
    mfh_report "MyFinanceHub instalado: versao ${mfh_version_name:-indisponivel}; codigo $mfh_version_code"
else
    mfh_report 'MyFinanceHub instalado: versao indisponivel ou aplicativo ausente.'
fi
mfh_report 'Tentativa de atualizacao: adb install -r (sem desinstalar nem limpar dados).'
mfh_install_output=$("$mfh_adb" -s "$mfh_serial" install -r "$mfh_apk" 2>&1)
mfh_install_code=$?
mfh_install_output=${mfh_install_output//"$mfh_serial"/'[dispositivo]'}
mfh_report "$mfh_install_output"
mfh_report "Codigo de saida do ADB: $mfh_install_code"
if [[ $mfh_install_code -eq 0 ]]; then
    mfh_report 'ADB informou sucesso. Abra o MyFinanceHub no celular e confira seus dados.'
else
    mfh_report 'A instalacao falhou. Compartilhe o relatorio local ou o resultado acima para identificar a causa. Preserve a versao instalada.'
fi
if [[ -n "$mfh_log" ]]; then printf 'Relatorio local: %s\n' "$mfh_log"; fi
exit "$mfh_install_code"
