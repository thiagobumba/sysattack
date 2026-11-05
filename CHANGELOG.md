# Histórico de versões

Etapas: **Alpha** → **Beta** → **RC** (release candidate) → **Oficial**. A versão atual fica em
`sysattack/src/main/webapp/version.json` e aparece no rodapé do menu e na tela de login.

## Não publicado

- Revisão geral de UX/UI (Visão geral, Projetos, Spot, Tarefas, Marcos, Cronograma, Relatórios, Usuários, IA Agent, E-mail): cabeçalhos mais leves, títulos de painel e rótulos com menos caixa-alta, tabelas sem colunas cortadas, abas do projeto em linha própria com rolagem, foco visível nos campos, ajustes de mobile.

## 1.0.0 - Alpha (05/11/2026)

Primeira versão.

- Login por e-mail e senha forte; usuários (administrador e comum); troca de senha confirmada por código enviado por e-mail.
- Projetos com escopo, datas e orçamento; tarefas pai e filha; marcos; Gantt; Financeiro (notas fiscais e faturas); Documentos.
- Projetos Spot: horas fixadas, apontamentos, demandas, ações e relatórios semanais gerados com o Claude, com envio por e-mail.
- Configurações IA Agent (chave da API do Claude) e Configurações de E-mail (SMTP pela tela e gatilhos por projeto), só para administradores.
- Relatórios & Dashs (projetos e orçamentária) e aba Changes nos projetos.
- Menu lateral em grupos, com o usuário logado e a versão no rodapé.
