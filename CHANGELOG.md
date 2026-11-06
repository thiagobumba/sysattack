# Histórico de versões

Etapas: **Alpha** → **Beta** → **RC** (release candidate) → **Oficial**. A versão atual fica em
`sysattack/src/main/webapp/version.json` e aparece no rodapé do menu e na tela de login.

## Não publicado

- Campos de valor em reais com a notação brasileira (1.234,56): os 5 campos (orçamento do projeto, valor de lançamento financeiro, impacto em custo da change, valor do projeto spot e valor da nota fiscal spot) passaram de campo numérico do navegador, que mostrava 189090.89 ou 189.09089 conforme o idioma, para máscara de moeda: os dígitos entram como centavos (digitar 18909089 mostra 189.090,89) e a API recebe 189090.89. Ao editar, o valor volta formatado. Todas as telas de exibição já usavam o formato pt-BR. Campos de horas continuam numéricos.
- Projetos: flag **★ Meta Empresa** (projeto do plano de desenvolvimento da empresa). Marcada no cadastro/edição do projeto, aparece como selo laranja ao lado da prioridade (lista, visão geral e detalhe), ganha filtro próprio em "Todas as prioridades" e coloca o projeto acima de qualquer prioridade, inclusive Crítica, na ordenação da carteira e da visão geral. Não muda a prioridade escolhida (Alta, Crítica etc.). Esquema do banco 42 (coluna `company_goal`).
- Configuração embutida no .war: `src/main/resources/config.properties` (vai para `WEB-INF/classes`) traz o `app.url` do servidor, então os e-mails de convite saem com o endereço certo sem copiar arquivo algum para a pasta de dados. O config.properties da pasta de dados continua valendo e tem prioridade (é o lugar de senhas). Novo `AppConfig` unifica a leitura (convites, SMTP, backups). O `sysattack/config.properties` da raiz foi removido (não era lido).
- Convites por e-mail: se o endereço do sistema for localhost (só abre na máquina do servidor), o e-mail não é mais enviado e o administrador é orientado a definir `app.url` no config.properties da pasta de dados. A tela Segurança e dados mostra o endereço que vai nos e-mails e se ele é acessível por outras máquinas.

## 1.1.0 - Alpha (06/11/2026)

- Modelo de configuração `sysattack/config.exemplo.properties` (endereço do sistema, SMTP e backups) para copiar para a pasta de dados.

- E-mail de boas-vindas: texto do botão "Criar minha senha" forçado para branco (alguns clientes de e-mail o pintavam de roxo).

- Usuários: **cargo** (texto livre, escolhido pelo administrador; aparece na lista e no menu, com sugestão dos cargos já usados). **Novo usuário sem senha inicial**: a pessoa recebe um e-mail de boas-vindas com o endereço do sistema, as políticas de uso e um botão para criar a própria senha (link de uso único, válido por 72 horas, guardado só como hash). Se o e-mail não puder ser enviado, o usuário é criado e o administrador vê o aviso e usa o botão de e-mail na lista para reenviar o convite (ou enviar link de nova senha). Nova página definir-senha.html. Toda senha definida pelo administrador na edição obriga a troca no primeiro acesso (tela que não pode ser fechada). Endereço do sistema no e-mail: `app.url` no config.properties (ou `-Dsysattack.url`/`SYSATTACK_URL`); sem isso usa o endereço da requisição.
- **Verificação em duas etapas (2FA)** com aplicativo autenticador (TOTP, RFC 6238): cada pessoa ativa em "Segurança da conta" (QR code + chave manual), recebe 8 códigos de recuperação de uso único, pode gerar novos ou desativar (senha + código). O administrador pode **exigir 2FA** na edição do usuário, depois de criado (a pessoa configura no próximo acesso) e redefinir o 2FA de quem perdeu o celular. O login ganhou a etapa do código; códigos repetidos são recusados e há bloqueio por tentativas. Segredo guardado criptografado (AES-GCM). Novas ações na auditoria.

- Novo visual em todas as telas, no estilo da tela de entrada: menu lateral escuro com o degradê industrial no topo e item ativo em laranja, faixa de título de cada página com o mesmo degradê (título leve em creme, caminho e botão Sair em branco), fundo quente, cartões com sombra suave, tabelas com realce ao passar o mouse, foco laranja nos campos e marca d'água mais discreta. Responsivo: no celular e tablet estreito o menu vira uma barra no topo com botão que abre o menu (fecha ao escolher uma tela ou com Esc, com rótulos acessíveis), alvos de toque de 44 px e cabeçalho compacto. Também respeita "reduzir movimento" e tem estilo próprio para impressão. Tudo fica em css/theme.css, carregado depois do style.css (para voltar ao visual antigo basta remover esse arquivo do index.html).

- Tela de entrada: removido o link "Site Momesso" do topo (o link "Site" do rodapé continua).

- Direitos autorais e marca d'água: declaração "© <ano> - Momesso Indústria de Máquinas - Todos os direitos reservados" no rodapé de todas as telas (sistema, entrada e Política de Privacidade; o ano atualiza sozinho) e marca d'água discreta com o logotipo da Momesso no canto da tela do sistema (aparece também na impressão). Correção: a Política de Privacidade (privacidade.html) usava script dentro da página, que a nova política de segurança (CSP) bloquearia; o script foi para js/privacidade.js.

- Tela de entrada redesenhada no estilo do site da Momesso: fundo em degradê industrial (preto ao laranja, com granulado) e a máquina em silhueta, frase "O projeto, você planeja hoje.", cartão de login branco com botão laranja, e rodapé em faixas (logo e texto, links da Política de Privacidade e do site, barra escura). A Política de Privacidade agora aparece também dentro do cartão, logo abaixo do botão. Responsiva (celular e tablet), inclusive a tela de primeiro acesso. O CSS da tela de entrada saiu do style.css para css/login.css.

- Importação do Status Report da Fábrica (HTML): o importador agora entende o modelo novo ("Consumo do mês por demanda" + "Decisões do comitê", sem detalhe do chatbot nem "em andamento"). A linha de consumo é a fonte: cada linha vira um apontamento de horas (data = "Usadas até dd/mm"), as linhas "Demanda: atividade" são agrupadas em demandas (as previsões também viram demandas) e as ações e definições entram em Ações e pendências (novos status Salesforce, Apresentar e Aguardando). O relatório completo importado mostra consumo com total e saldo da franquia, decisões do comitê e previsão. O modelo antigo continua funcionando como antes.

- Backups e banco: pasta de dados explícita (`-Dsysattack.data` ou `SYSATTACK_DATA`, com aviso na tela se estiver no perfil do usuário), `app_meta` com identificador do banco, cópia do arquivo do banco antes de atualizar o esquema e novo serviço de backup: um único zip por backup (banco físico + script SQL, documentos, ai.key e config.properties), gravado como .part e só então renomeado, verificado ao final (CRC e leitura de todas as tabelas da cópia), com SHA-256, segunda cópia opcional (`backup.mirror`), histórico, retenção diária/semanal/mensal e execução às 02:00. Nova tela de administrador "Segurança e dados" (backup agora, verificar, baixar, avisos de risco) e script `restaurar-backup.ps1`. O backup antigo (só o arquivo do banco, 14 cópias) foi substituído.

- Segurança: cabeçalhos em todas as respostas (CSP que só aceita scripts do próprio sistema, X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy, HSTS quando em HTTPS), conferência de Origin nas escritas, cookie de sessão HttpOnly e SameSite=Lax, sessão de 4 h de inatividade, páginas de erro sem versão do servidor, senhas com PBKDF2 de 600 mil iterações (hashes antigos são atualizados no próximo login), trava de login por conta, por IP e por conta+IP, "primeiro acesso" recusado se o banco aparecer vazio numa instalação já configurada (impede que um banco perdido vire porta de entrada para um novo administrador), permissões 600 na pasta de dados (Linux) e trilha de auditoria (logins, falhas, senhas, usuários, backups) visível ao administrador.

- Relatórios em HTML compatíveis com o Outlook em todos os tipos: projetos de horas e "Relatórios com Claude" (SpotReportHtml) e Status Report importado (ImportedReportHtml) agora usam só tabelas com bgcolor, etiquetas e cartões em células, espaçamento nas células e fonte Segoe UI, sem inline-block nem margem em tabela/div (que o Outlook ignora). As etiquetas de status ficam com cantos retos nos clientes que não arredondam; o conteúdo não mudou.

- Relatório tradicional (escopo fechado): HTML reescrito para o Outlook (motor do Word): só tabelas, cores de fundo em atributo bgcolor, bordas e espaçamentos nas células, etiquetas e barras de progresso em células (sem inline-block nem largura em span, que o Outlook ignora), largura máxima com tabela fantasma para o Outlook e fonte Segoe UI. Corrige datas coladas no texto dos apontamentos, cartões sem fundo e texto cortado. Nos demais clientes o visual é o mesmo.

- Relatório tradicional: o envio por e-mail fica no relatório aberto (botão "Enviar por e-mail"), igual aos projetos de horas: Para, CC e CCO separados por ";", relatório no corpo do e-mail e registro de envio.

- Financeiro e visão do projeto spot: os valores em R$ não vazam mais dos cartões (fonte ajusta à largura, cartões financeiros mais largos) e a tela ficou responsiva: cartões em 2 colunas no tablet/celular, guias com rolagem lateral, botão de ação abaixo das guias e tabelas mais compactas. O mesmo vale para o relatório tradicional (valores longos reduzem a fonte).

- Relatório tradicional (escopo fechado): visual do HTML refeito para leitura profissional: cabeçalho com situação e destaques, cartões de indicadores com barra de progresso nas porcentagens, informações em grade, demandas em blocos com seus apontamentos (data e atividade), tabela de notas com etiquetas de situação, ajuste para impressão/PDF (sem quebrar blocos) e para telas pequenas.

- Projetos Spot (escopo fechado): nova aba "Relatório tradicional" (sem Claude), exibida somente em projetos de escopo fechado. O título vem do nome do projeto e a tela de preenchimento tem três guias já preenchidas com os dados do sistema: Visão do projeto (indicadores e informações), Demandas e apontamentos (cada demanda com os apontamentos vinculados a ela) e Financeiro (valor do projeto, faturado, a faturar, recebido e as notas). Ao salvar, o sistema gera o HTML formatado, que pode ser editado, impresso/PDF e enviado por e-mail (CC/CCO). Fica separado da aba "Relatórios com Claude".

- Lista de escopo fechado: Valor e Faturado agora ficam numa só coluna "Financeiro" e o cabeçalho "Arquivos" foi encurtado, para a tabela caber na tela sem rolagem lateral.

- Escopo fechado, controle financeiro: novo campo "Valor do projeto (R$)" (obrigatório no escopo fechado) e nova guia "Financeiro" para lançar as notas (número, emissão, vencimento, valor, situação Emitida/Paga/Cancelada) com o PDF anexado, guardado no banco. A visão do projeto mostra Valor Projeto (R$), Faturado, A faturar, Recebido e % faturado; a lista de escopo fechado mostra valor e faturado. Notas canceladas não entram no faturado.

- Escopo fechado: a demanda só conta como entregue quando existe ao menos um apontamento vinculado a ela. O percentual de entrega é demandas entregues ÷ demandas do escopo (as canceladas não contam). Todo apontamento de escopo fechado exige escolher "qual demanda este apontamento entrega". Os cartões mostram entregues, sem apontamento e concluídas sem apontamento (alerta); o Resumo e a aba Demandas mostram, por demanda, quantos apontamentos tem e se está entregue.

- Projetos Spot por tipo: a lista agora tem guias "Contrato" (contratos recorrentes), "Escopo fechado" e, quando existirem, "Avulsos". Novo tipo Escopo fechado (flag no cadastro, excludente com Contrato): não controla horas (modelo e horas fixadas ficam desabilitados) e acompanha só o escopo, o que foi feito e o que foi entregue. Na tela do projeto, os cartões mostram demandas do escopo, entregues, em andamento, a iniciar e % entregue; apontamentos e demandas não têm horas; nova aba "Escopo" para anexar o PDF ou arquivo do projeto (até 25 MB), guardado no banco somente para registro, com abrir e excluir.

- Projetos Spot: a lista é ordenada pela data de início (a menor em cima, a maior embaixo), depois pela data final e pelo nome.

- E-mail: cada teste de envio (Configurações de E-mail) gera um arquivo .log, baixado na hora, com data, quem disparou, destino, configuração SMTP usada (sem senha), a conversa com o servidor SMTP, o resultado e, na falha, a cadeia de erros e o stack trace. O log fica no banco (lista "Logs dos testes de envio", com botão Baixar) e também na pasta `email-logs` dentro da pasta de dados do servidor, para extração.

- Projetos Spot: botão "Copiar projeto" (na lista e na tela do projeto), disponível só para contratos recorrentes. Copia nome, cliente, fornecedor/equipe, modelo e horas fixadas, participantes e destinatários; início, data final, observação de faturamento e descrição ficam em branco, e a situação vem sempre como Ativo. Demandas, horas, ações e relatórios não são copiados.

- Projetos Spot: o período do projeto é sempre a vigência do contrato (início até a data final). A visão do período, os apontamentos, o resumo e os relatórios passam a olhar a vigência, em vez do mês corrente; a aba de horas ganhou o filtro "De / Até" limitado à vigência. Lançamentos de horas e a data de referência dos relatórios só aceitam datas dentro da vigência. Novo botão "Voltar" na tela do projeto.

- Projetos Spot: a lista mostra "Saldo atual" no lugar de "Saldo projetado". Projeto encerrado só pode ser alterado por administrador, com justificativa obrigatória (mínimo de 10 caracteres) que fica no histórico da aba Resumo; usuários comuns só consultam. O envio do relatório por e-mail ganhou CC e CCO, e aceita vários endereços separados por ponto e vírgula.
- Consistência dos dados: toda alteração no sistema (projetos, tarefas, marcos, financeiro, documentos, usuários, e-mail, Projetos Spot e as importações) é gravada de forma atômica, tudo ou nada; e-mails automáticos só saem e arquivos de documentos só são apagados depois que o banco confirma; o banco grava no disco a cada confirmação e faz cópia de segurança diária (zip, últimas 14, na pasta de dados em `backups`).

- Projetos Spot: importação do Status Report da Fábrica (HTML), sempre com prévia para revisão e em cada aba: Apontamentos de horas (só as horas; o detalhamento do chatbot substitui a linha agregada), Demandas (só as demandas, com situação sugerida e editável), Ações e pendências (ações do comitê e definições da Momesso) e Relatórios (o relatório completo, com o mesmo conteúdo e organização do arquivo, sem usar o Claude, pronto para abrir, imprimir ou enviar por e-mail). Itens já existentes são atualizados ou ignorados, nunca duplicados. A visão do período (horas consumidas, saldo) agora acompanha o mês escolhido em Apontamentos de horas. Corrigido o corte do campo Destinatários.

- Projetos Spot: novo campo "Data final" (opcional, validada contra o início) e a marca "Contrato recorrente". A lista mostra o selo Recorrente e a vigência; projeto ativo com a data final vencida é sinalizado. Projetos existentes ficam sem data final e como não recorrentes.

- Indicadores de custo e prazo por projeto (valor agregado): SPI, CPI, EAC e desvio no término. Aparecem no detalhe do projeto e na aba Orçamentária de Relatórios & Dashs ("Projeção de custo no término"). Orçamento = previsto; progresso ponderado pela duração das tarefas; custo = notas fiscais e faturas lançadas. A saúde de custo vira Atenção quando a projeção passa do orçamento (com progresso a partir de 10%).

- Saúde e prioridade por projeto. A saúde (verde, amarelo, vermelho) é calculada na hora, pelo pior resultado entre prazo, custo, marcos e mudanças, e aparece em Projetos (com filtros), na Visão geral (painel "Saúde do portfólio") e no detalhe do projeto. A prioridade (Crítica, Alta, Média, Baixa) é definida no cadastro do projeto. Projetos existentes ficam como Média. Limites: prazo 5 e 15 p.p. atrás do previsto; custo 90% e 100% do orçamento; marcos 1 e 2 atrasados; change pendente há mais de 7 dias.

- Identidade alinhada ao site momesso.ind.br: laranja #F45200, creme #FDDDB3, cinza #DDDDE0, Inter com títulos finos e cantos retos. Login redesenhado (card com faixa preta, botão preto, rodapé escuro).
- Revisão geral de UX/UI (Visão geral, Projetos, Spot, Tarefas, Marcos, Cronograma, Relatórios, Usuários, IA Agent, E-mail): cabeçalhos mais leves, títulos de painel e rótulos com menos caixa-alta, tabelas sem colunas cortadas, abas do projeto em linha própria com rolagem, foco visível nos campos, ajustes de mobile.

## 1.0.0 - Alpha (05/11/2026)

Primeira versão.

- Login por e-mail e senha forte; usuários (administrador e comum); troca de senha confirmada por código enviado por e-mail.
- Projetos com escopo, datas e orçamento; tarefas pai e filha; marcos; Gantt; Financeiro (notas fiscais e faturas); Documentos.
- Projetos Spot: horas fixadas, apontamentos, demandas, ações e relatórios semanais gerados com o Claude, com envio por e-mail.
- Configurações IA Agent (chave da API do Claude) e Configurações de E-mail (SMTP pela tela e gatilhos por projeto), só para administradores.
- Relatórios & Dashs (projetos e orçamentária) e aba Changes nos projetos.
- Menu lateral em grupos, com o usuário logado e a versão no rodapé.
