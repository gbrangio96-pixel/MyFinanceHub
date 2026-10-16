# Validação da versão 0.3.0

As tarefas de build dos dois APKs, testes e Android Lint terminaram com **BUILD SUCCESSFUL**. São as mesmas tarefas de `scripts/verify.sh`; nesta última execução, os APKs foram montados primeiro para permitir a validação visual enquanto os testes eram executados. Lint: **No issues found.** em ambos os flavors.

O Painel agora reúne saldo das contas e posições de investimentos: valor atual, total aplicado e variação. Os investimentos não são somados ao saldo disponível das contas. Cadastro, importação, filtros e resumo mensal ficam em Lançamentos. Carteira, Categorias e Ajustes preservam suas funções e estrutura; Ajustes recebeu Aparência.

Claro, Escuro e Seguir o sistema cobrem as telas e os controles nativos, incluindo diálogos, seletores e calendário. A escolha fica salva no aparelho e é independente do backup financeiro. A navegação preserva busca, filtros e rolagem; as listas exibem inicialmente 30 registros com opção de mostrar mais. Após importar, o app abre o mês mais recente do arquivo e limpa busca/filtro para os registros aparecerem; a prévia informa o período.

| Suíte | Local: passaram | Local: ignorados | Notificações: passaram | Notificações: ignorados |
| --- | ---: | ---: | ---: | ---: |
| AppAppearanceTest | 4 | 0 | 4 | 0 |
| FinanceStoreTest | 23 | 0 | 23 | 0 |
| LocalBuildTest | 3 | 0 | 0 | 3 |
| MainActivityTest | 18 | 0 | 18 | 0 |
| MoneyTest | 4 | 0 | 4 | 0 |
| NotificationCaptureServiceTest | 0 | 7 | 7 | 0 |
| NotificationFlowTest | 0 | 11 | 11 | 0 |
| NotificationInboxTest | 12 | 0 | 12 | 0 |
| NotificationParserTest | 18 | 0 | 18 | 0 |
| StatementImporterTest | 36 | 0 | 36 | 0 |
| **Total** | **118** | **18** | **133** | **3** |

Não houve falhas nem erros. Captura e revisão de notificações têm 18 verificações exclusivas do flavor correspondente; três verificações são exclusivas do APK local. Os novos casos cobrem saldo separado dos investimentos, importação histórica com filtros/busca anteriores, persistência e troca de aparência, preservação de estado e contraste AA das cores semânticas. Há um caso específico de abertura, navegação e recriação nos modos claro/escuro em Android 15/API 35; os demais casos de interface usam API 28. Os dados são fictícios.

A versão final foi instalada com `adb install -r` no emulador Android 15, sem desinstalação ou limpeza de dados. Foram conferidos Painel claro/escuro, Lançamentos escuro, formulário de lançamento escuro e fonte do Android em 130%. A verificação com fonte ampliada mostrou os cinco destinos acessíveis, sem sobreposição e com o nome Lançamentos completo em uma linha. A fonte do emulador foi restaurada após o teste. A aplicação das cores nas barras do sistema ocorre depois de criar a vista da janela; a abertura final não apresentou crash.

Capturas em `artifacts/screenshots/0.3.0/`: `painel-claro.png`, `painel-escuro.png`, `lancamentos-escuro.png`, `formulario-escuro.png` e `fonte-maior.png`.

Ambos os APKs têm package `br.com.myfinancehub`, versão 0.3.0/code5, mínimo API 26 e target API 35. Assinatura APK v2, certificado igual às versões anteriores e alinhamento foram conferidos. O local não registra serviço, consulta de apps ou permissões. O flavor notifications registra o serviço protegido por BIND_NOTIFICATION_LISTENER_SERVICE e mantém a captura experimental opcional. Eles atualizam o mesmo app; não são instalados lado a lado.

FinanceStore.java, Models.java, StatementImporter.java, Money.java e NotificationInbox.java permanecem idênticos ao projeto 0.2.1. Banco e backup continuam v1, sem migração destrutiva. Salve um backup na versão que abre e atualize por cima, sem desinstalar.

**Limites:** a validação visual usou emulador sem aceleração, não o S22 Ultra. A aceitação da versão 0.3.0 pelo Play Protect e os formatos reais das notificações bancárias ainda precisam de validação no aparelho. Mantenha as proteções se aparecer novo bloqueio. O módulo de notificações permanece experimental; Open Finance, B3 e integrações diretas com bancos/carteiras continuam futuras.

Resumo e checksums: `artifacts/validation-0.3.0.json` e arquivos `.apk.sha256`. Siga [o roteiro no celular](TESTES-NO-CELULAR.md).
