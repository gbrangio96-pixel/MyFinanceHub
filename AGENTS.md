# MyFinanceHub

- Use o checkout existente e preserve os dados e as alterações do usuário.
- Entregue um APK atualizado ao concluir alterações no Android. Só disponibilize projeto em ZIP se o usuário pedir.
- A versão 0.5.1 tem uma única edição (`assembleDebug`), pacote `br.com.myfinancehub`, código 8. Preserve a chave de desenvolvimento fora do Git para atualizar sem desinstalar.
- Notificações restauradas na edição única por solicitação do usuário. Captura opcional só cria sugestões; revisão e confirmação no app são obrigatórias antes de salvar. Não reintroduza flavors antigos ou WhatsApp/transcrição.
- Valide Android com `./scripts/verify.sh` (testDebugUnitTest, lintDebug, assembleDebug). Ferramentas em `/workspace/.myfinancehub-tools`; não publique chaves, caches ou dados financeiros reais.
- Banco/backup v3: migração aditiva de v1/v2, restauração de backups v1/v2/v3. Preserve os lançamentos já confirmados e as origens históricas. Não altere valores anteriores durante a migração.
- Relatórios: crédito entra em receitas/despesas; transferências próprias e faturas ficam fora do resultado. Caixa exclui crédito e inclui transferências/faturas. Não invente saldos históricos, retorno do período ou fluxo de dividendos.
- Investimentos cadastrados e posição B3 ficam separados do saldo e entre si. B3 importada é snapshot original; a estimativa por cotação é separada, com quantidade da importação. Não deduza custo de aquisição ou rentabilidade B3. Consulte `docs/B3.md` e `docs/RELATORIOS-E-COTACOES.md`.
- Cotações são opcionais, com chave do usuário em preferências privadas, fora de JSON/Git/APK. Use HTTPS brapi e horário do provedor. Não prometa tempo real: bolsa tem atraso conforme plano. A atualização automática começa desligada, ocorre com app aberto e preserva valores nas falhas.
- Finanças usam centavos inteiros; quantidades/preços, decimais exatos. Quantidade de investimento até 8 casas; preço até 12. Rentabilidade simples = (atual - aplicado) / aplicado, sem anualização ou proventos.
- O backend Meu Pluggy em `backend/` continua preparatório e separado do APK. Seus testes offline usam `cd backend && npm test`.
