# Diagnostico local: apenas consultas e atualizacao com adb install -r.
param([string]$ApkPath = '')

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$mfhApkName = 'MyFinanceHub-0.2.0-debug.apk'
$mfhExpectedSha = 'e1318bf0ea1c329b145239dfba6b7b9860a97fffbcd7de23c27e5afcf33cbdf0'
$mfhLog = Join-Path ([System.IO.Path]::GetTempPath()) ('MyFinanceHub-instalacao-' + [Guid]::NewGuid().ToString('N') + '.txt')

function Write-MfhReport([string]$Message) {
    Write-Host $Message
    try { Add-Content -LiteralPath $script:mfhLog -Value $Message -Encoding UTF8 }
    catch { Write-Host 'Nao foi possivel gravar o relatorio local; copie a saida deste terminal.' }
}

function Stop-Mfh([string]$Message) {
    Write-MfhReport $Message
    Write-Host ('Relatorio local: ' + $script:mfhLog)
    exit 2
}

function Invoke-MfhAdb([string[]]$AdbArguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $captured = @(& $script:mfhAdb @AdbArguments 2>&1 | ForEach-Object { $_.ToString() })
        $nativeExitCode = $LASTEXITCODE
    }
    catch {
        $captured = @('Nao foi possivel executar o ADB. Confira a instalacao do Platform-Tools.')
        $nativeExitCode = 127
    }
    finally { $ErrorActionPreference = $previousPreference }
    return [PSCustomObject]@{ Lines = $captured; ExitCode = $nativeExitCode }
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    foreach ($candidate in @(
        (Join-Path $PSScriptRoot $mfhApkName),
        (Join-Path $PSScriptRoot ('..\artifacts\' + $mfhApkName)),
        (Join-Path $PSScriptRoot ('..\' + $mfhApkName))
    )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $ApkPath = $candidate; break }
    }
}
if ([string]::IsNullOrWhiteSpace($ApkPath) -or !(Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    Stop-Mfh 'APK nao encontrado. Extraia todo o ZIP ou use -ApkPath "caminho-do-APK".'
}
try {
    $ApkPath = (Resolve-Path -LiteralPath $ApkPath).ProviderPath
    $actualSha = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
}
catch { Stop-Mfh 'Nao foi possivel ler o APK e verificar o SHA256. Nada foi instalado.' }
if ($actualSha -ine $mfhExpectedSha) {
    Stop-Mfh 'SHA256 diferente do APK oficial 0.2.0. Nada foi instalado. Baixe e extraia novamente o pacote enviado.'
}
Write-MfhReport 'APK MyFinanceHub 0.2.0: SHA256 conferido.'

$mfhAdb = $null
$adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $adbCommand) { $mfhAdb = $adbCommand.Source }
else {
    foreach ($candidate in @(
        (Join-Path $PSScriptRoot 'platform-tools\adb.exe'),
        (Join-Path $PSScriptRoot '..\platform-tools\adb.exe')
    )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $mfhAdb = $candidate; break }
    }
}
if ($null -eq $mfhAdb) {
    Stop-Mfh 'ADB nao encontrado. Baixe Android SDK Platform-Tools em https://developer.android.com/tools/releases/platform-tools e extraia a pasta platform-tools ao lado deste script, ou adicione adb ao PATH.'
}

$deviceResult = Invoke-MfhAdb -AdbArguments @('devices')
if ($deviceResult.ExitCode -ne 0) {
    Stop-Mfh 'Nao foi possivel consultar o ADB. Confira a instalacao do Platform-Tools e a conexao USB. Nenhum APK foi instalado.'
}
$deviceRows = @()
$headerSeen = $false
foreach ($line in $deviceResult.Lines) {
    if ($line.StartsWith('List of devices attached')) { $headerSeen = $true; continue }
    if (!$headerSeen -or [string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line -match '^\s*(\S+)\s+(\S+)') {
        $deviceRows += [PSCustomObject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
}
if ($deviceRows.Count -eq 0) {
    Stop-Mfh 'Nenhum dispositivo encontrado. Conecte somente o celular por cabo de dados, ative a depuracao USB, mantenha a tela desbloqueada e confira o driver USB do fabricante.'
}
if ($deviceRows.Count -ne 1) {
    Stop-Mfh 'Mais de um dispositivo/emulador encontrado. Desconecte os demais e execute novamente com somente o celular conectado.'
}
if ($deviceRows[0].State -eq 'unauthorized') {
    Stop-Mfh 'Celular nao autorizado. Desbloqueie a tela e aceite no celular a solicitacao de depuracao USB deste computador; execute novamente.'
}
if ($deviceRows[0].State -ne 'device') {
    Stop-Mfh 'Celular indisponivel no ADB (offline, permissao USB ou outro modo). Reconecte o cabo de dados, mantenha o celular desbloqueado e confira a autorizacao e o driver USB; execute novamente.'
}
$mfhSerial = $deviceRows[0].Serial
$releaseResult = Invoke-MfhAdb -AdbArguments @('-s', $mfhSerial, 'shell', 'getprop', 'ro.build.version.release')
$apiResult = Invoke-MfhAdb -AdbArguments @('-s', $mfhSerial, 'shell', 'getprop', 'ro.build.version.sdk')
$release = ($releaseResult.Lines -join '').Trim()
$api = ($apiResult.Lines -join '').Trim()
if ($releaseResult.ExitCode -ne 0 -or $release -notmatch '^[A-Za-z0-9._-]{1,40}$') { $release = 'indisponivel' }
if ($apiResult.ExitCode -ne 0 -or $api -notmatch '^[0-9]{1,3}$') { $api = 'indisponivel' }
Write-MfhReport ('Android: ' + $release + '; API: ' + $api)
$packageResult = Invoke-MfhAdb -AdbArguments @('-s', $mfhSerial, 'shell', 'dumpsys', 'package', 'br.com.myfinancehub')
$packageText = $packageResult.Lines -join "`n"
$versionCode = [regex]::Match($packageText, '(?m)^\s*versionCode=([0-9]+)').Groups[1].Value
$versionName = [regex]::Match($packageText, '(?m)^\s*versionName=([A-Za-z0-9._+-]+)\s*$').Groups[1].Value
if ($versionCode) {
    if (!$versionName) { $versionName = 'indisponivel' }
    Write-MfhReport ('MyFinanceHub instalado: versao ' + $versionName + '; codigo ' + $versionCode)
}
else { Write-MfhReport 'MyFinanceHub instalado: versao indisponivel ou aplicativo ausente.' }
Write-MfhReport 'Tentativa de atualizacao: adb install -r (sem desinstalar nem limpar dados).'
$installResult = Invoke-MfhAdb -AdbArguments @('-s', $mfhSerial, 'install', '-r', $ApkPath)
Write-MfhReport (($installResult.Lines -join "`n").Replace($mfhSerial, '[dispositivo]'))
Write-MfhReport ('Codigo de saida do ADB: ' + $installResult.ExitCode)
if ($installResult.ExitCode -eq 0) {
    Write-MfhReport 'ADB informou sucesso. Abra o MyFinanceHub no celular e confira seus dados.'
}
else {
    Write-MfhReport 'A instalacao falhou. Compartilhe o relatorio local ou o resultado acima para identificar a causa. Preserve a versao instalada.'
}
Write-Host ('Relatorio local: ' + $mfhLog)
exit $installResult.ExitCode
