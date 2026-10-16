# Validação do backend preparatório — 16/10/2026

Esta entrega prepara a consulta de contas, saldos e transações pessoais pelo Meu Pluggy. Não implanta um serviço nem ativa sincronização no Android.

## Backend

`cd backend && npm test` terminou com **54 testes aprovados**, sem falhas, cancelamentos, ignorados ou casos pendentes, em Node.js **24.19.0**. Após a revisão final das identidades, a suíte completa foi executada novamente com o reporter JUnit, também sem falhas. A contagem do runner inclui o grupo de testes de paginação.

Os testes cobrem:

- Autenticação do app antes de consultar o provedor, configuração privada, HTTPS, somente GET e ausência de rotas de pagamento.
- Autenticação na origem fixa da Pluggy; erros sem conteúdo bancário, chaves ou detalhes internos; respostas sem cache.
- Itens e contas permitidos, paginação restrita à conta e ao período, repetição de cursor e falha integral se uma página não terminar.
- Valores exatos em centavos, BRL explícito, datas válidas sem deslocar o dia original, direção CREDIT/DEBIT e separação POSTED/PENDING.
- Identificação de Pix apenas por metadados estruturados; campos de resposta limitados ao contrato.
- Deduplicação por identidade de origem e rejeição de mudanças conflitantes no mesmo identificador bruto ou bancário.
- Limites de 40 requisições, 20 páginas por conta, 100 linhas de contas, 5.000 linhas de transações, 2 MiB por resposta e 8 MiB agregados. O deadline de 45 segundos usa relógio controlado no teste, sem esperar esse tempo.

Todas as respostas financeiras usadas são fictícias, com rede substituída. Não houve chamada autenticada a uma instituição, configuração de secrets reais, conta criada, contrato ou implantação de Worker.

## Android e ambiente

Os 29 arquivos de `app/` distribuídos no ZIP do projeto 0.3.0 foram comparados byte a byte e não tiveram alterações nesta preparação. Não há cliente HTTP ou gravação de dados bancários no aplicativo.

`./scripts/verify.sh` terminou com **BUILD SUCCESSFUL**. Os testes e artefatos Android estavam atualizados e foram reutilizados pelo Gradle; não foram executados novamente do zero. Os relatórios existentes correspondem ao código inalterado:

| Flavor | Aprovados | Ignorados | Falhas/erros | Android Lint |
| --- | ---: | ---: | ---: | --- |
| local | 118 | 18 | 0 | No issues found. |
| notifications | 133 | 3 | 0 | No issues found. |

Ambos os APKs gerados conferem com os checksums dos artefatos 0.3.0 já entregues. Não foi produzido um APK de Open Finance. A validação anterior de aparência e emulador está em [VALIDACAO-0.3.0.md](VALIDACAO-0.3.0.md); não foi repetida nesta etapa.

`./scripts/setup-android.sh` foi repetido com sucesso, confirmando as licenças e os pacotes necessários. A execução exibiu avisos de compatibilidade do formato de metadados do SDK e da pasta de métricas do Android, sem impedir a verificação. O backend não requer instalação de dependências npm.

## Pendências para sincronização real

1. O titular cria a conta Meu Pluggy, confere cobertura e autoriza seus bancos no portal oficial.
2. Configura os quatro secrets privadamente e publica o backend HTTPS. `/health` verifica configuração, não autenticação bancária.
3. Valida uma consulta real sem expor os dados em logs ou conversa.
4. Implementa no Android o cliente HTTPS, armazenamento protegido do token, associação de contas/cartões, revisão de duplicidades, gravação atômica e atualização agendada.

Saldo informado pelo banco não é lançamento ou saldo inicial. Importações CSV/OFX, notificações e Open Finance precisam de reconciliação antes de gravar; valor e data iguais não bastam para decidir duplicidade. Investimentos/B3, pagamentos Pix e histórico geral de carteiras não fazem parte deste backend.
