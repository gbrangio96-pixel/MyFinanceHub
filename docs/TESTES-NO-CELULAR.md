# Testar o MyFinanceHub 0.5.0 no Android

Este roteiro complementa os testes automatizados e não representa verificações já executadas no Samsung S22 Ultra. Use uma base de teste ou faça backup antes de alterar dados reais. APK único: `artifacts/MyFinanceHub-0.5.0-debug.apk`, Android 8.0 ou superior, código de versão 7.

## Atualização e preservação de dados

1. Na versão instalada, salve um backup JSON se ela abrir. Instale a 0.5.0 por cima, **sem desinstalar**. O pacote e a assinatura de desenvolvimento são mantidos.
2. Confira contas, saldos, categorias, cartões, lançamentos, investimentos e posição B3 existentes. Banco/backup passa a v3 com migração aditiva de v1/v2; não volte a um aplicativo antigo com esse banco.
3. Lançamentos já confirmados a partir de notificações em versões antigas devem continuar existindo. A captura e seus controles foram removidos; o aplicativo não deve pedir acesso a notificações, contatos, SMS ou microfone.
4. Mantenha as proteções do aparelho ativadas. Se houver bloqueio de instalação, anote a mensagem e preserve a instalação atual, sem desinstalar nem contornar a proteção.

## Fluxo e navegação

1. Confira as cinco abas: **Painel**, **Lançamentos**, **Relatórios**, **Carteira**, **Ajustes**. Painel mantém saldo e investimentos; criação e importação ficam em Lançamentos.
2. Em uma conta de teste com saldo inicial de R$ 1.000, registre entrada de R$ 500 e saída por Pix de R$ 120,50. Confira saldo de R$ 1.379,50 no Painel e na conta. Edite a saída para R$ 100: saldo de R$ 1.400. Exclua essa saída: saldo de R$ 1.500.
3. Use **Lançamentos → Gerenciar categorias**. Crie, busque, renomeie e exclua uma categoria. Seus lançamentos devem permanecer sem categoria. Teste **+ Nova categoria** dentro de um lançamento: descrição, valor e data não devem se perder.
4. Cadastre um cartão e uma compra de R$ 80 no crédito: o saldo permanece R$ 1.500. Registre saída de R$ 80 com **Pagamento de fatura**: saldo de R$ 1.420. O resultado registra R$ 80 de consumo, sem duplicar a fatura.
5. Faça uma transferência de R$ 100 entre duas contas de teste, com saída/entrada e forma **Transferência entre minhas contas**. O saldo somado deve se manter; o resultado não deve ganhar receita ou despesa de consumo.
6. Busque lançamentos, role, alterne abas e retorne. Confira busca e rolagem preservadas, incluindo Categorias. Acima de 30 resultados, **Mostrar mais** deve revelar os próximos sem duplicar registros.
7. Feche, reabra e gire o aparelho. Confira dados, filtros, teclado, calendário e navegação.

## Relatórios

1. Abra **Relatórios** e confira datas, entradas, despesas e resultado do mês. Compare com lançamentos conhecidos; crédito entra, faturas/transferências próprias ficam fora do resultado.
2. Troque para **Últimos 3 meses**, **Este ano** e **Personalizado**. As datas são inclusivas; confirme registros exatamente no primeiro/último dia. Um intervalo invertido ou maior que 120 meses deve mostrar erro, sem alterar dados.
3. Filtre uma conta e depois **Todas as contas**. Confira que transferências mudam a movimentação de caixa individual, mas não viram consumo. Resultado não deve ser confundido com saldo inicial/final.
4. Confira gastos por categoria, categorias sem vínculo e meses sem movimento. A comparação na tela mostra até os últimos 12 meses; o PDF deve incluir o período completo.
5. Abra **Lançamentos → Ver relatório do mês** e confira o mesmo mês selecionado em Lançamentos. Volte a Relatórios depois de girar o aparelho: período e conta devem ser preservados.
6. Use **Salvar PDF** e escolha um destino. Abra o arquivo em um leitor e confira acentos, páginas, período, conta, totais e todos os lançamentos. Textos longos devem quebrar linha; nenhum lançamento pode sumir. Acima de 5.000, a opção PDF deve orientar a reduzir o período ou usar CSV.
7. Use **Exportar CSV** e abra em uma planilha. Confira registros do filtro, tipos, valores, categorias, conta, cartão, método e indicação de inclusão no resultado. O CSV é tabela de consulta, não restauração completa da base.
8. Use **Compartilhar PDF** e escolha um app disponível. Confira a prévia e cancele o envio se não quiser transmitir dados. O compartilhamento deve oferecer aquele PDF para leitura.
9. Ative **Ocultar valores**, tente exportar e confira **Exportar valores visíveis?**. Cancelar não deve abrir o seletor nem compartilhar. Continuar exporta os números completos com autorização explícita.
10. No PDF de todas as contas, confira que investimentos são posição atual, fora do resultado do período, e B3 fica separada. Não deve haver rentabilidade B3 calculada sem custo.

## Investimentos e cotações

1. Cadastre R$ 1.000 aplicados e R$ 1.100 atuais em um investimento. Confira variação de R$ 100 e rentabilidade simples de 10% no Painel/Carteira. Saldo da conta não muda. Com aplicado zero, o percentual deve ficar indisponível.
2. Com internet, abra **Carteira → Cotações e atualização**. Sem chave, pesquise PETR4 e consulte o preço. Confira fonte brapi e horário do dado; a interface não deve prometer tempo real.
3. Pesquise um FII, ETF, BDR e unit, como HGLG11, BOVA11, AAPL34 e TAEE11. A pesquisa pode encontrá-los sem chave; consultar preços exige chave/plano quando não são ativos de teste. Não informe chave na conversa; configure somente no app.
4. Para testar atualização sem chave, crie um investimento com código PETR4 e quantidade 10, marque **Atualizar este investimento por cotação** e salve. Use **Atualizar investimentos agora**. Confira quantidade × preço BRL arredondado para centavos, horário e total atualizado; valor aplicado não deve mudar.
5. Desmarque a atualização desse investimento, informe um valor manual e consulte novamente. A rodada não deve substituir esse valor. Teste quantidade fracionária válida e rejeição de quantidades inválidas antes de salvar.
6. Confira que **Atualizar ao abrir a carteira** começa desligado. Ative e salve; abra Carteira/Painel/Posição B3. A consulta automática deve respeitar uma hora entre tentativas. Não deve haver exigência de serviço permanente nem promessa de atualização com o app fechado.
7. Desligue a internet e tente atualizar. Valores e horários anteriores devem permanecer; o erro deve ser compreensível. Repita com chave inválida ou sem cobertura do plano, sem enviar segredo nem exibir chave em mensagens de erro.
8. Se tiver plano compatível, escolha **Criptomoedas**, pesquise BTC e consulte o preço em BRL. Cadastre quantidade e código `BTC-BRL` para testar atualização, sem confundir com um ativo B3. Sem chave/plano, deve haver erro, sem inventar preço.
9. Confira que Tesouro, CDB, LCI, LCA e instrumentos sem preço compatível continuam manuais. Uma taxa CDI não pode substituir seu valor real.
10. Importe uma posição B3 de teste. Após consultar cotações, o valor original do arquivo e sua data devem permanecer; a estimativa separada usa a quantidade importada. Confira cobertura parcial e horário das referências; não deve haver custo ou rentabilidade B3 presumidos.

## Aparência e acessibilidade

1. Em **Ajustes → Aparência**, selecione Claro, depois Escuro e visite as cinco abas, Categorias, Posição B3 e Cotações. Abra formulários, calendários, seletores e confirmações sem concluir exclusões.
2. Aumente a fonte do Android e repita os caminhos principais nos dois temas. Confira rótulos, valores, exportações e botões alcançáveis por rolagem, inclusive com teclado.
3. Ative **Ocultar valores**: confira Painel, Carteira, Lançamentos, Relatórios e posições. Com leitor de tela, o valor também deve estar mascarado. Trocar tema não pode reexibir números.
4. Selecione **Seguir o sistema** e alterne o modo do Android; o app e diálogos devem acompanhar. A escolha manual de tema deve persistir após fechar e abrir.
5. Mudar aparência não altera dados financeiros. Preferência de tema e chave do provedor não pertencem ao backup JSON.

## Arquivos e recuperação

1. Importe `samples/extrato-exemplo.csv` em conta de teste. Confira prévia, datas, sinais e categorias. Repita na mesma conta: nenhuma duplicata nova deve ser criada.
2. Teste OFX em outra conta e CSV de cartão com a opção/cartão corretos. Cancele uma prévia; nada deve ser salvo. Um arquivo inválido deve impedir a importação inteira.
3. Importe datas de outro mês enquanto há busca e filtro Saídas ativos. Ao concluir, Lançamentos deve abrir o mês mais recente importado, busca vazia e filtro Todos. Confira os meses anteriores se o arquivo abranger vários.
4. Importe uma posição B3 XLSX/CSV compatível, confira data, quantidades, instituições e valores. Reimportar substitui o relatório após confirmação, sem acumular cópias ou criar lançamentos. Arquivo inválido mantém o snapshot anterior.
5. Exporte CSV e backup JSON v3. Confira o backup com dados fictícios: quantidade e última cotação aplicada pertencem ao investimento; chave brapi não deve aparecer. Guarde o arquivo em local privado.
6. Faça uma mudança de teste e restaure o JSON. Confira o estado anterior e a posição B3. Um backup inválido deve preservar a base. Backups v1/v2 válidos devem continuar aceitos; backup antigo sem B3 remove o snapshot ao restaurar, conforme a confirmação.

## Registrar problemas

Anote modelo, versão Android, versão do app, tela, ação e mensagem exata. Ao compartilhar exemplos, use nomes, contas, identificadores e valores fictícios, preservando o formato. Nunca envie chave brapi, senha, token ou dados financeiros reais.

A 0.5.0 não captura notificações nem usa WhatsApp. Não sincroniza custódia B3 ou contas bancárias e não faz pagamentos Pix. As consultas opcionais de mercado complementam os dados locais; não substituem o extrato da corretora.
