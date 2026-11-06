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
Novos usuários recebem um e-mail de boas-vindas com um link para criar a senha. Para o endereço do sistema aparecer correto no e-mail, informe a URL pública:
`app.url=https://sysattack.suaempresa.com.br` (também aceito: `-Dsysattack.url=` ou a variável `SYSATTACK_URL`). O texto das políticas de uso do e-mail fica em `Invites.java`; revise-o com o jurídico/TI.
Cada pessoa pode ativar a **verificação em duas etapas** em Usuários > Segurança da conta; o administrador pode exigi-la na edição do usuário.

O endereço do sistema (`app.url`) já vem **embutido no .war**, em `src/main/resources/config.properties` (vira `WEB-INF/classes/config.properties`): não precisa copiar nada para a pasta de dados. O `config.properties` da pasta de dados, se existir, tem prioridade e deve ser usado para senhas (nunca coloque senha no arquivo do projeto, que vai para o git). Modelo completo, com SMTP e backups: `config.exemplo.properties`.

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

## Dados e backups

**Onde ficam.** Defina um caminho fixo, fora do perfil do usuário, para o banco não "mudar" se a conta do serviço do Tomcat mudar:
`-Dsysattack.data=C:\dados\sysattack` (ou variável de ambiente `SYSATTACK_DATA`). Sem isso o padrão é `~/.sysattack`.
A tela **Segurança e dados** mostra qual pasta está em uso e avisa se algo estiver arriscado.

Conteúdo da pasta: `sysattack.mv.db` (banco), `docs/` (documentos), `ai.key` (chave que criptografa a chave do Claude e a senha do SMTP), `config.properties`.
Notas fiscais e arquivos dos projetos Spot ficam dentro do banco.

**Backup automático.** Todo dia às 02:00 (e logo após o Tomcat subir, se o último tem mais de 26 h), mais um clique em "Fazer backup agora". Cada backup é **um arquivo**
`sysattack-AAAAMMDD-HHMMSS-<tipo>.zip` com: `manifest.json`, `database/physical.zip` (cópia consistente feita com o sistema em uso), `database/script.sql.zip`
(texto SQL, serve para migrar de versão do H2), `files/docs/*` e `config/*` (ai.key e config.properties). Ao terminar ele é **verificado** (CRC do zip + abertura do banco copiado
lendo todas as tabelas) e recebe um `.sha256`. O histórico fica em `backup-history.jsonl` e na tela.
Retenção: 1 por dia (14 dias), 1 por semana (8), 1 por mês (12), manuais por 90 dias; os 3 mais novos nunca são apagados.
Antes de atualizar o sistema para uma versão com esquema novo, o arquivo do banco também é copiado para `pre-update-*.mv.db` (guarda as 5 últimas).

**Para proteger de verdade (recomendado):**
1. `backup.dir` (ou `-Dsysattack.backup=D:\backups\sysattack`): outro disco que não o do banco.
2. `backup.mirror` (ou `-Dsysattack.backup.mirror=\\servidor\backups\sysattack`): segunda cópia em outra máquina/rede/nuvem sincronizada. Também pode ser definido em `config.properties` na pasta de dados.
3. O backup contém a `ai.key`: trate-o como confidencial (acesso restrito à pasta; disco do servidor criptografado, ex.: BitLocker).
4. Teste a restauração em outra pasta de vez em quando (a tela tem o botão **Verificar** para checar a integridade).

**Restaurar** (Tomcat parado): `.\restaurar-backup.ps1 -Backup D:\backups\sysattack\sysattack-20261106-020000-auto.zip -Dados C:\dados\sysattack`.
O script confere o SHA-256, guarda o que existe hoje em `<dados>.antes-da-restauracao-<data>` e restaura banco, documentos e configuração.
Alternativa manual: extraia `database/script.sql.zip` e rode `java -cp h2-2.2.224.jar org.h2.tools.RunScript -url jdbc:h2:file:<pasta>/sysattack -user sa -script script.sql -options compression zip`.

**Segurança.** Veja `SEGURANCA.md`. Para recomeçar do zero, pare o Tomcat e apague a pasta de dados (e o arquivo `.initialized`, senão o primeiro acesso é recusado de propósito).

## Importar cronograma do Microsoft Project
No projeto (abas Cronograma ou Tarefas) clique em **Importar do Project** e envie o `.mpp` (Project 2010 ou mais novo) ou o XML do Project
(Arquivo > Salvar como > XML). A tela mostra o que seria criado, com uma caixa por linha; só depois de **Importar** algo é gravado.
Regras: o nível escolhido vira tarefa principal e o que está abaixo vira subtarefa (o SysAttack tem 2 níveis); marcos viram marcos;
datas são só o dia; a predecessora importada é a primeira da lista (o SysAttack guarda uma por tarefa). Não importa responsáveis nem calendários.
Código: `ProjectFileReader.java` (leitor) e `js/mpp.js` (tela). Para outras versões do Project, prefira o XML.

## Estrutura
- `src/main/java/br/com/sysattack` — `ApiServlet` (API JSON), `Db`, `BackupService`, `Auth`, `Audit`, `SecurityFilter`, `Mailer`, `AiConfig`, `TaskRules`, `AppListener`
- `src/main/webapp` — `login.html`, `index.html`, `css/`, `js/`, `img/`, `favicon.ico`

## Projetos Spot

Menu **Projetos Spot**: projetos ágeis por horas, sem marcos nem cronograma.

- **Projeto**: horas fixadas, modelo (franquia mensal ou pacote), início, participantes do comitê, destinatários e observação de faturamento.
- **Apontamentos de horas**, **Demandas** (com horas aprovadas) e **Ações e pendências**.
- **Cálculo (feito pelo sistema, nunca pela IA)**: mensal = do dia 1º do mês de referência até a data; pacote = desde o início. Consumidas = soma das horas do período; reservadas = Σ (aprovado − realizado) das demandas abertas; saldo = fixadas − consumidas; saldo projetado = saldo − reservadas.
- **Relatórios com Claude**: a aba *Relatórios com Claude* gera o texto (destaques, alertas, resumo por demanda, mensagem do e-mail) a partir dos números calculados. Requer a chave conectada em *Configurações IA Agent*. O texto pode ser editado antes do envio.
- **Envio**: o relatório vai no corpo do e-mail (precisa de SMTP em `config.properties`; com `smtp.dev=true` nada é enviado de fato). Para PDF use *Imprimir / salvar PDF*.

## Configurações de E-mail e gatilhos

Menu **Configurações de E-mail** (somente administradores):

- **SMTP pela tela**: servidor, porta, segurança, usuário, senha (guardada criptografada com a mesma chave do `ai.key`), remetente e botão **Enviar teste**. Se nada estiver salvo na tela, continua valendo o `config.properties`.
- **Gatilhos**: regras "quando acontecer X → enviar para Y". Podem valer para todos os projetos ou para um projeto (aba **Gatilhos de e-mail** dentro do projeto).
  Eventos: projeto criado, situação do projeto alterada, tarefa criada, tarefa concluída, marco concluído, tarefa atrasada (verificada a cada 30 min, um aviso por tarefa), lançamento financeiro criado e documento adicionado.
  Destinatários: lista de e-mails e, opcionalmente, o responsável (quando o nome dele é igual ao de um usuário ativo).
  Assunto aceita `{evento}`, `{projeto}` e `{item}`.
- **Histórico** dos envios (90 dias) com sucesso ou erro. Os envios acontecem em segundo plano e uma falha de e-mail nunca impede a operação no sistema.

## Atualizar a máquina após cada nova versão

Na pasta do repositório (onde está a pasta `.git`), no PowerShell:

```
.\atualizar.ps1 nome-da-branch
```

O script faz `git fetch`, `checkout` e `pull` da branch, apaga a pasta `target`, roda `mvn clean package -Pjakarta` e indica o `.war` gerado. Sem parâmetro, usa a `claude/dev`, branch única onde o Claude sobe as alterações. Com `-Watch` (`.\atualizar.ps1 nome-da-branch -Watch`) o script fica vigiando o GitHub e refaz o pull e o build sozinho a cada novo commit da branch. A troca do `.war` no Tomcat continua manual.

## Relatórios & Dashs

Menu **Relatórios & Dashs** (todos os usuários; projetos spot não entram). Filtro por projeto e botão *Imprimir / salvar PDF*.

- **Guia Projetos:** Timeline do Projeto, Itens em atraso, Previsto × Realizado (progresso esperado pelo prazo × progresso informado, ponderado pela duração), Quantidade de changes por projeto e Atrasos gerados por changes (somente changes **aprovadas** com impacto em dias).
- **Guia Orçamentária:** Previsto × Realizado em R$ (orçamento × notas fiscais e faturas lançadas) e Custo Projeto Total.
- **Changes:** nova aba dentro de cada projeto para registrar solicitações de mudança (data, motivo, solicitante, impacto em dias e R$, situação).

## Versões e git

- **Nomenclatura:** `1.0.0 - Alpha` → `1.0.0 - Beta` → `1.0.0 - RC` → `1.0.1 - Oficial`. A fonte da verdade é `sysattack/src/main/webapp/version.json` (`version` e `stage`); o rodapé do menu e a tela de login mostram esse valor sem precisar mexer em mais nada.
- **Branches:** `main` guarda só versões publicadas; o trabalho do dia a dia vai para `claude/dev`.
- **Publicar uma versão:** atualizar `version.json` e o `CHANGELOG.md`, abrir o pull request de `claude/dev` para `main`, fazer o merge e criar a tag `v<versão>[-etapa]` (ex.: `v1.0.0-alpha`, `v1.0.0-beta`, `v1.0.0-rc`, `v1.0.1`) com uma release no GitHub (pré-lançamento até a Oficial).
- Histórico das versões em `CHANGELOG.md`.
