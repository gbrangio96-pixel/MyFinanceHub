# MyFinanceHub para Android

Organizador de finanças pessoais em português. Contas, lançamentos, categorias, importações e relatórios funcionam no aparelho, sem login. A versão 0.5.1 preserva relatórios e cotações da 0.5.0, restaura sugestões por notificações e exige revisão e confirmação no aplicativo antes de gravar lançamentos.

## Instalar no celular

APK: [`artifacts/MyFinanceHub-0.5.1-debug.apk`](artifacts/MyFinanceHub-0.5.1-debug.apk). Requer **Android 8.0 ou superior**. Esta versão tem uma única edição, código de versão 8. A captura de notificações é opcional e local; não há integração com WhatsApp nem transcrição de áudio.

Exporte um backup na versão instalada, se ela abrir, e instale o APK por cima, **sem desinstalar**. O pacote `br.com.myfinancehub` e a assinatura de desenvolvimento são mantidos. O banco v3 recebe migração aditiva das versões v1/v2; backups novos são v3 e a restauração aceita v1, v2 e v3. Não abra esse banco em versões antigas do aplicativo.

1. Transfira o APK para o celular e abra o arquivo. Autorize a instalação pelo aplicativo de arquivos, se o Android solicitar.
2. Confira os dados existentes depois da atualização. Na primeira instalação, a conta “Principal” começa vazia; configure contas e cartões em **Carteira**.
3. Em **Lançamentos**, use **Novo lançamento**, **Importar extrato** ou **Gerenciar categorias**.
4. Em **Relatórios**, escolha período e conta para consultar entradas, despesas e resultado, salvar PDF/CSV ou compartilhar um PDF.
5. Em **Ajustes → Aparência**, escolha **Seguir o sistema**, **Claro** ou **Escuro**.

É um APK de testes assinado com chave de desenvolvimento, não uma publicação na Play Store. Preserve a chave e o identificador para futuras atualizações. **Desinstalar apaga os dados locais.** Se houver falha de instalação, mantenha a instalação atual e registre a mensagem. Mantenha as proteções do aparelho ativadas; a aceitação desta versão no Samsung S22 Ultra depende de verificação nesse aparelho.

## Organização do app

| Tela | Conteúdo |
| --- | --- |
| Painel | Saldo das contas e investimentos, com posições cadastradas e B3 separadas |
| Lançamentos | Cadastro, importação CSV/OFX, resumo mensal, busca, filtros e acesso às categorias |
| Relatórios | Período e conta, entradas, despesas, resultado, categorias, comparação mensal e exportação |
| Carteira | Contas, cartões, investimentos, rentabilidade simples, cotações e posição B3 |
| Ajustes | Aparência, categorias, arquivos, backup e restauração |

Categorias podem ser criadas, buscadas, editadas e excluídas em **Lançamentos → Gerenciar categorias** ou em **Ajustes**. Excluir uma categoria preserva seus lançamentos. Também é possível criar uma categoria dentro do formulário de lançamento sem perder o preenchimento.

Busca e posição de rolagem são preservadas por tela. As listas de lançamentos e posições B3 começam com 30 resultados e oferecem **Mostrar mais**. A opção **Ocultar valores** acompanha as telas e o conteúdo acessível. A aparência escolhida fica salva neste aparelho e não integra o backup financeiro.

## Notificações e confirmação

Em **Ajustes → Notificações de transações**, selecione os apps, conceda acesso nas configurações do Android e ative a captura. Ela começa desligada em instalações novas. Somente novos alertas financeiros compatíveis dos apps selecionados viram sugestões locais; nada é gravado no livro financeiro automaticamente. O reconhecimento depende do texto enviado pelo banco/carteira.

Em **Lançamentos → Revisar notificações**, confira a sugestão, escolha explicitamente a conta e ajuste os dados. No formulário manual ou de notificação, toque **Revisar**, confira o resumo e toque **Confirmar e salvar**. **Voltar à revisão** mantém o preenchimento sem gravar. Cancelar, voltar, fechar ou recriar a tela não confirma uma despesa. Importações mostram todos os registros para revisão e exigem o botão **Importar**.

Sugestões são guardadas separadamente dos dados financeiros, por até 30 dias, com limite de 100. Não integram o backup JSON. A mesma origem de notificação não pode ser confirmada duas vezes, mesmo em contas diferentes; alertas de origens diferentes ainda podem exigir conciliação manual. A captura não usa rede nem registra o texto integral das notificações. Pause-a no app ou revogue o acesso no Android quando desejar.

## Relatórios

O destaque é **entradas, despesas e resultado do período**. Selecione **Este mês**, **Últimos 3 meses**, **Este ano** ou **Personalizado**, com todas as contas ou uma conta específica. Os dias inicial e final são inclusivos; o limite é de 120 meses. As datas efetivas ficam visíveis, inclusive quando o período inclui lançamentos futuros já cadastrados.

Compras no crédito entram nas despesas. Transferências entre contas próprias e pagamentos de fatura ficam fora do resultado, evitando contar o pagamento novamente depois da compra. A **movimentação nas contas** é separada: inclui transferências e faturas, exclui crédito e não representa saldo inicial ou final histórico.

- **Salvar PDF:** resumo, categorias, comparação mensal e todos os lançamentos do filtro, até 5.000 registros. O PDF não corta silenciosamente os lançamentos excedentes.
- **Compartilhar PDF:** abre o seletor de compartilhamento do Android com acesso de leitura ao documento gerado.
- **Exportar CSV:** tabela completa dos lançamentos do filtro, com conta, categoria, cartão, método e indicação de inclusão no resultado. A exportação CSV não tem o limite de 5.000 registros do PDF.

Os arquivos contêm valores completos. Se os valores estiverem ocultos, o app pede confirmação antes de exportar ou compartilhar. O PDF com **Todas as contas** também informa as posições atuais dos investimentos separadamente; elas não representam a rentabilidade do período. Veja os [detalhes e limites](docs/RELATORIOS-E-COTACOES.md).

## Investimentos e cotações opcionais

Cadastre nome, código, tipo, total aplicado e valor atual. A **rentabilidade simples** é a diferença entre valor atual e aplicado, em reais e percentual sobre o aplicado; percentual indisponível quando o aplicado é zero. Ela não considera datas de aportes/resgates, dividendos, impostos ou taxas e não é retorno histórico ou anualizado.

Em **Carteira → Cotações e atualização**, pesquise os ativos disponíveis no catálogo da bolsa B3 ou criptomoedas. A cobertura inclui ações, FIIs, ETFs, BDRs e units, além dos outros fundos listados disponíveis no provedor. Criptomoedas são identificadas em reais, como `BTC-BRL`. Tesouro Direto, CDB, LCI, LCA e outros ativos sem preço compatível nesta integração continuam com valor manual.

Para atualizar uma posição cadastrada, informe código e quantidade e marque **Atualizar este investimento por cotação**. O app calcula quantidade × preço unitário em reais e atualiza o valor atual e os totais; o total aplicado permanece como foi cadastrado. Quantidades usam até oito casas decimais e preços até doze; a posição é arredondada para centavos.

A atualização global vem desligada. Em **Cotações e atualização**, salve a configuração e ative **Atualizar ao abrir a carteira** para consultar automaticamente com o app aberto, em Painel, Carteira ou Posição B3, respeitando o intervalo de uma hora. **Atualizar investimentos agora** solicita uma rodada manual. Não há atualização agendada com o app fechado.

Fonte: **brapi**, com horário do dado exibido. As cotações de bolsa têm atraso aproximado de **30 minutos no Gratuito, 15 no Startup e 5 no Pro**; não são preços em tempo real. Sem chave, PETR4, VALE3, ITUB4 e MGLU3 permitem testes. Outros ativos dependem da chave e da cobertura do plano; criptomoedas exigem Startup ou Pro. A chave é obtida pelo próprio usuário no [dashboard da brapi](https://brapi.dev/dashboard), informada somente no app e guardada em preferências privadas, fora do backup JSON. Nenhuma conta paga é criada pelo aplicativo.

Uma falha preserva o último preço e seu horário. Cotações antigas continuam sendo referências antigas, mesmo após uma nova tentativa. As consultas enviam ao provedor os códigos ou termos pesquisados e a chave, quando configurada, por HTTPS; quantidades, custos, saldos e lançamentos não são enviados. O provedor também recebe os dados de conexão usuais, como o IP. Confira [configuração, limites e fontes oficiais](docs/RELATORIOS-E-COTACOES.md).

## Dados, saldos e cartões

O SQLite fica no armazenamento privado do aplicativo. Valores financeiros usam centavos inteiros; multiplicações de quantidade e preço usam decimais exatos. Não há anúncios ou telemetria. A permissão `INTERNET` atende somente às consultas opcionais de mercado. Controles financeiros e relatórios não dependem dela nem de um backend.

O armazenamento não possui senha ou criptografia própria do app; proteja o aparelho com bloqueio de tela. Backups JSON, CSV e PDF exportados não são criptografados. O backup automático do Android está desativado. A chave do provedor, preferências e cache de cotações não fazem parte do backup financeiro; os dados de cotação já aplicados a investimentos cadastrados fazem parte do backup v3.

O saldo de uma conta é seu saldo inicial, mais entradas e menos saídas **fora do crédito**. Compras e estornos no crédito ficam associados ao cartão e não alteram o caixa. Ao pagar a fatura, cadastre uma **saída** na conta debitada com a forma **Pagamento de fatura**: ela reduz o saldo sem repetir a despesa no resultado. Ao importar extratos genéricos, revise essa forma manualmente.

Os totais de cartão mostram o **mês civil escolhido em Lançamentos**, não uma fatura calculada pelo fechamento. Parcelamento, juros e conciliação automática de faturas não são calculados; fechamento e vencimento são referências.

Para transferências próprias, registre saída na origem e entrada no destino, ambas com **Transferência entre minhas contas**. Não há pagamento ou transferência bancária real pelo app. Investimentos são independentes das contas; aportes e resgates precisam também dos respectivos lançamentos. Não há cálculo tributário ou recomendação de investimento.

## Importar posições B3

Baixe **Excel → Posição** na [Área do Investidor](https://www.investidor.b3.com.br/) pelo navegador, volte ao app e use **Carteira → Abrir posição B3 → Importar posição B3**. Confira arquivo e data na prévia. Nenhuma senha da B3 é recebida pelo app. Aceita XLSX/CSV compatível até 8 MiB e 5.000 posições; não aceita PDF, XLS, movimentações, fórmulas ou vínculos externos. Layout desconhecido gera erro sem alterar a posição anterior. Consulte os [formatos B3](docs/B3.md).

O relatório importado mantém os valores e as quantidades da sua data de referência. Ele fica separado dos investimentos cadastrados e do saldo; não estabelece custo de aquisição ou rentabilidade. As consultas de mercado podem mostrar uma **estimativa separada**, usando a quantidade importada e a cotação armazenada, sem modificar o documento original ou sincronizar a custódia. A estimativa pode cobrir apenas parte da carteira e ficar desatualizada se houver compras, vendas ou eventos societários desde a importação. A API direta de custódia B3 não está integrada.

## Importar extratos

Use **Lançamentos → Importar extrato** ou o acesso em **Ajustes** e escolha arquivos pelo seletor de documentos do Android. Há exemplos **fictícios** em [`samples/`](samples/); eles não são inseridos automaticamente. A prévia mostra o período encontrado. Após confirmar, a lista abre o mês mais recente do arquivo, sem busca nem filtro de tipo, para que os registros desse período fiquem visíveis.

CSV aceita UTF-8 com/sem BOM e, pelo seletor do app, Windows-1252 como alternativa; delimitador `;` ou `,`; campos entre aspas, inclusive quebras de linha. Requer colunas de data, descrição e valor, com cabeçalhos usuais em português/inglês:

```csv
data;descricao;valor;categoria;id
15/10/2026;Salário;5000,00;Salário;exemplo-001
15/10/2026;Mercado;-180,45;Alimentação;exemplo-002
```

- Datas: `dd/MM/yyyy` ou `yyyy-MM-dd`.
- Valores com até duas casas decimais, vírgula ou ponto decimal. Valores zero são rejeitados.
- CSV de conta: negativo é saída; positivo é entrada.
- Em arquivos separados por vírgula, valores com vírgula decimal precisam de aspas, como `"5000,00"`. Descrições que contêm o separador também precisam de aspas. Há um [exemplo completo separado por vírgulas](samples/extrato-exemplo-virgula.csv).
- CSV de cartão: marque a opção correspondente; positivo é compra e negativo é estorno.
- A coluna opcional `tipo`/`type` aceita `income`/`expense` e equivalentes em português; prevalece sobre o sinal. O CSV exportado usa esse campo.
- Categorias são associadas às existentes por nome e tipo; caso contrário, é usada a categoria padrão compatível da importação, ou “Sem categoria”. Colunas de conta, cartão e método não substituem a seleção de destino. Use JSON para restaurar todos os vínculos.
- OFX SGML 1.x/XML 2.x com `STMTTRN`, `DTPOSTED`, `TRNAMT`, `NAME`/`MEMO` e `FITID`: segue o sinal do banco (débito negativo), inclusive em cartões. Mantém o dia informado no extrato. DTDs e entidades externas são rejeitados. Arquivos em moeda diferente de BRL ou contendo múltiplos extratos devem ser separados/convertidos antes da importação.
- Limites: 8 MiB por arquivo e 20.000 transações. Registro inválido impede a importação inteira; nada é salvo parcialmente. A prévia mostra uma amostra dos primeiros 12 e os totais.

Na versão 0.1.1, células vazias extras no final das linhas ou do cabeçalho são toleradas. Campos extras com conteúdo continuam causando erro: o aplicativo informa a linha, as contagens e o separador encontrado, sem juntar ou descartar valores. O texto da mensagem pode ser selecionado para copiar. Se houver erro, verifique a linha indicada, que pode ser diferente da primeira transação.

Identificadores `FITID`/`id` detectam reimportações na mesma conta. Sem identificador, a detecção usa data, descrição, tipo, valor e ocorrência. Preserva transações idênticas repetidas no mesmo arquivo, mas **não permite conciliação perfeita entre origens diferentes ou intervalos sobrepostos**. Revise duplicatas nesses casos. Use uma conta de destino consistente para cada extrato; os identificadores são únicos por conta.

PDF, fotos e formatos proprietários não são suportados. O CSV serve para planilhas e protege campos textuais contra fórmulas. O **backup JSON** preserva os dados originais e todos os vínculos, com limite de 8 MiB por backup nesta versão. Bases maiores exigem exportação CSV e não têm restauração integral suportada. Restaurar exige confirmação e substitui todo o conteúdo atual de forma transacional; backups inválidos são rejeitados sem apagar a base.

Consulte o [registro de validação da 0.5.1](docs/VALIDACAO-0.5.1.md) para testes, atualização no emulador e limites da verificação.

## Desenvolvimento

Android nativo em Java 17, Android Gradle Plugin 8.9.2, Gradle Wrapper 8.11.1, SDK 35, mínimo SDK 26. Use o checkout existente; as tarefas na nuvem já são isoladas.

Em Linux x86_64 com `curl`, Python 3, `unzip`, `tar`, `sha256sum` e `sha1sum`:

```sh
cd /workspace/MyFinanceHub
./scripts/setup-android.sh
./scripts/verify.sh
```

O setup instala JDK 21, Gradle, SDK e caches em `/workspace/.myfinancehub-tools`, confere checksums publicados e aceita as licenças padrão necessárias. Ferramentas, caches e chave de desenvolvimento ficam fora do Git. Proxy e confiança TLS são obtidos em tempo de execução, sem credenciais gravadas no projeto.

Comandos individuais:

```sh
./scripts/gradle.sh --no-daemon testDebugUnitTest
./scripts/gradle.sh --no-daemon lintDebug
./scripts/gradle.sh --no-daemon assembleDebug
```

`verify.sh` executa testes, lint e build da única edição. APK gerado: `app/build/outputs/apk/debug/app-debug.apk`. Relatórios dos verificadores: `app/build/reports/`. Para atualizar a cópia distribuível após concluir a validação:

```sh
cp app/build/outputs/apk/debug/app-debug.apk artifacts/MyFinanceHub-0.5.1-debug.apk
sha256sum artifacts/MyFinanceHub-0.5.1-debug.apk > artifacts/MyFinanceHub-0.5.1-debug.apk.sha256
```

No Android Studio, configure JDK compatível e SDK 35. `local.properties`, chaves e saídas de build são ignorados. Os testes substituem as consultas de mercado por respostas fictícias: montar e validar o APK não exige chave brapi, credenciais bancárias ou backend. A validação HTTPS opcional usa apenas símbolos públicos, sem dados pessoais.

O repositório contém um [backend preparatório de Open Finance](backend/README.md), separado do APK. Requer Node.js 22 ou superior e seus testes offline não têm dependências npm:

```sh
cd backend
npm test
```

O Android não está conectado a esse backend. A execução real depende de cadastro, consentimento bancário, secrets privados e implantação HTTPS; consulte [OPEN-FINANCE.md](docs/OPEN-FINANCE.md). Os passos reutilizáveis do ambiente estão em [AMBIENTE-NUVEM.md](docs/AMBIENTE-NUVEM.md).

## Validação no celular

Siga o [roteiro de testes no Android](docs/TESTES-NO-CELULAR.md) para verificar atualização preservando dados, temas, importações, relatórios, compartilhamento e cotações. O roteiro é uma lista de verificações, não uma declaração de testes já realizados no seu aparelho.

Bancos, pagamentos Pix, integração direta com carteiras e sincronização automática da custódia B3 não estão conectados ao APK. Notificações compatíveis geram somente sugestões para revisão; WhatsApp e transcrição não estão integrados. Documentos de validação de versões antigas permanecem como histórico; não descrevem as opções da 0.5.1.
