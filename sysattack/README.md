# SysAttack — gestão de projetos

Java (Servlets) + H2 embutido (arquivo local) + HTML/CSS/JS puro.

## 1. Descubra a versão do seu Tomcat
- **Tomcat 9.x**        → `mvn clean package`
- **Tomcat 10.1 / 11**  → `mvn clean package -Pjakarta`

## 2. Gerar e publicar
1. JDK 11+ e Maven; rode o comando acima na pasta que contém o `pom.xml`.
2. Pare o Tomcat, apague `webapps\sysattack` e `sysattack.war` antigos, copie o novo `target\sysattack.war` e inicie.
3. Acesse `http://localhost:PORTA/sysattack/` e use **Ctrl+Shift+R** na primeira vez (limpa cache).

## 3. Acessar por um nome (ex.: http://momesso-sysattack)
O nome é só um "apelido" para o IP do servidor. Passos:

1. **Publicar na raiz (sem `/sysattack` na URL):**
   `mvn clean package -Pjakarta -Dwar.name=ROOT` (ou sem `-Pjakarta` no Tomcat 9).
   Pare o Tomcat, apague a pasta `webapps\ROOT` (a página padrão com o "gatinho") e copie o `target\ROOT.war` para `webapps`.
2. **Porta 80** (para não digitar `:8085`): em `TOMCAT\conf\server.xml`, no `<Connector ... port="8085" ...>` troque para `port="80"`.
   Garanta que nada mais usa a porta 80 (ex.: IIS) e libere a porta 80 no Firewall do Windows do servidor.
3. **Nome na rede:** peça à TI para criar no DNS interno um registro **A** `momesso-sysattack` apontando para o IP fixo do servidor.
   Todos passam a abrir `http://momesso-sysattack/`.
   *Teste rápido sem a TI (só no seu PC):* edite como administrador `C:\Windows\System32\drivers\etc\hosts` e adicione
   `IP_DO_SERVIDOR   momesso-sysattack` (use `127.0.0.1` se o Tomcat estiver no mesmo PC).
4. **HTTPS (recomendado):** peça à TI um certificado e um proxy reverso (IIS/ARR, nginx ou Caddy) na frente do Tomcat.
   Se um dia for abrir para a internet (domínio público, ex.: `momesso-sysattack.com.br`), faça somente com HTTPS e após revisão de segurança da TI.

## 4. Primeiro acesso, usuários e e-mail
Não há usuário/senha padrão: na primeira vez a tela de login pede para **criar o administrador**.
Senha forte: 10+ caracteres, com minúscula, maiúscula, número e símbolo. O administrador cadastra, bloqueia e ativa usuários.
A troca de senha exige um código enviado por e-mail. Crie `config.properties` na pasta de dados
(`C:\Users\SEU_USUARIO\.sysattack\config.properties`):

```
smtp.host=smtp.office365.com
smtp.port=587
smtp.security=starttls
smtp.user=sysattack@suaempresa.com.br
smtp.password=SENHA_OU_SENHA_DE_APLICATIVO
smtp.from=sysattack@suaempresa.com.br
```
Para **testar sem e-mail**, use somente `smtp.dev=true` (o código aparece no log do Tomcat — não use em produção).

## 5. Funcionalidades
- **Projetos:** escopo inicial, datas, responsável, situação e **orçamento**.
- **Tarefas pai e filhas:** crie tarefas principais e, dentro delas, filhas (botão **+ Filha**). A tarefa pai é calculada
  (início = menor início das filhas, fim = maior fim, progresso = média ponderada pela duração).
- **Marcos:** qualquer tarefa (pai ou filha) pode ser marcada como marco; aparece como losango no Gantt e na seção **Marcos**
  (menu e aba do projeto), com abas Todos / Atrasados / Concluídos e conclusão com um clique.
- **Gantt:** árvore à esquerda (recolher/expandir), barras de resumo, rótulos, dependências, hoje, zoom, "Mostrar tudo", impressão.
- **Financeiro (por projeto):** lançamento de notas fiscais de serviço e faturas, situação (pendente/pago/vencido),
  totais e saldo contra o orçamento.
- **Documentos (por projeto):** envio de PDF, imagens, Office, ZIP, DWG etc. (até 25 MB), visualização de PDF/imagem no sistema e download.

## 6. Configurações IA Agent (Claude)
Menu **Configurações IA Agent** (somente administrador): conecta e desconecta o Claude ao SysAttack — primeiro passo para o assistente de projetos.

- **Como conectar:** a Anthropic não permite o login de contas Claude.ai em sistemas de terceiros. A conexão usa uma **chave de API** criada
  na conta da empresa no Claude Console (`platform.claude.com/settings/keys`). Cole a chave (`sk-ant-...`), escolha o modelo e clique em *Conectar*;
  o SysAttack valida a chave na Anthropic antes de salvar.
- **Testar conexão:** envia uma mensagem mínima ao modelo escolhido (confere chave, modelo e saldo).
- **Desconectar:** apaga a chave do SysAttack. Para encerrar o acesso por completo, desative/exclua a chave também no Console.
- **Segurança:** a chave é gravada criptografada (AES-256-GCM); a chave-mestra fica em `ai.key` na pasta de dados (inclua no backup, junto do banco).
  A chave nunca volta ao navegador. Use uma chave exclusiva do SysAttack, com validade e limite de gastos no workspace.
- **Rede:** o servidor precisa acessar `api.anthropic.com:443`. Com proxy, inicie o Tomcat com
  `-Dhttps.proxyHost=SEU_PROXY -Dhttps.proxyPort=PORTA`.
- O uso é cobrado na conta do Console (separado de assinaturas do Claude.ai).

## Dados
Pasta `~/.sysattack/` (banco `sysattack.mv.db` e arquivos em `docs/`). Mude com `-Dsysattack.data=C:\dados\sysattack`.
**Faça backup dessa pasta.** Para recomeçar do zero, pare o Tomcat e apague-a.

## Estrutura
- `src/main/java/br/com/sysattack` — `ApiServlet` (API JSON), `Db`, `Auth`, `Mailer`, `AiConfig`, `TaskRules`, `AppListener`
- `src/main/webapp` — `login.html`, `index.html`, `css/`, `js/`, `img/`, `favicon.ico`
