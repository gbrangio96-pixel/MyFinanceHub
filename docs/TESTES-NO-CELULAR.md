# Testar o MyFinanceHub 0.3.0 no Android

Use dados de teste antes de importar extratos reais. Esta validação física depende do seu aparelho e complementa os testes automatizados; o roteiro não representa testes já executados no S22 Ultra. O APK padrão é `artifacts/MyFinanceHub-0.3.0-local-debug.apk`, sem leitura de notificações. Há também `artifacts/MyFinanceHub-0.3.0-notifications-debug.apk`, com o módulo experimental. Ambos oferecem a mesma organização de telas e os modos claro, escuro e conforme o sistema.

## Atualização opcional e proteção do aparelho

Exporte um backup na versão atual se ela abrir e instale por cima, **sem desinstalar**. A 0.3.0 mantém o pacote, a assinatura de desenvolvimento, o banco financeiro e o formato do backup; o código de versão é 5. Depois de atualizar, confira contas, lançamentos, categorias, cartões e investimentos antes de continuar.

**Escolha um dos APKs.** `local` e `notifications` atualizam o mesmo app, usam os mesmos dados locais e não podem ser instalados lado a lado. Mudar de edição não cria uma segunda base financeira. A edição local não mostra controles de captura, mesmo se essa opção foi usada em uma versão anterior.

No S22 Ultra, o usuário relatou que o Play Protect bloqueou a 0.2.0 e que ela instalou após pausar a proteção. **Mantenha o Play Protect ativado.** A 0.3.0 ainda precisa de validação nesse celular, e sua aceitação pela proteção não é garantida. Se houver bloqueio, anote a mensagem e preserve a instalação atual; não desative proteções nem altere configurações restritas para contorná-lo.

## Fluxo principal

1. Instale em Android 8.0 ou superior. Confirme que abre sem login e sem permissões de internet, contatos ou SMS. No APK local, não deve haver controles de captura nem solicitação de acesso a notificações.
2. Em **Carteira**, edite “Principal” para saldo inicial de R$ 1.000,00. Crie outra conta.
3. Em **Lançamentos**, cadastre uma entrada de R$ 500,00 e uma saída de R$ 120,50 na Principal. Confira saldo de **R$ 1.379,50** no Painel e na conta. Entradas, saídas e gráfico de despesas por categoria devem aparecer em Lançamentos.
4. Edite a saída para R$ 100,00: saldo R$ 1.400,00. Exclua a saída: saldo R$ 1.500,00.
5. Abra a aba **Categorias**. Crie, busque, renomeie pelo botão **Editar** e exclua pelo botão **Excluir**. Um lançamento vinculado deve continuar existindo, sem categoria. Teste também **+ Nova categoria** dentro de um lançamento e confirme que descrição/valor/data são preservados.
6. Cadastre um cartão. Registre uma compra de R$ 80,00 no Crédito; o saldo da conta não muda. Registre uma saída de R$ 80,00 com **Pagamento de fatura**. O saldo cai para R$ 1.420,00 e os totais de Lançamentos não contam a compra duas vezes.
7. Registre R$ 200,00 aplicados e R$ 210,00 atuais em um investimento. Confira no Painel e na Carteira o aplicado, o valor atual e a variação de R$ 10,00. O saldo da conta permanece independente. O Painel deve reunir saldo e investimentos; adicionar, importar, totais mensais e gráfico de categorias ficam em Lançamentos.
8. Em Lançamentos, busque uma descrição, role a lista e alterne para Carteira e de volta. Confira que busca e posição são preservadas. Faça o mesmo na busca de Categorias. Com mais de 30 resultados, use **Mostrar mais** e confira que os próximos registros aparecem sem repetir ou excluir os anteriores.
9. Feche e reabra o app. Gire o celular e alterne as abas. Confira persistência dos dados, teclado, datas e valores ocultos.

## Aparência e acessibilidade

1. Em **Ajustes → Aparência**, selecione **Claro**. Confira Painel, Lançamentos, Carteira, Categorias e Ajustes: textos, valores, filtros, botões e gráfico devem continuar legíveis. Categorias cadastradas devem continuar presentes, inclusive após limpar a busca.
2. Selecione **Escuro** e repita a visita às cinco abas. Abra um lançamento, as opções de categoria/conta/forma de pagamento, o calendário e uma confirmação de exclusão; confira os campos, as listas dos seletores e os botões sem confirmar a exclusão. Nenhum formulário deve ficar com texto ilegível sobre o fundo.
3. Ative **Ocultar valores** e confira saldo, aplicado, valor atual e variação no Painel, além dos valores em Carteira e Lançamentos. Troque o tema; os valores devem continuar ocultos até usar **Mostrar valores**.
4. Feche o app e abra novamente: **Claro** ou **Escuro** deve continuar escolhido. Mude para **Seguir o sistema** e alterne o modo do Android; o app deve acompanhar. Reabra formulários, seletores e calendário para conferir também os componentes nativos.
5. Aumente o tamanho da fonte nas configurações do Android e repita os caminhos principais nos dois temas. Confira se rótulos, valores e botões permanecem legíveis e se é possível rolar até **Salvar** e **Cancelar**, com o teclado aberto. Depois, restaure sua preferência de fonte.
6. Exporte um backup financeiro antes e depois de mudar a aparência, sem editar os dados. Contas, categorias, cartões, lançamentos e investimentos devem permanecer iguais. O modo de aparência é uma preferência local e não faz parte do JSON; restaurar dados financeiros não serve para transferir esse modo a outro aparelho.

## Arquivos e recuperação

1. Use o destaque **Importar extrato** em Lançamentos para importar `samples/extrato-exemplo.csv` em uma conta de testes. Confira também o acesso em Ajustes. Revise o período mostrado na prévia, datas, sinais, valores e categorias antes de confirmar.
2. Importe o arquivo novamente na mesma conta: nenhuma duplicata nova deve ser criada.
3. Teste o OFX de exemplo em outra conta. Teste um extrato real pequeno antes de importar todo o histórico. Para CSV de cartão, marque a opção e selecione o cartão correto.
4. Teste um arquivo com datas de um mês diferente do selecionado, mantendo uma busca e o filtro **Saídas** ativos antes da importação. Depois de confirmar, Lançamentos deve abrir o mês mais recente do arquivo, sem busca e com o filtro **Todos**, mostrando os registros desse mês. Se houver vários meses no arquivo, navegue pelos meses anteriores para conferir os demais.
5. Repita a prévia e a conclusão nos modos Claro e Escuro. Cancele uma prévia: nada deve ser salvo. Um CSV inválido deve impedir a importação inteira.
6. Exporte CSV e abra na planilha. Exporte o backup JSON e guarde uma cópia em local privado.
7. Faça outro lançamento. Restaure o backup, confirme a substituição e verifique o estado anterior, incluindo contas, categorias, cartões e investimentos.
8. Tente um backup inválido; a base atual deve permanecer intacta. Nunca teste desinstalação sem uma cópia válida.

## Somente flavor experimental `notifications` — XP, Itaú, Mercado Pago e Samsung Wallet

Esta seção não se aplica ao APK 0.3.0 local. A flag `BuildConfig.NOTIFICATION_CAPTURE_ENABLED` é ativada apenas no flavor `notifications`. Não é necessário instalar essa compilação para validar o controle local e os temas. O APK experimental substitui a edição local na atualização; não instala um segundo aplicativo. Se o Android bloquear o acesso, preserve as proteções e informe a mensagem.

1. Antes de ativar, confirme que o controle local continua funcionando com a captura desligada. Salve um backup.
2. Abra **Ajustes → Notificações de transações**. Selecione apenas os apps que deseja acompanhar; confira a lista de apps instalados.
3. Leia a explicação e, se desejar, conceda acesso ao **MyFinanceHub — sugestões de transações** no Android. Volte e ligue a captura. Não é necessário fazer uma transação financeira só para testar: aguarde uma operação que você já faria normalmente.
4. Após um alerta reconhecido, use **Atualizar lista** e confira app de origem, valor, data e final do cartão, quando houver. O saldo não deve mudar antes da confirmação.
5. Abra **Revisar → Conferir dados**. Cancele e confirme que a sugestão permanece. Reabra, escolha a conta e a categoria e confira Crédito/Débito/PIX antes de salvar.
6. Para crédito, confira o cartão escolhido e que a compra não reduziu o caixa. Registre pagamento de fatura separadamente. Se o final não corresponder a um único cartão, selecione manualmente.
7. Confira o aviso de possível duplicata quando já existir lançamento da mesma data/tipo/valor (inclusive importado por CSV ou notificado pelo banco e pela carteira). Descarte o alerta repetido quando for a mesma operação.
8. Descarte uma sugestão e confira que os lançamentos existentes permanecem intactos. Pause a captura e confirme que novos alertas não entram na fila. Teste também a revogação da permissão no Android.
9. Verifique que ofertas, códigos de acesso, recusas, saldos, faturas e ordens/cotações de investimento não viram lançamentos. Alguns formatos legítimos também podem não ser reconhecidos: anote um exemplo anonimizado para ajuste posterior.

A suíte automatizada usa exemplos sintéticos. Não há confirmação prévia de todos os formatos reais dos quatro apps nem teste físico desta versão. Alertas antigos não são recuperados; só novos avisos são processados após ativar. Preferências e sugestões não fazem parte do backup financeiro.

## Retorno para a próxima versão

Anote modelo do aparelho, versão do Android, problemas e formato/banco dos extratos que falharam. Ao compartilhar exemplos, substitua nomes, documentos, contas, identificadores e valores por dados fictícios, preservando o formato.

A versão 0.3.0 local não lê notificações. No flavor experimental `notifications`, a captura exige ativação e revisão antes de registrar. Conexões diretas com B3, bancos e carteiras continuam futuras; nenhum pagamento PIX é feito pelo app.
