# MyFinanceHub

- Use o checkout existente e preserve os dados e as alterações do usuário.
- Entregue um APK atualizado ao concluir alterações no Android. Só disponibilize projeto em ZIP se o usuário pedir.
- O APK padrão é `local`; `notifications` mantém o módulo experimental opcional. Ambos atualizam o mesmo pacote e usam a mesma chave de desenvolvimento, guardada fora do Git.
- Para validar Android, use `./scripts/verify.sh`. As ferramentas ficam em `/workspace/.myfinancehub-tools`; não publique chaves, caches ou arquivos financeiros reais.
- A versão 0.4.0 usa banco/backup v2, com migração aditiva da v1 e restauração de backups v1/v2. Preserve a atualização sem desinstalar.
- As posições B3 importadas ficam separadas dos investimentos manuais e do saldo. Valor de posição não estabelece custo, rentabilidade ou lançamento financeiro. Consulte `docs/B3.md`.
- O backend Meu Pluggy em `backend/` continua preparatório e separado do APK. Seus testes offline usam `cd backend && npm test`.
