# Meu Pluggy pessoal no Android 0.6.3

A consulta Android agora usa diretamente a API pessoal Pluggy, sem contratar hospedagem. O backend de leitura em `backend/` permanece disponível como alternativa técnica, mas não é necessário para a conexão pessoal no app. Não foi criada conta paga nem publicada infraestrutura.

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

**Onde encontrar os dados da conexão**, na tela nativa e na configuração, oferece o passo a passo e links oficiais. Abrir os links é uma ação explícita; consultar contas e transações não abre o navegador. Depois da configuração inicial, a opção **Consultar ao entrar no aplicativo** usa automaticamente as credenciais protegidas no aparelho.

Não envie credenciais, CPF, senhas ou extratos no chat. A autenticação local MyFinanceHub é separada da conta Meu Pluggy. O aplicativo consulta as contas pela API; você não digita seus saldos ou movimentações para fazer essa leitura. Para salvar uma despesa local, a classificação, revisão e confirmação continuam obrigatórias.

## Gerar um Connect Token no aplicativo

Abra **Configurar conexão → Gerar Connect Token**. Informe Client ID e Client Secret da sua aplicação, mesmo sem os itemId, ou deixe os dois campos vazios para usar as credenciais já protegidas no aparelho. Essa ação chama `POST /auth` e depois `POST /connect_token`, diretamente por HTTPS no domínio fixo da Pluggy. Não há credencial compartilhada no APK.

O token serve para uma sessão do **Pluggy Connect** e tem validade de aproximadamente 30 minutos. Ele não substitui Client ID/Secret, não permite consultar transações como a apiKey e não representa autorização bancária concluída. Gerá-lo não adiciona contas nem lança despesas. A configuração de leitura continua exigindo os itemId das conexões pessoais autorizadas.

O resultado é exibido somente em um diálogo nativo, com cópia marcada como conteúdo sensível. Não é salvo em preferências, backup ou estado da atividade. Ao sair da tela, bloquear o aparelho ou atingir o prazo local, a exibição é removida. Se necessário, gere outro; o app não renova automaticamente o token.

Não foi emitido token real nesta validação: não há Client ID/Secret reais configurados no ambiente de desenvolvimento. O token será solicitado à Pluggy no próprio celular após você informar suas chaves, sem enviá-las ao chat.

## Consulta e revisão

A tela principal é nativa: contas, cartões, saldos informados, horários e movimentações aparecem dentro do app, sem abrir navegador ou WebView. A configuração e o guia ficam em diálogos separados. Atualizar dados consulta diretamente a API; filtros por conta/situação e busca não alteram os dados financeiros. A opção Consultar ao entrar faz a leitura em primeiro plano após o login local. Pode ser desativada na tela de conexão. O período padrão é de 30 dias, e o máximo é 90 dias inclusivos, sem data final futura. A consulta é somente leitura: não executa Pix, pagamentos, cadastro de contas bancárias nem sincronização forçada. O Meu Pluggy normalmente atualiza os bancos a cada 24 horas; horários/status da origem ficam visíveis.

Contas e compras no crédito ficam identificadas separadamente. O saldo informado é referência do provedor, não uma entrada e não um saldo inicial aplicado automaticamente. Nenhum lançamento local é criado ao consultar.

Transações PENDING permanecem pendentes, sem botão de gravação. POSTED pode ser revisada: escolha conta local, cartão se for crédito, categoria e forma de pagamento. Classifique pagamentos de fatura e transferências próprias para preservar os relatórios. O resumo exige Confirmar e salvar. Cancelar mantém os dados locais intactos.

A identidade SHA-256 da origem é guardada com o lançamento em transação SQLite. A repetição da mesma origem é impedida globalmente, inclusive depois de vincular outra conta local e depois de restaurar backup. Valores iguais de CSV, notificações ou conexões recriadas podem representar o mesmo movimento; há aviso de possível duplicidade, mas não há eliminação automática baseada somente em data/valor.

## Credenciais e limites

Client ID, Client Secret e IDs pessoais são criptografados em armazenamento privado com AES-GCM/Android Keystore. Não são embutidos no APK, código, Git ou backup financeiro. A chave temporária da API permanece em memória durante a consulta. Não há log de conteúdo bancário ou credenciais. O acesso usa apenas HTTPS para `api.pluggy.ai`, sem redirecionamentos.

O cliente aplica os mesmos limites defensivos do backend: até 5 itens, 100 linhas de contas, 5.000 linhas de transações, 20 páginas por conta e 40 requisições; 2 MiB por resposta e 8 MiB somados, com prazos limitados. Falha de qualquer página invalida a consulta inteira. Valores usam centavos exatos, sem arredondamento de dados inválidos; moedas diferentes de BRL são recusadas. A data literal informada não é deslocada para o dia anterior por fuso.

Desconectar apaga as credenciais locais e limpa a consulta da tela, preservando lançamentos confirmados. Para revogar a chave ou o consentimento bancário, use Dashboard/Meu Pluggy. As credenciais pessoais podem precisar ser informadas novamente após mudança da chave protegida ou de aparelho.

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
