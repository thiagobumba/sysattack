# SysAttack — instruções para o Claude Code

Este arquivo é lido em toda sessão. É o contrato de trabalho neste repositório.
O passo a passo completo e o histórico das decisões estão em
[`docs/08-handoff.md`](docs/08-handoff.md); as regras técnicas detalhadas, em
[`docs/07-desenvolvimento.md`](docs/07-desenvolvimento.md).

---

## 1. Quem é quem

- **Produto:** SysAttack — sistema de gestão de projetos (PMO) da **Momesso Indústria de Máquinas**.
- **Dono do produto / único usuário técnico:** Thiago Porto, Analista de Projetos de TI.
- **Idioma:** **responda SEMPRE em pt-BR.** Código, comentários, nomes de tela, mensagens
  de erro, commits, documentação e CHANGELOG: tudo em português do Brasil, com acentuação.
- **Tom:** direto e curto. Ele é a pessoa que vai subir o `.war` no servidor em seguida —
  entregue o resultado e os passos para publicar, não a narração do trabalho.

## 2. Pilha e onde as coisas estão

| Camada | O que é |
|---|---|
| Servidor | **Java Servlets puros** (sem framework), `br.com.sysattack`, compilados com `--release 11` |
| Banco | **H2 embutido em arquivo**, acesso por `Db.query/one/insert/update` |
| Front-end | **HTML/CSS/JS baunilha**, sem build, sem framework, módulos IIFE |
| Empacotamento | **WAR** via Maven, perfil `jakarta` |
| Servidor de aplicação | **Tomcat 11** no Windows, `https://192.168.31.70:8085/sysattack` |

```
/                        raiz do repositório
├─ CLAUDE.md             este arquivo
├─ CHANGELOG.md          histórico de versões (seção "Não publicado" no topo)
├─ README.md             visão geral do repositório
├─ SEGURANCA.md          checklist de endurecimento do servidor
├─ atualizar.ps1         build na máquina do Thiago (git pull + mvn)
├─ restaurar-backup.ps1  restauração com o Tomcat parado
├─ docs/                 01 a 08 — documentação viva, mantida junto do código
└─ sysattack/
   ├─ pom.xml
   └─ src/main/
      ├─ java/br/com/sysattack/   ~30 classes, ~8.900 linhas
      ├─ resources/               config.properties embutido (sem segredos), logo dos relatórios
      └─ webapp/                  4 páginas HTML, js/ (23 módulos), css/ (style + theme)
```

## 3. O fluxo obrigatório de TODA mudança

Nenhuma alteração é considerada pronta sem estes seis passos:

1. **Código** alterado e **compilando**.
2. **`CHANGELOG.md`**: um item novo no topo da seção **Não publicado**, em linguagem
   de usuário (o que mudou e por quê), não em linguagem de código.
3. **`docs/`**: atualize o que o comportamento tocou —
   `02-guia-do-usuario.md` (como usar), `03-funcionalidades.md` (o que existe),
   `04-instalacao-e-operacao.md` (arquivos, configuração, operação),
   `05-api.md` (rotas), `06-modelo-de-dados.md` (tabelas e a linha
   "`SCHEMA_VERSION` (atualmente **N**)").
4. **Cache do front-end**: se mexeu em `js/` ou `css/`, aumente o `?v=NN` nos **quatro**
   HTML: `index.html`, `login.html`, `privacidade.html`, `definir-senha.html`.
   **Versão atual: `?v=121`.**
5. **Commit e push na branch `claude/dev`** (é a única branch de trabalho), terminando
   a mensagem com:
   ```
   Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
   Claude-Session: <link da sessão>
   ```
6. **Resposta com os passos para publicar** (seção 6 abaixo).

## 4. Regras de código que não se negociam

1. **CSP `script-src 'self'`**: nada de `<script>` inline nem `onclick=`. Use
   `data-action="..."` e trate no `app.js` ou no módulo. `style="..."` inline é permitido.
2. **Prefixo da ação define o módulo.** O `app.js` despacha por prefixo:
   `sp-`→Spot, `em-`→Mail, `sys-`→Sys, `ai-`→Ai, `rp-`→Rep, `mpp-`→Mpp,
   `ish-`→Ish, `kb-`→Kanban, `prio-`→Prio. **Ação com prefixo errado simplesmente não
   dispara** — foi assim que o botão `sy-addr-test` ficou mudo até virar `sys-addr-test`.
3. **SQL sempre parametrizado.** Nunca concatene entrada do usuário.
4. **`Db.query` transforma em texto** tudo que não for número, booleano ou `String`
   (serve para datas). **Bytes de BLOB têm de ser lidos por JDBC direto**
   (`Db.get()` + `PreparedStatement` + `rs.getBytes(1)`), como fazem
   `SpotService.invoiceFile` e `MailSignature.image()`. Ler BLOB pelo `Db.query`
   dá `ClassCastException` em produção.
5. **Esquema**: só em `Db.java`, com `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, e aumente
   `SCHEMA_VERSION` (**hoje 58**). Nunca apague nem renomeie coluna em uso.
6. **Cálculo de negócio é no servidor** (`HealthCalc`, `SpotCalc`, `TaskRules`). A IA
   escreve texto de relatório e **não calcula nada**.
7. **Segredos nunca no git.** O `config.properties` real (com senhas) vive só na pasta de
   dados do servidor e está no `.gitignore`. Segredo e token **nunca** entram em log —
   no log de e-mail vão só as informações públicas do token (`aud`, `appid`, `tid`, `roles`).
8. **Upload de bytes crus**: `POST` com `Content-Type: application/octet-stream` e o nome
   no cabeçalho `X-File-Name` (urlencoded). É assim em documentos, arquivos de Spot,
   assinatura de e-mail e importação de backup.
9. **Tarefas canceladas ficam fora de todo cálculo** (`status <> 'CANCELADA'`).
10. **E-mail em HTML**: só tabelas, `bgcolor` e Segoe UI — tem de abrir bonito no
    aplicativo do Outlook.
11. **Nada de SSRF**: teste de endereço usa só o `app.url` configurado no servidor,
    nunca uma URL vinda da requisição.

## 5. Grade de 8 (visual) — resumo operacional

O cabeçalho do `css/style.css` tem as regras completas. O essencial:

- **Espaçamento** só em 4, 8, 12, 16, 24, 32, 40, 48, 64 px (1–3 px só para linhas).
- **Tipografia** em 11, 12, 13, 15, 17, 20, 24, 32, 44 px; entrelinha múltipla de 4.
- **Alturas**: botão 40, botão pequeno 32, campo 48, item de menu 40, célula com 16 de recuo.
- **Quatro quebras apenas** — 1560, 1200, 900, 640 px — **todas no fim do `style.css`**,
  da mais larga para a mais estreita (a ordem importa: a cascata quebrou uma vez por isso).
- **`style.css` = estrutura/espaço/tipografia. `theme.css` = só cor, sombra e textura.**
- **`.page-head` tem margem negativa que precisa espelhar o recuo lateral de `.content`.**
  Mudou um, mude o outro, senão a página ganha rolagem lateral.
- Cada propriedade declarada **uma única vez**: para mudar um valor, mude onde ele já está.

## 6. Build, teste e publicação

**Build** (na pasta `sysattack/`):
```bash
mvn clean package -Pjakarta     # Tomcat 10.1/11 — é o que o Thiago usa
mvn clean package               # Tomcat 9 (javax.servlet)
```
O perfil `jakarta` copia o código, troca `javax.servlet`→`jakarta.servlet` e
`javax.mail`→`jakarta.mail`, e compila a cópia. **O código-fonte continua em `javax`.**

**Teste do front-end** (sem Tomcat): sirva a pasta e simule a API.
```bash
cd sysattack/src/main/webapp && (setsid python3 -m http.server 8099 &)
# Playwright com page.route('**/api/**', ...) devolvendo JSON simulado
```
Cubra sempre: estado vazio, com dados, celular (~420 px) e desktop (~1300 px);
confira `pageerror` vazio e que texto vindo da API aparece **escapado**.

**Publicar na máquina do Thiago** — é o texto que a resposta final deve trazer:
```powershell
.\atualizar.ps1          # ou .\atualizar.ps1 -Watch para vigiar a branch
```
Ou na mão: pare o Tomcat → apague `webapps\sysattack`, o `.war` antigo e
`work\Catalina\localhost\sysattack` → copie o novo `target\sysattack.war` →
suba o Tomcat → `Ctrl+Shift+R` → confira o `?v=` no código-fonte da página.
Mudança de esquema é aplicada sozinha ao subir (diga isso quando houver).

## 7. Alarme falso conhecido

O hook de parada às vezes diz **"Branch 'claude/dev' has N unpushed commit(s)"**.
É **sempre** falso alarme. Confira e responda em uma ou duas frases:
```bash
git ls-remote --heads origin claude/dev && git rev-parse HEAD
```
Se os dois hashes batem, está publicado.

## 8. Antes de implementar

- **Olhe o que já existe.** O sistema é grande e muita coisa pedida como "nova" já está
  meio pronta (o backup completo já existia quando foi pedido "fazer backup e importar";
  só faltava importar).
- Para mudança **cara ou destrutiva** (muitos arquivos, dados substituídos, algo
  irreversível), **apresente as opções antes** — o Thiago costuma pedir isso
  explicitamente e costuma escolher uma combinação das alternativas.
- Ele dá instruções curtas e corrige rápido. Se ele disser "desfaça", desfaça com
  `git revert` e entregue o que ele realmente pediu.
