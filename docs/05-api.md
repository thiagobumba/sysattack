# 05 – API

Visão geral da API JSON do SysAttack. A fonte da verdade é o código: `ApiServlet.java` (roteamento principal) e os serviços `ChangeService`, `IshikawaService`, `EmailService`, `SpotService`.

> Lista de referência resumida. Antes de integrar com uma rota, confirme método, campos e permissões no código correspondente.

## Convenções

- **Base:** `/api/...` (o servlet responde em `/api/*`). Corpo e resposta em **JSON** (UTF-8).
- **Autenticação:** sessão por cookie (`HttpOnly`, `SameSite=Lax`) criada em `POST /api/login`. Sem sessão válida, as rotas protegidas respondem **401**.
- **Escritas (POST/PUT/DELETE):** exigem o cabeçalho `X-Requested-With: SysAttack` e `Origin` do mesmo servidor (proteção contra CSRF); sem isso, **403**.
- **Atomicidade:** toda escrita roda em uma transação; erro desfaz tudo.
- **Erros:** `{ "error": "mensagem em português" }` com o status adequado:

| Status | Quando |
|---|---|
| 400 | Dados inválidos ou regra de negócio (ex.: dependência circular) |
| 401 | Sem sessão |
| 403 | Sem permissão (não administrador, CSRF, pendência de troca de senha ou de 2FA) |
| 404 | Rota ou registro inexistente |
| 405 | Operação não suportada |
| 409 | Conflito de integridade (ex.: excluir com vínculos) |
| 500 | Erro interno (detalhe só no log do servidor) |

- Enquanto o usuário tiver **troca de senha** ou **ativação de 2FA** pendente, só `me` responde; o restante retorna 403.
- Datas em `AAAA-MM-DD`. Valores monetários como número decimal.

## Acesso e conta

| Método e rota | Descrição |
|---|---|
| `GET /setup-status` · `POST /setup` | Verifica/cria o administrador do primeiro acesso |
| `POST /login` · `POST /login/2fa` · `POST /logout` | Entrada, segunda etapa e saída |
| `GET/POST /invite/{token}…` | Convite: consultar e definir a primeira senha |
| `GET /me` | Usuário logado |
| `POST /me/password-code` · `/me/password` · `/me/password-first` | Código por e-mail, troca de senha e primeira senha |
| `/me/2fa/...` | `start`, `enable`, `disable`, `recovery` (2FA) |
| `GET/POST /users` · `GET/PUT/DELETE /users/{id}` | Usuários (administrador; usuário comum vê só o próprio) |

## Projetos, tarefas e vínculos

| Método e rota | Descrição |
|---|---|
| `GET /dashboard` | Indicadores da Visão geral |
| `GET/POST /projects` | Listar (com saúde e EVM) e criar |
| `GET/PUT/DELETE /projects/{id}` | Detalhe, alterar, excluir (409 se houver vínculos) |
| `GET/PUT/POST/DELETE /projects/{id}/logo` | Imagem do projeto: `GET` devolve o arquivo; `PUT`/`POST` enviam o corpo binário (PNG/JPG/GIF/WEBP, máx. 1 MB, 413 se maior, 400 se o tipo não for imagem); `DELETE` remove. O projeto traz `logo_at` (data da imagem, nulo se não houver) |
| `GET /projects/{id}/links` | O que impede a exclusão (`message`) |
| `GET /projects/{id}/tasks` | Tarefas do projeto |
| `GET /projects/{id}/finance` | Financeiro do projeto com totais |
| `GET/POST /projects/{id}/documents` | Documentos do projeto (upload multipart) |
| `POST /projects/{id}/import-preview` · `/import-tasks` | Importação de tarefas: leitura sem gravar e gravação |
| `GET/POST /tasks` · `GET/PUT/DELETE /tasks/{id}` | Tarefas (a exclusão respeita os vínculos) |
| `GET /tasks/{id}/links` | O que impede a exclusão da tarefa |
| `PUT /tasks/{id}/done` | Concluir/reabrir (`{ "done": true }`); grava `completed_at` |
| `PUT /tasks/{id}/important` | Marcar importante (`{ "important": true }`) |
| `GET/POST /finance` · `PUT/DELETE /finance/{id}` | Lançamentos financeiros |
| `PUT/DELETE /documents/{id}` · `GET /documents/{id}/file` | Editar metadados, apagar, baixar/visualizar |
| `GET /changes` · `DELETE /changes/{id}` | Changes (`GET /changes?project_id=`, com dados da tarefa ligada); a change é criada e editada pela **tarefa**: `POST/PUT /tasks` com `is_change` e `change` (`status`, `change_date`, `impact_days`, `impact_cost`, `requested_by`, `reason`, `link_id` para ligar uma change antiga). `is_change: false` tira a tarefa do fluxo de changes; sem o campo, nada muda |
| `GET/POST /ishikawa` · `PUT/DELETE /ishikawa/{id}` | Diagramas (`GET /ishikawa?project_id=`) |
| `POST /ishikawa/{id}/causes` · `PUT/DELETE /ishikawa/causes/{id}` | Causas e subcausas |
| `GET /reports` | Dados de Relatórios & Dashs |

### Campos da tarefa (POST/PUT `/tasks`)
`project_id`, `name`, `description`, `responsible`, `start_date`, `end_date`, `progress`, `status`, `depends_on`, `parent_id`, `is_milestone`, `is_important`. O servidor calcula `completed_at` e recalcula a tarefa-pai. A listagem devolve também `project_name`, `project_priority`, `project_goal`, `project_status` e `child_count`.

## Spot (`/spot`)

| Método e rota | Descrição |
|---|---|
| `GET/POST /spot` | Listar e criar projetos Spot (cada item traz `health`, o semáforo de horas: `level`, `label`, `dims`, `reasons`, `projected`…; nulo no escopo fechado, que traz o seu em `scope.health`: entregas × prazo) |
| `GET/PUT/DELETE /spot/{id}` | Detalhe (`?ref=` data de referência), alterar, excluir |
| `GET/POST /spot/{id}/hours` · `…/hours/{hid}` | Apontamentos (`?month=`, `?from=&to=`) |
| `GET/POST /spot/{id}/demands` · `…/demands/{did}` | Demandas |
| `GET/POST /spot/{id}/requests` · `…/requests/{rid}` | Pedidos dos projetos evolutivos (`kind` `EVOL_SF`/`EVOL_PT`): canal `TIFLUX`/`EMAIL`, nº do chamado, solicitante, situação (`TRIAGEM`, `EM_DESENVOLVIMENTO`, `EM_HOMOLOGACAO`, `ENTREGUE`, `RECUSADA`), solução do TI e feature (obrigatória ao entregar). `GET /spot` e `GET /spot/{id}` trazem `kind`, `ticket_url` e o resumo `evo` |
| `GET/POST /spot/{id}/actions` · `…/actions/{aid}` | Ações e pendências |
| `GET/POST /spot/{id}/invoices` · `…/invoices/{iid}` · `…/invoices/{iid}/file` | Notas do escopo fechado |
| `GET/PUT/POST/DELETE /spot/{id}/logo` | Imagem do projeto Spot (mesmas regras de `/projects/{id}/logo`; em projeto encerrado exige administrador e `X-Justification`) |
| `GET/POST /spot/{id}/files` | Arquivos do projeto |
| `GET /spot/{id}/audit` | Histórico de alterações |
| `POST /spot/{id}/import` | Importar Status Report (HTML), com prévia |
| `GET /spot/{id}/reports` · `POST …/reports/generate` · `…/import` · `…/manual` · `…/reports/{rid}/send` | Relatórios: listar, gerar com Claude, importar, criar manual, enviar por e-mail |

## Administração (somente administrador, salvo indicação)

| Método e rota | Descrição |
|---|---|
| `GET /ai` · `PUT /ai` (`in_use`: `claude`, `openai` ou `azure`) | Estado das três IAs e escolha da IA em uso nos relatórios (administrador) |
| `PUT/DELETE /ai/{ia}` · `POST /ai/{ia}/connect` · `POST /ai/{ia}/test` · `GET /ai/{ia}/models` · `POST /ai/openai/probe` | Conexão de cada IA (`claude`, `openai`, `azure`); `probe` valida a chave da OpenAI e lista os modelos |
| `GET /ai-status` | Disponibilidade da IA (qualquer usuário logado) |
| `GET/PUT/DELETE /email/settings` · `POST /email/settings/test` · `GET /email/settings/test-logs` | SMTP e testes |
| `GET /email/events` · `GET/POST /email/rules` · `PUT/DELETE /email/rules/{id}` · `GET /email/log` | Gatilhos e histórico |
| `/backup/...` | Listar, `run` (fazer agora), verificar, `download` |
| `GET /security` | Trilha de auditoria (últimos 200 eventos) |

## Exemplos

Concluir uma tarefa:

```http
PUT /sysattack/api/tasks/42/done
X-Requested-With: SysAttack
Content-Type: application/json

{ "done": true }
```

Erro de regra de negócio:

```json
{ "error": "Dependência circular entre tarefas." }
```
