#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    printf 'Este instalador prepara Linux x86_64. Em outro sistema, use Android Studio e o Gradle Wrapper.\n' >&2
    exit 1
fi
for command in curl python3 unzip tar sha256sum sha1sum; do
    command -v "$command" >/dev/null || { printf 'Comando necessário: %s\n' "$command" >&2; exit 1; }
done
mkdir -p "$MYFINANCEHUB_TOOLS_DIR/downloads" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

fetch_verified() {
    local url="$1" file="$2" algorithm="$3" expected="$4"
    if [[ -f "$file" ]] && printf '%s  %s\n' "$expected" "$file" | "${algorithm}sum" --check --status; then
        return
    fi
    curl --fail --silent --show-error --location --retry 2 "$url" --output "$file.partial"
    printf '%s  %s\n' "$expected" "$file.partial" | "${algorithm}sum" --check --status
    mv "$file.partial" "$file"
}

# SHA-256 published by Adoptium alongside this release:
# https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.9%2B10
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    fetch_verified 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz' \
        "$MYFINANCEHUB_TOOLS_DIR/downloads/jdk.tar.gz" sha256 810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f
    tar -xzf "$MYFINANCEHUB_TOOLS_DIR/downloads/jdk.tar.gz" -C "$MYFINANCEHUB_TOOLS_DIR"
fi

# SHA-256: https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256
if [[ ! -x "$MYFINANCEHUB_TOOLS_DIR/gradle-8.11.1/bin/gradle" ]]; then
    fetch_verified 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' \
        "$MYFINANCEHUB_TOOLS_DIR/downloads/gradle-8.11.1-bin.zip" sha256 f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
    unzip -q "$MYFINANCEHUB_TOOLS_DIR/downloads/gradle-8.11.1-bin.zip" -d "$MYFINANCEHUB_TOOLS_DIR"
fi

# Google's official repository metadata publishes this archive's SHA-1:
# https://dl.google.com/android/repository/repository2-1.xml
if [[ ! -f "$ANDROID_HOME/cmdline-tools/latest/source.properties" ]] || ! grep -q 'Pkg.Revision=19.0' "$ANDROID_HOME/cmdline-tools/latest/source.properties"; then
    fetch_verified 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' \
        "$MYFINANCEHUB_TOOLS_DIR/downloads/commandlinetools-linux-13114758_latest.zip" sha1 5fdcc763663eefb86a5b8879697aa6088b041e70
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$MYFINANCEHUB_TOOLS_DIR/downloads/commandlinetools-linux-13114758_latest.zip" -d "$ANDROID_HOME/cmdline-tools"
    if [[ -d "$ANDROID_HOME/cmdline-tools/latest" ]]; then
        mv "$ANDROID_HOME/cmdline-tools/latest" "$MYFINANCEHUB_TOOLS_DIR/cmdline-tools-before-19-$(date +%s)"
    fi
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

printf 'Aceitando as licenças padrão do Android SDK para compilar o aplicativo.\n'
sdkmanager --sdk_root="$ANDROID_HOME" --licenses < <(python3 -c 'print("y\n" * 100)')
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
python3 - <<'PY'
import os
from pathlib import Path
root = Path(os.environ['MYFINANCEHUB_PROJECT_ROOT'])
destination = root / 'local.properties'
sdk = os.environ['ANDROID_HOME'].replace('\\', '\\\\').replace(':', '\\:')
if destination.exists():
    lines = destination.read_text().splitlines()
    lines = [line for line in lines if not line.startswith('sdk.dir=')]
else:
    lines = ['# Local Android SDK path; this file is ignored by Git.']
lines.append('sdk.dir=' + sdk)
destination.write_text('\n'.join(lines) + '\n')
PY
printf 'Toolchain preparada. Build: ./scripts/gradle.sh assembleDebug\n'
