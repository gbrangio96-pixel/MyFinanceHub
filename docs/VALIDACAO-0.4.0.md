# Validação da versão 0.4.0 — B3

`./scripts/verify.sh` terminou com **BUILD SUCCESSFUL**. Testes, lint e montagem dos dois flavors foram verificados. Ambos os relatórios Android Lint informam **No issues found.**

| Flavor | Casos | Aprovados | Ignorados | Falhas/erros |
| --- | ---: | ---: | ---: | ---: |
| local | 181 | 163 | 18 | 0 |
| notifications | 181 | 178 | 3 | 0 |

Os ignorados continuam específicos do flavor: captura experimental de notificações não é habilitada no local; verificações exclusivas da edição local não se aplicam à edição notifications.

Foram acrescentados 45 casos B3 em cada flavor: 19 de leitura XLSX/CSV, 15 de domínio/armazenamento/migração e 11 de fluxo da interface. Os dados e as planilhas são fictícios. Há cobertura de documentos escolhidos pelo seletor, leitura de bytes, prévia, data obrigatória, cancelamento, erro sem alteração parcial, reimportação, confirmação de posição mais antiga, navegação, privacidade e aparência em API 35.

## Tratamento financeiro e preservação

As posições B3 ficam em tabelas próprias. Elas não entram no saldo das contas nem no total de investimentos manuais, não criam lançamentos e não presumem custo de aquisição ou rentabilidade. Quantidades são decimais exatos; valores monetários são inteiros em centavos. Avaliação ausente permanece desconhecida, com indicação de soma parcial; se todas estiverem ausentes, a tela não apresenta uma avaliação de zero.

Testes verificam identidades simultaneamente por ISIN e ticker, por instituição/conta, incluindo duplicatas em abas com tipos diferentes e ISIN ausente numa das linhas. Reimportar substitui o relatório e não acumula cópias. Um relatório parcial não é interpretado como venda dos ativos ausentes.

A migração de banco v1 para v2 acrescenta as tabelas B3 e preserva dados e IDs. Backups novos são v2; restauração aceita v1/v2 e é atômica. Restaurar v1 substitui o estado e remove a posição B3 ausente nesse backup. Os testes incluem falha após alterações dentro da transação, conservação dos dados anteriores, valores fora do limite, data inválida e mudança do relógio após importação.

O primeiro teste integrado encontrou aceitação indevida de um NUL no final do texto porque `trim()` o removia antes da validação. A validação foi corrigida para verificar o texto original. A revisão também acrescentou rejeição de moeda estrangeira explícita, sem conversão silenciosa; a suíte completa final passou.

## APKs e atualização

Ambos os APKs têm pacote `br.com.myfinancehub`, versão **0.4.0/code 6**, mínimo API 26 e target API 35. Assinaturas v2 e alinhamento foram verificados. O certificado SHA-256 é `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, igual ao da 0.3.0. A edição local continua sem permissões de internet ou serviço de captura. A edição notifications preserva o módulo experimental opcional.

No emulador Android 15/API 35, a 0.3.0 foi instalada, recebeu registros fictícios e foi atualizada com `adb install -r` para a 0.4.0, sem desinstalação ou limpeza. Após abrir a versão nova, foi conferida a migração para schema 2 e a igualdade de todas as linhas/IDs das tabelas financeiras anteriores.

Um XLSX fictício foi selecionado em Downloads pelo seletor real do Android e importado após revisão. A prévia e a posição salva mostraram três posições, R$ 1.334,46 em valores informados e uma avaliação desconhecida preservada como null. As cinco tabelas financeiras anteriores continuaram idênticas. Lista, detalhes e ocultação de valores/quantidades foram conferidos no modo escuro; capturas estão em `artifacts/screenshots/0.4.0/`. Claro e Escuro têm cobertura de interface em Robolectric/API 35; não foi repetida conferência visual clara nem fonte ampliada no emulador desta etapa.

## Limites

Não houve acesso autenticado à conta de um investidor ou homologação de um XLSX real da B3. O leitor usa cabeçalhos compatíveis explicitamente documentados e rejeita dados desconhecidos, negativos, moeda explícita diferente de BRL, fórmulas, vínculos externos, planilhas antigas e movimentações sem alterar o relatório salvo. Mudanças no layout da B3 podem exigir adaptação.

O emulador usa renderização em software e apresentou um ANR do System UI durante o cold boot, recuperado antes do fluxo B3; não houve crash do MyFinanceHub na verificação funcional. Esse teste não mede fluidez em um aparelho físico.

A B3 restringe a API direta ao acesso empresarial contratado e autorizado. A versão 0.4.0 atualiza posições por arquivo, não por API ou cotação automática. A aceitação e o comportamento no Samsung S22 Ultra ainda precisam ser conferidos no aparelho. A assinatura preservada permite solicitar atualização; não garante aceitação pelo Play Protect. Mantenha as proteções do Android.

Guia de uso e fontes oficiais: [B3.md](B3.md). APKs, checksums e resumo: `artifacts/MyFinanceHub-0.4.0-*-debug.apk`, arquivos `.sha256` e `artifacts/validation-0.4.0.json`. A entrega é em APK; ZIP somente mediante pedido.
