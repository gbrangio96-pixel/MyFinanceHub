# Ambiente de desenvolvimento na nuvem

Use o checkout existente em `/workspace/MyFinanceHub`; cada tarefa já tem isolamento. Não crie outro worktree sem pedido explícito. Leia as instruções do repositório e preserve alterações existentes.

## Android

Linux x86_64, Python 3, curl, tar, unzip e utilitários de checksum são necessários para o instalador. Execute na raiz:

```sh
./scripts/setup-android.sh
./scripts/verify.sh
```

O instalador confere os downloads e prepara JDK 21, Gradle 8.11.1, SDK 35 e caches em `/workspace/.myfinancehub-tools`, fora do Git. Pode ser repetido. `verify.sh` executa `testDebugUnitTest`, `lintDebug` e `assembleDebug`. A 0.5.0 tem uma única edição; os flavors de notificações foram removidos. Consulte `app/build/reports/` e `app/build/test-results/`.

APK gerado: `app/build/outputs/apk/debug/app-debug.apk`. A cópia de distribuição é `artifacts/MyFinanceHub-0.5.0-debug.apk`, acompanhada do SHA-256.

Preserve a chave de desenvolvimento em `/workspace/.myfinancehub-tools/android-user/debug.keystore` para atualizar instalações anteriores do pacote `br.com.myfinancehub`. Nunca a publique no Git ou em ZIPs. Salve backup financeiro antes de atualizar e instale por cima; não desinstale uma instalação com dados para resolver falhas.

O banco/backup da 0.5.0 é v3, com migração aditiva das versões v1/v2. O app restaura backups v1, v2 e v3. Os dados de quantidade e da última cotação aplicada pertencem ao investimento; chave do provedor, preferências e cache de cotações não pertencem ao backup financeiro.

Os testes de cotações usam transporte substituível e fixtures fictícias, sem chamadas à API ou chaves. Cotações reais são opcionais: HTTP GET por HTTPS para `brapi.dev`, com chave privada fornecida pelo usuário somente no app. Build, testes, controle financeiro, importação e relatórios não dependem dessa chave. Não solicite segredo em conversa nem coloque chaves no APK, logs ou instruções salvas.

Não é necessário manter serviços ativos para testes ou build. Emulador e ADB não sobrevivem necessariamente à restauração do ambiente. Se disponíveis, siga [TESTES-NO-CELULAR.md](TESTES-NO-CELULAR.md); registre separadamente o que foi conferido no emulador, no aparelho e o que continua pendente. Não crie links públicos para localhost.

## Backend preparatório

Node.js 22 ou superior precisa estar disponível. Não há dependências npm para instalar nem servidor obrigatório para executar os testes:

```sh
node --version
cd backend
npm test
```

Os testes substituem a rede por respostas fictícias. O Android não está conectado a esse backend. A execução real do conector exige conta Meu Pluggy, consentimento bancário e quatro secrets na hospedagem HTTPS; não são requisitos para desenvolver ou validar o APK. Veja [OPEN-FINANCE.md](OPEN-FINANCE.md) e [backend/README.md](../backend/README.md).

## Persistência e publicação

Commit registra os arquivos no checkout; push publica o commit no GitHub. As instruções salvas no rascunho do ambiente são separadas: não executam comandos nem publicam um snapshot. A publicação de uma versão do ambiente é feita pela interface da plataforma, quando disponível. Nenhuma dessas operações implanta o backend ou ativa sincronização bancária.
