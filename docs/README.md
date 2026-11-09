# Documentação do SysAttack

Sistema de gestão de projetos da Momesso Indústria de Máquinas. Java (Servlets) + banco H2 embutido + HTML/CSS/JS puro, publicado como `.war` no Tomcat.

Versão documentada: **1.1.0 – Alpha** (arquivo `sysattack/src/main/webapp/version.json`). O que mudou em cada versão está em [`../CHANGELOG.md`](../CHANGELOG.md); a seção "Não publicado" lista o que já está no código mas ainda não recebeu número de versão.

## Por onde começar

| Eu quero… | Leia |
|---|---|
| Entender o que o sistema é, a arquitetura e quem pode fazer o quê | [01 – Visão geral](01-visao-geral.md) |
| Aprender a usar as telas, passo a passo | [02 – Guia do usuário](02-guia-do-usuario.md) |
| Ver **todas** as funcionalidades, regras e fórmulas | [03 – Funcionalidades](03-funcionalidades.md) |
| Instalar, atualizar, configurar e fazer backup | [04 – Instalação e operação](04-instalacao-e-operacao.md) |
| Integrar ou depurar pela API | [05 – API](05-api.md) |
| Conhecer as tabelas do banco | [06 – Modelo de dados](06-modelo-de-dados.md) |
| Alterar o código, testar e publicar | [07 – Desenvolvimento](07-desenvolvimento.md) |
| Revisar segurança e limites conhecidos | [`../SEGURANCA.md`](../SEGURANCA.md) |

## Como manter esta documentação

- A documentação mora no repositório, na pasta `docs/`, e é versionada junto com o código (branch `claude/dev`, depois `main`).
- Toda mudança de comportamento atualiza **três** lugares: o código, o `CHANGELOG.md` (seção "Não publicado") e o arquivo de `docs/` correspondente.
- Ao publicar uma versão, o número em "Versão documentada" acima acompanha o `version.json`.
- Os detalhes que mudam com frequência (rotas, colunas) estão resumidos aqui; a fonte da verdade continua sendo o código (`ApiServlet.java`, `Db.java`).
