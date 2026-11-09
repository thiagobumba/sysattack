# 01 – Visão geral

## O que é o SysAttack

Sistema web interno para planejar e acompanhar projetos: escopo, cronograma (Gantt), tarefas e marcos, custos, documentos, mudanças, análises de causa e indicadores de saúde, prazo e custo. Também controla **Projetos Spot** (trabalho contratado por horas ou por escopo fechado) e gera relatórios para clientes, com ajuda opcional do Claude.

Tudo roda em um único `.war`, sem serviços externos obrigatórios: o banco é um arquivo local (H2) e o front-end é HTML/CSS/JS sem framework nem build.

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Servidor | Java 11+, Servlets (`javax.servlet`; variante `jakarta` pelo perfil Maven `-Pjakarta`) |
| Contêiner | Apache Tomcat 9 (javax) ou 10.1/11 (jakarta) |
| Banco | H2 embutido, arquivo `sysattack.mv.db` na pasta de dados |
| Front-end | HTML + CSS + JavaScript puro (módulos em IIFE, sem bundler) |
| JSON | Gson |
| Build | Maven (`pom.xml`), gera `sysattack.war` |
| Integrações opcionais | SMTP (e-mail), API do Claude (relatórios dos Projetos Spot) |

## Arquitetura em uma página

```
Navegador (SPA, rotas por #/...)
   │  fetch JSON  →  /api/*  (cabeçalho X-Requested-With: SysAttack nas escritas)
   ▼
SecurityFilter (/*)  →  cabeçalhos de segurança, CSP, Origin, páginas de erro
   ▼
ApiServlet (/api/*)  →  autenticação por sessão → roteamento → regras de negócio
   │                     ├─ Db (H2, SQL parametrizado, transações atômicas Db.tx)
   │                     ├─ TaskRules, HealthCalc, ChangeService, IshikawaService
   │                     ├─ SpotService / SpotCalc (Projetos Spot)
   │                     ├─ EmailService / EmailTriggers / Mailer (SMTP)
   │                     ├─ AiConfig (chave do Claude) e BackupService
   │                     └─ Audit (trilha de auditoria)
   ▼
Pasta de dados:  sysattack.mv.db · docs/ · ai.key · config.properties · backups
```

Pontos de projeto que valem saber:

- **Toda escrita é atômica.** Qualquer POST/PUT/DELETE roda dentro de `Db.tx`: grava tudo ou nada. E-mails automáticos e apagamento de arquivos só acontecem depois que o banco confirma (`Db.afterCommit`).
- **Regras no servidor.** O front-end valida para ajudar o usuário, mas as regras (exclusões bloqueadas, dependências, datas) são sempre revalidadas na API.
- **Cálculos feitos pelo sistema, nunca pela IA.** Saldos, horas e indicadores são calculados em Java; o Claude só redige o texto dos relatórios.
- **Segurança por padrão.** CSP que só aceita scripts do próprio sistema (nada de script ou manipulador de evento *inline*), cookie `HttpOnly` + `SameSite=Lax`, SQL sempre parametrizado. Detalhes em [`../SEGURANCA.md`](../SEGURANCA.md).

## Estrutura do repositório

```
/
├─ CHANGELOG.md            histórico de versões
├─ SEGURANCA.md            políticas e limites de segurança
├─ atualizar.ps1           atualiza o código e gera o .war (Windows/PowerShell)
├─ restaurar-backup.ps1    restaura um backup
├─ docs/                   esta documentação
└─ sysattack/
   ├─ pom.xml
   ├─ config.exemplo.properties
   └─ src/main/
      ├─ java/br/com/sysattack/   servidor (API, regras, banco, e-mail, backup)
      ├─ resources/config.properties   configuração embutida no .war (sem senhas)
      └─ webapp/                  front-end (index.html, login.html, css/, js/, img/)
```

Classes principais do servidor: `ApiServlet` (rotas de projetos, tarefas, financeiro, documentos, usuários, 2FA), `Db` (esquema e acesso), `TaskRules` (tarefas pai/filha), `HealthCalc` (saúde e EVM), `ChangeService`, `IshikawaService`, `SpotService`/`SpotCalc`, `EmailService`/`EmailTriggers`/`Mailer`, `AiConfig`, `BackupService`, `Auth`/`Totp`/`Invites`, `Audit`, `SecurityFilter`, `ProjectFileReader` (importação) e os geradores de relatório HTML (`ScopeReportHtml`, `SpotReportHtml`, `ImportedReportHtml`).

Módulos do front-end (`webapp/js`): `core` (utilitários, `SA`), `app` (roteamento e telas principais), `tips` (balões de explicação), `gantt`, `burndown`, `pert`, `ishikawa`, `prioridades` (Eisenhower), `reports`, `spot`, `mail`, `sys`, `mpp` (importação), `qr`, `login`, `definir-senha`, `privacidade`.

## Perfis de acesso

| Perfil | Pode |
|---|---|
| **Administrador** | Tudo: usuários, Configurações IA Agent, Configurações de E-mail, Segurança e dados (backup, auditoria), gatilhos de e-mail, exigir 2FA, editar Projeto Spot encerrado (com justificativa), apagar qualquer documento |
| **Usuário** | Usar projetos, tarefas, marcos, financeiro, documentos, changes, Ishikawa, Spot, relatórios; ver e editar apenas o próprio cadastro; apagar os documentos que ele mesmo enviou |

Não existe usuário padrão. No primeiro acesso a tela de login pede a criação do administrador (esse primeiro acesso só é aceito uma vez).

## Glossário

| Termo | Significado no SysAttack |
|---|---|
| **Tarefa-pai / filha** | Dois níveis apenas. O pai é calculado pelas filhas (datas, progresso, situação) |
| **Marco** | Tarefa marcada como marco; aparece como losango no Gantt e na área Marcos |
| **Meta Empresa** | Marca (★) de projeto do plano de desenvolvimento da empresa; sobe a prioridade acima de Crítica |
| **Saúde** | Verde, amarelo ou vermelho: o pior entre prazo, custo, marcos e mudanças |
| **EVM** | Valor agregado: SPI (prazo), CPI (custo), EAC (custo no término), VAC (desvio no término) |
| **Burndown** | Gráfico do trabalho restante ao longo do tempo, contra a linha ideal |
| **PERT / CPM** | Rede de dependências e caminho crítico (tarefas com folga zero) |
| **Eisenhower** | Matriz urgente × importante, na tela Prioridades |
| **Ishikawa (6M)** | Diagrama de causa e efeito: Método, Material, Mão de obra, Máquina, Meio ambiente, Medição |
| **Change** | Solicitação de mudança de escopo, prazo ou custo, com impacto em dias e R$ |
| **Projeto Spot** | Trabalho contratado por horas (contrato recorrente ou pacote) ou de escopo fechado |
| **Gatilho** | Regra "quando acontecer X → enviar e-mail para Y" |
