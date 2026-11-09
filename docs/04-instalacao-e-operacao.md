# 04 – Instalação e operação

## Requisitos

- **JDK 11+** e **Maven** (para gerar o `.war`).
- **Tomcat 9.x** (`javax.servlet`) ou **Tomcat 10.1/11** (`jakarta`).
- Acesso de escrita a uma pasta de dados (banco, documentos, chaves).
- Opcional: servidor SMTP (e-mails), saída para `api.anthropic.com:443` (Claude), proxy reverso com HTTPS.

## Gerar o `.war`

Na pasta que contém o `pom.xml` (`sysattack/`):

| Tomcat | Comando |
|---|---|
| 9.x | `mvn clean package` |
| 10.1 / 11 | `mvn clean package -Pjakarta` |
| Publicar na raiz (sem `/sysattack` na URL) | acrescente `-Dwar.name=ROOT` |

O resultado fica em `target/sysattack.war` (ou `ROOT.war`).

## Publicar

1. Pare o Tomcat.
2. Apague `webapps\sysattack` (a pasta extraída) e o `sysattack.war` antigo.
3. Copie o novo `.war` para `webapps`.
4. Inicie o Tomcat.
5. Acesse `http://servidor:PORTA/sysattack/` e use **Ctrl+Shift+R** na primeira vez (limpa o cache do navegador; os arquivos `.js`/`.css` usam `?v=NN` para forçar atualização).

## Atualizar a máquina a cada versão (`atualizar.ps1`)

No PowerShell, na pasta do repositório (onde está `.git`):

```powershell
.\atualizar.ps1            # usa a branch claude/dev
.\atualizar.ps1 nome-da-branch
.\atualizar.ps1 -Watch     # fica vigiando o GitHub e gera o .war a cada novidade
```

O script faz `git fetch`, `checkout` e `pull`, apaga `target`, roda `mvn clean package -Pjakarta` e indica o `.war`. Se o `pull` falhar ou o código local não ficar igual ao GitHub, ele para com uma mensagem clara. No modo `-Watch` grava `atualizar.log`, mostra uma linha de "sem novidades" a cada 10 verificações e apita ao terminar um build. Depois: troque o `.war` no Tomcat, apague a pasta extraída, reinicie e use Ctrl+Shift+R.

## Pasta de dados

Defina um caminho fixo, fora do perfil do usuário: `-Dsysattack.data=C:\dados\sysattack` (ou variável `SYSATTACK_DATA`). Sem isso o padrão é `~/.sysattack`. A tela **Segurança e dados** mostra a pasta em uso e avisa quando algo está arriscado.

| Item | O que é |
|---|---|
| `sysattack.mv.db` | Banco H2 |
| `docs/` | Documentos dos projetos |
| `ai.key` | Chave que criptografa a chave do Claude, a senha do SMTP e os segredos do 2FA. **Faça backup junto do banco** |
| `config.properties` | Ajustes locais e senhas (tem prioridade sobre o embutido no `.war`) |
| `.initialized` | Marcador de que o primeiro acesso já foi feito |
| `backup-history.jsonl`, `pre-update-*.mv.db` | Histórico de backups e cópias antes de atualizar o esquema |

## Configuração (`config.properties`)

Existem dois arquivos, com esta prioridade (o primeiro vence):

1. **Pasta de dados** (`C:\Users\SEU_USUARIO\.sysattack\config.properties`): lugar de **senhas** e ajustes da máquina.
2. **Embutido no `.war`** (`sysattack/src/main/resources/config.properties`, vai para o git): **nunca coloque senhas aqui**. Só vale depois de gerar o `.war` de novo.

Modelo comentado: `sysattack/config.exemplo.properties`. Linhas com `#` são comentários; sem aspas nem espaços em volta do `=`; em caminhos do Windows use `/` ou `\\`.

| Chave | Para quê |
|---|---|
| `app.url` | Endereço do sistema nos e-mails (sem `/` no fim). Também aceito: `-Dsysattack.url=` ou `SYSATTACK_URL`. Se for `localhost`, convites não são enviados |
| `smtp.host`, `smtp.port`, `smtp.security` (`starttls`), `smtp.user`, `smtp.password`, `smtp.from` | Envio de e-mail (também configurável pela tela, que tem prioridade quando salva) |
| `smtp.dev=true` | **Só para teste**: nada é enviado; link/código aparece no log do Tomcat |
| `backup.dir` | Pasta de backup em **outro disco** (ou `-Dsysattack.backup=`) |
| `backup.mirror` | Segunda cópia em outra máquina/rede (ou `-Dsysattack.backup.mirror=`) |

Para o Claude por proxy, inicie o Tomcat com `-Dhttps.proxyHost=SEU_PROXY -Dhttps.proxyPort=PORTA`.

## Primeiro acesso

Não há usuário padrão: a tela de login pede para **criar o administrador**. Depois, em **Usuários**, cadastre as pessoas; cada uma recebe um e-mail com link para criar a senha. Em seguida configure **E-mail** (SMTP) e, se for usar relatórios dos Projetos Spot, **IA Agent**.

## Acessar por um nome (ex.: `http://momesso-sysattack`)

1. **Raiz:** gere com `-Dwar.name=ROOT`, apague `webapps\ROOT` e copie o `ROOT.war`.
2. **Porta 80:** em `conf\server.xml`, troque `port="8085"` do conector por `port="80"`; garanta que nada mais usa a porta 80 (ex.: IIS) e libere-a no firewall.
3. **DNS:** peça à TI um registro **A** apontando para o IP fixo do servidor. Teste rápido na sua máquina: adicione `IP_DO_SERVIDOR momesso-sysattack` em `C:\Windows\System32\drivers\etc\hosts`.
4. **HTTPS:** use um proxy reverso (IIS/ARR, nginx, Caddy) com certificado. Para internet, só com HTTPS e revisão de segurança da TI.

## Backup e restauração

**Automático:** todo dia às 02:00 e logo após subir o Tomcat (se o último tem mais de 26 h). Botões **Fazer backup agora** e **Verificar** na tela *Segurança e dados*.

Cada backup é um arquivo `sysattack-AAAAMMDD-HHMMSS-<tipo>.zip` com `manifest.json`, `database/physical.zip` (cópia consistente com o sistema em uso), `database/script.sql.zip` (SQL, serve para migrar de versão do H2), `files/docs/*` e `config/*`. É verificado ao terminar (CRC + abertura do banco) e recebe um `.sha256`. Retenção: 1 por dia (14 dias), 1 por semana (8), 1 por mês (12), manuais por 90 dias; os 3 mais novos nunca são apagados.

**Para proteger de verdade:**
1. `backup.dir` em outro disco que não o do banco.
2. `backup.mirror` em outra máquina/rede/nuvem sincronizada.
3. O backup contém a `ai.key`: trate-o como confidencial (acesso restrito, disco criptografado).
4. Teste a restauração de vez em quando.

**Restaurar** (Tomcat parado):

```powershell
.\restaurar-backup.ps1 -Backup D:\backups\sysattack\sysattack-20261106-020000-auto.zip -Dados C:\dados\sysattack
```

O script confere o SHA-256, guarda o que existe hoje em `<dados>.antes-da-restauracao-<data>` e restaura banco, documentos e configuração. Manualmente: extraia `database/script.sql.zip` e rode `java -cp h2-2.2.224.jar org.h2.tools.RunScript -url jdbc:h2:file:<pasta>/sysattack -user sa -script script.sql -options compression zip`.

**Recomeçar do zero:** pare o Tomcat e apague a pasta de dados **e** o arquivo `.initialized` (senão o primeiro acesso é recusado de propósito).

## Endurecimento do servidor

Checklist completo em [`../SEGURANCA.md`](../SEGURANCA.md): HTTPS, porta 8085 fora da internet, remover apps de exemplo do Tomcat, conta de serviço sem privilégios, disco criptografado, `ErrorReportValve`, backups fora do servidor e senha de SMTP de uso restrito.

## Solução de problemas

| Sintoma | O que verificar |
|---|---|
| Tela antiga depois de atualizar | Trocou o `.war`, apagou a pasta extraída, reiniciou? Use Ctrl+Shift+R |
| Convite por e-mail não sai | `app.url` não pode ser `localhost`; confira o SMTP (use *Enviar teste* e baixe o log) |
| "Primeiro acesso recusado" | Banco vazio com `.initialized` presente: restaure um backup ou apague o marcador (só se for recomeçar do zero) |
| Banco "mudou" depois de trocar a conta do Tomcat | Defina `sysattack.data` com caminho fixo |
| Claude não conecta | Servidor sem saída para `api.anthropic.com:443` ou falta de proxy; use *Testar conexão* |
| Erro `Referência de variável inválida` no `atualizar.ps1` | Baixe a versão atual dos scripts (gravados com BOM UTF-8) |
