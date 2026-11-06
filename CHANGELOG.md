# Histórico de versões

Etapas: **Alpha** → **Beta** → **RC** (release candidate) → **Oficial**. A versão atual fica em
`sysattack/src/main/webapp/version.json` e aparece no rodapé do menu e na tela de login.

## Não publicado

- Projetos Spot: importação do Status Report da Fábrica (HTML), com prévia para revisão. Na aba "Apontamentos de horas" importa só as horas consumidas (o detalhamento do chatbot substitui a linha agregada); horas já lançadas não duplicam. Na aba "Demandas" importa só as demandas em andamento e previstas, com situação sugerida e editável; demandas de mesmo nome são atualizadas.

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
