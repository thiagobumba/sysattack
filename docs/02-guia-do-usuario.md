# 02 – Guia do usuário

Guia passo a passo das telas. Dica geral: passe o mouse (ou foque com o teclado) sobre selos, indicadores e títulos para ver um balão com a explicação detalhada, a fórmula e os valores usados. Esc fecha o balão.

## Entrar no sistema

1. Abra o endereço do SysAttack e entre com e-mail e senha.
2. Se a sua conta tem **verificação em duas etapas (2FA)**, informe o código de 6 dígitos do aplicativo autenticador (ou um código de recuperação).
3. No primeiro acesso após um convite, use o link recebido por e-mail para **criar sua senha** (link de uso único, válido por 72 horas). Senha forte: 10+ caracteres com minúscula, maiúscula, número e símbolo.
4. **Esqueci/troquei a senha:** a troca exige um código enviado por e-mail.

Depois de 5 tentativas erradas o acesso é bloqueado por um tempo. A sessão expira após 4 horas sem uso.

### Segurança da conta (2FA)
Em **Usuários › Segurança da conta**: ative o 2FA (QR code ou chave manual), guarde os 8 códigos de recuperação, gere novos ou desative (pede senha e código). O administrador pode exigir 2FA de um usuário.

## O menu lateral

| Grupo | Item | Para que serve |
|---|---|---|
| **Gestão** | Visão geral | Painel do portfólio: saúde, projetos que precisam de ação, atenção do dia |
| | Projetos | Lista e cadastro de projetos, com abas por situação |
| | Projetos Spot | Trabalho por horas ou escopo fechado, relatórios para clientes |
| | Tarefas | Todas as tarefas de todos os projetos |
| | Prioridades | Matriz de Eisenhower das tarefas em aberto |
| | Marcos | Todos os marcos, com abas Todos / Atrasados / Concluídos |
| | Cronograma | Gantt de todos os projetos |
| **Análise** | Relatórios & Dashs | Gráficos de projetos e de orçamento, com impressão |
| **Administração** | Usuários | Cadastro de usuários (administrador) ou "Meu usuário" |
| | IA Agent | Conexão com o Claude (só administrador) |
| | E-mail | SMTP e gatilhos (só administrador) |
| | Segurança e dados | Backups, pasta de dados, auditoria (só administrador) |
| **Informações** | Política de Privacidade | Texto da política de uso |

## Projetos

### Criar e editar
**Projetos › + Novo projeto** (ou "Editar projeto" na tela do projeto). Campos: nome, escopo inicial, responsável, situação, **prioridade** (Crítica, Alta, Média, Baixa), **★ Meta Empresa**, início, fim e **orçamento** (em R$, com máscara 1.234,56).

**Imagem do projeto:** no mesmo formulário, "Escolher imagem" (logo ou foto, PNG, JPG, WEBP ou GIF) mostra a prévia; ela é gravada ao salvar e aparece como avatar ao lado do título. "Remover imagem" volta às iniciais. Vale igual nos Projetos Spot.

### Abas por situação
A lista tem abas **Planejamento**, **Em andamento** (padrão), **Concluído** e **Pausado**, cada uma com a contagem de projetos. Há filtros de saúde e de prioridade.

### Tela do projeto
No topo: escopo, situação, saúde, prioridade, responsável, datas, duração, progresso e orçamento; abaixo, os **indicadores de custo e prazo** (SPI, CPI, EAC, desvio no término) e as dimensões da saúde.

Abaixo ficam as telas do projeto, organizadas em cinco áreas. Escolha a área na barra superior e a tela nos botões logo abaixo (cada área lembra a última tela aberta):

| Área | Telas |
|---|---|
| **Cronograma e gráficos** | Cronograma (Gantt) · Burndown · PERT / Caminho crítico · Prioridades (Eisenhower) · Ishikawa |
| **Tarefas e marcos** | Tarefas · Marcos |
| **Financeiro e documentos** | Financeiro · Documentos |
| **Mudanças** | Changes |
| **Automação** (administrador) | Gatilhos de e-mail |

### Excluir um projeto
O sistema **não deixa excluir** projeto com vínculos (tarefas, lançamentos financeiros, documentos, changes, diagramas Ishikawa ou gatilhos). A mensagem diz o que ainda existe. Toda exclusão pede confirmação.

## Tarefas e marcos

- **+ Nova tarefa** cria uma tarefa principal; **+ Filha** cria uma subtarefa (dois níveis). **+ Novo marco** cria um marco.
- Campos: nome, descrição, responsável, início, fim, progresso, **situação** (A fazer, Em andamento, Concluída, Bloqueada, **Cancelada**), **Depende de** (uma tarefa predecessora), **Tarefa pai**, **Marcar como importante**.
- A tarefa-pai tem **progresso** (média simples das filhas: cada filha pesa igual) e **situação** calculados pelas filhas; filhas canceladas são ignoradas. O **início e o fim do pai são dele**: você os define na edição e mudar as datas das filhas **não altera** o pai. Uma tarefa pai não pode ser marco.
- **Cancelada** é para tarefas que deixaram de fazer sentido. Ficam com selo cinza, barra hachurada no Gantt e **fora de todos os cálculos** (atraso, progresso, saúde, relatórios, burndown, PERT).
- **Excluir tarefa:** bloqueado enquanto houver tarefa-pai, dependência, filhas ou outras tarefas dependendo dela; o aviso diz o que ajustar.
- **Concluir marco:** um clique na área Marcos.

### Importar tarefas
Botão **Importar** (telas Cronograma e Tarefas). Aceita **.mpp** (Project 2010+), **XML do Project**, **JSON** (lista genérica ou exportação do Teamwork) e **CSV**. Mostra uma **pré-visualização** com caixa de seleção por linha; só grava depois de confirmar. As tarefas importadas são **adicionadas** (não substituem as existentes). Detalhes dos formatos em [03 – Funcionalidades](03-funcionalidades.md#importação-de-tarefas).

## Cronograma (Gantt)

Árvore de tarefas à esquerda (recolher/expandir), barras com rótulos, barra de resumo do projeto, setas de dependência, linha de "hoje" e marcos em losango. Botões: Configurações, Ocultar concluídos, aproximar/afastar (zoom), Mostrar tudo e Imprimir. Clique em uma linha para abrir a tarefa (ou o projeto).

## Burndown

Mostra **quanto trabalho falta** ao longo do tempo:
- linha **ideal** (tracejada): do total no início a zero no prazo;
- linha **real**: cai a cada tarefa concluída;
- **projeção** (pontilhada): quando terminaria se o ritmo atual continuar.

Uma frase no topo resume a situação ("Faltam 62 dias de trabalho… 56 dias depois do prazo"). Escolha **medir por** duração (dias) ou quantidade de tarefas. Passe o mouse em qualquer ponto do gráfico para ver o valor do dia. Marcos, tarefas canceladas e tarefas-pai ficam de fora. Se houver tarefas concluídas antes do registro do dia de conclusão, o gráfico avisa que o histórico é aproximado (usa o fim planejado).

## PERT / Caminho crítico

Monta a rede a partir do campo **Depende de**. Cada caixa mostra duração, início/término mais cedo (ES/EF), mais tarde (LS/LF) e **folga**. O **caminho crítico** (folga zero) aparece em vermelho: qualquer atraso nele atrasa o projeto. Opções: mostrar só o caminho crítico, ajustar à largura ou tamanho real. Há indicadores (duração pela rede × prazo planejado, tarefas críticas, tarefas sem dependência) e a tabela das 40 folgas mais apertadas. Sem dependências cadastradas, a tela pede que você as cadastre.

## Prioridades (matriz de Eisenhower)

Disponível em dois lugares: no menu lateral (**Prioridades**, todos os projetos) e na aba **Prioridades (Eisenhower)** dentro de cada projeto (só as tarefas dele).

Distribui as tarefas em aberto em quatro quadrantes:

| | Urgente | Não urgente |
|---|---|---|
| **Importante** | **Fazer agora** | **Agendar** |
| **Não importante** | **Delegar** | **Eliminar ou adiar** |

*Importante* = tarefa marcada com a ★, ou de projeto Meta Empresa ou de prioridade Crítica/Alta. *Urgente* = vence em até N dias (3, 7, 14 ou 30) ou está atrasada. Filtre por responsável (Todos, Minhas tarefas, Sem responsável ou uma pessoa). Marque a estrela no cartão para mudar a importância na hora. Não entram tarefas concluídas, canceladas, tarefas-pai, nem projetos Concluídos ou Pausados.

## Financeiro (por projeto)

**+ Nota fiscal** ou **+ Fatura**: número, favorecido, descrição, emissão, vencimento, valor, situação (Pendente ou Pago) e data de pagamento. A tela mostra totais e o saldo contra o orçamento. Esses lançamentos alimentam o custo real (AC) dos indicadores.

## Documentos (por projeto)

Envie PDF, imagens, Office, ZIP, DWG etc. (até 25 MB), visualize PDF/imagem no sistema e baixe. Use o lápis para **editar** título, categoria e descrição (o arquivo em si não muda). Apagar: o administrador ou quem enviou.

## Changes

Toda change é também uma **tarefa** do projeto marcada como change: no formulário de tarefa há a opção **“Esta tarefa é uma change”**, que mostra os campos da change; o botão **+ Nova change** da aba Changes abre esse mesmo formulário já marcado. A tarefa aparece no cronograma, nas tarefas e no Kanban com a etiqueta **CHANGE**. Changes antigas (sem tarefa) mostram “sem tarefa” e, ao editar, viram tarefa. Remover a change (ou desmarcar a opção) tira a tarefa do fluxo de changes, mas a tarefa continua; excluir a tarefa mantém a change registrada. Campos da change: título (nome da tarefa), motivo, solicitante, data, **impacto em dias** e **em R$**, situação (pendente, aprovada, rejeitada). Changes aprovadas com impacto em dias aparecem em Relatórios (Atrasos gerados por changes); changes pendentes há muito tempo afetam a saúde do projeto.

## Ishikawa (espinha de peixe)

1. **+ Novo diagrama** e descreva o **problema** (o efeito), de forma objetiva (ex.: "Entrega do módulo Compras atrasou 20 dias").
2. Em cada categoria (Método, Material, Mão de obra, Máquina, Meio ambiente, Medição) clique em **+** para registrar uma causa.
3. Em cada causa, **+** adiciona uma **subcausa** (pergunte "por quê?" até chegar à raiz). Clique no texto para editar.
4. Uma causa que tem subcausas não pode ser excluída antes das subcausas.

Limites: causa até 200 caracteres, problema até 300, no máximo 300 causas por diagrama, um nível de subcausa.

## Relatórios & Dashs

Para todos os usuários (Projetos Spot não entram). Os relatórios são **de um projeto por vez**: a tela abre com uma explicação e a lista de projetos; escolha um (para fechamento, um projeto concluído) no campo **Projeto** ou clicando na lista. Há **Imprimir / salvar PDF**. Cada aba começa com um texto sobre a sua finalidade.
- **Projetos:** Timeline, Itens em atraso, Previsto × Realizado, Quantidade de changes por projeto, Atrasos gerados por changes.
- **Orçamentária:** Previsto × Realizado em R$, Custo do projeto total, Projeção de custo no término.

## Projetos Spot

Menu **Projetos Spot**. A lista tem guias **Contrato** (recorrentes), **Escopo fechado**, **Evolutivas** e **Avulsos** (quando existirem).

**Evolutivas Salesforce / Evolutivas Protheus:** escolha o **Tipo do projeto** no cadastro. O projeto reúne os pedidos que os usuários fazem por e-mail ou chamado TIFlux (não controla horas). Na aba **Demandas** registre cada pedido: canal, nº do chamado, solicitante, o que foi pedido, situação (Triagem → Em desenvolvimento → Em homologação → Entregue, ou Recusada), responsável e **o que o TI fez**. No campo **Anexos** guarde prints, o corpo do e-mail (.eml, .msg, .txt ou print) e o conteúdo do chamado TIFlux; eles aparecem como 📎 na lista. O campo **Validado por** (e-mails separados por `;`) aparece em Em homologação e Entregue. Os campos **O que o TI fez** e **Responsável do TI** são obrigatórios só ao entregar (o primeiro só aparece nessa situação). Ao marcar **Entregue** abre a caixinha **Feature entregue**: crie uma feature nova (recebe o número FT-001, FT-002…) ou escolha uma existente, descreva **o que foi feito** e anexe a documentação. Pedidos parecidos entram na mesma feature. A aba **Features entregues** tem filtro por **tipo de objeto** (Módulo / objeto) e mostra a ficha de cada uma, com o botão **Documentar** para completar depois. O **Resumo** lista os pedidos em aberto com os dias de espera.

- **Projeto:** nome, cliente, fornecedor/equipe, modelo (franquia mensal ou pacote), horas fixadas, início, data final (vigência), participantes do comitê, destinatários e observação de faturamento. **Escopo fechado** não controla horas; exige o valor do projeto em R$.
- **Semáforo de horas:** na lista (coluna Saúde) e no Resumo (painel Ritmo de horas) a franquia é dividida pelas semanas úteis do período e comparada com o que já foi consumido: verde = no ritmo, amarelo = perto do limite, vermelho = vai estourar. O painel mostra até quando o saldo dura e quanto ainda cabe por semana. Na primeira semana útil aparece "poucos dados".
- **Abas do projeto:** Resumo, Escopo e Financeiro (só escopo fechado), Apontamentos de horas, Demandas (com horas aprovadas), Ações e pendências, Relatório tradicional (só escopo fechado) e Relatórios com Claude.
- **Fatura excedente:** preencha **Hora desenvolvimento parceiro** e **Hora excedente desenvolvimento parceiro** (valor da hora/homem negociado; vale só para projetos Spot que controlam horas). Se as horas consumidas passarem da franquia, o Resumo mostra as horas excedentes e a **fatura excedente a gerar** (horas excedentes × valor da hora excedente), com a conta escrita por extenso. Os valores são **sem impostos acrescidos**.
- **Copiar projeto** (contratos recorrentes): copia o cadastro (inclusive os valores de hora); datas e observação ficam em branco e a situação vem como Ativo.
- **Projeto encerrado:** só o administrador altera, com justificativa (mín. 10 caracteres) registrada no histórico.
- **Importar Status Report da Fábrica (HTML):** com prévia para revisão, nas abas de apontamentos, demandas e ações.
- **Relatórios:** o texto é gerado pelo Claude a partir dos números calculados pelo sistema, pode ser editado, enviado por e-mail (Para, CC, CCO, separados por `;`) ou impresso/salvo em PDF.

## Administração

- **Usuários:** cadastrar (nome, e-mail, perfil, **cargo**), bloquear/ativar, exigir 2FA. O novo usuário recebe e-mail de boas-vindas com link para criar a senha.
- **IA Agent:** cole a chave de API do Claude, escolha o modelo, *Conectar*, *Testar conexão* ou *Desconectar*.
- **E-mail:** configure o SMTP pela tela (com *Enviar teste*, que gera um arquivo de log), crie **gatilhos** e consulte o **histórico** de envios (90 dias).
- **Segurança e dados:** pasta de dados em uso, endereço (`app.url`), backups (fazer agora, verificar, histórico) e trilha de auditoria.
