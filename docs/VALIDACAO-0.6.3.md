# Validação 0.6.3

Validação em 23/10/2026, a partir da 0.6.2/commit `241120e`. Pacote `br.com.myfinancehub`, código 12. [Registro estruturado](../artifacts/validation-0.6.3.json).

## Resultado

`./scripts/verify.sh --no-build-cache` aprovou **435 testes**, sem falhas, erros ou ignorados. Após mover cinco textos sem alteração de conteúdo para recursos Android, lint e empacotamento debug/release foram concluídos novamente. Lint final nas duas variantes: **No issues found.** Assinatura, alinhamento e ausência de depuração foram conferidos no APK final.

- APK: [MyFinanceHub-0.6.3-release.apk](../artifacts/MyFinanceHub-0.6.3-release.apk), 204628 bytes.
- SHA-256: `280c6619aeed07bd5309b2bd50aeb4818e158efa8d67035c80a8718911975605`.
- Certificado SHA-256 preservado: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`.
- Android mínimo API26, target35; código 12.

## Correção biométrica

A operação AES-GCM da chave com autenticação por uso agora recebe AAD e a prova somente após o sucesso do `BiometricPrompt.CryptoObject`. Antes, AAD era enviado antes da autenticação, podendo ser recusado pelo Android Keystore. O callback continua exigindo a mesma instância do cipher e a prova autenticada correta. Bytes de AAD, prova, aliases e preferências permanecem compatíveis com a configuração anterior; não há autorização por um booleano ou callback isolado.

Uma chave ausente, alterada ou incapaz de verificar a prova invalida a concessão correspondente. O login com senha passa a oferecer uma nova ativação. Cancelamento/indisponibilidade temporária não apagam uma configuração válida. Mensagens explicam a indisponibilidade de biometria forte e o uso da senha. Falha de ativação oferece tentar novamente ou continuar com o acesso por senha já validado.

Pausar a atividade por causa da janela biométrica do sistema não cancela o prompt. A saída efetiva para segundo plano (`onStop`), destruição da atividade ou tela apagada cancelam a operação. O sucesso enquanto a atividade está pausada é enfileirado e só pode liberar a interface no retorno, com a geração da sessão, o estado do aparelho e o prazo de dez minutos ainda válidos. Resultados antigos, duplicados, com cipher substituído ou prova alterada são recusados.

## Meu Pluggy: ajuda e limitação da conexão automática

A tela nativa e a configuração oferecem **Onde encontrar os dados da conexão**. O guia mostra Applications → Client ID; Client Secret exibido somente ao gerar; Demo → Connect Account → MeuPluggy; Connected Items → itemId. Também informa que gerar um novo secret invalida o anterior. Links abrem somente após toque explícito e apontam para Meu Pluggy, Dashboard e guia oficial em português, sem dados pessoais em parâmetros ou extras. A consulta financeira continua dentro do app pela mesma API; nenhum saldo ou lançamento precisa ser digitado para fazer a leitura.

O guia não apresenta conexão automática como concluída. Uma conta pessoal Meu Pluggy não autoriza o app por login OAuth, e `GET /v2/items` não está disponível nesse plano. Retirar os campos de desenvolvedor exige projeto Pluggy Connect, servidor com chaves privadas, registro das conexões retornadas e pareamento seguro do aparelho. Não há servidor/credenciais configurados nesta execução. Não foi criado serviço, contratado plano, embutida chave no APK ou usado um projeto de terceiros. As autorizações iniciais dos bancos no caminho pessoal continuam no Meu Pluggy.

As referências oficiais foram consultadas em 23/10/2026: [uso pessoal](https://docs.pluggy.ai/en/docs/guides/meu-pluggy-personal-use.md), [Dashboard](https://docs.pluggy.ai/en/docs/guides/dashboard-connectors.md), [Pluggy Connect](https://docs.pluggy.ai/en/docs/connect-widget/introduction.md) e [autenticação](https://docs.pluggy.ai/en/docs/authentication.md). A documentação de configuração completa está em [OPEN-FINANCE.md](OPEN-FINANCE.md).

## Geração explícita de Connect Token

A configuração inclui **Gerar Connect Token**. Usa Client ID/Secret informados no aparelho ou já guardados no cofre; não exige itemId, não salva os campos digitados por essa ação e não modifica lançamentos. O cliente autentica no host HTTPS fixo por `POST /auth` e solicita `POST /connect_token` com objeto JSON `{}`, usando a apiKey somente no cabeçalho da segunda chamada. O resultado validado é mostrado temporariamente em diálogo protegido, com cópia sensível, limite local de trinta minutos e limpeza ao sair/bloquear.

O token não libera consultas de transações nem substitui o projeto de integração. A emissão de token real requer chaves próprias no aparelho; não havia essas credenciais nesta execução. Os testes usam transporte injetado e respostas fictícias, sem emitir grants reais. Nenhuma conta bancária é criada e nenhuma despesa é gravada ao gerar o token.

## Verificações e limites

Os testes usam um provider criptográfico de regressão que recusa escrita de AAD/dados antes da autorização simulada e executa AES-GCM real após ela. O protocolo é exercitado em API28 e API35. Também verificam reativação após prova inválida, cancelamento, callback duplicado, cipher diferente, pausa versus segundo plano, sucesso pendente no bloqueio/prazo expirado e preservação da senha.

Os testes de UI do Pluggy verificam o conteúdo do guia, links oficiais sem abertura automática, ausência de credenciais nos intents e recusa de botões retidos após bloquear a tela. A suíte existente continua cobrindo leitura da API com transporte injetado, revisão obrigatória de despesas, preservação de dados, relatórios, cotações, notificações e investimentos.

Schema e backup permanecem v4. Não há alterações de saldos, lançamentos ou credenciais pessoais Pluggy já salvas. O bloqueio ao apagar a tela ou após dez minutos fora do app, o que ocorrer primeiro, é preservado; recriar o processo exige login. A assinatura anterior permite instalar por cima sem desinstalar.

Não foi feito teste físico no Samsung ou em sensor biométrico. Os testes não substituem validação do Keystore e das particularidades do aparelho do usuário. Não foram consultados bancos ou credenciais reais. A correção foi validada por testes automatizados e revisão do protocolo; publicação no GitHub não constitui publicação na Play Store.
