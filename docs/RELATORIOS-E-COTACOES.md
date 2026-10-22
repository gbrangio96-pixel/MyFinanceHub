# Relatórios, investimentos e cotações

## Consultar e exportar relatórios

Abra a aba **Relatórios**. Escolha período e conta; o resumo destaca entradas, despesas e resultado. Os filtros não alteram os lançamentos. **Ver relatório do mês**, em Lançamentos, abre o relatório do mês que está sendo consultado.

| Filtro | Intervalo |
| --- | --- |
| Este mês | Primeiro ao último dia do mês atual |
| Últimos 3 meses | Dois meses anteriores e o mês atual completo |
| Este ano | 1º de janeiro a 31 de dezembro do ano atual |
| Personalizado | Datas inicial e final escolhidas no calendário |

As duas datas são inclusivas. Registros futuros já cadastrados participam se estiverem no intervalo. O período precisa ter ordem válida e abranger no máximo 120 meses. Conta excluída do cadastro deixa de ser uma opção; o relatório volta a **Todas as contas**.

### Como os números são calculados

| Número | Regra |
| --- | --- |
| Entradas | Entradas de consumo/receita, incluindo estornos cadastrados como entrada no crédito |
| Despesas | Saídas de consumo, incluindo compras no crédito |
| Resultado do período | Entradas menos despesas |
| Gastos por categoria | Despesas de consumo agrupadas pela categoria atual do lançamento |
| Movimento de caixa | Entradas menos saídas que afetam a conta: exclui crédito, inclui faturas e transferências |

**Transferência entre minhas contas** e **Pagamento de fatura** ficam fora das entradas/despesas e do resultado. Assim, pagar uma compra já registrada no cartão não duplica o consumo. A movimentação de caixa aparece em uma seção própria e pode diferir do resultado. Ela não é saldo inicial ou final histórico: o cadastro não contém um histórico de saldos iniciais suficiente para estabelecer esses valores.

Exemplo: entrada de salário R$ 5.000, compra no crédito R$ 180, despesa por Pix R$ 50 e pagamento parcial de fatura R$ 100. O relatório mostra entradas de R$ 5.000, despesas de R$ 230 e resultado de R$ 4.770. A movimentação de caixa é R$ 4.850: salário menos Pix e pagamento da fatura. A compra no crédito não saiu da conta naquele dia.

Transferências próprias devem ter saída na origem e entrada no destino com a forma correta. Em **Todas as contas**, uma transferência completa se anula no caixa; filtrando somente a origem, aparece a saída de caixa, sem virar despesa de consumo. Revise faturas e transferências após importar extratos genéricos.

O app usa centavos inteiros para os totais. Barras e proporções do gráfico não são usadas para calcular valores financeiros. Meses sem lançamentos aparecem com zero. A tela mostra até os últimos 12 meses da comparação; o PDF inclui todos os meses selecionados.

### Arquivos

- **Salvar PDF:** documento A4 paginado com período, conta, resumo, caixa, categorias, comparação mensal e lançamentos detalhados. Aceita até 5.000 lançamentos; textos muito extensos também podem atingir o limite de 1.200 páginas. Acima dos limites, reduza o intervalo ou use CSV. O app não remove registros para fazer o arquivo caber.
- **Compartilhar PDF:** gera o mesmo relatório e abre o seletor de aplicativos do Android. O destinatário escolhido recebe acesso de leitura àquele PDF por uma URI `content://`; o provedor não oferece acesso a outros arquivos ou escrita. A cópia temporária fica no cache privado; cópias com mais de 24 horas são limpas na próxima geração.
- **Exportar CSV:** todos os lançamentos do filtro, em uma única tabela: data, descrição, valor, tipo, categoria, cartão, conta, método, inclusão no resultado e ID. Não tem o limite de 5.000 registros do PDF. Usa UTF-8, separador `;`, aspas e proteção de campos textuais contra fórmulas de planilha. Não substitui o backup JSON nem restaura todos os vínculos da base.

O seletor de documentos do Android permite escolher onde salvar, sem permissão ampla de armazenamento. A exportação usa o relatório selecionado quando a ação começou; não altera a base financeira.

**Ocultar valores** mascara a interface, não os arquivos exportados. Nesse modo, **Exportar valores visíveis?** exige confirmação antes de salvar ou compartilhar. PDF, CSV e JSON não são criptografados pelo app.

Com **Todas as contas**, o PDF acrescenta uma seção de investimentos com os valores atuais cadastrados e a posição B3 importada, em blocos separados. Esses valores não são recortados historicamente pelo período do relatório. No filtro de uma conta, o PDF não associa investimentos que não possuem vínculo com essa conta.

## Organizar os investimentos cadastrados

Em **Carteira → Investimentos**, use **Organizar investimentos por**:

- **Tipo:** agrupa pelo tipo informado no cadastro (CDB, ação, Tesouro etc.); cadastros sem tipo aparecem em **Tipo não informado**.
- **Liquidez:** agrupa em **Diária**, **Até 30 dias**, **Acima de 30 dias**, **No vencimento** e **Não informada**, nessa ordem.
- **Vencimento / validade:** ordena pelas datas mais próximas primeiro; investimentos sem data ficam no final.
- **Valor aplicado (maior primeiro):** ordena pelo valor aplicado em centavos, do maior ao menor. Não usa o valor atual nem a rentabilidade para essa ordem.

Cada posição mostra tipo, total aplicado, valor atual, liquidez e vencimento, além da rentabilidade e da última cotação. A organização escolhida fica salva neste aparelho. Alterar a ordem não altera valores, saldos ou cálculos dos relatórios.

Ao criar ou editar, escolha a **Liquidez** e, se existir, o **Vencimento / validade** no calendário. **Limpar vencimento** remove somente a data do formulário; a alteração é aplicada ao salvar. Datas passadas são aceitas para manter contratos vencidos no cadastro. Liquidez e prazo são informações do contrato, não uma garantia de resgate nem um cálculo de preço.

Os dados antigos recebem **liquidez não informada** e **sem vencimento informado**, sem deduções pelo ticker, preço ou tipo de investimento. O banco/backup v4 adiciona esses campos e preserva os valores, quantidades e cotações anteriores. A restauração aceita backups v1, v2, v3 e v4; backups antigos mantêm os novos campos sem informação. A posição B3 importada continua separada e inalterada.

## Rentabilidade dos investimentos

Para investimentos cadastrados:

```text
Variação em reais = valor atual − total aplicado
Rentabilidade simples (%) = variação ÷ total aplicado × 100
```

Exemplo: R$ 1.000 aplicados e R$ 1.100 atuais indicam R$ 100 e 10%. Quando o aplicado é zero, o percentual fica indisponível. A medida é simples, não anualizada: não considera datas de aportes/resgates, dividendos, taxas ou impostos e não representa o retorno do período escolhido no relatório. O total é a comparação entre as somas dos valores atuais e aplicados, não a média dos percentuais individuais.

O app não extrai custo de aquisição do valor de posição B3. Sem custo conhecido, não calcula rentabilidade B3. Cadastros manuais e posições B3 não são somados, pois podem representar os mesmos ativos.

## Pesquisar preços e atualizar posições

Abra **Carteira → Cotações e atualização**. Selecione **Bolsa B3** para pesquisar código/nome ou **Criptomoedas** para pesquisar uma sigla. Toque em um resultado para consultar seu preço. A pesquisa só identifica o ativo; o preço exibido vem de uma consulta separada com horário próprio.

A pesquisa de bolsa inclui ações, FIIs, ETFs, BDRs, units e outros fundos listados presentes no catálogo do provedor. Criptomoedas usam um código distinto com par em reais, como `BTC-BRL`, sem confundir a sigla com um ativo da B3. Cobertura e autenticação dependem do provedor e do plano; a presença na pesquisa não garante autorização para obter preço.

Para usar uma cotação em um investimento cadastrado:

1. Edite ou crie o investimento em **Carteira**.
2. Informe seu código e a quantidade efetivamente possuída.
3. Marque **Atualizar este investimento por cotação** e salve.
4. Abra **Cotações e atualização** e use **Atualizar investimentos agora** para uma rodada manual.
5. Para rodadas automáticas, habilite **Atualizar ao abrir a carteira** e use **Salvar configuração**.

O valor atual passa a ser **quantidade × preço unitário em BRL**, arredondado em centavos. O custo aplicado não muda. Quantidade admite até oito casas decimais, preço até doze; a multiplicação usa decimais exatos, sem ponto flutuante monetário. Para voltar ao valor manual, desmarque a atualização e informe o valor desejado.

A atualização global começa desligada. Quando habilitada, o app consulta ao abrir ou permanecer nas telas Painel, Carteira ou Posição B3, respeitando uma hora desde a tentativa anterior. Não há serviço ou tarefa agendada com o aplicativo fechado. A rodada manual também respeita a marcação de cada investimento, mas pode ser acionada antes da próxima rodada automática.

Cada rodada consulta sequencialmente até 200 códigos distintos, uma vez por código mesmo que apareça em várias posições. Essa limitação atende o plano gratuito de uma consulta simultânea e um ativo por requisição; carteiras maiores podem ter cobertura parcial. Falhas de rede, autenticação, limite ou resposta inválida preservam os valores anteriores. O horário mostrado continua sendo o horário daquela cotação, não o momento da última tentativa.

Se o provedor devolver outro código por renomeação ou conversão societária, o app não aplica o preço automaticamente. Revise ativo e quantidade: um evento societário pode mudar a quantidade de unidades necessária para valorar a posição corretamente.

### Posição B3 e estimativa

O snapshot importado mantém data, quantidade e valores originais. Quando há cotação compatível armazenada, uma **estimativa separada** usa a quantidade importada e esse preço. O resumo informa cobertura e horário mais antigo das cotações usadas. Isso não atualiza custódia, não corrige quantidades por operações posteriores e não produz custo ou rentabilidade.

Reimporte o relatório B3 quando houver mudanças na posição. Valores de referência do arquivo, estimativa de mercado, investimentos cadastrados e saldo das contas são informações separadas. Renda fixa sem preço compatível não deve aparecer como valoração completa apenas porque outros ativos foram cotados.

### Cobertura e chave

Sem chave, a brapi permite consultar PETR4, VALE3, ITUB4 e MGLU3 para testes. Outros ativos exigem chave válida e plano compatível. Criptomoedas exigem Startup ou Pro. Obtenha sua própria chave no [dashboard oficial](https://brapi.dev/dashboard), informe-a no campo **Chave brapi (opcional)** e salve. Nenhuma contratação, cobrança ou conta paga é criada pelo app.

| Plano brapi | Referência de atraso de bolsa | Requisições por mês | Ativos por chamada / simultaneidade |
| --- | --- | --- | --- |
| Gratuito | Aproximadamente 30 minutos | 15.000 | 1 / 1 |
| Startup | Aproximadamente 15 minutos | 150.000 | 10 / 4 |
| Pro | Aproximadamente 5 minutos | 500.000 | 20 / 16 |

Referência consultada em 21/10/2026; o provedor pode alterar planos. O app usa uma chamada por código mesmo em planos maiores. Consultar a cada segundo não elimina o atraso. **Os planos públicos não oferecem preço de bolsa em tempo real.** Criptomoedas podem retornar a referência do último dia completo em UTC se a cotação atual estiver indisponível; confira sempre o horário do dado.

Tesouro Direto e renda fixa privada — CDB, LCI, LCA e contratos semelhantes — permanecem com valor manual nesta integração. A brapi possui endpoints próprios de Tesouro com dados diários indicativos, mas eles não estão conectados ao atualizador desta versão. Uma taxa CDI, Selic ou percentual contratado isolado não estabelece o preço de resgate de cada instrumento.

### Rede, privacidade e backup

As consultas opcionais usam HTTPS para `brapi.dev`; a chave segue no header `Authorization`, nunca na URL. O app envia código/termo de busca e chave configurada, sem enviar quantidades, custos, lançamentos ou saldos. A conexão expõe ao provedor os metadados usuais, como IP. Não há integração com notificações, WhatsApp ou bancos nesse fluxo.

A chave fica nas preferências privadas do app, sem criptografia própria, e não é incluída no backup financeiro, nos relatórios ou no código distribuído. Configuração global e cache de preços também ficam fora do JSON. Após restaurar em outro aparelho, configure a chave novamente. Quantidade, habilitação individual e última cotação aplicada a um investimento são dados financeiros preservados pelo backup v4 (com restauração dos formatos anteriores).

As finanças continuam disponíveis sem internet. Em modo offline, o app mantém as posições com seus últimos valores e datas; a ausência de uma consulta nova não confirma que o preço antigo continua atual.

## Fontes oficiais

- [brapi: autenticação e limites](https://brapi.dev/docs/authentication)
- [brapi: planos e preços](https://brapi.dev/pricing)
- [brapi: atraso de dados entre planos](https://brapi.dev/faq/qual-a-diferenca-real-no-atraso-delay-dos-dados-entre-os-planos)
- [brapi: cotação B3 v2](https://brapi.dev/docs/acoes/cotacao)
- [brapi: pesquisa de tickers](https://brapi.dev/docs/tickers)
- [brapi: criptomoedas](https://brapi.dev/docs/criptomoedas)
- [brapi: Tesouro Direto e frequência diária](https://brapi.dev/docs/tesouro-direto)
- [Área do Investidor B3](https://www.investidor.b3.com.br/)
