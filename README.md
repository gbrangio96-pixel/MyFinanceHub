# MyFinanceHub para Android

Organizador de finanças pessoais em português. O APK atual funciona inteiramente de forma local: não exige login, não solicita permissão de internet e não envia os dados a um servidor.

O repositório também contém um [backend preparatório de Open Finance](backend/README.md), separado do APK. Ele consulta contas, saldos e transações pelo Meu Pluggy; ainda depende de cadastro, autorização dos bancos, configuração privada e implantação HTTPS. Consulte o [guia de ativação e limites de Pix/carteiras](docs/OPEN-FINANCE.md).

## Instalar no celular

O APK de testes padrão está em [`artifacts/MyFinanceHub-0.3.0-local-debug.apk`](artifacts/MyFinanceHub-0.3.0-local-debug.apk), quando gerado. Requer **Android 8.0 ou superior**. A versão 0.3.0 reorganiza as telas e oferece aparência clara, escura ou conforme o sistema. O Painel reúne saldo e investimentos; os lançamentos, a importação, os totais mensais e os gastos por categoria ficam em **Lançamentos**.

Também há o [`artifacts/MyFinanceHub-0.3.0-notifications-debug.apk`](artifacts/MyFinanceHub-0.3.0-notifications-debug.apk), com o módulo experimental de notificações. **Escolha um dos APKs:** ambos usam o mesmo pacote e a mesma assinatura, atualizam o mesmo aplicativo e não são instalados lado a lado. A edição `local` não declara o serviço de leitura de notificações nem mostra os controles de captura.

Para atualizar as versões anteriores enviadas aqui, exporte um backup na versão atual, se ela abrir, e instale por cima, **sem desinstalar**. O pacote `br.com.myfinancehub`, a assinatura de desenvolvimento, o banco financeiro e o formato do backup foram preservados; o código da versão passa para 5. Confira os dados existentes após a atualização.

1. Transfira o APK para o celular e abra o arquivo.
2. Autorize a instalação por esse aplicativo de arquivos, se o Android solicitar.
3. Abra **MyFinanceHub**. Na primeira instalação, a conta “Principal” começa vazia; em **Carteira**, edite seu saldo inicial e cadastre suas outras contas e cartões. Após uma atualização, confira os dados existentes.
4. Abra **Lançamentos** para adicionar registros ou usar **Importar extrato**. Em **Ajustes → Aparência**, escolha **Seguir o sistema**, **Claro** ou **Escuro**.

Este APK é assinado com uma chave de desenvolvimento. Não é uma publicação na Play Store. Para atualizar sem desinstalar, preserve a mesma chave e o identificador `br.com.myfinancehub`. **Desinstalar apaga os dados locais.** Se houver conflito de assinatura, preserve a instalação atual e registre a mensagem para diagnóstico. Para distribuição definitiva, configure uma chave de release própria fora do Git.

No Samsung S22 Ultra, o usuário relatou bloqueio do Play Protect na 0.2.0 e informou que ela instalou após pausar a proteção. A orientação é **manter o Play Protect ativado**. A instalação e a aceitação da 0.3.0 nesse aparelho ainda precisam ser verificadas; a edição local não garante aceitação. Se houver bloqueio, preserve a instalação atual e registre a mensagem, sem desativar proteções para contorná-lo.

## Funcionalidades

- Contas com saldo inicial e saldo calculado a partir dos lançamentos.
- Entradas e saídas com data, categoria, conta, valor e forma de pagamento; criação, edição e exclusão.
- Categorias personalizadas de entrada, saída ou ambas. Excluir uma categoria preserva os lançamentos.
- Cartões por nome e últimos quatro dígitos, fechamento e vencimento cadastrados, e movimentações mensais por cartão.
- Investimentos manuais, com nome, ticker opcional, tipo, valor aplicado e valor atual.
- Painel com saldo das contas e resumo dos investimentos: aplicado, valor atual e variação.
- Lançamentos com resumo mensal de entradas e saídas, despesas por categoria, busca e filtros.
- Aparência clara, escura ou conforme o sistema; formulários, diálogos, seletores e calendário acompanham o tema.
- Busca e posição de rolagem preservadas por aba. A lista começa com 30 registros; **Mostrar mais** permite continuar a consulta.
- Opção de ocultar valores.
- Importação CSV/OFX com escolha de conta e cartão, prévia, validação e deduplicação.
- Exportação CSV e backup/restauração integral em JSON.
- Importação em destaque em Lançamentos, também disponível em Ajustes; a prévia informa o período e a conclusão abre o mês mais recente do arquivo, limpando busca e filtros.
- Aba Categorias com busca, botões Editar/Excluir e criação de categoria dentro do lançamento sem perder o formulário.
- Módulo de notificações preservado no flavor experimental `notifications`, separado do APK local distribuído.

O SQLite fica no armazenamento privado do aplicativo. Valores são inteiros em centavos, sem cálculos monetários com ponto flutuante. Não há anúncios, telemetria ou serviços remotos. O armazenamento não tem senha ou criptografia própria do app: proteja o aparelho com bloqueio de tela. Backups exportados são texto sem criptografia; mantenha-os em local seguro. O backup automático do Android está desativado.

A aparência escolhida fica nas preferências locais e é mantida ao fechar o app. **Seguir o sistema** acompanha o modo claro/escuro do Android. Essa preferência não faz parte do backup financeiro JSON; a troca de aparência não altera contas, categorias ou lançamentos.

## Saldos e cartões

O saldo de uma conta é seu saldo inicial, mais entradas e menos saídas **fora do crédito**. Compras e estornos no crédito ficam associados ao cartão, mas não alteram o caixa.

Ao pagar uma fatura, cadastre uma **saída** na conta debitada com a forma **Pagamento de fatura**. Ela reduz o saldo, mas fica fora dos totais e gráficos de despesas em Lançamentos, pois as compras já foram registradas. Ao importar extratos bancários, revise os pagamentos de fatura e altere essa forma manualmente: os formatos genéricos não permitem identificá-los com segurança.

Os totais dos cartões mostram o **mês civil escolhido em Lançamentos**, não uma fatura calculada pelo fechamento. Parcelamentos, juros e conciliação automática de faturas não são calculados nesta versão. Fechamento e vencimento são dados de referência.

As posições de investimentos são independentes das contas. Aportes e resgates precisam também dos respectivos lançamentos na conta. Não há cotação automática, cálculo tributário ou recomendação de investimento.

Para transferir entre contas próprias, registre uma saída na origem e uma entrada no destino, ambas com a forma **Transferência entre minhas contas**. O saldo total permanece correto; esses registros ficam fora dos totais de receitas e despesas em Lançamentos. Nenhum pagamento ou transferência real é feito pelo app.

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

## Notificações — módulo experimental separado

O flavor `local` é a distribuição padrão: `BuildConfig.NOTIFICATION_CAPTURE_ENABLED` fica desativado, o manifesto não declara o serviço de captura nem suas consultas a apps e a interface não mostra os controles de notificações. **O APK 0.3.0 local não oferece este fluxo.**

O código do módulo permanece no flavor `notifications`, com a flag ativada, para desenvolvimento e validação separados. Nesse flavor experimental, o fluxo em **Ajustes → Notificações de transações** é:

1. Toque em **Escolher apps** e selecione os bancos/carteiras instalados que deseja acompanhar. XP, Itaú, Mercado Pago e Samsung Wallet aparecem com prioridade quando encontrados pelo nome; nenhum app é selecionado automaticamente.
2. Toque em **Permitir acesso no Android**, leia a explicação e conceda o acesso ao serviço **MyFinanceHub — sugestões de transações**, se desejar. O Android concede acesso amplo às notificações; o aplicativo filtra os apps escolhidos antes de ler o conteúdo. É possível revogar o acesso a qualquer momento.
3. Ligue **Capturar novas notificações**. Por padrão, a captura está desligada. Apenas conceder o acesso do Android não liga a captura.
4. Após receber um alerta compatível, abra essa tela. Use **Atualizar lista** se o aplicativo já estava aberto.
5. Toque em **Revisar → Conferir dados**, escolha a conta, confira data/valor/tipo/categoria/forma de pagamento e cartão e salve. Cancelar mantém a sugestão pendente; **Descartar** remove a sugestão sem alterar seus lançamentos.

Nenhum lançamento é criado automaticamente. O módulo usa padrões genéricos em português para compras/pagamentos aprovados, PIX enviado/recebido e depósitos/transferências recebidos. O texto varia por banco, versão do app e Android: **a compatibilidade real com XP, Itaú, Mercado Pago e Samsung Wallet precisa ser conferida no aparelho**. Os exemplos automatizados são fictícios, não modelos oficiais desses fornecedores.

O método Crédito/Débito só é sugerido quando explícito no alerta. Se estiver indefinido, confira e escolha a forma correta: ela determina se a compra altera o saldo da conta. Um único cartão cadastrado com o mesmo final pode ser sugerido; finais repetidos ou desconhecidos exigem seleção manual. A data sugerida é a data de publicação do alerta, que pode diferir da transação.

Não são buscadas notificações antigas ao ativar. Alertas sem valor em reais reconhecível, códigos/senhas, ofertas, saldo/limite isolados, faturas, recusas, investimentos e valores ambíguos são ignorados. Ordens de investimento e cotações da XP continuam no controle manual de investimentos.

As sugestões guardam apenas dados resumidos localmente, sem o texto bruto do alerta, e ficam até 30 dias/máximo de 100 pendentes. Marcadores de descarte/revisão ficam até 90 dias/máximo de 1.000 para reduzir reentregas. O app não envia esses dados pela internet.

Reentregas do mesmo alerta com a mesma chave, horário e dados financeiros são deduplicadas. Atualizações do banco com novo horário ou alertas de duas fontes (banco e Samsung Wallet) podem aparecer separados. A revisão avisa quando já existe lançamento da mesma data/tipo/valor; confira possíveis duplicatas de CSV, lançamento manual ou outro app. Não unificamos automaticamente transações com valores iguais. Um alerta já confirmado não pode ser gravado novamente em outra conta com a mesma origem.

O backup JSON inclui os lançamentos confirmados, mas não a fila de sugestões nem preferências/permissões da captura. Configure novamente em outro aparelho. Para pausar, desligue a captura; para revogar o acesso, use as configurações do Android.

Se o Android ou o Play Protect bloquear o módulo experimental, mantenha as proteções e registre a mensagem para diagnóstico. O controle financeiro local não depende desse acesso.

## Desenvolvimento

Android nativo em Java 17, Android Gradle Plugin 8.9.2, Gradle Wrapper 8.11.1, SDK 35, mínimo SDK 26. Use o checkout existente: as tarefas na nuvem já são isoladas; não é necessário criar outro worktree.

Em Linux x86_64 com `curl`, Python 3, `unzip`, `tar`, `sha256sum` e `sha1sum`:

```sh
cd /workspace/MyFinanceHub
./scripts/setup-android.sh
./scripts/verify.sh
```

O setup instala JDK 21, Gradle e Android SDK em `/workspace/.myfinancehub-tools`, confere checksums publicados e aceita as licenças padrão necessárias do Android SDK. Pode ser repetido; ferramentas, caches e chave de desenvolvimento ficam fora do Git. Proxy e confiança TLS são obtidos em tempo de execução, sem credenciais gravadas no projeto.

Comandos individuais:

```sh
./scripts/gradle.sh --no-daemon testLocalDebugUnitTest
./scripts/gradle.sh --no-daemon lintLocalDebug
./scripts/gradle.sh --no-daemon assembleLocalDebug
```

Para validar o módulo experimental separadamente:

```sh
./scripts/gradle.sh --no-daemon testNotificationsDebugUnitTest
./scripts/gradle.sh --no-daemon lintNotificationsDebug
./scripts/gradle.sh --no-daemon assembleNotificationsDebug
```

`verify.sh` executa testes e lint dos dois flavors e monta ambos os APKs. APK local gerado: `app/build/outputs/apk/local/debug/app-local-debug.apk`. O experimental fica em `app/build/outputs/apk/notifications/debug/app-notifications-debug.apk`. Relatórios: `app/build/reports/`. Para atualizar as cópias distribuíveis:

```sh
cp app/build/outputs/apk/local/debug/app-local-debug.apk artifacts/MyFinanceHub-0.3.0-local-debug.apk
sha256sum artifacts/MyFinanceHub-0.3.0-local-debug.apk > artifacts/MyFinanceHub-0.3.0-local-debug.apk.sha256
cp app/build/outputs/apk/notifications/debug/app-notifications-debug.apk artifacts/MyFinanceHub-0.3.0-notifications-debug.apk
sha256sum artifacts/MyFinanceHub-0.3.0-notifications-debug.apk > artifacts/MyFinanceHub-0.3.0-notifications-debug.apk.sha256
```

No Android Studio, abra o projeto e configure JDK compatível e SDK 35. `local.properties`, chaves e saídas de build são ignorados. O APK local e seus testes não exigem backend, chaves de API ou credenciais bancárias.

O backend usa Node.js 22 ou superior, sem dependências externas. Seus testes são offline e usam dados fictícios:

```sh
cd backend
npm test
```

Os passos reutilizáveis do ambiente estão em [docs/AMBIENTE-NUVEM.md](docs/AMBIENTE-NUVEM.md). A validação desta preparação está em [docs/VALIDACAO-OPEN-FINANCE.md](docs/VALIDACAO-OPEN-FINANCE.md).

## Próximas etapas

Siga o [roteiro de testes no celular](docs/TESTES-NO-CELULAR.md) para verificar os dados preservados, os três modos de aparência, as telas e a importação histórica. O módulo experimental de notificações precisa de validação separada com os alertas reais do aparelho; a identificação automática não é garantida.

A preparação do backend de Open Finance está em [`backend/`](backend/); a integração com o Android e a sincronização agendada ainda serão desenvolvidas após configurar e validar uma conexão real. O [guia](docs/OPEN-FINANCE.md) recomenda o Meu Pluggy para uso pessoal e explica o cadastro.

Integração autorizada com a B3, pagamentos Pix e conexões diretas com Samsung Wallet e Google Wallet são módulos futuros. Dependem de APIs oficiais, consentimento e, quando aplicável, provedores/contratos autorizados. O APK local usa lançamentos manuais e importação de extratos; não se conecta à conta bancária ou à carteira e não faz pagamentos.
