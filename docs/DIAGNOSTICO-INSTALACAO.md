# Diagnóstico de instalação no celular

O aviso “Aplicativo não instalado” não informa a causa. Este procedimento usa um computador para tentar atualizar o MyFinanceHub 0.2.0 por USB e obter o erro completo do Android. Ele ajuda no diagnóstico; não garante resolver a instalação.

**Mantenha a versão anterior instalada.** Os scripts usam somente `adb install -r`: não desinstalam, não limpam dados, não reiniciam o aparelho, não fazem downgrade e não alteram as proteções do Android. A tentativa instala a atualização se o Android a aceitar. Se puder abrir a versão anterior, exporte um backup antes da tentativa.

## Preparar o computador e o celular

1. Extraia todo o ZIP recebido para uma pasta do computador. Não execute o script de dentro do ZIP.
2. Baixe o [Android SDK Platform-Tools oficial](https://developer.android.com/tools/releases/platform-tools) para seu sistema e extraia a pasta `platform-tools` ao lado do script. Se o `adb` já estiver no `PATH`, essa pasta não é necessária.
3. No Samsung, abra **Configurações → Sobre o telefone → Informações do software** e toque sete vezes em **Número de compilação** para habilitar as opções de desenvolvedor. Depois habilite **Depuração USB** nas opções de desenvolvedor.
4. Conecte apenas esse celular com um cabo USB que transfira dados. Mantenha a tela desbloqueada e aceite a autorização de depuração USB deste computador no próprio celular. Se o Windows não reconhecer o aparelho, confira o [driver USB oficial da Samsung](https://developer.samsung.com/android-usb-driver).

Não é necessário desativar Play Protect, Bloqueador automático, políticas do aparelho ou outras proteções para coletar o diagnóstico. Se alguma proteção impedir a operação, informe a mensagem exibida.

## Windows — PowerShell

Abra o PowerShell na pasta extraída e execute:

```powershell
.\diagnosticar-instalacao.ps1
```

Se estiver usando o projeto completo, na raiz do projeto execute:

```powershell
.\scripts\diagnosticar-instalacao.ps1
```

Para indicar outro caminho de arquivo, incluindo pastas com espaços:

```powershell
.\scripts\diagnosticar-instalacao.ps1 -ApkPath "C:\Minha pasta\MyFinanceHub-0.2.0-debug.apk"
```

Se a política do PowerShell bloquear scripts, use os comandos diretos abaixo na pasta que contém o APK e a pasta `platform-tools`. Eles conferem o mesmo SHA256 antes da tentativa. Mantenha somente um celular conectado; não é necessário alterar a política de execução.

```powershell
$apk = ".\MyFinanceHub-0.2.0-debug.apk"
$esperado = "e1318bf0ea1c329b145239dfba6b7b9860a97fffbcd7de23c27e5afcf33cbdf0"
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ieq $esperado) {
    .\platform-tools\adb.exe install -r $apk
    "Codigo de saida do ADB: $LASTEXITCODE"
} else {
    throw "SHA256 diferente; baixe novamente o APK enviado."
}
```

Essa alternativa direta mostra o resultado da instalação no terminal; ela não cria o relatório filtrado do script. Copie o resultado da tentativa. O script PowerShell passou por revisão estática; sua execução no Windows ainda precisa ser conferida.

## Linux ou macOS — Terminal

Na pasta extraída, execute:

```bash
bash ./diagnosticar-instalacao.sh
```

Se estiver usando o projeto completo, na raiz do projeto execute:

```bash
bash ./scripts/diagnosticar-instalacao.sh
```

Para indicar outro caminho de arquivo:

```bash
bash ./scripts/diagnosticar-instalacao.sh "/minha pasta/MyFinanceHub-0.2.0-debug.apk"
```

No Linux, se não houver permissão para o dispositivo USB, confira a configuração de [acesso USB/udev para desenvolvimento Android](https://developer.android.com/studio/run/device#setting-up) e reconecte o aparelho. Os scripts não usam `sudo` nem alteram essas permissões.

## O que enviar após a tentativa

O script informa a versão/API do Android, a versão instalada do MyFinanceHub e o resultado de `adb install -r`, com seu código de saída. Se o ADB mencionar o identificador do dispositivo no erro, o script o substitui por `[dispositivo]`; o restante da mensagem é preservado. O caminho de um relatório temporário aparece ao final. Envie esse relatório ou copie essa saída. Ele não inclui o número de série do aparelho, extratos, transações, notificações, logcat ou o conteúdo completo de `dumpsys`.

Se aparecer `Success` com código `0`, abra o app no celular e confira seus dados. Se aparecer `Failure [INSTALL_...]`, preserve a mensagem inteira: ela permite distinguir conflito de atualização, versão incompatível, falta de espaço, restrição do aparelho e outros problemas. Não desinstale o app para contornar o erro.

Se o script parar antes da instalação por falta de ADB, nenhum aparelho, autorização pendente ou mais de um aparelho, siga a orientação exibida e execute novamente. Nenhuma instalação é feita nesses casos. Um SHA256 diferente também interrompe o procedimento: baixe novamente o arquivo enviado.

Depois do diagnóstico, você pode desativar a depuração USB nas opções de desenvolvedor. Se não tiver acesso a um computador, informe isso e envie o texto completo da mensagem de instalação exibida no celular; este procedimento exige um computador e não foi executado no seu aparelho.

Validação dos scripts: a sintaxe Bash passou e sete cenários com ADB simulado conferiram os bloqueios por ausência, autorização pendente, aparelho offline, múltiplos aparelhos e SHA256 incorreto, além de sucesso e erro de instalação. A saída e os relatórios ocultaram o número de série. Esses testes não acessaram um celular; o PowerShell foi revisado estaticamente, sem execução no Windows.
