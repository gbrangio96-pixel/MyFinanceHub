# Validação 0.6.0

Validação em 22/10/2026, a partir da 0.5.1/commit 9007cb1. APK de distribuição, pacote `br.com.myfinancehub`, código 9. [Registro estruturado](../artifacts/validation-0.6.0.json).

## Resultado

`./scripts/verify.sh` concluiu a suíte: **378 testes aprovados**, zero falhas, erros ou ignorados. Build e lint debug/release concluídos. Após ajustar recursos de texto e o texto de configuração pessoal, lint e empacotamento foram executados novamente: **No issues found.** nas duas variantes. Assinatura, alinhamento e `debuggable=false` verificados no APK final. A suíte offline do backend alternativo também passou; nenhum serviço foi hospedado.

## Acesso local

Login/senha locais, regra de 8–128 caracteres com maiúscula, minúscula, número e especial. Derivação PBKDF2-HMAC-SHA256 com 210.000 iterações e sais aleatórios de 32 bytes; comparação constante e bloqueio persistente depois de 5 erros. Não é uma conta online e não envia senhas a serviços.

O código de recuperação de 128 bits é mostrado uma vez; só sua derivação é guardada. A recuperação renova senha/código, desativa biometria e exige novo login sem alterar as finanças. Credenciais, biometria e código não entram no backup financeiro. O banco permanece em armazenamento privado do Android, sem criptografia própria adicional; arquivos exportados não são criptografados.

Biometria é opcional em Android 9/API28 ou posterior, com biometria forte, prova AES-GCM e chave Android Keystore exigindo autenticação por operação. Alterar biometrias pode invalidar a chave. Android 8 usa senha. Cancelamento, prova corrompida ou callback antigo não autorizam entrada.

Login é o launcher exportado; as três telas financeiras são privadas e exigem sessão em memória. Ir ao segundo plano bloqueia a sessão. O conteúdo financeiro permanece invisível inclusive após Android `makeVisible()` na abertura e quando o MainActivity redesenha. Uma tarefa de senha concluída no segundo plano não desbloqueia o app antes do retorno. A correção desse comportamento foi verificada por regressão de ciclo de vida.

## Meu Pluggy pessoal

Cliente direto de `https://api.pluggy.ai`, somente leitura. Credenciais próprias digitadas no app e criptografadas com AES-GCM/Keystore; nenhuma credencial real está no código/APK. Não requer hospedagem nem contrata plano. O uso/limites e a gratuidade dependem das condições do provedor.

O titular ainda precisa autorizar os bancos no Meu Pluggy e espelhar os itens pela aplicação pessoal no Dashboard, usando o conector MeuPluggy. Ter apenas uma conta cadastrada não produz automaticamente os Client ID/Secret/itemIds necessários. [Passo a passo](OPEN-FINANCE.md).

Consulta manual ou ao entrar, conforme opção salva. Contas, saldo informado, status/horário e transações são exibidos para consulta. Saldo informado não vira saldo inicial nem entrada. PENDING não pode ser gravado. POSTED exige vínculo explícito de conta/cartão, revisão e confirmação. A mesma origem é impedida globalmente dentro da transação SQLite, inclusive após mudar a conta local/restaurar backup. Outras origens ainda precisam de conciliação; aviso de possível duplicidade exige conferência.

Os 18 testes específicos do cliente pessoal cobrem host fixo, headers de autenticação, centavos exatos, data literal, hash independente, pendências/crédito, repetição/conflito de identidades, paginação sem mudança de conta/período, URLs hostis, limites de páginas/requisições/linhas/bytes, falhas tardias, cancelamento e erros sem segredos. Falha em qualquer página invalida a consulta inteira.

## Investimentos e preservação

Em Carteira, organização por Tipo/Liquidez/Vencimento ou validade/Valor aplicado decrescente. Liquidez e vencimento são opcionais e vêm do contrato informado pelo usuário, nunca deduzidos de ticker ou cotação. Datas desconhecidas ficam por último.

Banco/backup passam a v4, com migração aditiva de 1/2/3 e restauração1–4. Investimentos antigos recebem liquidez desconhecida e vencimento vazio; valores, quantidade e dados de cotação anteriores permanecem. Nove novos testes de metadata/organização verificam backup, migração, ordem e edição. Posição B3 original continua separada/intacta. Relatórios, exportações, notificações, precisão monetária e demais funcionalidades anteriores continuam cobertos.

## Artefato e limites da validação

- APK: [MyFinanceHub-0.6.0-release.apk](../artifacts/MyFinanceHub-0.6.0-release.apk), 191,092 bytes.
- SHA-256: `8dcd1f12fa1be6fa8e932effacae4155205da54f68639d799fd18bafd60e1ca0`.
- Certificado SHA-256: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, igual à 0.5.1. A chave anterior foi mantida fora do Git.
- `apksigner verify`, `zipalign -c 4` e `apkanalyzer manifest debuggable` aprovados; depuração desativada.
- Android mínimo 8.0/API26, target 35. Uma edição; sem ZIP de projeto.

É um APK de distribuição com a assinatura anterior preservada. O certificado ainda é o de desenvolvimento usado nas versões anteriores, para permitir atualização sem desinstalar; não é publicação na Play Store. Nenhuma conta/assinatura de hospedagem foi criada.

Não houve acesso à conta Meu Pluggy do usuário, credenciais pessoais, bancos reais ou dados reais. A consulta real acontecerá no aparelho após configuração. Biometria nativa, atualização física, aceitação pelo Play Protect e comportamento específico do S22 Ultra não foram testados em hardware nesta execução. O emulador restaurado apresentava falhas do sistema nas tentativas anteriores; os testes Android desta versão usam Robolectric/ciclo de vida e fixtures fictícias, sem afirmar aprovação em dispositivo.

Instale por cima, sem desinstalar; exporte backup antes. Não abra o banco v4 em APK antigo. Na primeira abertura crie o acesso local, guarde o código de recuperação e configure sua conexão pessoal para consultar.
