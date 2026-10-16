# CSV — validação da versão 0.1.1

Em 15/10/2026, `./scripts/verify.sh` terminou com código zero e **BUILD SUCCESSFUL**. Android Lint: **No issues found.**

| Suíte | Passaram | Falhas | Ignorados |
| --- | ---: | ---: | ---: |
| StatementImporterTest | 36 | 0 | 0 |
| MainActivityTest | 10 | 0 | 0 |
| FinanceStoreTest | 14 | 0 | 0 |
| MoneyTest | 4 | 0 | 0 |
| **Total** | **64** | **0** | **0** |

Os dois registros fictícios fornecidos pelo usuário (salário de R$ 5.000,00 e mercado de R$ 185,47) são aceitos pelo importador anterior e pelo atualizado, com as direções e datas corretas. Portanto, **os trechos recebidos ainda não reproduzem o erro do arquivo original**.

Foi reproduzida a mesma mensagem de quantidade de colunas quando cabeçalho e linhas terminam com quantidades diferentes de campos vazios. O importador atualizado tolera somente essas células vazias finais. Continua rejeitando campos adicionais com conteúdo, campos faltantes e valores com vírgula sem aspas em CSV separado por vírgula. Não junta nem descarta valores para tentar inferir a intenção do arquivo.

As mensagens agora mostram número físico da linha, quantidade esperada/encontrada e separador detectado. O texto pode ser selecionado e copiado. Cabeçalhos, identificadores e regras de deduplicação existentes foram preservados.

Foram acrescentados testes pelo fluxo real do seletor Android: retorno de URI, leitura UTF-8 com BOM e Windows-1252, ausência de metadados, cancelamento e falha de leitura sem alteração dos dados. Os testes de UI usam Robolectric/API 28; não substituem testes no aparelho físico.

O arquivo [CSV de referência](../samples/extrato-exemplo-virgula.csv) foi lido do disco e validado: quatro registros, incluindo os valores e direções informados. Contém apenas dados fictícios.

O [APK 0.1.1](../artifacts/MyFinanceHub-0.1.1-debug.apk) tem `versionCode=2`, o mesmo package `br.com.myfinancehub` e o mesmo certificado da versão 0.1.0, conferidos com `aapt` e `apksigner`. Pode ser instalado como atualização sem desinstalar nem limpar os dados. Nenhuma migração do banco é necessária. O APK atualizado ainda precisa ser testado no celular do usuário.

Resumo e checksum: `artifacts/validation-0.1.1.json` e `artifacts/MyFinanceHub-0.1.1-debug.apk.sha256`.
