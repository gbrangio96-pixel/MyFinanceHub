# Meu Pluggy pessoal no Android 0.6.1

A consulta Android agora usa diretamente a API pessoal Pluggy, sem contratar hospedagem. O backend de leitura em `backend/` permanece disponível como alternativa técnica, mas não é necessário para a conexão pessoal no app. Não foi criada conta paga nem publicada infraestrutura.

## Preparar suas conexões

1. Acesse [meu.pluggy.ai](https://meu.pluggy.ai), entre na conta que você criou e conecte seus bancos. Conclua os consentimentos no ambiente oficial das instituições.
2. Entre no [Dashboard Pluggy](https://dashboard.pluggy.ai) com a mesma conta.
3. Em sua aplicação, abra Demo → Connect Account e escolha o conector **MeuPluggy**, e não o banco nem Sandbox. O objetivo é espelhar conexões que já autorizou no Meu Pluggy.
4. Se necessário, habilite MeuPluggy em Customization → Connectors → Direct Connectors. Confira os itens espelhados em Connected Items.
5. Guarde Client ID, Client Secret e os itemId. A API pessoal não lista automaticamente todos os itens: o app pede de um a cinco IDs, separados por vírgula.
6. No MyFinanceHub, abra Painel → Contas conectadas ou Ajustes → Meu Pluggy e toque Configurar conexão. Preencha suas credenciais somente nesse diálogo e toque Salvar e consultar. O campo Client Secret não é a senha bancária nem a senha de login no portal. Campos vazios mantêm a configuração salva; ao alterar, preencha os três campos juntos.

Não envie credenciais, CPF, senhas ou extratos no chat. Não cadastre uma chave compartilhada de terceiros. A autenticação do aplicativo financeiro é local e separada da conta Meu Pluggy.

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
