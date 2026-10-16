# Validação da versão 0.2.0

`./scripts/verify.sh` terminou com código zero: **BUILD SUCCESSFUL**. Android Lint: **No issues found.**

| Suíte | Passaram | Falhas | Ignorados |
| --- | ---: | ---: | ---: |
| FinanceStoreTest | 23 | 0 | 0 |
| MainActivityTest | 13 | 0 | 0 |
| MoneyTest | 4 | 0 | 0 |
| NotificationCaptureServiceTest | 7 | 0 | 0 |
| NotificationFlowTest | 11 | 0 | 0 |
| NotificationInboxTest | 12 | 0 | 0 |
| NotificationParserTest | 18 | 0 | 0 |
| StatementImporterTest | 36 | 0 | 0 |
| **Total** | **124** | **0** | **0** |

As verificações cobrem importação por URI, CSV/OFX, atalhos do Painel, edição/exclusão de categorias, criação durante um lançamento sem perder campos, cálculo financeiro, backup, captura desligada por padrão, seleção de apps, interpretação conservadora, retenção, revisão/cancelamento/confirmação e proteção contra duplicidade em outra conta. Métodos indefinidos exigem escolha antes de salvar. Os testes Android usam Robolectric/API 28 e notificações simuladas.

O APK tem package `br.com.myfinancehub`, versionCode 3, mínimo API 26 e target API 35. Sua assinatura v2 foi validada pelo apksigner e o certificado é o mesmo da versão 0.1.1. O banco financeiro continua com schema/backup v1, sem migração destrutiva. A fila opcional de sugestões usa um banco separado. Instale como atualização, sem desinstalar.

O manifesto empacotado contém o serviço protegido por `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`. Não há permissões de internet, SMS, contatos ou de acesso amplo a arquivos. A leitura depende do acesso especial concedido pelo usuário no Android, da escolha de apps e da ativação local.

Os formatos de XP, Itaú, Mercado Pago e Samsung Wallet usados nos testes são **exemplos fictícios**. Não há teste físico desta versão nem confirmação de compatibilidade com todos os textos reais. A leitura de notificações não constitui integração direta com bancos, PIX, B3 ou carteiras. Siga [o roteiro no celular](TESTES-NO-CELULAR.md).

Reentregas idênticas preservam identidade; alertas com novo horário ou vindos do banco e da carteira podem requerer descarte manual. A revisão informa possíveis correspondências com lançamentos existentes; nenhum valor é gravado automaticamente.

Resumo: `artifacts/validation-0.2.0.json`. APK: `artifacts/MyFinanceHub-0.2.0-debug.apk`. Checksum ao lado do APK.
