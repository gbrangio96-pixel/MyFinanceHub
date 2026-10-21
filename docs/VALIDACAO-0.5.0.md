# Validação da versão 0.5.0

Validação realizada em 21/10/2026. APK único, versão 0.5.0, código 7, pacote `br.com.myfinancehub`. [Registro estruturado](../artifacts/validation-0.5.0.json).

## Android e cálculos

`./scripts/verify.sh` terminou com sucesso: **240 testes aprovados**, sem falhas, erros ou testes ignorados; lint **sem problemas encontrados**; APK montado. A suíte cobre importações, migrações, backup/restauração, temas, relatórios, limites monetários, PDF paginado, compartilhamento somente para leitura, consulta de preços, cache, cancelamento e corridas entre edição e atualização.

Os testes de rede usam transporte substituível, dados fictícios e decimais exatos. Foram conferidos crédito, pagamento de fatura e transferência sem duplicidade; filtro inclusivo por período/conta; exportação de uma cópia dos dados selecionados; campos CSV protegidos contra fórmulas; preço × quantidade com um único arredondamento final.

## APK e atualização

- APK: [MyFinanceHub-0.5.0-debug.apk](../artifacts/MyFinanceHub-0.5.0-debug.apk), 317.022 bytes.
- SHA-256: `8d045e80c49e7855b1d59de261cdf1edb8bf43cc0bae8765a70c88c00efa852b`.
- Assinatura verificada por `apksigner` e alinhamento por `zipalign`. Certificado SHA-256: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, igual ao das versões anteriores.
- Android mínimo 8.0/API26, target35. Única permissão: INTERNET, para cotações opcionais. Não declara serviço de notificações ou permissões de áudio, contatos, mensagens e leitura de outros apps.

No emulador API35, `adb install -r` atualizou a 0.4.0/code6 com sucesso. A migração de schema2 para3 preservou integralmente as colunas anteriores de contas, categorias, cartões, lançamentos, investimentos e B3, comparadas antes e depois: uma conta, 14 categorias, um lançamento, um investimento e três posições B3. Os novos campos começaram vazios/desabilitados. São dados sintéticos do ambiente, sem acesso ao celular do usuário.

A exportação nativa **Compartilhar PDF** produziu um arquivo real de 85.812 bytes, duas páginas A4, versão PDF1.4, e abriu o seletor do Android. `pdfinfo`, `pdftotext` e renderização da primeira página confirmaram resumo, totais, categoria, investimento, valores B3 parciais e lançamento detalhado, sem corte ou ausência do registro. O gerador de PDF não depende de um serviço externo.

Os relatórios foram conferidos visualmente nos modos [Escuro](../artifacts/screenshots/0.5.0-relatorios-escuro.png) e [Claro](../artifacts/screenshots/0.5.0-relatorios-claro.png), preservando o filtro e a posição de rolagem ao trocar o tema.

O cold boot do emulador sem aceleração apresentou um aviso de ANR do **System UI do Android**, anterior à validação do APK. O framework se recuperou; a atividade do MyFinanceHub abriu e permitiu o fluxo descrito. Isso não é um teste físico no Samsung S22 Ultra.

## Cotações e limites

O cliente Java foi compilado e consultado por HTTPS contra a API pública v2 da brapi, sem chave. A consulta de PETR4 retornou preço BRL e horário do provedor; a pesquisa encontrou ação, FII HGLG11, ETF BOVA11, BDR AAPL34 e unit TAEE11. A consulta de cripto sem chave respondeu 401, tratada como indisponível. Nenhuma conta paga foi criada ou configurada.

A integração cobre os ativos disponíveis de bolsa e criptomoedas compatíveis com a chave/plano do usuário. Dados de bolsa têm atraso aproximado de 30/15/5 minutos conforme plano: não há garantia de tempo real. Atualização global é opt-in, com app em primeiro plano e intervalo de uma hora, ou por ação manual. Renda fixa privada e Tesouro permanecem manuais. Falhas preservam os valores anteriores e seus horários.

A posição B3 original permanece intacta. Estimativas por cotações usam as quantidades da data importada e ficam separadas da carteira cadastrada e do saldo, sem presumir custo ou rentabilidade B3. Rentabilidade dos cadastros é a variação simples sobre o valor aplicado; não inclui proventos, impostos ou retorno ponderado por aportes.

A aceitação pelo Play Protect e o uso com arquivos/ativos reais no S22 Ultra ainda precisam ser conferidos nesse aparelho. Preserve a instalação existente e os dados; não desative proteções nem desinstale para contornar falhas. O backend preparatório não foi alterado nem conectado ao Android nesta entrega.
