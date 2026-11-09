# 06 – Modelo de dados

Banco **H2** embutido (arquivo `sysattack.mv.db` na pasta de dados). O esquema é criado e atualizado por `Db.java` ao subir o sistema; a versão do esquema está em `Db.SCHEMA_VERSION` (atualmente **46**). Colunas novas entram com `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, então atualizar o `.war` nunca apaga dados. Antes de mudar o esquema, o sistema copia o banco para `pre-update-*.mv.db`.

> A lista abaixo foi gerada a partir do `Db.java`. Se houver divergência, o código é a fonte da verdade.

## Tabelas

| Tabela | Colunas |
|---|---|
| `users` | `id`, `username`, `name`, `password_hash`, `salt`, `role`, `active`, `job_title`, `invite_pending`, `must_change`, `totp_secret_enc`, `totp_enabled`, `totp_required`, `totp_last_step` |
| `recovery_codes` | `id`, `user_id`, `code_hash` |
| `user_tokens` | `id`, `user_id`, `token_hash`, `expires_at`, `created_at` |
| `projects` | `id`, `name`, `scope`, `owner`, `status`, `start_date`, `end_date`, `created_at`, `budget`, `priority`, `company_goal` |
| `tasks` | `id`, `project_id`, `name`, `description`, `responsible`, `start_date`, `end_date`, `progress`, `status`, `depends_on`, `parent_id`, `is_milestone`, `is_important`, `completed_at` |
| `finance_entries` | `id`, `project_id`, `kind`, `number`, `party`, `description`, `issue_date`, `due_date`, `amount`, `status`, `paid_date`, `created_at` |
| `documents` | `id`, `project_id`, `title`, `category`, `description`, `file_name`, `stored_name`, `size`, `uploaded_by`, `uploaded_by_id`, `uploaded_at` |
| `spot_projects` | `id`, `name`, `client`, `provider`, `description`, `hours_model`, `contracted_hours`, `start_date`, `status`, `participants`, `recipients`, `billing_note`, `created_at`, `end_date`, `recurring`, `closed_scope`, `project_value`, `partner_hour_rate`, `partner_overage_rate` |
| `spot_files` | `id`, `spot_id`, `title`, `description`, `file_name`, `size`, `content`, `uploaded_by`, `uploaded_at` |
| `spot_invoices` | `id`, `spot_id`, `number`, `issue_date`, `due_date`, `amount`, `status`, `description`, `file_name`, `file_size`, `file_content`, `created_by`, `created_at`, `updated_at` |
| `spot_demands` | `id`, `spot_id`, `name`, `status`, `next_step`, `approved_hours`, `notes`, `updated_at` |
| `spot_hours` | `id`, `spot_id`, `work_date`, `front`, `activity`, `demand_id`, `hours`, `created_by`, `created_at` |
| `spot_actions` | `id`, `spot_id`, `kind`, `description`, `responsible`, `status`, `note`, `updated_at` |
| `spot_audit` | `id`, `spot_id`, `spot_name`, `user_name`, `action`, `justification`, `created_at` |
| `spot_reports` | `id`, `spot_id`, `title`, `ref_date`, `snapshot`, `narrative`, `model`, `created_by`, `created_at`, `sent_at`, `sent_to`, `sent_by` |
| `ai_settings` | `id`, `api_key_enc`, `key_hint`, `model`, `workspace_id`, `connected_by`, `connected_by_id`, `connected_at`, `last_test_at`, `last_test_ok`, `last_test_msg` |
| `email_settings` | `id`, `host`, `port`, `security`, `username`, `password_enc`, `from_email`, `from_name`, `enabled`, `updated_by`, `updated_at`, `last_test_at`, `last_test_ok`, `last_test_msg` |
| `email_rules` | `id`, `project_id`, `event`, `recipients`, `include_responsible`, `subject`, `message`, `enabled`, `created_by`, `created_at` |
| `email_log` | `id`, `rule_id`, `project_id`, `event`, `ref_key`, `recipients`, `subject`, `status`, `error`, `created_at` |
| `email_test_log` | `id`, `created_at`, `created_by`, `recipient`, `ok`, `summary`, `file_name`, `log` |
| `project_changes` | `id`, `project_id`, `title`, `reason`, `requested_by`, `change_date`, `impact_days`, `impact_cost`, `status`, `created_by`, `created_at` |
| `ishikawa` | `id`, `project_id`, `problem`, `created_by`, `created_at` |
| `ishikawa_causes` | `id`, `ishikawa_id`, `category`, `text`, `parent_id` |
| `app_meta` | `k`, `v` |
| `audit_log` | `id`, `at`, `actor`, `action`, `detail`, `ip`, `ok` |

## Para que serve cada grupo

| Grupo | Tabelas | Observações |
|---|---|---|
| Acesso | `users`, `recovery_codes`, `user_tokens` | Senha como PBKDF2; tokens de convite só como hash; segredo do 2FA criptografado |
| Projetos | `projects`, `tasks`, `finance_entries`, `documents`, `project_changes`, `ishikawa`, `ishikawa_causes` | `tasks` guarda tarefas, filhas (`parent_id`), marcos (`is_milestone`), predecessora (`depends_on`), importância (`is_important`) e dia da conclusão (`completed_at`) |
| Spot | `spot_projects`, `spot_files`, `spot_invoices`, `spot_demands`, `spot_hours`, `spot_actions`, `spot_audit`, `spot_reports` | Arquivos e notas fiscais ficam **dentro do banco** |
| E-mail | `email_settings`, `email_rules`, `email_log`, `email_test_log` | Senha do SMTP criptografada com a `ai.key` |
| IA | `ai_settings` | Chave do Claude criptografada (AES-256-GCM) |
| Sistema | `app_meta`, `audit_log` | `app_meta` guarda versão do esquema, identificador do banco e a marca `seeded` |

## Valores fixos (enumerações)

| Campo | Valores |
|---|---|
| `projects.status` | PLANEJAMENTO, EM_ANDAMENTO, CONCLUIDO, PAUSADO |
| `projects.priority` | CRITICA, ALTA, MEDIA, BAIXA |
| `tasks.status` | A_FAZER, EM_ANDAMENTO, CONCLUIDA, BLOQUEADA, CANCELADA |
| `finance_entries.kind` / `status` | NF_SERVICO, FATURA / PENDENTE, PAGO |
| `project_changes.status` | PENDENTE, APROVADA, REJEITADA |
| `ishikawa_causes.category` | METODO, MATERIAL, MAO_DE_OBRA, MAQUINA, MEIO_AMBIENTE, MEDICAO |
| `spot_projects.status` | ATIVO, PAUSADO, ENCERRADO |
| `spot_demands.status` | EM_ANALISE, APROVADO, EM_ANDAMENTO, EM_HOMOLOGACAO, EM_ESPERA, CONCLUIDA, CANCELADA |
| `spot_actions.kind` / `status` | ACAO, PENDENCIA / PENDENTE, A_CONFIRMAR, PARCIAL, CONCLUIDO |
| `spot_invoices.status` | EMITIDA, PAGA, CANCELADA |
| `users.role` | ADMIN, USER |

## Relações importantes

- `tasks.project_id → projects.id`; `tasks.parent_id → tasks.id` (só um nível); `tasks.depends_on → tasks.id` (mesmo projeto, sem ciclos).
- A tarefa-pai tem datas, progresso e situação **derivados** das filhas (`TaskRules.rollup`); não os edite diretamente.
- `ishikawa_causes.parent_id → ishikawa_causes.id` (uma camada de subcausa).
- Excluir projeto ou tarefa é bloqueado pela API enquanto houver vínculos (veja [03 – Funcionalidades](03-funcionalidades.md#2-projetos)).

## Onde ficam os arquivos

| Conteúdo | Local |
|---|---|
| Documentos dos projetos | Pasta `docs/` dentro da pasta de dados (nome gerado pelo servidor; coluna `documents.stored_name`) |
| Arquivos e notas dos Projetos Spot | Dentro do banco (colunas `content` / `file_content`) |
| Chave que criptografa segredos | `ai.key` na pasta de dados (**inclua no backup**) |
