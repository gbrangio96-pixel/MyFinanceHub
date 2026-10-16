# Validação da versão 0.2.1 local

`./scripts/verify.sh` terminou com código zero: **BUILD SUCCESSFUL**. O Android Lint informou **No issues found.** nos dois flavors.

A opção `local` mantém o controle financeiro, a importação em destaque e a edição de categorias, sem registrar o serviço de leitura de notificações, consultar outros apps ou expor controles de captura. O módulo de notificações permanece separado no flavor experimental `notifications`.

| Suíte | Local: passaram | Local: ignorados | Notifications: passaram | Notifications: ignorados |
| --- | ---: | ---: | ---: | ---: |
| FinanceStoreTest | 23 | 0 | 23 | 0 |
| LocalBuildTest | 3 | 0 | 0 | 3 |
| MainActivityTest | 13 | 0 | 13 | 0 |
| MoneyTest | 4 | 0 | 4 | 0 |
| NotificationCaptureServiceTest | 0 | 7 | 7 | 0 |
| NotificationFlowTest | 0 | 11 | 11 | 0 |
| NotificationInboxTest | 12 | 0 | 12 | 0 |
| NotificationParserTest | 18 | 0 | 18 | 0 |
| StatementImporterTest | 36 | 0 | 36 | 0 |
| **Total** | **109** | **18** | **124** | **3** |

Não houve falhas nem erros. As 18 verificações de captura e revisão são exclusivas do flavor de notificações; as três verificações do APK local são exclusivas do flavor local. Parser, importação, persistência financeira e categorias continuam sendo testados nos dois.

Os novos testes locais verificam ausência do serviço e de permissões no manifesto, importação acessível, ausência dos controles de captura mesmo com preferências/sugestões antigas e preservação do backup financeiro e da fila anterior ao recriar a Activity. A restauração da antiga página de notificações mostra apenas o módulo futuro. Os testes usam Robolectric/API 28 e dados fictícios.

O manifesto do APK final foi inspecionado com aapt2: package `br.com.myfinancehub`, versionCode 4, versão 0.2.1, minSdk 26 e targetSdk 35, sem `uses-permission`, serviços ou consultas de apps. Assinatura APK v2 e alinhamento passaram. O certificado é o mesmo das versões 0.1.0, 0.1.1 e 0.2.0.

FinanceStore.java, Models.java, StatementImporter.java, Money.java e NotificationInbox.java permanecem idênticos ao ZIP entregue da 0.2.0. O banco financeiro e o backup continuam no formato v1. Não houve teste físico de atualização para 0.2.1; as evidências de compatibilidade são assinatura/pacote, incremento de versão, esquema preservado e testes de preservação com dados fictícios.

No S22 Ultra, a captura de tela mostrou bloqueio do Google Play Protect por acesso a dados sensíveis. O usuário informou que a 0.2.0 instalou ao pausar essa proteção e foi orientado a reativá-la. **A aceitação da 0.2.1 local pelo Play Protect ainda precisa ser verificada no aparelho.** Esta opção é uma atualização facultativa, sem captura de notificações; não há garantia de que o Play Protect a aceitará. Mantenha a versão atual se não optar por esta alternativa e preserve os dados em caso de novo bloqueio.

Se optar pela versão local, salve um backup na versão que abre e instale como atualização, sem desinstalar. Veja [o roteiro no celular](TESTES-NO-CELULAR.md). O projeto preserva o código experimental de notificações para validação separada; Open Finance, B3 e integrações diretas com bancos/carteiras continuam futuras.

APK: `artifacts/MyFinanceHub-0.2.1-local-debug.apk`. Checksum ao lado do APK. Resumo: `artifacts/validation-0.2.1.json`.
