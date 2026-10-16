# Validação da versão 0.1.0

Executada em 15/10/2026, no ambiente Linux de desenvolvimento.

```sh
./scripts/verify.sh
```

O comando terminou com código zero: **BUILD SUCCESSFUL**. O Android Lint informou **No issues found.**

| Suíte | Testes | Falhas | Ignorados |
| --- | ---: | ---: | ---: |
| FinanceStoreTest — SQLite, integridade, backup, importação, saldos | 14 | 0 | 0 |
| MainActivityTest — fluxos reais de telas e formulários | 6 | 0 | 0 |
| MoneyTest — centavos, limites e formatação | 4 | 0 | 0 |
| StatementImporterTest — CSV, OFX, sinais, duplicatas e arquivos inválidos | 24 | 0 | 0 |
| **Total** | **48** | **0** | **0** |

Os testes de Android executam com Robolectric/API 28. Os testes monetários e do importador são Java puro. Os testes não substituem a validação no aparelho físico.

O APK `artifacts/MyFinanceHub-0.1.0-debug.apk` foi compilado para API 35, com mínimo API 26. Sua assinatura de desenvolvimento foi verificada por `apksigner verify --verbose` (APK Signature Scheme v2). O manifesto não declara permissões de internet, notificações, SMS ou acesso amplo a arquivos. O seletor de documentos fornece acesso somente aos arquivos escolhidos pelo usuário.

Instalação e abertura também confirmadas em um emulador Android 15/API 35. O painel inicial renderizou com saldo zero, navegação e botão de novo lançamento, sem exceção fatal do aplicativo observada. A [captura inicial](../artifacts/screenshots/painel-inicial.png) foi obtida desse APK, sem dados fictícios inseridos na base inicial do app. O emulador usa software sem aceleração de hardware, e não serve como medição de desempenho do celular. CRUD e persistência não foram concluídos nesse emulador devido à lentidão da entrada e do UI Automator; esses fluxos passaram nos testes Robolectric e aguardam validação no aparelho físico.

O checksum está em `artifacts/MyFinanceHub-0.1.0-debug.apk.sha256`, e o resumo estruturado em `artifacts/validation.json`. Os relatórios completos ficam em `app/build/reports/` após executar os comandos de verificação.

Também foram executadas verificações de sintaxe dos scripts e de whitespace do diff. O setup foi repetido com sucesso, reutilizando ferramentas e caches.

Validação no aparelho do usuário: **pendente**, conforme [roteiro](TESTES-NO-CELULAR.md). Integrações com bancos, B3, carteiras e notificações não foram implementadas nem validadas nesta versão.
