# Validação da versão 0.6.4

Validação concluída em 23/10/2026. Pacote `br.com.myfinancehub`, código de versão 13, sobre a base `e0eb98bcf4cda22a0ee269b832194a2ef3787e37`.

## Comportamento entregue

- O painel consulta exclusivamente a API pessoal do Pluggy e apresenta saldos e movimentações por conta, identificada pelo par `itemId`/`accountId`. Contas bancárias e cartões não são somados em um saldo único.
- Movimentações locais anteriores, inclusive as inseridas para testes, ficam fora do painel. O banco local, relatórios, investimentos, posição B3, cotações e backups são preservados. Não houve exclusão de lançamentos nem mudança do esquema ou do backup v4.
- O painel mostra o horário e o período da consulta. Consultas recentes podem ser reutilizadas por 30 segundos entre telas; Atualizar painel solicita nova leitura. Falhas preservam a última consulta identificada. Sem configuração, o aplicativo informa a necessidade de conectar o Meu Pluggy e não inventa saldo zero.
- O cache permanece somente na memória e é invalidado pela alteração das credenciais. Respostas de configurações ou sessões antigas são recusadas. Consultar o Pluggy não grava lançamentos automaticamente; revisão e confirmação continuam obrigatórias.
- A captura por notificações foi encerrada: não há listener no manifesto e as rotinas de captura/enfileiramento estão inertes, inclusive com preferências antigas ativadas. O histórico anterior fica somente leitura em Ajustes.
- Login local, biometria e bloqueio ao apagar a tela ou após dez minutos em segundo plano foram preservados.

## Verificações

Comando: `./scripts/verify.sh --no-build-cache`.

- 442 testes unitários aprovados, sem falhas, erros ou testes ignorados.
- Lint de debug e release: `No issues found.`
- Compilações debug e release concluídas; APK release sem depuração, com alinhamento e assinatura verificados.
- Testes cobrem isolamento de contas com identificadores iguais em conexões diferentes, exclusão dos dados locais do painel, privacidade dos valores, falhas de consulta, cache, troca de configuração, bloqueio durante consultas, atualização ao entrar e aposentadoria da captura de notificações.

## APK

- Arquivo: [MyFinanceHub-0.6.4-release.apk](../artifacts/MyFinanceHub-0.6.4-release.apk)
- Tamanho: 200.412 bytes.
- SHA-256: `654d321ddd57312996ed372b598c802cff3bb0e7a9468c97573e11075b09ff5c`.
- Certificado SHA-256 preservado: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`.
- A assinatura preservada permite atualizar a instalação anterior sem desinstalar. É a chave de desenvolvimento existente; não representa publicação na Play Store.

Resumo verificável: [validation-0.6.4.json](../artifacts/validation-0.6.4.json).

## Limites da validação

As consultas automatizadas usam respostas fictícias e transporte controlado. Não foram utilizadas credenciais nem dados financeiros reais, e não houve teste em aparelho físico ou sensor biométrico nesta execução. Os dados reais aparecerão após configurar e consultar a conexão pessoal Pluggy no aplicativo. Nenhum servidor ou conta online foi implantado.
