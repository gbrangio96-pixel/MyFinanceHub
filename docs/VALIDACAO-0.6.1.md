# Validação 0.6.1

Validação em 22/10/2026, a partir da 0.6.0/commit `281d725`. Pacote `br.com.myfinancehub`, código 10, APK release sem depuração. [Registro estruturado](../artifacts/validation-0.6.1.json).

## Resultado

`./scripts/verify.sh` aprovou **394 testes**, zero falhas, erros ou ignorados, e concluiu builds/lint debug e release. Após remover recursos obsoletos, ajustar rótulos de contagem e documentar o motivo de persistir a sessão sincronamente, o empacotamento/lint final também terminou: **No issues found.** nas duas variantes. A assinatura preservada, o alinhamento e `debuggable=false` foram verificados no arquivo final.

## Sessão de dez minutos

A sessão inicia uma tolerância de 600.000 ms quando o aplicativo realmente vai ao segundo plano. Retornar antes do prazo dispensa o login; aos dez minutos completos, o conteúdo financeiro continua oculto e a autenticação é exigida. Enquanto o usuário está no aplicativo a sessão permanece ativa. Navegação entre telas e rotação não encerram a sessão. Bloquear aplicativo e recuperar a senha invalidam o acesso imediatamente.

Uma concessão local, sem senha ou credenciais, é persistida fora do backup financeiro. Ela usa tempo monotônico e contador de boot do Android. O processo pode ser recriado no mesmo boot durante a tolerância; a restauração respeita o prazo restante. Reinício, relógio monotônico retrocedido ou concessão inválida são recusados. Se o contador de boot não puder ser lido, a tolerância continua no processo atual, mas a restauração é recusada por segurança. O login inicial não é repetido quando o launcher encontra uma sessão válida.

Nove testes determinísticos de sessão verificam limites exatos, retorno, uso ativo, recriação de processo, reinício/rollback, bloqueio manual e ausência de dados de sessão no backup. Dois testes adicionais de launcher verificam que a sessão válida abre o painel sem formulário e a expirada mantém a senha. Os testes de ciclo de vida executam `restart()`, incluindo o `performStart` correto do Android, para simular o retorno de uma atividade parada.

## Dados Meu Pluggy no aplicativo

A tela principal deixa de ser um formulário de configuração com acesso ao portal e passa a ser um painel nativo: contas e cartões com valores informados, horário da consulta, período efetivo dos dados, movimentações e filtros por conta/situação, além de busca. O acesso está no Painel → Contas conectadas e em Ajustes → Meu Pluggy.

Configurar conexão abre um diálogo nativo protegido; Salvar e consultar chama a API. Atualizar dados e a entrada em uma tela configurada também consultam o cliente pessoal existente. Nenhuma consulta abre navegador ou WebView. O guia de configuração explica a autorização e o espelhamento dos itens no ambiente oficial do provedor; esses passos iniciais continuam necessários para obter credenciais/IDs válidos. A leitura e apresentação de contas/transações acontecem no app.

O cliente de produção continua usando somente `https://api.pluggy.ai`, TLS verificado e consultas limitadas. A integração de UI foi exercitada com o cliente real e transporte injetado: autenticação, item, contas bancárias/crédito e transações POSTED/PENDING retornam dados que são exibidos em componentes Android. O teste comprova ausência de Intent externo, presença dos valores, filtros e prazo/horário. Nova consulta com erro mantém o resultado anterior e seu período/horário, sem mostrar dados antigos como atualizados.

Consultar não grava finanças nem altera saldos locais. Pendências permanecem somente para leitura. POSTED exige revisão, vínculo de conta/cartão e Confirmar e salvar; a proteção contra origens repetidas foi preservada. Credenciais já salvas permanecem no mesmo cofre criptografado do aparelho.

A experiência de centralizar contas e movimentações tomou o [S1NC, apresentação oficial na Play Store](https://play.google.com/store/apps/details?id=com.w1consultoria.app) como referência funcional. Marca, código e recursos próprios do MyFinanceHub foram mantidos; não foi assumida atualização em tempo real ou alteradas as condições do Meu Pluggy.

## Preservação e artefato

Nenhuma alteração no schema/banco ou formato de backup v4. Relatórios, cotações, notificações, B3 e organização dos investimentos continuam cobertos pela suíte. Nenhuma chave, cache ou finança real foi incluída no Git/APK.

- APK: [MyFinanceHub-0.6.1-release.apk](../artifacts/MyFinanceHub-0.6.1-release.apk), 196088 bytes.
- SHA-256: `8384f5e9b77d58d515e4e5b83da43f08ae59f3a285ff5c2a03235df24dad6513`.
- Certificado SHA-256: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, igual ao da 0.6.0 e versões anteriores.
- `apksigner verify`, `zipalign -c 4` e ausência de depuração aprovados.
- Android mínimo 8.0/API26, target 35. Código 10.

O certificado de desenvolvimento anterior foi preservado para atualização por cima da instalação existente; não houve publicação na Play Store. Instale sem desinstalar e mantenha seu backup. A primeira autenticação após atualizar pode ser necessária para criar a nova tolerância de sessão.

Não foram utilizadas as credenciais do usuário nem consultados bancos/dados reais nesta validação. O teste é offline com dados fictícios e não comprova a cobertura de cada banco. Sensor biométrico e instalação física no S22 Ultra não foram testados nesta execução. As credenciais e autorizações pessoais serão usadas somente no aparelho após configuração.
