# Meu Pluggy pessoal no Android 0.6.4

O Painel do Android usa exclusivamente dados consultados da API pessoal Pluggy, separados por conta, sem contratar hospedagem. As movimentações locais de teste ficam fora dessa visão e a atualização por notificações está encerrada. O backend de leitura em `backend/` permanece disponível como alternativa técnica, mas não é necessário para a conexão pessoal no app. Não foi criada conta paga nem publicada infraestrutura.

## Por que só o login Meu Pluggy não basta

A conta pessoal e as credenciais de uma aplicação são coisas diferentes. A Pluggy não documenta um login OAuth que transforme a entrada no portal em permissão para o MyFinanceHub consultar todas as transações. No plano MeuPluggy, `GET /v2/items` não está disponível: o aplicativo não consegue descobrir automaticamente todos os IDs existentes. A edição atual usa somente IDs próprios informados na configuração, preserva os já salvos e não tenta contornar essa limitação.

Para o fluxo em que o usuário apenas escolhe e autoriza bancos, é preciso configurar um projeto de integração Pluggy, servidor privado e pareamento do aparelho. O servidor guarda Client ID/Secret, gera um Connect Token e recebe o item retornado pelo Pluggy Connect, mantendo um registro de conexões autorizado. O token do widget não permite consultar transações e não substitui o servidor. Não foi implantada essa infraestrutura, criada conta nem contratado plano pago. No uso pessoal, as autorizações iniciais dos bancos continuam no Meu Pluggy; o conector MeuPluggy espelha essas conexões na aplicação.

## Preparar suas conexões pessoais

1. Acesse [meu.pluggy.ai](https://meu.pluggy.ai), entre na conta que você criou e autorize seus bancos no ambiente oficial.
2. Entre no [Dashboard Pluggy](https://dashboard.pluggy.ai) com a mesma conta e abra **Applications**. O **Client ID** aparece na linha da aplicação.
3. O **Client Secret** aparece somente quando é gerado. Se não o guardou, abra a aplicação e localize **Generate new secret** (permissão Owner/Admin). Gerar outro invalida imediatamente o anterior; atualize os outros usos da chave caso existam. Não use sua senha bancária ou do portal nesse campo.
4. Na aplicação, abra **Demo → Connect Account → MeuPluggy**. Selecione **MeuPluggy**, não o banco nem Sandbox, e entre na conta pessoal para espelhar os itens autorizados.
5. Se MeuPluggy não aparecer, procure em **Customization → Connectors → Personal → Direct Connectors** e habilite o conector.
6. Em **Demo → Connected Items**, abra cada item espelhado e copie o **itemId**. São de um a cinco IDs próprios, sem repetições.
7. No MyFinanceHub, abra **Painel → Contas conectadas → Configurar conexão**. Preencha Client ID, Client Secret e os itemId somente no aparelho e toque **Salvar e consultar**. Os três campos vazios mantêm a configuração existente; ao alterar, preencha os três juntos.

**Onde encontrar os dados da conexão**, na tela nativa e na configuração, oferece o passo a passo e links oficiais. Abrir os links é uma ação explícita; consultar contas e transações não abre o navegador. Depois da configuração inicial, a opção **Consultar ao entrar no aplicativo** permite que o painel consulte a API em primeiro plano com as credenciais protegidas no aparelho.

Não envie credenciais, CPF, senhas ou extratos no chat. A autenticação local MyFinanceHub é separada da conta Meu Pluggy. O aplicativo consulta as contas pela API; você não digita seus saldos ou movimentações para fazer essa leitura. Para salvar uma despesa local, a classificação, revisão e confirmação continuam obrigatórias.

## Gerar um Connect Token no aplicativo

Abra **Configurar conexão → Gerar Connect Token**. Informe Client ID e Client Secret da sua aplicação, mesmo sem os itemId, ou deixe os dois campos vazios para usar as credenciais já protegidas no aparelho. Essa ação chama `POST /auth` e depois `POST /connect_token`, diretamente por HTTPS no domínio fixo da Pluggy. Não há credencial compartilhada no APK.

O token serve para uma sessão do **Pluggy Connect** e tem validade de aproximadamente 30 minutos. Ele não substitui Client ID/Secret, não permite consultar transações como a apiKey e não representa autorização bancária concluída. Gerá-lo não adiciona contas nem lança despesas. A configuração de leitura continua exigindo os itemId das conexões pessoais autorizadas.

O resultado é exibido somente em um diálogo nativo, com cópia marcada como conteúdo sensível. Não é salvo em preferências, backup ou estado da atividade. Ao sair da tela, bloquear o aparelho ou atingir o prazo local, a exibição é removida. Se necessário, gere outro; o app não renova automaticamente o token.

Não foi emitido token real nesta validação: não há Client ID/Secret reais configurados no ambiente de desenvolvimento. O token será solicitado à Pluggy no próprio celular após você informar suas chaves, sem enviá-las ao chat.

## Painel por conta e consulta detalhada

O login abre **Contas do Pluggy**. Cada conta bancária ou cartão tem seu próprio bloco: saldo informado pelo provedor, contagem de movimentações e pendências no período e até três movimentos recentes. **Conta do painel** filtra todas as contas ou uma específica. A associação de cada movimento usa sua conta e conexão de origem; identificadores de conta iguais em conexões diferentes não misturam os dados. Contas bancárias e cartões não compõem um saldo agregado único.

**Atualizar painel** faz uma leitura para os últimos 30 dias. **Contas conectadas** abre a consulta detalhada, com período de até 90 dias inclusivos, busca e filtros por conta/situação. O período efetivo e o horário da resposta exibida ficam visíveis. Todas as contas e movimentações aparecem em componentes nativos, sem navegador ou WebView; configuração e guia ficam separados.

Com **Consultar ao entrar no aplicativo** ativado, o painel consulta em primeiro plano quando necessário. As duas telas compartilham em memória a última resposta completa e podem reutilizar uma consulta recebida há menos de 30 segundos ao navegar. Não há atualização bancária agendada a cada 30 segundos nem leitura com o app fechado. Fechar o processo elimina essa cópia. Ela é vinculada à configuração atual; trocar credenciais ou desconectar a invalida e uma resposta atrasada da configuração anterior é recusada. Falhas de atualização preservam a última consulta identificada com seu período e horário. Sem consulta disponível, o painel informa a ausência dos dados, sem inventar saldo zero ou aproveitar valores locais.

As movimentações previamente inseridas para testes ficam fora dos valores e listas do painel. Registros antigos, relatórios locais, investimentos cadastrados e posição B3 permanecem preservados nas telas próprias e no backup financeiro. Consultar o Pluggy não grava lançamentos nem modifica saldos locais: o saldo exibido no painel é a referência informada pelo provedor, não uma entrada ou saldo inicial aplicado automaticamente.

A captura de notificações foi encerrada. Configurações antigas de acesso não a reativam e o app não solicita nova permissão. **Ajustes → Histórico de notificações** oferece apenas consulta das sugestões antigas, sem atualização de saldos ou promoção a lançamentos.

O Meu Pluggy normalmente atualiza os bancos a cada 24 horas; consultar a API não força atualização na instituição. Esta integração não executa Pix, pagamentos ou cadastro de contas bancárias.

Transações PENDING permanecem pendentes, sem botão de gravação. POSTED pode ser revisada em **Contas conectadas**: escolha conta local, cartão se for crédito, categoria e forma de pagamento. Classifique pagamentos de fatura e transferências próprias para preservar os relatórios. O resumo exige **Confirmar e salvar**. Cancelar mantém os dados locais intactos.

A identidade SHA-256 da origem é guardada com o lançamento em transação SQLite. A repetição da mesma origem é impedida globalmente, inclusive depois de vincular outra conta local e depois de restaurar backup. Valores iguais de CSV, registros históricos de notificações ou conexões recriadas podem representar o mesmo movimento; há aviso de possível duplicidade, sem eliminação automática baseada somente em data/valor.

## Credenciais e limites

Client ID, Client Secret e IDs pessoais são criptografados em armazenamento privado com AES-GCM/Android Keystore. Não são embutidos no APK, código, Git ou backup financeiro. A chave temporária da API permanece em memória durante a consulta. Não há log de conteúdo bancário ou credenciais. O acesso usa apenas HTTPS para `api.pluggy.ai`, sem redirecionamentos.

O cliente aplica os mesmos limites defensivos do backend: até 5 itens, 100 linhas de contas, 5.000 linhas de transações, 20 páginas por conta e 40 requisições; 2 MiB por resposta e 8 MiB somados, com prazos limitados. Falha de qualquer página invalida a consulta inteira. Valores usam centavos exatos, sem arredondamento de dados inválidos; moedas diferentes de BRL são recusadas. A data literal informada não é deslocada para o dia anterior por fuso.

Desconectar apaga as credenciais locais e invalida a consulta compartilhada entre o painel e a tela detalhada, preservando lançamentos confirmados. Para revogar a chave ou o consentimento bancário, use Dashboard/Meu Pluggy. As credenciais pessoais podem precisar ser informadas novamente após mudança da chave protegida ou de aparelho.

As condições de uso pessoal, gratuidade, limites e cobertura pertencem ao provedor. Não há garantia de atualização em tempo real. Nenhum plano é contratado automaticamente. Conectar só a conta no portal, sem autorizar bancos/espelhar itens e configurar a aplicação pessoal, não basta para consultar.

## Fora desta integração

O login do MyFinanceHub é local, com senha/biometria e código de recuperação. Não existe conta online MyFinanceHub ou recuperação por e-mail. Não há sincronização financeira entre celulares; para migrar dados use backup. A consulta pessoal não importa investimentos, não sincroniza custódia B3 e não fornece histórico geral de Samsung/Google Wallet. Crédito é identificado pela conta de origem, sem atribuir carteira digital sem evidência.

## Referências oficiais

- [Guia pessoal Meu Pluggy](https://meu.pluggy.ai/api-guide)
- [Uso pessoal da API](https://docs.pluggy.ai/en/docs/guides/meu-pluggy-personal-use.md)
- [Autenticação](https://docs.pluggy.ai/en/docs/authentication.md)
- [OpenAPI](https://docs.pluggy.ai/openapi/pluggy-api.json)

- [Campos e conexões no Dashboard](https://docs.pluggy.ai/en/docs/guides/dashboard-connectors.md)
- [Pluggy Connect e arquitetura](https://docs.pluggy.ai/en/docs/connect-widget/introduction.md)
