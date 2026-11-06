# Histórico de versões

Etapas: **Alpha** → **Beta** → **RC** (release candidate) → **Oficial**. A versão atual fica em
`sysattack/src/main/webapp/version.json` e aparece no rodapé do menu e na tela de login.

## Não publicado

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
