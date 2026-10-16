# Ambiente de desenvolvimento na nuvem

Use o checkout existente em `/workspace/MyFinanceHub`; cada tarefa já tem isolamento. Não crie outro worktree sem pedido explícito. Leia as instruções do repositório e preserve alterações existentes.

## Android

Linux x86_64, Python 3, curl, tar, unzip e utilitários de checksum são necessários para o instalador. Execute na raiz do repositório:

```sh
./scripts/setup-android.sh
./scripts/verify.sh
```

O instalador confere os downloads e prepara JDK, Gradle, SDK e caches em `/workspace/.myfinancehub-tools`, fora do Git. Pode ser repetido. `verify.sh` executa testes, lint e build dos dois flavors. Consulte resultados em `app/build/reports/` e `app/build/test-results/`.

APKs gerados:

- `app/build/outputs/apk/local/debug/app-local-debug.apk`: app local padrão.
- `app/build/outputs/apk/notifications/debug/app-notifications-debug.apk`: módulo experimental de notificações.

Preserve a chave de desenvolvimento em `/workspace/.myfinancehub-tools/android-user/debug.keystore` para atualizar instalações anteriores. Nunca a publique no Git ou em ZIPs. Ambos os flavors atualizam o mesmo pacote; não são instalados lado a lado. Exportar o backup JSON antes de atualizar é recomendado. Não desinstale uma instalação com dados para resolver uma falha de atualização.

Emuladores, ADB e outros processos não precisam estar ativos para testes e build e não sobrevivem necessariamente à restauração do ambiente. Com dispositivo ou emulador disponível, siga [TESTES-NO-CELULAR.md](TESTES-NO-CELULAR.md); sem ele, registre a validação no aparelho como pendente. Não crie links públicos para localhost.

## Backend preparatório

Node.js 22 ou superior precisa estar disponível. A validação desta entrega usa Node.js 24.19.0. Não há dependências npm para instalar nem servidor que precise permanecer ativo para executar os testes:

```sh
node --version
cd backend
npm test
```

Os testes substituem as chamadas de rede por respostas fictícias. O Android atual não está conectado a esse backend. Não são necessárias credenciais para desenvolver, testar ou montar o APK.

A execução real do conector requer conta pessoal Meu Pluggy, consentimento bancário e quatro secrets na hospedagem HTTPS. Não são requisitos do ambiente de testes. Veja [OPEN-FINANCE.md](OPEN-FINANCE.md) e [backend/README.md](../backend/README.md). Não coloque segredos no código, APK, logs ou conversa.

## Persistência e publicação

Um commit local registra os arquivos no checkout. O push publica esse commit no GitHub. As instruções salvas no rascunho do ambiente são uma configuração separada: não executam os comandos nem publicam um snapshot. A publicação de uma versão do ambiente é feita pela interface da plataforma, quando disponível. Nenhuma dessas operações implanta o Worker ou ativa a sincronização bancária.
