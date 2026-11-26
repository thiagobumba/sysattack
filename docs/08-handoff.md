# 08 – Handoff

Estado do projeto, decisões tomadas, armadilhas descobertas na prática e o que ficou
pendente. Escrito para que uma sessão nova do Claude Code (ou outra pessoa) retome o
trabalho sem precisar reler o histórico de conversas.

**Última atualização:** 26/11/2026 · commit `ffba7f7` · branch `claude/dev` ·
`?v=122` · `SCHEMA_VERSION = 58` · versão do produto **1.1.0 – Alpha**.

As regras de trabalho do dia a dia estão em [`../CLAUDE.md`](../CLAUDE.md) (lido
automaticamente pelo Claude Code). Este documento é o contexto: o porquê de cada coisa.

---

## Índice

1. [Contexto e pessoas](#1-contexto-e-pessoas)
2. [Ambiente real de produção](#2-ambiente-real-de-produção)
3. [Arquitetura em uma página](#3-arquitetura-em-uma-página)
4. [Mapa das classes do servidor](#4-mapa-das-classes-do-servidor)
5. [Mapa do front-end](#5-mapa-do-front-end)
6. [Banco de dados](#6-banco-de-dados)
7. [Segurança: o que está implementado](#7-segurança-o-que-está-implementado)
8. [E-mail e Microsoft 365: a saga completa](#8-e-mail-e-microsoft-365-a-saga-completa)
9. [Backup e importação](#9-backup-e-importação)
10. [Projetos Spot evolutivos](#10-projetos-spot-evolutivos)
11. [Grade de 8: o que foi feito e o que quebrou](#11-grade-de-8-o-que-foi-feito-e-o-que-quebrou)
12. [Como testar sem Tomcat](#12-como-testar-sem-tomcat)
13. [Armadilhas que já custaram caro](#13-armadilhas-que-já-custaram-caro)
14. [Histórico recente, versão por versão](#14-histórico-recente-versão-por-versão)
15. [Pendências](#15-pendências)
16. [Perguntas abertas e ideias no gelo](#16-perguntas-abertas-e-ideias-no-gelo)

---

## 1. Contexto e pessoas

O **SysAttack** é o sistema de gestão de projetos (PMO) da **Momesso Indústria de
Máquinas**. Controla projetos internos (cronograma, tarefas, marcos, financeiro,
documentos, changes, saúde e EVM) e **projetos Spot** — contratos com fornecedores
externos, por franquia de horas ou escopo fechado, incluindo os **evolutivos
Salesforce e Protheus**.

- **Thiago Porto** — Analista de Projetos de TI, dono do produto, único desenvolvedor,
  quem publica no servidor. Pede em português, em frases curtas, e espera resposta em
  português. Costuma pedir **"apresente sugestões antes de desenvolver"** quando a
  mudança é grande; quando diz "mãos à obra" ou "executar", é para implementar tudo.
- Não há time de desenvolvimento, nem CI, nem ambiente de homologação. **A máquina de
  produção é a única máquina.** Por isso tanto cuidado com backup, verificação,
  migração idempotente e "nada é apagado".

## 2. Ambiente real de produção

| Item | Valor |
|---|---|
| Servidor de aplicação | Apache **Tomcat 11.0** no Windows |
| Caminho do Tomcat | `C:\Program Files\Apache Software Foundation\Tomcat 11.0` |
| JDK | Java 27 (o código continua compilado com `--release 11`) |
| Perfil Maven | **`-Pjakarta`** (Tomcat 11 usa `jakarta.servlet`) |
| URL | `https://192.168.31.70:8085/sysattack` |
| Pasta de dados | `C:\Users\thii\.sysattack` (padrão do perfil — veja o aviso abaixo) |
| Repositório | `/home/claude/gitrepo`, GitHub `thiagobumba/sysattack` |
| Branch de trabalho | **`claude/dev`** (única). `main` guarda só versões publicadas |

> **A pasta de dados está no perfil do usuário do serviço.** A própria tela *Segurança e
> dados* avisa que o certo é um caminho fixo fora do perfil
> (`-Dsysattack.data=C:\dados\sysattack`). Isso **ainda não foi feito** e é um risco real:
> se a conta que roda o serviço mudar, o banco "some". Está na lista de pendências.

> **Não peça acesso a `C:\Users\thii\.sysattack` sem autorização dele.** A pasta tem o
> banco e a `ai.key`. Ele foi explícito sobre isso.

Acesso por HTTPS com **certificado autoassinado** (o IP não tem nome DNS). Isso bloqueia
qualquer coisa que dependa de origem confiável no navegador — foi o motivo de engavetar
o login por passkey (seção 16).

## 3. Arquitetura em uma página

Sem framework, de propósito: tudo que o Tomcat já traz, mais quatro dependências
(`javax.servlet-api` provided, H2, JavaMail, Gson).

```
navegador (HTML/CSS/JS baunilha, sem build)
   │  fetch('api/...')  +  X-Requested-With: SysAttack
   ▼
SecurityFilter  → cabeçalhos de segurança, CSP, checagem de origem (CSRF)
   ▼
ApiServlet (/api/*)  → sessão, roteamento por segmento, validação, Db.tx
   ├─ serviços: SpotService, ChangeService, IshikawaService, EmailService,
   │            AiConfig, BackupService/BackupRestore, LogoService, ProjectFileReader
   ├─ cálculo:  HealthCalc (saúde + EVM), SpotCalc, TaskRules (pai/filha)
   └─ saída:    ScopeReportHtml, SpotReportHtml, EvoReportHtml, ImportedReportHtml
   ▼
Db  → H2 em arquivo; esquema criado e migrado no AppListener.contextInitialized()
```

`AppListener` também agenda, num único executor de uma thread (daemon):
`EmailTriggers::scanLate` a cada 30 min, o backup diário (`BackupService.schedule`,
02:00 + um logo após subir se o último tem mais de 26 h) e `Audit::prune` a cada 24 h.

### Convenções da API

- Base `/api/...`, JSON UTF-8, sessão por cookie `HttpOnly` + `SameSite=Lax`.
- **Escritas exigem `X-Requested-With: SysAttack`** e `Origin` do próprio servidor.
- Toda escrita roda em transação (`Db.tx`) — exceção: `POST spot/{id}/reports/generate` confere
  a permissão antes, chama a IA sem transação e só então abre uma para gravar (`SpotService.isAiGenerate`).
  Efeito externo (e-mail, apagar arquivo) vai
  em `Db.afterCommit`, e limpeza de arquivo órfão em `Db.onRollback`.
- Erro = `{ "error": "mensagem em português" }` com o status certo
  (400 regra de negócio, 401 sem sessão, 403 sem permissão/CSRF, 404, 405, 409 vínculo, 500).
- Projeto Spot encerrado exige o cabeçalho **`X-Justification`** na escrita.
- Upload de bytes crus: `Content-Type: application/octet-stream` + `X-File-Name`.
  Limite geral `ApiServlet.MAX_UPLOAD = 25 MB`; a importação de backup tem limite
  próprio de **1 GB** e grava direto em disco, sem passar pela memória.

## 4. Mapa das classes do servidor

| Classe | Linhas | Papel |
|---|---:|---|
| `ApiServlet` | 1436 | Roteamento, sessão, validação, upload/download, usuários, 2FA |
| `SpotService` | 1134 | Projetos Spot: demandas, pedidos, features, horas, faturas, relatórios |
| `Db` | 721 | Esquema (`SCHEMA_VERSION`), migrações, `query/one/insert/update`, `tx` |
| `ProjectFileReader` | 656 | Importação de cronograma (MPP/XML/CSV) |
| `BackupService` | 532 | Backup completo, verificação, espelho, retenção, histórico, status |
| `AiConfig` | 424 | Chave das IAs, criptografia AES-256-GCM com a `ai.key` |
| `SpotCalc` | 391 | Franquia, ritmo semanal, projeção, saldo comprometido, economia |
| `BackupRestore` | 362 | **Importação de backup** (seção 9) |
| `EmailConfig` | 271 | Configuração de envio, modos de autenticação, assinatura, teste |
| `Invites` | 261 | Convites, link de senha, `app.url`, `addressInfo`, `testAddress` |
| `HealthCalc` | 215 | Saúde do projeto e EVM |
| `Mailer` | 212 | Montagem e entrega da mensagem (SMTP ou Graph) |
| `MailOAuth` | 177 | Token client-credentials, cache, leitura das claims, dicas de erro |
| `EmailTriggers` | 154 | Gatilhos ("quando X → enviar para Y") e varredura de atraso |
| `MailGraph` | 101 | `POST /users/{mailbox}/sendMail` com MIME em base64 |
| `MailSignature` | 72 | Assinatura (texto + imagem embutida por `cid:`) |
| `TaskRules` | 60 | Rollup de tarefa pai/filha |
| `SecurityFilter` | 72 | Cabeçalhos, CSP, origem |
| `Audit` | 57 | Trilha de auditoria (1 ano) |

### Fórmulas de EVM (`HealthCalc.evm`)

`BAC` = orçamento · `PV` = BAC × % previsto pelo prazo · `EV` = BAC × % realizado ·
`AC` = soma do Financeiro · **`SPI` = EV ÷ PV** · **`CPI` = EV ÷ AC** ·
**`EAC` = BAC ÷ CPI** · `VAC` = BAC − EAC · `ETC` = EAC − AC.

O progresso é a **média ponderada pela duração** das tarefas não-marco. Isso explica o
susto do "custo quase dobrou": com pouco progresso e custo já lançado, o CPI despenca e
o EAC (= BAC ÷ CPI) estoura. **Não é bug.** Quando ele perguntar de novo: confira se os
lançamentos do Financeiro incluem adiantamentos (pagamento antecipado infla o AC e
derruba o CPI cedo demais).

## 5. Mapa do front-end

Sem build. As quatro páginas carregam os scripts com `?v=NN` para furar o cache.
`window.App` expõe `me`, `main`, `view`, `isLate`, `T_STATUS`, `dayDiff`, `dlg`, `api`,
`openDialog`, `toast`, `pageHead` e `route`. `SA` (em `core.js`) traz `api`, `esc`,
`ic`, datas e moeda.

| Módulo | Linhas | Prefixo de ação | Papel |
|---|---:|---|---|
| `spot.js` | 1358 | `sp-` | Projetos Spot (a tela mais complexa do sistema) |
| `app.js` | 1022 | — | Rotas, telas principais, formulários, **despacho das ações** |
| `reports.js` | 252 | `rp-` | Relatórios & Dashs |
| `sys.js` | 207 | `sys-` | Segurança e dados (backup, importação, auditoria) |
| `mail.js` | 206 | `em-` | E-mail de saída (abas Envio e Assinatura) e gatilhos |
| `gantt.js` · `burndown.js` · `pert.js` · `ishikawa.js` · `prioridades.js` · `kanban.js` | | `ish-`, `prio-`, `kb-` | Visões e gráficos em SVG |
| `ai.js` | 185 | `ai-` | IA Agent (Claude, ChatGPT, Copilot) |
| `mpp.js` | 168 | `mpp-` | Importação de cronograma |
| `logo.js` | 107 | — | Imagem do projeto: prévia, redução, avatar |
| `tips.js` | 62 | — | Balões de explicação (`Tip.attr`, `Tip.wrap`) |

**O prefixo é o roteamento.** `app.js` linhas ~834-844 decide para qual módulo a ação
vai. Uma ação `sy-algo` nunca chega em `Sys.action` — tem de ser `sys-algo`.

**Hints:** ao criar um indicador novo, escreva o balão junto —
`Tip.attr('Título', ['parágrafo', '# subtítulo', '• item', '= fórmula'])`.

## 6. Banco de dados

H2 em arquivo (`sysattack.mv.db`), `DB_CLOSE_DELAY=-1`, `SET WRITE_DELAY 0` (grava a cada
confirmação — sem janela de perda em queda de energia). **30 tabelas**; a lista completa
com colunas está em [`06-modelo-de-dados.md`](06-modelo-de-dados.md).

Grupos: acesso (`users`, `recovery_codes`, `user_tokens`), projetos (`projects`, `tasks`,
`finance_entries`, `documents`, `project_changes`, `ishikawa*`, `project_logos`),
Spot (`spot_projects` + 11 tabelas), integrações (`ai_settings`, `email_*`) e
infraestrutura (`app_meta`, `audit_log`).

### Regras de migração

- Tudo em `Db.init()`, na ordem, **idempotente**: `CREATE TABLE IF NOT EXISTS` e
  `ALTER TABLE … ADD COLUMN IF NOT EXISTS`. Subir um `.war` nunca apaga dados.
- O `ALTER TABLE` de uma tabela vem **depois** do `CREATE TABLE` dela. Num banco vazio a
  tabela ainda não existe; as colunas de `email_settings` chegaram a ficar antes da criação
  e a instalação nova não subia (corrigido na revisão de 26/11).
- Aumente `SCHEMA_VERSION`. Quando ela muda, `preUpdateCopy()` guarda uma cópia do
  arquivo do banco em `pre-update-*.mv.db` (as 5 últimas) **antes** de migrar.
- Migrações recentes: **55** `external_rate`/`estimate_hours`/`effort_hours` ·
  **56** `feature_seq` · **57** colunas de OAuth no e-mail · **58** colunas de assinatura.

### `Db.dataId()`

Identificador do conjunto de dados, guardado em `app_meta`. Serve para dizer se dois
bancos são "o mesmo". Depois de importar um backup, o `data_id` passa a ser o do backup —
é o comportamento esperado.

## 7. Segurança: o que está implementado

- **Senha** com salt por usuário; política verificada em `Auth.checkPassword`.
- **2FA (TOTP)** com segredo criptografado, códigos de recuperação com hash, bloqueio
  após 5 falhas, e "2FA obrigatório" por usuário.
- **Convite por e-mail** para a primeira senha; link de troca de senha com token de uso
  único em `user_tokens`.
- **Primeiro acesso** só com o banco vazio **e** sem o marcador `.initialized`. Com o
  marcador presente e o banco vazio, o acesso é recusado de propósito (é sinal de que a
  pasta de dados errada está em uso — a mensagem manda conferir ou restaurar um backup).
- **CSP `script-src 'self'`**, `nosniff`, checagem de `Origin`/`Referer` nas escritas.
- **`ai.key`** (AES-256-GCM) criptografa: chave das IAs, senha do SMTP, segredo do
  cliente OAuth e segredos de 2FA. **Quem tem o backup tem a `ai.key`** — por isso o
  arquivo de backup é tratado como confidencial em toda a documentação.
- **Auditoria** (`audit_log`, 1 ano): login, falha, bloqueio, troca de senha, mudanças de
  usuário, 2FA, backup manual, download de backup, **envio e cancelamento de importação**.
- **Sem SSRF**: `Invites.testAddress()` só testa o `app.url` do servidor.
- Checklist de endurecimento do servidor em [`../SEGURANCA.md`](../SEGURANCA.md).

## 8. E-mail e Microsoft 365: a saga completa

Isto consumiu boa parte de uma sessão inteira e **hoje funciona**. Vale saber como, para
não refazer o caminho.

### Os três modos de autenticação (`email_settings.auth_mode`)

| Modo | Como envia | Permissão no Entra | Escopo |
|---|---|---|---|
| `BASICO` | SMTP com usuário e senha | — | — |
| `OAUTH2` | SMTP com **XOAUTH2** | `SMTP.SendAsApp` | `https://outlook.office365.com/.default` |
| `GRAPH` | `POST graph.microsoft.com/v1.0/users/{mailbox}/sendMail` com o MIME em base64 | `Mail.Send` | `https://graph.microsoft.com/.default` |

Todos pelo fluxo **client credentials** (locatário + aplicativo + segredo). O token fica
em cache na memória, com chave `tenant|clientId|scope`, e é renovado 2 minutos antes de
expirar. O Graph tem limite de 4 MB por mensagem.

### O que travou, na ordem

1. **535 5.7.3 Authentication unsuccessful** com senha — a Microsoft desligou a
   autenticação básica. Daí o OAuth.
2. Ele colou o **ID** do segredo no lugar do **Valor**. O sistema passou a avisar a
   diferença na própria tela (v110).
3. **5.7.139 SMTP AUTH desligado** no locatário ou na caixa — o log passou a explicar
   as duas variantes (v111).
4. O bloqueio real era o **consentimento do administrador ausente**. Quem revelou isso foi
   o próprio recurso de log de claims (v113): o token saía **sem o `roles`** esperado, e o
   log passou a imprimir uma linha **ATENÇÃO** quando a função necessária não está no token.
5. Com o SMTP OAuth ainda resistindo, entrou o **Graph** como terceira opção (v112), que
   dispensa SMTP. **Foi o que funcionou** — ele confirmou com print do relatório no Outlook.

### Como o log de e-mail ajuda (e o que ele nunca grava)

`EmailConfig.test()` gera um `.log` completo (`email_test_log`): conversa SMTP, URL do
Graph, cabeçalhos mascarados, tamanho, status HTTP, tempo, `request-id`,
`client-request-id`, `x-ms-ags-diagnostic`, claims públicas do token
(`aud`, `appid`, `tid`, `roles`) e dicas por código (AADSTS, 535, 5.7.139, 550 5.7.60,
401/403/404/413/429 do Graph).

**Nunca grava a senha, o segredo nem o token.** Quando precisar diagnosticar, peça o log —
não peça o segredo.

### Assinatura (v114–v117)

Texto + imagem guardados em `email_settings`, embutidos por `cid:sysattack-assinatura`
no fim de relatórios e avisos (o e-mail do código de senha não leva). Largura até
**1000 px**, imagem até 2 MB. Aparece como aba dentro de *E-mail de saída*, visível
quando a caixinha **Assinatura** está marcada.

Duas falhas sérias nasceram aqui, ambas corrigidas — veja a seção 13.

## 9. Backup e importação

### Backup (já existia)

Cada backup é **um** zip `sysattack-AAAAMMDD-HHMMSS-<tipo>.zip` com:

```
manifest.json              data/hora, tipo, pasta, data_id, versão do H2, contagem por tabela
database/physical.zip      BACKUP TO do H2 — cópia consistente com o sistema em uso
database/script.sql.zip    SCRIPT TO … COMPRESSION ZIP — SQL portátil entre versões do H2
files/docs/*               documentos dos projetos
config/ai.key              a chave-mestra
config/config.properties
```

Gravado como `.part` e só então renomeado; **verificado ao terminar** (CRC do zip +
abertura do banco copiado percorrendo todas as tabelas); ganha um `.sha256`; copiado para
a pasta espelho se houver; retenção de 1/dia (14 dias), 1/semana (8), 1/mês (12),
manuais por 90 dias, e os 3 mais novos nunca são apagados. Histórico em
`backup-history.jsonl`.

Configuração: `-Dsysattack.backup` / `SYSATTACK_BACKUP` / `backup.dir`
(padrão `<dados>/backups`) e `backup.mirror` para a segunda cópia.

### Importação (feita em 24–26/11/2026, `BackupRestore.java`)

**O problema:** o arquivo do H2 só pode ser trocado com o banco **fechado**, e com o
Tomcat no ar ele está aberto. Por isso a importação é em dois tempos.

1. **`POST api/backup/import`** (só administrador) recebe o zip no corpo cru
   (`X-File-Name`, `X-With-Config: 0|1`, até 1 GB, gravado direto em disco).
   O arquivo é **conferido antes de ser aceito**: estrutura, `manifest.json`,
   `database/physical.zip`, CRC, SHA-256 e abertura do banco da cópia com o H2 **atual**
   (é isso que garante que ele vai abrir depois da troca). Reprovou, não é aceito.
   Passando, vira `restore-pending.zip` + `restore-pending.json` na pasta de dados.
2. **`BackupRestore.applyPending()`** roda no começo do `Db.init()`, antes de qualquer
   conexão. Extrai tudo para `.restore-work/` **primeiro**; só então move o que existe
   para `antes-da-restauracao-<data>/` e põe o novo no lugar. Se falhar no meio,
   `rollback()` tira o que entrou, devolve o que saiu e o sistema sobe como estava.
   A marcação sai em qualquer caso (senão cada início tentaria de novo) e o zip vira
   `restore-falhou.zip`.
3. **`DELETE api/backup/import`** cancela enquanto não reiniciou.

**O que é substituído:** banco, `docs/` e `ai.key`. O `config.properties` é **opcional**
(desmarcado por padrão: endereço, SMTP e pastas do novo local costumam ser outros).
Se o backup não trouxer a `ai.key`, a chave atual é mantida e a tela avisa — sem ela, os
segredos do banco importado não abrem.

**Nunca é mexido:** a pasta `backups/` (os backups e o histórico ficam).

Resultado em `restore-history.jsonl` (na pasta de backups) e na tela depois do reinício.

**Fluxo para instalar em outro local:** sobe o `.war` → primeiro acesso cria um
administrador provisório → *Segurança e dados* → **Importar backup** → reinicia o
Tomcat → entra com um usuário **do backup** (o provisório deixou de existir) → confere
o `app.url`.

Alternativa com o Tomcat parado: `restaurar-backup.ps1` continua valendo.

## 10. Projetos Spot evolutivos

`spot_projects.kind`: `PADRAO`, `EVOL_SF` (Salesforce), `EVOL_PT` (Protheus).

- **Jornada de atendimento** em 6 etapas, com campos próprios por etapa; a entrega exige
  solução, responsável e feature.
- **Features numeradas** `FT-nnn` por projeto, com documentação e anexos.
  `feature_seq` guarda o maior número já usado: **número excluído nunca volta** (decisão
  explícita dele). Só administrador exclui feature.
- **Economia**: `estimate_hours` (estimativa do time externo) × `external_rate` menos o
  `effort_hours` interno. Placar, aba **Economia** com gráfico (mensal, ou **por dia**
  quando o projeto é curto, usando as datas de início e fim do projeto como eixo) e
  observação na visão do projeto.
- **Rótulo do time externo** depende do tipo:
  `extName = k => k === 'EVOL_PT' ? 'Fornecedor Protheus' : 'Beecloud'`.
  Troca na hora ao mudar o tipo no cadastro (v120).
- **Relatório evolutivo** (`EvoReportHtml`, `evoReportHtml` no JS): HTML em tabelas,
  responsivo e compatível com o aplicativo do Outlook, com a logo da Momesso, prévia,
  edição do HTML antes de enviar, gravação em `spot_reports` e envio por e-mail.

## 11. Grade de 8: o que foi feito e o que quebrou

A v108 passou toda a aplicação para a grade de 8 (regras no cabeçalho do `style.css` e
resumidas no `CLAUDE.md`). O caminho até lá tem duas lições:

1. **A primeira tentativa de consolidar o CSS ("juntar tudo na última regra") quebrou 29
   telas.** Foi abandonada e substituída por **eliminação de propriedade morta**: remover
   só as declarações que uma regra posterior do mesmo seletor já sobrescreve. Essa deu
   resultado **pixel a pixel idêntico**.
2. **Juntar as media queries mudou a ordem da cascata.** A correção foi mover todos os
   blocos `max-width` para o fim do arquivo, da mais larga para a mais estreita, e
   comparar as capturas de tela antes e depois.

E a armadilha que volta sempre: **`.page-head` tem margem negativa igual ao recuo lateral
de `.content`.** Mudar `.content` para 40 px sem mudar a margem negativa deu rolagem
lateral em toda a aplicação. Está documentado no cabeçalho do `style.css` e nos docs.

## 12. Como testar sem Tomcat

### Compilação do servidor

O certo é `mvn clean package -Pjakarta`. Em ambiente sem Maven ou sem rede, dá para
compilar o pacote inteiro com `javac` e **stubs** das APIs ausentes
(`javax.servlet`, `javax.mail`):

```bash
cd sysattack/src/main/java
javac --release 11 -proc:none -cp <caminho>/gson.jar -d /tmp/jc \
      $(find /tmp/stubs -name "*.java") br/com/sysattack/*.java
```

Os stubs usados numa sessão anterior cobriam `MimeBodyPart`, `MimeMultipart`,
`InternetHeaders`, `MimeMessage.writeTo` e `setContent(MimeMultipart)`, entre outros.
**Eles não estão no repositório** — se precisar de novo, recrie conforme os erros
aparecerem, e lembre: com os stubs completos o pacote compila com **0 erros**, então
qualquer erro que sobrar é real.

### Front-end

```bash
cd sysattack/src/main/webapp && (setsid python3 -m http.server 8099 &)
```

Playwright com `page.route('**/api/**', ...)` devolvendo JSON simulado. O que conferir
em todo teste de tela:

- `page.on('pageerror')` **vazio**;
- texto vindo da API aparece **escapado** (injete `<img src=x onerror=alert(1)>` num
  campo de texto do mock e confira que `main img` continua em 0);
- **estado vazio** e **estado com dados**;
- **celular (~420 px)** e **desktop (~1300 px)**, sem rolagem horizontal;
- o mock precisa ter **todos** os campos que a tela lê — falta de `warnings`, `last_ok`,
  `backups` ou `history` já derrubou a tela de Segurança e dados com
  "Cannot read properties of undefined".

Para conferir sintaxe de JS rapidamente, sem navegador:

```bash
node -e "new (require('vm').Script)(require('fs').readFileSync('js/sys.js','utf8'))"
```

### Banco

O comportamento do H2 (migração, consulta) **só é validado rodando no Tomcat**. Teste
numa cópia e confira o `pre-update-*.mv.db`.

## 13. Armadilhas que já custaram caro

| Armadilha | O que aconteceu | Como evitar |
|---|---|---|
| **`Db.query` e BLOB** | A assinatura com imagem quebrou **todo** o envio de e-mail com `ClassCastException` (`String` não vira `byte[]`): o `Db.query` converte para texto tudo que não é número, booleano ou `String`. | BLOB **sempre** por JDBC direto (`Db.get()` + `rs.getBytes(1)`). Veja `SpotService.invoiceFile` e `MailSignature.image()`. |
| **DELETE + INSERT apaga coluna vizinha** | `EmailConfig.save()` recriava a linha e levava junto texto e imagem da assinatura. | Ao regravar uma linha de configuração, **carregue e devolva** as colunas que o formulário não envia. |
| **Prefixo de ação errado** | O botão `sy-addr-test` não fazia nada: só ações `sys-` chegam em `Sys.action`. | Confira a tabela de prefixos antes de nomear uma ação. |
| **Falha silenciosa na montagem da assinatura** | Qualquer erro ali impedia o e-mail de sair. | `MailSignature.current()` tem `try/catch`: o e-mail sai **sem** assinatura em vez de não sair. Siga esse padrão para enfeites. |
| **`.page-head` × `.content`** | Rolagem lateral em toda a aplicação. | Mudou um recuo, mude o outro. |
| **Ordem das media queries** | Cascata invertida depois de "organizar" o CSS. | Todas no fim do `style.css`, da mais larga para a mais estreita. |
| **Template literal fechado com aspa simples** | Erro de sintaxe no `ecoTab` depois de uma substituição automática. | Rode o `vm.Script` depois de qualquer edição grande em JS. |
| **Substituição por texto exato** | Patches falhando por string que não batia. | `grep` o texto real primeiro e exija **uma** ocorrência. |
| **Servidor estático morto** | `ERR_CONNECTION_REFUSED` no meio dos testes. | Confira/suba o `http.server` antes de cada lote. |
| **GitHub 503** | `git push` falhou com "credential service temporarily unavailable". | Tente de novo; costuma passar na segunda. |
| **Logo: ele queria o PNG, não o código** | A v121 (recorte/preenchimento do avatar) foi implementada e **revertida a pedido** (`git revert`, commit `05b3380`). O que ele queria era o arquivo `horus-avatar-400.png` para baixar. | Quando o pedido for "me manda a imagem", mande o arquivo — não mexa no código. |
| **ALTER antes do CREATE** | As colunas de OAuth e assinatura de `email_settings` eram acrescentadas antes do `CREATE TABLE` dela. Em banco existente passava; em instalação nova o Tomcat não subia. | Teste `Db.init()` com uma pasta de dados **vazia** (`-Dsysattack.data=<pasta nova>`) sempre que mexer no esquema. |
| **Auditoria antes da validação** | `Audit.log` grava em conexão própria e não é desfeito no rollback; registrado antes da validação, deixava na trilha uma "senha redefinida" que não aconteceu. | Chame `Audit.log` só depois de todas as validações que podem lançar erro. |
| **Lista Spot monta as três tabelas** | A lista monta as tabelas de horas, escopo e evolutivos com as mesmas linhas antes de escolher qual mostrar. Quando o servidor passou a mandar só os indicadores de cada tipo, a tabela de escopo leu `p.scope` de um contrato e a tela quebrou ("reading 'health'"). | As tabelas agora são funções e só a da guia aberta é montada. Ao enxugar uma resposta da API, procure no JS todo template que lê o campo, mesmo os que não aparecem na tela. |

Outras regras de comportamento que nasceram da prática:

- **Tarefa-pai** tem progresso e situação derivados (`TaskRules.rollup` sobrescreve o que
  for salvo direto); as datas são do pai, e só `rollup(id, true)` as ajusta às filhas
  (importação e seed).
- **Uma predecessora por tarefa**; o PERT usa essa e a do pai, quando houver.
- Tarefas concluídas antes da coluna `completed_at` (esquema 45) não têm o dia real: os
  gráficos usam o fim planejado e avisam.
- Gráficos em SVG precisam medir a largura real do contêiner — `viewBox` esticado faz a
  fonte crescer junto.

## 14. Histórico recente, versão por versão

143 commits no total. As últimas, do mais novo para o mais antigo:

| `?v=` | Commit | O que entrou |
|---|---|---|
| 121 | `ffba7f7` | **Importar backup pela aplicação** (`BackupRestore`) |
| — | `05b3380` | Revert da v121 anterior (logo) — ver seção 13 |
| (121) | `45cfd1e` | Logo do projeto: recorta moldura e preenche o avatar — **revertido** |
| 120 | `101f7d0` | Spot Protheus: time externo vira "Fornecedor Protheus" |
| 119 | `550a265` | Selo correto quando o `app.url` é `localhost` |
| 118 | `f7247da` | Compara e testa o endereço do sistema (DNS → porta → HTTPS → certificado) |
| 117 | `dcf4387` | Assinatura: largura até 1000 px |
| 116 | `b08ba85` | Assinatura como aba; preserva assinatura ao salvar o envio |
| 115 | `ef04b70` | **Correção**: assinatura com imagem bloqueava o envio |
| 114 | `8452d48` | Assinatura configurável com imagem embutida (esquema 58) |
| 113 | `1621221` | Log do e-mail: permissões do token e chamada completa do Graph |
| 112 | `b161aec` | **Microsoft Graph** como terceira opção de envio |
| 111 | `03d6705` | Log explica o 5.7.139 (SMTP autenticado desligado) |
| 110 | `c412a17` | Aviso de Valor × ID do segredo do cliente |
| 109 | `a95896b` | **OAuth 2.0 (XOAUTH2)** com log detalhado (esquema 57) |
| 108 | `06aae1d` | **Grade de 8** em toda a aplicação e CSS consolidado |
| 107 | `8b80ccc` | Excluir feature (admin), número nunca reaproveitado (esquema 56) |
| 106 | `e437675` | Economia: gráfico por dia em projetos curtos; observação no projeto |
| 105 | `f0d78d9` | Relatório evolutivo: logo, responsivo e Outlook |
| 104 | `916b843` | Aba Economia com gráfico mensal |
| 103 | `087723d` | Relatório guardado, editável e enviável por e-mail |
| 102 | `73131f2` | Aba Relatório com prévia, HTML e PDF |
| 101 | `1bf91d4` | Retira a aba "Ações e pendências" do Spot |
| 100 | `3c599ab` | Estimativa externa × esforço interno e economia (esquema 55) |

O `CHANGELOG.md` tem o texto completo de cada item, em linguagem de usuário.

## 15. Pendências

### Do lado do Thiago (nada a implementar)

1. **Girar o segredo do cliente no Entra.** Ele colou o Valor do segredo numa conversa.
   Criar um segredo novo, colar na tela e **apagar o antigo** (`appsysattack`).
   **Continua pendente.**
2. **Opcional:** `New-ApplicationAccessPolicy` para limitar o `Mail.Send` a uma única
   caixa (hoje o aplicativo pode enviar como qualquer caixa do locatário).
3. **Caminho fixo para a pasta de dados** (`-Dsysattack.data=C:\dados\sysattack`), fora
   do perfil do usuário do serviço. A própria tela avisa.
4. **`backup.dir` em outro disco** e **`backup.mirror`** em rede/nuvem. Hoje os backups
   estão no mesmo disco do banco — não protege contra falha de disco, e a tela avisa.
5. **Nome DNS + certificado** para a aplicação (pré-requisito de qualquer trabalho com
   passkey, seção 16).
6. Conferir se os custos do projeto que "quase dobrou" incluem **adiantamentos**
   (seção 4).

### Oferecido e não aceito (não implemente sem ele pedir)

- Renomear o rótulo ambíguo **"Realizado"** no painel de EVM.
- Oferecer a **fórmula otimista de EAC** (`AC + (BAC − EV)`) como alternativa à atual.

## 16. Perguntas abertas e ideias no gelo

- **Login com cofre de senha / Windows Hello / passkey (WebAuthn).** Ele pediu insights,
  recebeu, e decidiu: **"por enquanto não vamos optar."** O bloqueio técnico real é o
  acesso por IP com certificado autoassinado — WebAuthn exige origem segura e confiável.
  Só faz sentido retomar **depois** do nome DNS e do certificado.
- **Importação com o sistema no ar**, sem reiniciar o Tomcat. Seria possível com
  `DROP ALL OBJECTS` + `RUNSCRIPT` a partir do `script.sql.zip`, mas deixa o banco em
  estado inconsistente se falhar no meio, e precisaria de um modo de manutenção global
  para impedir que as tarefas agendadas reabram o banco durante a troca. A rota atual
  (troca física com o banco fechado) foi escolhida por ser a única em que dá para
  **desfazer**. Reavaliar só se o reinício virar um problema real.
