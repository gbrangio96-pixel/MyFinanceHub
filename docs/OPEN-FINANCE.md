# Open Finance, Pix e carteiras: preparação da próxima etapa

Esta entrega prepara um **backend de consulta aos próprios dados pelo Meu Pluggy**. O código faz requisições HTTP reais à API documentada da Pluggy quando recebe configuração válida. Os testes usam respostas fictícias, sem consultar bancos.

**Ainda não há conta de provedor configurada, credenciais, hospedagem publicada ou integração com o Android.** O APK e as telas da versão 0.3.0 continuam iguais. Nenhum botão simula uma conexão bancária concluída, e este backend não altera o banco financeiro local.

## O que pode ser conectado

| Objetivo | Caminho desta etapa | Limite |
| --- | --- | --- |
| Consultar contas, saldos e transações próprias | Backend → API Pluggy → conexões Meu Pluggy autorizadas pelo titular | Depende da instituição, dos produtos compartilhados e da autorização vigente. |
| Identificar Pix no extrato | Metadados explícitos da transação, quando presentes | Não executa Pix nem prova recebimento em tempo real. |
| Consultar compras de cartão | Conta de crédito e transações disponibilizadas pelo provedor | Não identifica automaticamente se a compra passou pela Samsung Wallet ou Google Wallet. |
| Pagar, receber por cobrança ou agendar Pix | Módulo futuro de pagamentos | Exige produto, habilitação e autorização específicos. O consentimento de leitura não permite movimentar dinheiro. |
| Histórico completo da Samsung Wallet ou Google Wallet | Não implementado | As APIs públicas pesquisadas não oferecem um extrato geral de compras da carteira. |

O backend atual consulta **contas e transações**. A sincronização de investimentos permanece para uma etapa posterior, mesmo quando o provedor possui esses dados.

## Por que começar pelo Meu Pluggy

A documentação oficial consultada em **16/10/2026** oferece um caminho para pessoas que querem consultar os próprios dados: **Meu Pluggy + conector MeuPluggy**. Ela informa gratuidade, até **cinco conexões**, uso pessoal e não comercial, sem due diligence empresarial e sem encerramento pelo prazo do trial. As conexões são atualizadas automaticamente a cada **24 horas**; o item espelhado na aplicação não aceita atualização manual.

Essas condições são do fornecedor e devem ser conferidas no cadastro. Uma aplicação comercial ou que acesse dados de outras pessoas segue outro processo. Open Finance empresarial e iniciação de pagamentos são habilitações separadas; esta preparação não as contrata.

## Cadastro e autorização, inclusive pelo celular

Estes passos podem ser iniciados no Chrome do S22 Ultra, sem cabo USB:

1. Acesse [meu.pluggy.ai](https://meu.pluggy.ai) e crie pessoalmente sua conta. Confira o endereço antes de entrar.
2. Dentro do Meu Pluggy, conecte cada banco desejado e conclua a autorização no ambiente da instituição. Cada banco precisa da sua própria autorização. Não forneça senha bancária ao MyFinanceHub ou nesta conversa.
3. Acesse o [Dashboard da Pluggy](https://dashboard.pluggy.ai) com **a mesma conta**.
4. Abra **Applications → sua aplicação → Demo → Connect Account**. Os nomes podem aparecer traduzidos no portal.
5. Escolha o conector **MeuPluggy**, e não o nome do banco nem Sandbox. Caso use seleção personalizada de conectores, habilite MeuPluggy em **Customization → Connectors → Direct Connectors**.
6. Confira os itens espelhados em **Connected Items**. Guarde o `itemId` de cada conexão e o `Client ID` da aplicação. O `Client Secret` aparece somente quando é gerado; armazene-o de forma privada.

Se o Dashboard pedir novamente CPF e aprovação no banco nessa etapa, confira se foi escolhido **MeuPluggy**. A autorização bancária deste caminho pessoal acontece no Meu Pluggy.

Não envie `Client Secret`, chave de API, token de acesso, CPF, extratos ou senhas pelo chat. Não salve esses valores no Git, em ZIPs do projeto, no APK nem no backup financeiro.

O código usa uma lista explícita de até cinco `itemId`. Isso é necessário porque **`GET /v2/items` não está disponível para contas pessoais Meu Pluggy**. Uma conexão real do Meu Pluggy contém dados reais; ela não é um sandbox.

## Onde ficam as credenciais

O Android não deve receber `PLUGGY_CLIENT_SECRET` nem a chave de API da Pluggy. O backend guarda esses segredos, autentica a consulta e retorna somente os campos financeiros necessários.

O repositório contém configuração para executar o backend como **Cloudflare Worker**. Ela está preparada para revisão e publicação manual, mas **nenhum serviço foi publicado nesta etapa**. A Cloudflare possui um [plano Free](https://developers.cloudflare.com/workers/platform/pricing/); limites e cobranças dependem do plano e do uso. Não há garantia de custo zero para qualquer cenário.

A criação de contas e o preenchimento de secrets pelo Dashboard podem ser feitos no celular. Publicar o código do Worker é uma etapa adicional: cadastrar secrets ou criar um Worker vazio não instala este conector. O procedimento técnico, os quatro secrets e a alternativa por CLI estão em [backend/README.md](../backend/README.md).

## O que a consulta entrega

`GET /v1/snapshot` retorna um JSON de **schema versão 1**, com conexões, contas e transações normalizadas. É uma fotografia para consulta e futura revisão; não é um lançamento contábil.

- `reportedBalanceCents` é o saldo informado pela instituição. **Não deve ser cadastrado como entrada nem somado novamente ao saldo local.** Contas de crédito precisam de tratamento separado das contas bancárias.
- Valores monetários são **strings de centavos inteiros**, por exemplo, `"18547"` corresponde a R$ 185,47. A direção fica em `income` ou `expense`; o valor da transação é absoluto.
- `POSTED` representa uma movimentação confirmada. `PENDING` pode incluir pagamentos agendados e fica separado; não deve alterar saldo confirmado.
- `date` preserva o dia escrito no campo de data da Pluggy, e `sourceDate` preserva seu valor original. Uma data à meia-noite UTC não é deslocada automaticamente para o dia anterior no Brasil.
- Pix só é marcado quando `operationType` ou `paymentData.paymentMethod` informa explicitamente `PIX`. Uma palavra na descrição não basta.
- A resposta indica quando foi consultada (`observedAt`) e, quando informado pelo provedor, quando cada conexão foi atualizada (`providerUpdatedAt`). Consultar agora não obriga o banco a disponibilizar dados novos naquele instante.

A consulta retorna o período inteiro apenas quando todas as páginas e contas válidas foram processadas. Em falha, paginação inconsistente ou limite excedido, retorna erro sem apresentar um resultado financeiro parcial como completo.

## Duplicidades e vinculação das contas

Cada transação tem `externalId` e `sourceKey`. Quando existe `providerId`, ele é preferido na identidade; caso contrário, usa-se o identificador da Pluggy. O hash também inclui a conexão e a conta de origem.

Essa identidade ajuda a evitar repetições na mesma conexão, mas **não garante equivalência após excluir e recriar uma conexão**. Uma importação CSV/OFX ou notificação já salva também pode representar a mesma compra com outro identificador. Comparar somente data, descrição e valor não é prova suficiente: compras legítimas podem ser iguais.

Antes de qualquer sincronização que grave no Android, será necessário:

1. Vincular cada conta externa a uma conta ou cartão local, com confirmação do usuário.
2. Definir uma data de início e reconciliar o saldo de referência, evitando somar saldo e histórico duas vezes.
3. Apresentar pendências e possíveis duplicidades para revisão, inclusive após reconexão e quando houver CSV ou notificações.
4. Salvar as alterações e as identidades de origem na mesma transação do banco local.

Nada disso é executado automaticamente por este backend.

## Carteiras e cartões

Google Pay permite aceitar pagamentos no checkout do próprio app. Google Wallet oferece APIs para passes de emissores, como ingressos e fidelidade. Esses produtos não fornecem ao MyFinanceHub um histórico geral das compras feitas em outros estabelecimentos.

Samsung Wallet/Pay possui recursos para parceiros, emissores e comerciantes. O `getAllCards` documentado depende de `PartnerInfo`, `serviceId` e filtros de emissor definidos no onboarding; não significa acesso irrestrito a todos os cartões e compras do celular. Os finais de PAN físico e digital também podem ser diferentes.

Para despesas pessoais, o caminho é consultar a conta/cartão pelo provedor, manter a importação de extratos e, na variante que inclui notificações, usar sugestões opcionais com revisão. A origem Samsung/Google Wallet só poderá ser atribuída quando houver evidência explícita; não será deduzida pelo final do cartão.

## Próxima entrega Android

Com a conta pessoal e o backend funcionando, a próxima implementação será uma conexão HTTPS autenticada, armazenamento do token do app com proteção do Android Keystore, vinculação de contas, prévia das transações e sincronização compatível com a atualização diária do Meu Pluggy. Conexão, consentimento expirado e falhas precisam de estados visíveis. A programação de sincronização a cada 24 horas ainda não foi implementada.

O controle local e a importação manual continuam disponíveis. Esta preparação não exige desinstalar o aplicativo atual.

## Fontes oficiais

- [Meu Pluggy: guia pessoal da API](https://meu.pluggy.ai/api-guide)
- [Pluggy: uso pessoal, limites e itens espelhados](https://docs.pluggy.ai/en/docs/guides/meu-pluggy-personal-use.md)
- [Pluggy: autenticação e segredos no servidor](https://docs.pluggy.ai/en/docs/authentication.md)
- [Pluggy: referência OpenAPI](https://docs.pluggy.ai/openapi/pluggy-api.json)
- [Pluggy: cobertura Open Finance e suas restrições](https://docs.pluggy.ai/en/docs/open-finance/institutions-coverage.md)
- [Pluggy Payments: habilitação separada e autorização de pagamento](https://docs.pluggy.ai/en/docs/payments/overview.md)
- [Cloudflare Workers: preços e limites de planos](https://developers.cloudflare.com/workers/platform/pricing/)
- [Google Pay: visão geral Android](https://developers.google.com/pay/api/android/overview)
- [Google Wallet: referência REST](https://developers.google.com/wallet/reference/rest)
- [Samsung Pay: casos de uso suportados](https://developer.samsung.com/pay/welcome/supported-use-cases.html)
- [Samsung Wallet: introdução para parceiros](https://developer.samsung.com/wallet/welcome/introduction.html)
- [Samsung Pay: CardManager e getAllCards](https://developer.samsung.com/pay/api-reference/com/samsung/android/sdk/samsungpay/v2/card/CardManager.html)
