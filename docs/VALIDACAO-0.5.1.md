# Validação 0.5.1

Validação em 22/10/2026, a partir do commit `d5f09cc` da versão 0.5.0. APK único, pacote `br.com.myfinancehub`, código 8. [Registro estruturado](../artifacts/validation-0.5.1.json).

## Comportamento

A captura opcional de notificações voltou à edição única, sem restaurar flavors antigos. O serviço exige a permissão de vínculo do Android e só inspeciona novos alertas dos apps selecionados quando a captura está ativada. Não grava lançamentos: guarda sugestões resumidas numa base separada, compatível com a fila histórica. Novas instalações começam com a captura desativada.

O formulário de lançamento manual ou sugerido exige **Revisar** e depois **Confirmar e salvar**. O resumo mostra descrição, valor, tipo, data, conta, categoria, forma de pagamento e cartão quando aplicável. **Voltar à revisão** conserva o formulário sem gravar. Cancelar ou recriar a atividade não confirma valores. Sugestões exigem conta explícita e escolha de método quando ambíguo; crédito exige cartão. A proteção transacional contra repetir a mesma origem foi preservada.

A importação de extratos oferece todos os registros na prévia, inclusive além dos 12 da amostra antiga. A lista recicla as linhas para suportar lotes grandes. O botão de importação continua sendo confirmação explícita; cancelar não grava nada.

## Testes e build

`./scripts/verify.sh` concluiu com **293 testes aprovados**, zero falhas, erros ou ignorados; lint **No issues found.**; `assembleDebug` concluído.

A suíte inclui o serviço de captura com permissões/seleção/desativação, alertas ambíguos e não financeiros, deduplicação, expiração da fila, conta e cartão explícitos, revisão e cancelamento de sugestões, confirmação única, despesas manuais novas e editadas, retorno ao formulário com o valor preservado, importação de mais de 12 despesas e cancelamento sem gravação.

Permanecem os testes de relatórios, PDF/CSV e compartilhamento, migração aditiva v1/v2 para v3, backup/restauração v1/v2/v3, B3, precisão decimal, consulta/cache de cotações, proteção da chave do provedor, cancelamento e concorrência com atualização de investimentos. `FinanceStore`, esquema financeiro, formato de backup e implementação de relatórios/cotações não foram alterados. Os testes usam dados fictícios, sem acessar finanças do usuário.

## Artefato e limites

- APK: [MyFinanceHub-0.5.1-debug.apk](../artifacts/MyFinanceHub-0.5.1-debug.apk), 359.549 bytes.
- SHA-256: `174a398a32596d4492b837629a509f1f09d9ea0038bda5a0104fbee45a200e46`.
- `apksigner verify` e `zipalign -c 4` aprovados.
- Certificado SHA-256: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, igual ao da 0.5.0. Chave mantida fora do Git.
- Android mínimo 8.0/API26, target35; APK de testes com assinatura de desenvolvimento.

O emulador API35 iniciou sem aceleração, mas o sistema Android apresentou reinicializações/`DeadSystemException` antes de liberar o armazenamento e abrir a atividade anterior da 0.5.0. A validação de instalação/atualização e a conferência visual nesse emulador não foram concluídas nesta execução. Os resultados de versões anteriores permanecem históricos, não equivalem a um teste físico da 0.5.1.

Instale por cima da versão existente, sem desinstalar, para manter os dados. O celular do usuário, notificações reais dos bancos e aceitação pelo Play Protect não foram acessados/testados. Ative a captura em Ajustes → Notificações de transações, escolha os apps e autorize o acesso no Android. Formatos de alertas variam e podem não ser reconhecidos. Sugestões de origens diferentes ainda podem exigir conciliação manual.
