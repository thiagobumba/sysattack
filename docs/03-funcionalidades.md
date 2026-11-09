# 03 – Funcionalidades e regras

Catálogo completo do que o sistema faz, com as regras de negócio e as fórmulas. Para o passo a passo das telas veja o [Guia do usuário](02-guia-do-usuario.md).

## Índice
1. [Acesso e segurança](#1-acesso-e-segurança)
2. [Projetos](#2-projetos)
3. [Tarefas e marcos](#3-tarefas-e-marcos)
4. [Importação de tarefas](#importação-de-tarefas)
5. [Visões e gráficos de gestão](#5-visões-e-gráficos-de-gestão)
6. [Saúde do projeto e indicadores de valor agregado](#6-saúde-do-projeto-e-indicadores-de-valor-agregado)
7. [Financeiro, documentos e changes](#7-financeiro-documentos-e-changes)
8. [Ishikawa](#8-ishikawa)
9. [Relatórios & Dashs](#9-relatórios--dashs)
10. [Projetos Spot](#10-projetos-spot)
11. [E-mail e gatilhos](#11-e-mail-e-gatilhos)
12. [IA Agent (Claude)](#12-ia-agent-claude)
13. [Dados, backup e auditoria](#13-dados-backup-e-auditoria)
14. [Interface e usabilidade](#14-interface-e-usabilidade)

---

## 1. Acesso e segurança

| Recurso | Regra |
|---|---|
| Primeiro acesso | Cria o administrador; só é aceito uma vez (marcador `.initialized` protege contra banco vazio em instalação já configurada) |
| Senha | PBKDF2-HMAC-SHA256 (600 mil iterações, sal por usuário); 10+ caracteres com minúscula, maiúscula, número e símbolo |
| Troca de senha | Exige código enviado por e-mail |
| Convite | Usuário novo nasce sem senha; recebe e-mail com link de uso único (token de 256 bits, só o hash fica no banco, válido por 72 h) |
| Senha definida pelo administrador | Exige troca no primeiro login |
| 2FA (TOTP, RFC 6238) | SHA-1, 6 dígitos, 30 s, janela ±1, sem reuso do mesmo código; 8 códigos de recuperação (guardados como hash); administrador pode exigir |
| Bloqueio de login | Após 5 falhas (conta+IP), 20 (conta) ou 40 (IP); resposta igual para e-mail inexistente |
| Sessão | Nova a cada login; expira em 4 h sem uso |
| Perfis | Administrador e Usuário (ver [Visão geral](01-visao-geral.md#perfis-de-acesso)); cada usuário tem também um **cargo** (texto livre) |
| Proteções do navegador | CSP restritiva, X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy, HSTS (em HTTPS), cookie HttpOnly + SameSite=Lax, conferência de `Origin` e cabeçalho `X-Requested-With` nas escritas |
| Auditoria | Logins, falhas, bloqueios, senhas, usuários, backups e downloads, por 1 ano |

Mais detalhes e limites em [`../SEGURANCA.md`](../SEGURANCA.md).

## 2. Projetos

- **Cadastro:** nome, escopo inicial, responsável, situação, prioridade, ★ Meta Empresa, início, fim, orçamento.
- **Situações:** Planejamento, Em andamento, Concluído, Pausado. A lista é dividida em abas por situação (padrão: Em andamento), com contagem.
- **Prioridade:** Crítica, Alta, Média, Baixa. **★ Meta Empresa** coloca o projeto acima de qualquer prioridade na ordenação.
- **Orçamento:** em R$, com máscara brasileira (1.234,56).
- **Progresso do projeto:** média do progresso das tarefas principais (a tarefa-pai é calculada pelas filhas, ponderada pela duração); canceladas não entram.
- **Duração:** dias corridos entre início e fim, contando os dois.
- **Exclusão protegida:** bloqueada se houver tarefas, lançamentos financeiros, documentos, changes, diagramas Ishikawa ou gatilhos de e-mail. A mensagem lista o que existe. A checagem vale também no servidor (HTTP 409).
- **Projetos de exemplo:** criados só uma vez, em banco totalmente novo (marca `seeded` em `app_meta`); bancos em uso nunca os recebem de novo.

## 3. Tarefas e marcos

### Campos
Nome (até 160 caracteres), descrição, responsável, início, fim, progresso (0–100), situação, predecessora (**Depende de**), tarefa-pai, marco, **importante**. O sistema também registra o **dia da conclusão** (`completed_at`).

### Situações
A fazer · Em andamento · Concluída · Bloqueada · **Cancelada**.

### Regras
- **Dois níveis:** tarefa principal e filhas. Uma filha não pode ter filhas.
- **Tarefa-pai calculada** (`TaskRules.rollup`): início = menor início das filhas; fim = maior fim; progresso = média ponderada pela duração; situação derivada. Filhas **canceladas são ignoradas**; se todas forem canceladas, o pai fica Cancelado.
- **Dependência:** uma predecessora por tarefa, **do mesmo projeto**; o servidor (`checkDependency`) recusa dependência da tarefa nela mesma, predecessora inexistente e dependência circular.
- **Cancelada:** fica fora de atraso, progresso, saúde, relatórios, burndown e PERT.
- **Atrasada** (cálculo, não uma situação): fim anterior a hoje e situação diferente de Concluída/Cancelada.
- **Dia da conclusão:** gravado ao virar Concluída; mantido enquanto continuar concluída; apagado se for reaberta. Tarefas concluídas antes dessa função não têm o dia e usam o fim planejado como aproximação nos gráficos.
- **Exclusão protegida:** bloqueada se a tarefa tem pai, dependência (Depende de), filhas ou outras tarefas dependendo dela. A mensagem diz o relacionamento e o que ajustar.
- **Marcos:** qualquer tarefa pode ser marcada como marco (losango no Gantt). A área Marcos tem abas Todos / Atrasados / Concluídos e conclusão com um clique.
- **Importante:** marca manual usada pela matriz de Eisenhower (`PUT tasks/{id}/important`).

## Importação de tarefas

Botão **Importar**. A leitura (`ProjectFileReader`) detecta o formato pelo conteúdo: JSON (`{` ou `[`), CSV, XML e, por último, .mpp (arquivo OLE). Sempre há **pré-visualização** com caixa por linha; só grava depois de confirmar (`POST projects/{id}/import-tasks`). As tarefas são **adicionadas**.

| Formato | Observações |
|---|---|
| **.mpp** | Project 2010 ou mais novo. Para versões antigas, exporte para XML |
| **XML (MSPDI)** | Arquivo › Salvar como › XML no Project |
| **JSON genérico** | Lista de tarefas ou objeto com `tasks`/`tarefas`; campos `id`, `name`, `start`, `end` (AAAA-MM-DD ou DD/MM/AAAA), `percent`, `level`, `children`, `predecessors`, `milestone`, `responsible` |
| **JSON do Teamwork** | Reconhecido por `tasklists`, `milestones`, `subtasks`: cada lista vira tarefa de nível 1, marcos viram marcos, tarefa sem data herda a do marco da lista, "completed" vira 100% |
| **CSV** | Cabeçalho na primeira linha; mesmas ideias do JSON |

Regras: o nível escolhido vira tarefa principal e o que está abaixo vira subtarefa (2 níveis); marcos viram marcos; em JSON/CSV uma tarefa só é marco se tiver o indicador de marco (tarefa de um dia não é marco automaticamente); a predecessora importada é a primeira da lista; o responsável é importado quando existe no arquivo. **Não importa** recursos nem calendários do Project.

## 5. Visões e gráficos de gestão

### Cronograma (Gantt)
Árvore à esquerda (recolher/expandir), barras de resumo, rótulos, dependências, linha de hoje, marcos em losango, zoom, "Mostrar tudo", ocultar concluídos, configurações e impressão. Tarefas canceladas aparecem hachuradas. Há o Gantt do projeto (aba) e o Gantt geral (menu Cronograma).

### Burndown (`js/burndown.js`)
- **Escopo:** tarefas-filha (ou sem filhas), sem marcos e sem canceladas.
- **Peso:** duração em dias (`fim − início + 1`) ou 1 por tarefa, conforme "Medir por".
- **Eixo:** de `D0 = min(início do projeto, início das tarefas)` até o maior entre prazo, hoje e fim das tarefas.
- **Ideal:** reta do total em D0 até zero no prazo.
- **Real:** `restante(dia) = total − Σ pesos das tarefas concluídas até o dia`, em degraus.
- **Projeção:** `ritmo = concluído ÷ dias decorridos`; `término previsto = hoje + restante ÷ ritmo`; comparado ao prazo.
- **Situação:** compara o restante real com o ideal de hoje, com tolerância de 3% do total (No ritmo / Adiantado / Atrás do ideal).
- **Histórico aproximado:** tarefas concluídas sem `completed_at` usam `min(fim planejado, hoje)`; o gráfico avisa quantas.

### PERT / caminho crítico (`js/pert.js`)
- **Nós:** tarefas-filha não canceladas; marcos têm duração 0; dependência em tarefa-pai vale para todas as filhas dela.
- **Duração:** dias corridos da tarefa (`fim − início + 1`).
- **Passagem para frente:** `ES = max(EF das predecessoras)`, `EF = ES + duração`. **Para trás:** `LF = min(LS das sucessoras)`, `LS = LF − duração`. **Folga** `= LS − ES`; **crítica** quando a folga é 0.
- **Tipo de dependência:** término-início.
- **Ciclos** são ignorados, com aviso; tarefas sem nenhuma ligação ficam fora da rede e são contadas à parte.
- **Duração pela rede** é comparada ao prazo planejado do projeto.

### Prioridades (Eisenhower, `js/prioridades.js`)
Tela geral (menu) e aba por projeto, com as mesmas regras.
Quatro quadrantes (Fazer agora, Agendar, Delegar, Eliminar ou adiar). *Importante* = ★ manual, projeto Meta Empresa ou prioridade Crítica/Alta. *Urgente* = fim em até N dias (3/7/14/30) ou atrasada. Filtros por responsável e por janela de urgência (a janela fica salva no navegador).

## 6. Saúde do projeto e indicadores de valor agregado

### Saúde (`HealthCalc`)
Calculada na hora, **só para projetos Em andamento**. O resultado é o pior entre quatro dimensões (Verde, Amarelo, Vermelho; rótulos Saudável / Atenção / Crítico):

| Dimensão | Amarelo | Vermelho |
|---|---|---|
| **Prazo** | Progresso > 5 p.p. abaixo do previsto, ou há tarefa atrasada | > 15 p.p. abaixo, ou data final do projeto vencida |
| **Custo** | Gasto > 90% do orçamento | Gasto > 100% do orçamento |
| **Marcos** | 1 marco atrasado | 2 ou mais marcos atrasados |
| **Mudanças** | Change pendente há mais de 7 dias, ou changes aprovadas que somam dias de atraso | Change pendente há mais de 7 dias com impacto em dias |

Um projeto **Concluído acima do orçamento** recebe o selo "Concluído c/ estouro" (nunca aparece como "tudo certo").

### Valor agregado (EVM)
Requer orçamento > 0 e tarefas. Progresso e prazo são ponderados pela duração das tarefas (marcos fora); custo real = notas fiscais e faturas lançadas.

| Indicador | Fórmula |
|---|---|
| **BAC** | Orçamento |
| **PV** (previsto) | `BAC × % previsto pelo prazo` |
| **EV** (agregado) | `BAC × % realizado` |
| **AC** (custo real) | Soma dos lançamentos do Financeiro |
| **SPI** | `EV ÷ PV` (abaixo de 1 = atrasado) |
| **CPI** | `EV ÷ AC` (abaixo de 1 = acima do custo); arredondado em 4 casas e exibido sem arredondar para cima |
| **EAC** | `BAC ÷ CPI` (custo previsto no término) |
| **VAC** | `BAC − EAC` (desvio no término) |
| **ETC** | `EAC − AC` |

A projeção só é considerada confiável com um mínimo de progresso realizado; o cartão fica vermelho quando `AC > BAC`.

## 7. Financeiro, documentos e changes

- **Financeiro (por projeto):** tipos **Nota fiscal de serviço** e **Fatura**; situação **Pendente** ou **Pago**; totais e saldo contra o orçamento.
- **Documentos (por projeto):** PDF, imagens, Office, ZIP, DWG etc. até 25 MB; só tipos permitidos (nada de HTML/SVG); o nome do arquivo no disco é gerado pelo servidor; visualização de PDF/imagem no sistema; download com `nosniff`; **edição** de título, categoria e descrição; apagar = administrador ou autor do envio. O arquivo só é apagado do disco depois que o banco confirma.
- **Changes:** título, motivo, solicitante, data, impacto em dias e em R$, situação **Pendente / Aprovada / Rejeitada**. Alimentam a saúde e os relatórios.

## 8. Ishikawa

Diagramas por projeto, com as 6 categorias (Método, Material, Mão de obra, Máquina, Meio ambiente, Medição), causas e **uma** camada de subcausas. Limites: causa 200 caracteres, problema 300, 300 causas por diagrama. Causa com subcausas não pode ser excluída (HTTP 409). Dá para ter vários diagramas por projeto.

## 9. Relatórios & Dashs

Para todos os usuários; projetos Spot não entram. **Projetos:** Timeline, Itens em atraso, Previsto × Realizado (progresso esperado pelo prazo × informado, ponderado pela duração), Quantidade de changes por projeto, Atrasos gerados por changes (só aprovadas com impacto em dias). **Orçamentária:** Previsto × Realizado em R$ e Custo do projeto total; inclui a Projeção de custo no término. Filtro por projeto e impressão/PDF. Tarefas canceladas ficam de fora.

## 10. Projetos Spot

- **Tipos:** *Contrato* (recorrente, controla horas), *Escopo fechado* (sem horas; controla escopo, entregas e financeiro) e *Avulso*.
- **Cadastro:** nome, cliente, fornecedor/equipe, modelo (franquia mensal ou pacote), horas fixadas, início, data final (vigência), participantes do comitê, destinatários, observação de faturamento; escopo fechado exige o **valor do projeto**.
- **Situações do projeto:** Ativo, Pausado, Encerrado. Encerrado só é alterado por administrador, com justificativa (mín. 10 caracteres) que vai ao histórico.
- **Apontamentos de horas:** data, frente, atividade, demanda, horas; filtro De/Até limitado à vigência.
- **Demandas:** situação Em análise, Aprovado, Em andamento, Em homologação, Em espera, Concluída ou Cancelada; horas aprovadas, próximos passos, observações.
- **Ações e pendências:** tipo Ação ou Pendência; situação Pendente, A confirmar, Parcial ou Concluído.
- **Financeiro (escopo fechado):** notas com número, emissão, vencimento, valor, situação Emitida/Paga/Cancelada e PDF anexado (guardado no banco). A visão mostra Valor do projeto, Faturado, A faturar etc.
- **Entrega (escopo fechado):** uma demanda só conta como entregue com ao menos um apontamento vinculado; `% de entrega = demandas entregues ÷ demandas do escopo` (canceladas não contam); todo apontamento exige a demanda que ele entrega.
- **Fatura excedente (somente Projetos Spot que controlam horas):** o cadastro tem **Hora desenvolvimento parceiro (R$/h)** e **Hora excedente desenvolvimento parceiro (R$/h)**, o valor da hora/homem negociado. No Resumo: *Horas excedentes* = `max(0, consumidas − fixadas)`; **Fatura excedente a gerar** = horas excedentes × valor da hora excedente; *Projetada* = `max(0, consumidas + reservadas − fixadas)` × valor da hora excedente; a franquia em R$ (horas fixadas × valor da hora) aparece como informação. O bloco aparece sempre nos projetos que controlam horas (sem os valores, avisa onde preencher) e não vai nos relatórios enviados ao cliente.
- **Cálculo de horas (feito pelo sistema):** mensal = do dia 1º do mês de referência até a data; pacote = desde o início. Consumidas = soma das horas do período; reservadas = Σ(aprovado − realizado) das demandas abertas; saldo = fixadas − consumidas; saldo projetado = saldo − reservadas.
- **Copiar projeto:** só para contratos recorrentes (cadastro, modelo, horas, participantes, destinatários; datas e observação em branco; situação Ativo).
- **Importar Status Report da Fábrica (HTML):** sempre com prévia; entende o modelo "Consumo do mês por demanda" + "Decisões do comitê"; cada linha de consumo vira um apontamento.
- **Relatórios com Claude:** texto (destaques, alertas, resumo por demanda, mensagem do e-mail) gerado a partir dos números calculados; editável antes do envio.
- **Relatório tradicional (escopo fechado):** HTML profissional preenchido com os dados do sistema (visão, demandas e apontamentos, notas), com envio por e-mail.
- **Envio:** Para, CC e CCO (separados por `;`), corpo em HTML compatível com o Outlook (só tabelas, `bgcolor`, Segoe UI), registro do envio; PDF via Imprimir / salvar PDF.

## 11. E-mail e gatilhos

- **SMTP pela tela** (administrador): servidor, porta, segurança, usuário, senha (criptografada com a `ai.key`), remetente; botão **Enviar teste**, que gera um `.log` completo (conversa SMTP, resultado, erros; sem a senha). Se nada estiver salvo na tela, vale o `config.properties`.
- **Gatilhos** ("quando X → enviar para Y"), globais ou por projeto: projeto criado, situação do projeto alterada, tarefa criada, tarefa concluída, marco concluído, **tarefa atrasada** (verificada a cada 30 minutos; um aviso por tarefa), lançamento financeiro criado, documento adicionado.
- **Destinatários:** lista de e-mails e, opcionalmente, o responsável (quando o nome dele coincide com o de um usuário ativo). **Assunto** aceita `{evento}`, `{projeto}`, `{item}`.
- **Histórico** de envios por 90 dias. O envio é em segundo plano; falha de e-mail nunca impede a operação. Tarefas canceladas não geram aviso de atraso.
- **Convites** não são enviados se o endereço do sistema for `localhost`; o administrador é orientado a definir `app.url`.

## 12. IA Agent (Claude)

A Anthropic não permite login de contas Claude.ai em sistemas de terceiros; a conexão usa uma **chave de API** do Claude Console. O SysAttack valida a chave antes de salvar, grava criptografada (AES-256-GCM, chave-mestra `ai.key`) e nunca a devolve ao navegador. Há *Conectar*, *Testar conexão* (mensagem mínima) e *Desconectar*. O servidor precisa alcançar `api.anthropic.com:443` (proxy via `-Dhttps.proxyHost`/`-Dhttps.proxyPort`). O uso é cobrado na conta do Console. A IA só escreve texto de relatório; **não calcula nada**.

## 13. Dados, backup e auditoria

- **Pasta de dados:** `-Dsysattack.data=...` ou `SYSATTACK_DATA` (padrão `~/.sysattack`). Contém `sysattack.mv.db`, `docs/`, `ai.key`, `config.properties`.
- **Backup automático:** todo dia às 02:00 (e logo após subir, se o último tem mais de 26 h) mais "Fazer backup agora". Um `.zip` por backup (banco físico + script SQL, documentos, configuração), **verificado** (CRC + abertura do banco) e com `.sha256`. Retenção: 1 por dia (14 dias), 1 por semana (8), 1 por mês (12), manuais por 90 dias; os 3 mais novos nunca são apagados.
- **Cópia espelho** (`backup.mirror`) e outro disco (`backup.dir`).
- **Antes de atualizar o esquema** o arquivo do banco é copiado para `pre-update-*.mv.db` (guarda as 5 últimas).
- **Restauração:** `restaurar-backup.ps1` (confere o SHA-256 e guarda o estado atual antes de restaurar).
- **Segurança e dados** (tela): pasta em uso, endereço nos e-mails, backups e trilha de auditoria.
- **Atualização do esquema:** versão em `Db.SCHEMA_VERSION` (atualmente 46); colunas novas entram com `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, sem perder dados.

## 14. Interface e usabilidade

- **Identidade** alinhada ao site da Momesso (laranja `#F45200`, creme `#FDDDB3`, fonte Inter, cantos retos), menu lateral escuro agrupado, cabeçalho de página com degradê, marca d'água e rodapé de direitos autorais.
- **Hints detalhados** em selos, indicadores, abas e gráficos (título, explicação, fórmula, valores); abrem com mouse ou teclado.
- **Navegação do projeto** em cinco áreas com telas agrupadas por tipo de ação (Cronograma e gráficos, Tarefas e marcos, Financeiro e documentos, Mudanças, Automação).
- **Responsivo:** cartões em duas colunas no tablet/celular, gráficos que usam a largura real da tela (sem cortes), tabelas compactas.
- **Acessibilidade:** foco visível nos campos, papéis ARIA nas abas, balões acessíveis por teclado.
- **Confirmações** em toda exclusão.
- **Impressão/PDF** nos relatórios, no Gantt e nos relatórios dos Projetos Spot.
- **Versão** exibida no rodapé do menu e na tela de entrada, a partir de `version.json` (`1.1.0 – Alpha`).
