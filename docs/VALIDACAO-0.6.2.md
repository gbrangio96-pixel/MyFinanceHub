# Validação 0.6.2

Validação em 22/10/2026, a partir da 0.6.1/commit `bce2fd3`. Pacote `br.com.myfinancehub`, código 11. [Registro estruturado](../artifacts/validation-0.6.2.json).

## Resultado

`./scripts/verify.sh` aprovou **410 testes**, sem falhas, erros ou ignorados, e concluiu builds debug/release, lint e verificação do APK. Lint nas duas variantes: **No issues found.** A expectativa de um teste antigo do resumo Meu Pluggy foi atualizada para o rótulo já existente no app; a suíte completa foi repetida e aprovada.

- APK: [MyFinanceHub-0.6.2-release.apk](../artifacts/MyFinanceHub-0.6.2-release.apk), 197264 bytes, sem depuração.
- SHA-256: `b04e768a8f7470135d6c8846caaad2703ddcbace99d803373192993c21076b4e`.
- Certificado SHA-256: `3aed65b8149ccd92287cc1055b57a5ae6b909f12d9282ffab221c24a8dadb446`, preservado.
- Assinatura e alinhamento verificados com `apksigner` e `zipalign`; Android mínimo API26, target35.

## Regra de bloqueio

A sessão termina no primeiro evento: tela apagada/bloqueio do celular ou 600.000 ms fora do app. Alternar entre aplicativos antes do prazo conserva o acesso somente se a tela continuar acesa e desbloqueada. A contagem usa tempo monotônico; navegar entre telas e girar o aparelho preservam a sessão. Uso ativo não inicia o prazo. Bloquear aplicativo e recuperar a senha continuam invalidando o acesso.

O receptor de `ACTION_SCREEN_OFF` invalida a sessão, cancela biometria pendente, oculta o conteúdo e encerra as atividades, removendo também suas janelas de diálogo. Apagar a tela bloqueia o app mesmo quando o Android está configurado para atrasar o keyguard. Início/retorno das atividades e concessão de acesso também consultam `PowerManager` e `KeyguardManager`. `SCREEN_ON` e `USER_PRESENT` nunca autorizam acesso. Não há serviço permanente ou nova permissão privilegiada.

Uma nova execução começa bloqueada. O Android não informa de forma confiável se houve bloqueio enquanto o processo estava encerrado, por isso a concessão persistida da 0.6.1 é descartada. Senha, biometria, conexão Meu Pluggy e dados financeiros são preservados. Não se promete conservar a sessão quando o Android encerra o processo, mesmo dentro dos dez minutos.

Resultados de senha e biometria são vinculados à geração da sessão. Bloqueio ou fim do prazo rejeitam resultados antigos, inclusive a senha já verificada enquanto a tela de login estava em segundo plano. A recuperação desativa a biometria antiga no worker após trocar a senha, independentemente de a interface ter sido encerrada. Confirmações de lançamentos, importação, restauração e formulários não aceitam alterações com acesso revogado.

## Verificações

A suíte cobre retorno antes do prazo, limite exato de dez minutos, uso ativo, navegação, relógio monotônico/boot, processo recriado, descarte da concessão antiga, tela apagada, estado do keyguard e aparelho não interativo. O fechamento das telas financeiras por tela apagada é exercitado em Android API28 e API35 via Robolectric. Testes também cobrem senha em processamento ou resultado já enfileirado no bloqueio, prazo expirado no login e revogação de biometria durante recuperação interrompida.

Os fluxos de despesa manual e Meu Pluggy abrem o formulário e a confirmação reais, bloqueiam a tela e tentam acionar a referência antiga do botão: nenhum lançamento é salvo e o backup permanece igual. Os testes existentes continuam verificando revisão obrigatória para notificações/importações, relatórios, cotações, B3 e organização dos investimentos. A consulta Meu Pluggy permanece nativa e usa o mesmo cliente de API pessoal.

## Preservação e limites

Schema e backup continuam v4, sem migração financeira nova. O APK mantém pacote e certificado anteriores, permitindo instalar por cima sem desinstalar. Guarde seu backup. A primeira abertura após atualizar exige autenticação.

Se a tela apagar durante criação/recuperação e a troca de credenciais já tiver sido concluída, a nova senha informada permanece válida; o código de recuperação que ainda não foi guardado deixa de ser exibido. Não há armazenamento persistente do código em texto aberto.

Testes são automatizados, com dados fictícios. Não foram usadas credenciais bancárias, consultadas finanças reais ou realizados testes em sensor biométrico/celular físico. Android pode adiar broadcasts de processos em cache; as verificações de estado no retorno complementam o receptor. Publicação no GitHub e entrega por APK não constituem publicação na Play Store.
