# 07 – Desenvolvimento

## Ambiente

- JDK 11+ (o código é compilado com `--release 11`: não use `record`, `var` em lambdas nem recursos acima do Java 11).
- Maven. Build: `mvn clean package` (Tomcat 9) ou `mvn clean package -Pjakarta` (Tomcat 10.1/11; o perfil converte `javax.servlet` em `jakarta.servlet`).
- Para o front-end basta servir `sysattack/src/main/webapp` com qualquer servidor estático (ex.: `python3 -m http.server`) — as telas dependem da API, então testes visuais usam respostas simuladas (*mock*) de `/api/**`.

## Estrutura do código

**Servidor** (`sysattack/src/main/java/br/com/sysattack`): veja a lista de classes em [01 – Visão geral](01-visao-geral.md#estrutura-do-repositório). Regras de negócio ficam no servidor; o front-end só apresenta e valida para ajudar.

**Front-end** (`sysattack/src/main/webapp`):

| Arquivo | Papel |
|---|---|
| `index.html` / `login.html` / `definir-senha.html` / `privacidade.html` | Páginas; carregam os scripts com `?v=NN` |
| `js/core.js` | Utilitários `SA` (datas, moeda, `api`, escape HTML, ícones) |
| `js/app.js` | Roteamento (`#/...`), telas principais, formulários, despacho de ações (`data-action`) |
| `js/tips.js` | Balões de explicação (`Tip`) |
| `js/gantt.js`, `burndown.js`, `pert.js`, `ishikawa.js`, `prioridades.js` | Visões e gráficos |
| `js/reports.js`, `spot.js`, `mail.js`, `sys.js`, `mpp.js`, `qr.js` | Relatórios, Spot, e-mail, sistema, importação, QR do 2FA |
| `css/style.css` | Estrutura, espaçamento, tamanhos, tipografia e responsivo |
| `css/theme.css` | Só cor, sombra, gradiente e textura (carregado depois do `style.css`) |

`window.App` expõe `me`, `main`, `view`, `isLate`, `T_STATUS`, `dayDiff`, `dlg`, `api`, `openDialog`, `toast`, `pageHead` e `route` para os módulos.

## Grade de 8 (visual)

Toda a tela segue a grade de 8. O cabeçalho do `css/style.css` tem as regras completas; em resumo:

- **Espaçamento** (`padding`, `margin`, `gap`) só em 4, 8, 12, 16, 24, 32, 40, 48 e 64px. De 1 a 3px apenas para linhas e ajustes ópticos.
- **Tipografia** em 11, 12, 13, 15, 17, 20, 24, 32 e 44px, com entrelinha múltipla de 4 (corpo 15/24).
- **Alturas**: botão 40px, botão pequeno 32px, campo 48px, item do menu 40px, célula de tabela com 16px de recuo. No celular, botão e campo vão a 48px.
- **Responsivo**: só quatro quebras — 1560, 1200, 900 e 640px, todas no fim do `style.css`.
- Cada propriedade é declarada **uma única vez**. Se precisar mudar um valor, mude onde ele já está em vez de criar uma regra nova no fim do arquivo.
- A faixa laranja do cabeçalho (`.page-head`) sangra até a borda com margem negativa igual ao recuo lateral de `.content`. **Mudou um, mude o outro**, ou a página ganha rolagem lateral.
- O mesmo vale para os estilos escritos direto no JS (`style="padding:16px"`): use os valores da grade.

## Regras de código que importam

1. **CSP: nada de script ou manipulador inline.** A política só aceita `script-src 'self'`. Use `data-action="..."` nos elementos e trate o clique em `app.js` (ou no módulo, pelo prefixo `rp-`, `mpp-`, `ish-`, `prio-`). `style="..."` inline é permitido.
2. **SQL sempre parametrizado** (`Db.query/one/insert/update` com `?`). Nunca concatene entrada do usuário.
3. **Escritas dentro de `Db.tx`** (o `ApiServlet` já faz isso). Efeitos externos (e-mail, apagar arquivo) via `Db.afterCommit`.
4. **Esquema:** mude em `Db.java`, com `ALTER TABLE … ADD COLUMN IF NOT EXISTS` e aumente `SCHEMA_VERSION`. Nunca apague nem renomeie colunas em uso.
5. **Validação no servidor:** `str(...)`, `bool(...)`, conjuntos como `TASK_STATUS`; mensagens em português via `ApiException(status, msg)`.
6. **Segredos nunca no git:** senhas ficam só no `config.properties` da pasta de dados. Não registre credenciais em logs.
7. **Cálculos de negócio no servidor** (`HealthCalc`, `SpotCalc`, `TaskRules`); a IA não calcula.
8. **Texto de interface em pt-BR**, com acentos; datas `dd/mm/aaaa`, moeda `1.234,56`.
9. **Tarefas canceladas** ficam fora de todo cálculo; use sempre `status <> 'CANCELADA'` (ou o equivalente no JS) ao somar, contar ou projetar.
10. **E-mails em HTML:** só tabelas, `bgcolor`, fonte Segoe UI (compatível com o Outlook).

## Hints (balões de explicação)

Use `Tip.attr('Título', ['linha', '• item', '# subtítulo', '= fórmula'])` num elemento (funciona também em SVG) ou `Tip.wrap(html, título, linhas)`. Prefixos: `# ` subtítulo, `• ` item, `= ` fórmula, sem prefixo = parágrafo. Ao criar um indicador novo, escreva o balão junto: o que é, como é calculado e o que fazer.

## Como testar

- **Compilação do servidor:** compile com `javac --release 11` contra o Gson e as dependências do Servlet; o comando deve terminar sem erros.
- **Front-end:** sirva `webapp/` e use o Playwright (ou o navegador) com rotas simuladas de `/api/**` (`page.route`), cobrindo ao menos os estados vazio, com dados e a largura de celular (≈420 px) e de desktop (≈1300 px).
- **Banco:** o comportamento do H2 (migrações, consultas) só é validado rodando no Tomcat; teste em uma cópia do banco e confira o backup `pre-update-*.mv.db`.
- **Checklist de tela:** nada cortado, hints presentes, foco por teclado visível, sem rolagem horizontal da página, mensagens de erro claras.

## Fluxo de trabalho e versionamento

- **Branches:** `claude/dev` é onde o trabalho do dia a dia é publicado; `main` guarda só versões publicadas.
- **A cada mudança:**
  1. Altere o código **e** a documentação em `docs/` quando o comportamento mudar.
  2. Atualize o `CHANGELOG.md` na seção **Não publicado** (linguagem clara, o que mudou e por quê).
  3. Aumente o número do cache `?v=NN` nos quatro HTML (`index`, `login`, `privacidade`, `definir-senha`) quando alterar `js/` ou `css/`.
  4. Faça o commit e envie para `claude/dev`.
- **Publicar uma versão:** atualize `sysattack/src/main/webapp/version.json` (`version`, `stage`, `released`) e o `CHANGELOG.md` (mova "Não publicado" para a versão), abra o pull request de `claude/dev` para `main`, faça o merge e crie a tag `v<versão>[-etapa]` (`v1.0.0-alpha`, `v1.0.0-beta`, `v1.0.0-rc`, `v1.0.1`) com a release no GitHub (pré-lançamento até a Oficial).
- **Nomenclatura de versão:** `1.0.0 – Alpha` → `1.0.0 – Beta` → `1.0.0 – RC` → `1.0.1 – Oficial`. A fonte da verdade é o `version.json`; o rodapé do menu e a tela de login leem esse arquivo.

## Como adicionar uma funcionalidade (roteiro)

1. **Dados:** coluna/tabela nova em `Db.java` (+ `SCHEMA_VERSION`).
2. **API:** rota no serviço adequado, com validação, mensagens em português e transação.
3. **Tela:** módulo em `js/` (IIFE) ou ajuste em `app.js`; ações por `data-action`; estilos em `style.css`; registre o `<script>` em `index.html` com `?v=NN`.
4. **Hints** nos indicadores novos.
5. **Documentação:** `docs/` (funcionalidade, API, modelo de dados) e `CHANGELOG.md`.
6. **Testes:** compilação, tela com dados simulados em desktop e celular.

## Armadilhas conhecidas

- Tarefa-pai tem progresso e situação derivados (salvar direto os valores do pai é sobrescrito pelo `TaskRules.rollup`); as datas são do pai e só `rollup(id, true)` as ajusta às filhas (importação e seed).
- Tarefas só têm **uma** predecessora; o PERT usa essa predecessora (e a do pai, quando houver).
- Tarefas concluídas antes da coluna `completed_at` (esquema 45) não têm o dia real; os gráficos usam o fim planejado e avisam.
- Gráficos em SVG precisam medir a largura real do contêiner; não use `viewBox` esticado (as fontes crescem junto).
- Em `.proj-tabs` as abas quebram em várias linhas; não force rolagem horizontal.
