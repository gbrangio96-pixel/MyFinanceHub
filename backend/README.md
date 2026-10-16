# Backend de consulta pessoal — Meu Pluggy

Backend HTTP de leitura, preparado para Cloudflare Workers com módulos ECMAScript. Consulta contas e transações dos `itemId` explicitamente configurados e devolve um snapshot normalizado. Usa `fetch` padrão e autenticação real da API Pluggy; os testes substituem a rede por respostas fictícias.

**Estado da entrega:** não publicado, sem conta configurada, sem credenciais reais e sem ligação com o Android. Não inicia pagamentos, não altera o SQLite do aplicativo e não fornece uma atualização de APK. As telas da versão 0.3.0 permanecem iguais.

Para o cadastro pessoal, autorização dos bancos e limitações de Pix/carteiras, leia [docs/OPEN-FINANCE.md](../docs/OPEN-FINANCE.md).

## Arquivos e execução local dos testes

| Arquivo | Responsabilidade |
| --- | --- |
| `src/worker.mjs` | HTTPS, autenticação do app, período de consulta e respostas HTTP. |
| `src/pluggy.mjs` | Autenticação do provedor, itens permitidos, contas, paginação e limites. |
| `src/normalize.mjs` | Validação financeira, valores exatos, datas e identidade de origem. |
| `test/*.test.mjs` | Casos offline com dados fictícios e rede substituída. |
| `wrangler.jsonc` | Entrada do Worker e compatibilidade da plataforma; sem secrets. |

É necessário **Node.js 22 ou superior** para executar a suíte:

```sh
cd backend
npm test
```

O script usa `node --test`. Os testes não precisam de conta Pluggy nem acessam instituições reais. Aprovação desses testes não comprova funcionamento de uma conexão bancária real, disponibilidade da instituição ou instalação no celular.

## Configuração privada

Configure estes quatro valores como **secrets do Worker**, nunca em `wrangler.jsonc`, código, Git, APK, ZIP distribuído ou backup financeiro:

| Secret | Conteúdo |
| --- | --- |
| `APP_ACCESS_TOKEN` | Token privado exclusivo deste app, gerado com 32 bytes aleatórios em base64url. Não reutilizar credencial bancária ou chave da Pluggy. |
| `PLUGGY_CLIENT_ID` | Client ID da aplicação pessoal no Dashboard Pluggy. |
| `PLUGGY_CLIENT_SECRET` | Client Secret da mesma aplicação, guardado de forma privada. |
| `PLUGGY_ITEM_IDS` | De um a cinco `itemId` próprios, separados por vírgula, sem duplicatas. |

Um comando para gerar o token em um ambiente local confiável é:

```sh
node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64url') + '\n')"
```

O resultado é um segredo: guarde-o no gerenciador de senhas e no painel privado, sem enviá-lo nesta conversa. O comando acima é uma instrução para execução manual; a entrega não gera nem configura um token real.

### Configurar pelo Dashboard, sem USB

1. Crie pessoalmente a conta Meu Pluggy e autorize as instituições, seguindo o guia vinculado acima. No Dashboard Pluggy, conecte **MeuPluggy**, não o banco ou Sandbox, para espelhar os itens próprios.
2. Crie ou entre na sua conta da Cloudflare pelo navegador. O [plano Free existe](https://developers.cloudflare.com/workers/platform/pricing/), com limites sujeitos ao plano e ao uso; não pressupor gratuidade irrestrita.
3. Publique o código deste Worker por uma interface de implantação compatível ou pela CLI abaixo. **Isso ainda não foi feito.** Um Worker vazio ou uma página estática não executa este backend.
4. No Worker correspondente, abra **Settings → Variables and Secrets** e cadastre os quatro valores como **Secret**. O painel pode exigir salvar/publicar uma nova versão para aplicar as alterações.
5. Consulte `/health` em HTTPS. Ele verifica apenas a configuração local; não testa autenticação no provedor nem disponibilidade de bancos.

Cadastro e preenchimento dos secrets podem ser feitos no Chrome do celular. O projeto não automatiza a abertura de contas nem a publicação de código no painel. Se você está somente com o celular, pode concluir os cadastros e guardar os dados privadamente antes de preparar a implantação. Nenhuma etapa pede USB ou envio de segredos pelo chat.

### Publicação manual por CLI, opcional

Revise o código e a configuração antes de publicar. Em uma máquina de confiança, com Node e acesso à internet:

```sh
cd backend
npx wrangler login
npx wrangler secret put APP_ACCESS_TOKEN
npx wrangler secret put PLUGGY_CLIENT_ID
npx wrangler secret put PLUGGY_CLIENT_SECRET
npx wrangler secret put PLUGGY_ITEM_IDS
npx wrangler deploy
```

Os comandos `secret put` solicitam o valor de forma interativa. Não passe segredos como argumento de comando, que pode ficar no histórico do terminal. Dependendo do estado da conta, o Wrangler pode solicitar a criação do Worker. Revise o nome e o destino antes de confirmar. Esses comandos são exemplos para execução manual; **não foram executados para publicar um serviço nesta entrega**.

O `compatibility_date` do projeto fixa o comportamento da plataforma e não representa a data de publicação. Para testes de código, `npm test` funciona sem implantação e sem secrets.

## API do MyFinanceHub

O serviço aceita **HTTPS**, sem CORS aberto e sem autenticação em query string. O token completo funciona como uma chave de acesso aos seus dados; trate-o como segredo. Este é um backend de um usuário, não um sistema de contas multiusuário.

### `GET /health`

Retorna `{ "configured": true, "provider": "pluggy", "access": "read_only" }` com HTTP 200 quando a configuração requerida está presente e válida; retorna `configured: false` e HTTP 503 quando falta configuração válida. Não retorna secrets, os `itemId`, saldos ou transações. Não consulta a Pluggy. Portanto, resposta de saúde não prova que a chave do provedor é válida ou que houve sincronização.

### `GET /v1/snapshot?from=YYYY-MM-DD&to=YYYY-MM-DD`

Exige o cabeçalho:

```http
Authorization: Bearer <APP_ACCESS_TOKEN privado>
```

As datas precisam estar no formato de calendário indicado. Sem `to`, a data final é hoje em `America/Sao_Paulo`; sem `from`, a data inicial é 29 dias antes de `to`. Sem os dois parâmetros, o período padrão cobre os últimos **30 dias, incluindo hoje**. O período máximo é de **90 dias, contando as duas datas**; a data final não pode estar no futuro. Parâmetros desconhecidos ou repetidos são recusados. Não envie secrets na URL. O snapshot contém dados financeiros; evite abri-lo em ferramentas de terceiros ou anexá-lo em relatos de erro.

A resposta completa só é produzida se todas as consultas necessárias terminarem com sucesso. Erros usam mensagens controladas, sem reproduzir respostas bancárias ou credenciais. O serviço não registra payloads financeiros nem mantém um banco de dados próprio.

| HTTP | Exemplo de erro | Próxima ação |
| --- | --- | --- |
| 400 | `https_required` ou `invalid_range` | Usar HTTPS e conferir o período e os parâmetros. |
| 401 | `unauthorized` | Conferir o token do app no cabeçalho privado. |
| 503 | `setup_required` | Conferir os quatro secrets na hospedagem. |
| 502 | `snapshot_too_large` | Reduzir período ou conexões e tentar novamente. |
| 502 | `provider_unavailable` | A consulta não foi concluída; conferir conexão/autorização no portal e tentar mais tarde. |

Todos os JSONs usam `Cache-Control: no-store`. Não há método de gravação ou pagamento; métodos diferentes de GET são recusados.

## Contrato JSON, versão 1

| Campo raiz | Significado |
| --- | --- |
| `schemaVersion` | Inteiro `1`. |
| `provider` | `"pluggy"`. |
| `observedAt` | Instante UTC em que a consulta foi concluída, em ISO 8601. |
| `range` | `{ "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }`. |
| `connections` | Conexões consultadas, identificador externo, status e atualização do provedor quando disponível. |
| `accounts` | Contas bancárias ou de crédito normalizadas. |
| `transactions` | Transações normalizadas, mantendo o status de confirmação. |

### Contas

Cada conta inclui `externalId`, `itemId`, `name`, `kind` (`BANK` ou `CREDIT`), `currency` (`BRL`) e `reportedBalanceCents`.

**`reportedBalanceCents` é um saldo informado, não uma entrada nem um saldo inicial a ser aplicado automaticamente.** Pode ser negativo. Uma conta de crédito não deve compor o saldo bancário como se fosse dinheiro disponível.

### Transações

| Campo | Regra |
| --- | --- |
| `externalId`, `externalAccountId`, `itemId` | Identidades da transação, conta e conexão de origem. |
| `providerTransactionId` | `providerId` da instituição, ou `null`. |
| `identityKind` | `providerId` quando disponível; caso contrário, `id`. |
| `sourceKey` | Prefixo `pluggy:` seguido de SHA-256 da identidade de origem. |
| `direction` | `income` para `CREDIT`; `expense` para `DEBIT`. |
| `status` | `POSTED` ou `PENDING`, sem converter pendência em confirmação. |
| `amountCents` | String de centavos positivos; a direção é informada separadamente. |
| `date` | Dia literal da data informada pelo provedor, `YYYY-MM-DD`. |
| `sourceDate` | Valor original da data, para auditoria e tratamento explícito pelo futuro cliente. |
| `description` | Descrição normalizada e limitada. |
| `method` | `pix` apenas com metadado explícito; `other` nos demais casos. |

Valores monetários são **strings**, como `"18547"`, para representar R$ 185,47 sem cálculo financeiro em ponto flutuante. O normalizador rejeita valores ausentes, inválidos, fora do limite, moedas diferentes de BRL e casas decimais que exigiriam arredondamento. Nenhum valor ausente vira zero automaticamente.

O campo `date` **não converte meia-noite UTC para a madrugada do dia anterior no Brasil**: preserva o prefixo de calendário da origem. Isso é diferente de calcular o dia atual GMT-3 para o período padrão de consulta.

## Consultas ao provedor e limites

- Host fixo `https://api.pluggy.ai`, sem aceitar URL arbitrária do cliente ou redirecionamentos do provedor.
- `POST /auth` com as credenciais privadas; a chave resultante é usada em memória na consulta.
- `GET /items/{id}`, `GET /accounts?itemId=...` e `GET /v2/transactions?accountId=...&dateFrom=...&dateTo=...`.
- A paginação aceita apenas a query documentada em `next`, preservando conta e período. Não interpreta `next` como uma URL externa nem como o valor inteiro de `after`.
- Até cinco itens configurados, **40 requisições ao provedor** por snapshot e **20 páginas de transações por conta**.
- Até **100 linhas brutas de contas** e **5.000 linhas brutas de transações**, incluindo repetições. Até **2 MiB por resposta** e **8 MiB somados por snapshot**, incluindo autenticação e metadados.
- Até **15 segundos por requisição** e **45 segundos no total**. Exceder os limites encerra a consulta sem resultado parcial. Reduza o período ou as conexões em uma nova tentativa.
- `/accounts` não possui paginação documentada nesse contrato. Se o provedor indicar uma lista truncada, a consulta falha em vez de apresentar totais incompletos.
- O serviço não cadastra webhook, não agenda atualização e não solicita sincronização forçada dos itens. O Meu Pluggy atualiza as conexões a cada 24 horas; a consulta apenas lê o que já estiver disponível.

Os limites locais mantêm a consulta previsível; não substituem os limites operacionais, de plano ou de disponibilidade da Pluggy e da hospedagem.

## Identidade e futura gravação no Android

`sourceKey` inclui item, conta, tipo de identificador e `providerId` ou `id`. Repetições idênticas na mesma origem são consolidadas. O mesmo identificador bruto não pode mudar de identidade bancária na mesma consulta; um novo identificador bruto com o mesmo `providerId` só é consolidado se os dados normalizados concordarem. Conflitos encerram o snapshot, sem escolher silenciosamente um dos valores.

Excluir e recriar uma conexão pode mudar seus identificadores. CSV, OFX e notificações também não compartilham necessariamente a mesma identidade. Esses casos precisam de reconciliação; igualdade de data e valor não prova duplicidade.

Antes de gravar qualquer dado no aplicativo, a próxima implementação precisa de cliente HTTPS, token protegido por Android Keystore, vinculação explícita de contas/cartões, período inicial, prévia e revisão de duplicidades, tratamento de pendências e gravação atômica no SQLite. Sincronização agendada compatível com 24 horas ainda será desenvolvida. Não transformar o saldo informado em entrada e não contar o mesmo histórico duas vezes.

## Referências

- [Guia pessoal Meu Pluggy](https://docs.pluggy.ai/en/docs/guides/meu-pluggy-personal-use.md)
- [Autenticação Pluggy](https://docs.pluggy.ai/en/docs/authentication.md)
- [OpenAPI Pluggy](https://docs.pluggy.ai/openapi/pluggy-api.json)
- [Cloudflare: configuração de secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
- [Cloudflare: preços e limites de planos](https://developers.cloudflare.com/workers/platform/pricing/)
