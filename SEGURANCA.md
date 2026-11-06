# Segurança do SysAttack

## O que o sistema já faz
- **Senhas**: PBKDF2-HMAC-SHA256 (600 mil iterações, sal por usuário), política forte (10+ caracteres, maiúscula, minúscula, número, símbolo), troca de senha com código por e-mail.
- **Login**: trava temporária após 5 falhas (conta+IP), 20 (conta, qualquer IP) ou 40 (IP); resposta igual para e-mail inexistente; sessão nova a cada login; sessão de 4 h de inatividade; usuário bloqueado perde o acesso na hora.
- **Navegador**: CSP restritiva (só scripts do próprio sistema), anti-clickjacking, nosniff, cookie HttpOnly + SameSite=Lax, escritas exigem cabeçalho próprio e Origin do mesmo servidor (anti-CSRF).
- **Dados**: SQL sempre parametrizado; uploads só de tipos permitidos (sem HTML/SVG), nome de arquivo gerado pelo servidor, baixados com nosniff; chave do Claude e senha do SMTP criptografadas com AES-256-GCM.
- **Auditoria**: logins, falhas, bloqueios, senhas, usuários, backups e downloads, por 1 ano (tela Segurança e dados).
- **Primeiro acesso**: só aceito uma vez; se o banco aparecer vazio numa instalação já configurada, é recusado (marcador `.initialized`).

## O que depende do ambiente (faça no servidor)
1. **HTTPS**: coloque o Tomcat atrás de HTTPS (IIS/Nginx/Apache como proxy reverso, ou conector TLS). Sem isso, senhas e sessão trafegam em texto puro na rede. Com HTTPS o sistema envia HSTS e o cookie vira `Secure`. Se usar proxy, repasse `X-Forwarded-Proto` e `X-Forwarded-Host`.
2. **Rede**: não exponha a porta 8085 à internet; libere só a rede da empresa/VPN. Remova os apps de exemplo do Tomcat (`docs`, `examples`, `manager`, `host-manager`) e mantenha Tomcat e Java atualizados.
3. **Conta de serviço**: rode o Tomcat com um usuário sem privilégios de administrador, dono único da pasta de dados (e da pasta de backups). No Windows: remova "Usuários" das permissões da pasta (`icacls C:\dados\sysattack /inheritance:r /grant:r "SERVICO:(OI)(CI)F" "Administradores:(OI)(CI)F"`).
4. **Disco criptografado** (BitLocker/LUKS): o banco e os backups contêm dados de clientes e a chave `ai.key`.
5. **Tomcat**: em `server.xml` use `<Valve className="org.apache.catalina.valves.ErrorReportValve" showReport="false" showServerInfo="false"/>` no Host e, se houver proxy, `RemoteIpValve` para o IP real aparecer na auditoria e no limite de tentativas.
6. **Backups**: segunda cópia fora do servidor (`backup.mirror`) e teste de restauração periódico.
7. **Senha do SMTP**: use senha de app / conta dedicada com permissão só de envio.

## Limites conhecidos
- O arquivo do banco H2 não é criptografado por padrão (depende do disco criptografado).
- Não há autenticação em dois fatores; quem controla o e-mail do usuário controla a troca de senha.
- O sistema não foi submetido a teste de intrusão; trate esta lista como base, não como certificação.
