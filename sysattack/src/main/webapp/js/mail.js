/* SysAttack — Configurações de E-mail (SMTP) e gatilhos de e-mail dos projetos. Somente administradores. */
const Mail = (() => {
  const { api, esc, fmt, ic } = SA;
  const A = () => window.App;
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const SEC = { starttls: 'STARTTLS (porta 587)', ssl: 'SSL/TLS (porta 465)', none: 'Sem criptografia (não recomendado)' };
  const L_STATUS = { ENVIADO: ['Enviado', 'b-green'], TESTE: ['Modo teste', 'b-grey'], FALHA: ['Falhou', 'b-red'] };
  let events = [], projects = [], projectId = null;
  const evLabel = k => (events.find(e => e.key === k) || {}).label || k;

  async function load() {
    [events, projects] = await Promise.all([api('GET', 'email/events'), api('GET', 'projects')]);
  }

  function rulesTable(rules, showProject) {
    if (!rules.length) return empty('Nenhum gatilho ainda', 'Crie uma regra para avisar por e-mail quando algo acontecer.');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th>${showProject ? '<th>Projeto</th>' : ''}<th>Quem recebe</th><th>Último envio</th><th>Situação</th><th></th></tr></thead><tbody>
      ${rules.map(r => `<tr><td class="strong">${esc(evLabel(r.event))}${r.subject ? `<span class="small">Assunto: ${esc(r.subject)}</span>` : ''}</td>
        ${showProject ? `<td>${r.project_id ? esc(r.project_name) : '<span class="badge b-dark">Todos</span>'}</td>` : ''}
        <td>${esc(r.recipients || '—')}${r.include_responsible ? '<span class="small">+ responsável da tarefa/projeto</span>' : ''}${!showProject && !r.project_id ? '<span class="small">Regra global (vale para todos os projetos)</span>' : ''}</td>
        <td>${r.last_sent ? fmt(String(r.last_sent).slice(0, 10)) : '—'}</td>
        <td>${r.enabled ? '<span class="badge b-green">Ativa</span>' : '<span class="badge b-grey">Pausada</span>'}</td>
        <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar regra" data-action="em-edit-rule" data-id="${r.id}">${ic('edit')}</button>
        <button class="icon-btn del" title="Excluir" aria-label="Excluir regra" data-action="em-del-rule" data-id="${r.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`;
  }

  function logTable(log, showProject) {
    if (!log.length) return empty('Nada enviado ainda', 'Os envios dos gatilhos aparecem aqui (guardados por 90 dias).');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th><th>Evento</th>${showProject ? '<th>Projeto</th>' : ''}<th>Destinatários</th><th>Assunto</th><th>Resultado</th></tr></thead><tbody>
      ${log.map(l => `<tr><td>${esc(String(l.created_at).slice(0, 16).replace('T', ' '))}</td><td>${esc(evLabel(l.event))}</td>${showProject ? `<td>${esc(l.project_name || '—')}</td>` : ''}
        <td>${esc(l.recipients)}</td><td>${esc(l.subject)}</td>
        <td><span class="badge ${L_STATUS[l.status][1]}">${L_STATUS[l.status][0]}</span>${l.error ? `<span class="small">${esc(l.error)}</span>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
  }

  function testLogsTable(logs, dir) {
    if (!logs.length) return '';
    return `<h3 style="margin:24px 0 8px;font-size:15px">Logs dos testes de envio</h3>
      <p class="hint" style="margin:0 0 8px">Cada tentativa gera um arquivo .log (sem senha), guardado no banco e também na pasta <code>${esc(dir || '')}</code> do servidor, para extração.</p>
      <div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th><th>Por</th><th>Destino</th><th>Resultado</th><th></th></tr></thead><tbody>
      ${logs.map(l => `<tr><td>${esc(String(l.created_at).slice(0, 16).replace('T', ' '))}</td><td>${esc(l.created_by)}</td><td>${esc(l.recipient)}</td>
        <td><span class="badge ${l.ok ? 'b-green' : 'b-red'}">${l.ok ? 'OK' : 'Falhou'}</span><span class="small">${esc(l.summary)}</span></td>
        <td class="acts"><a class="btn btn-sm" href="api/email/settings/test-logs/${l.id}/download" download>Baixar</a></td></tr>`).join('')}</tbody></table></div>`;
  }

  // ------------------------------------------------------------------ página de configurações
  async function view() {
    await load();
    const [st, rules, log, tlogs] = await Promise.all([api('GET', 'email/settings'), api('GET', 'email/rules'), api('GET', 'email/log'), api('GET', 'email/settings/test-logs')]);
    const test = st.last_test_at ? `${st.last_test_ok ? '<span class="badge b-green">OK</span>' : '<span class="badge b-red">Falhou</span>'} <span class="small" style="display:inline">${esc(String(st.last_test_at).slice(0, 16).replace('T', ' '))} · ${esc(st.last_test_msg || '')}</span>` : '<span class="small" style="display:inline">Ainda não testado</span>';
    A().main.innerHTML = A().pageHead(['SysAttack', 'Configurações de E-mail'], 'Configurações de E-mail', 'E-mail de saída do SysAttack e gatilhos que avisam pessoas sobre o andamento dos projetos.') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">E-mail de saída (SMTP)</h2>
        <span class="ai-status ${st.configured && st.enabled ? 'on' : ''}"><i></i>${st.configured ? (st.enabled ? 'Ativo' : 'Pausado') : (st.file_fallback ? 'Usando config.properties' : 'Não configurado')}</span></div>
        <form id="em-form" class="fgrid" novalidate autocomplete="off">
          <div class="field"><label for="em-h">Servidor SMTP</label><input id="em-h" name="host" maxlength="200" value="${esc(st.host)}" placeholder="smtp.office365.com" required></div>
          <div class="field"><label for="em-p">Porta</label><input id="em-p" name="port" type="number" min="1" max="65535" value="${esc(st.port)}" required></div>
          <div class="field"><label for="em-s">Segurança</label><select id="em-s" name="security">${Object.entries(SEC).map(([k, v]) => `<option value="${k}"${k === st.security ? ' selected' : ''}>${v}</option>`).join('')}</select></div>
          <div class="field"><label for="em-a">Autenticação</label><select id="em-a" name="auth_mode"><option value="BASICO"${st.auth_mode === 'OAUTH2' ? '' : ' selected'}>Usuário e senha</option><option value="OAUTH2"${st.auth_mode === 'OAUTH2' ? ' selected' : ''}>OAuth 2.0 (Microsoft 365)</option></select></div>
          <div class="field"><label for="em-u">Usuário<span class="opt" id="em-u-h"></span></label><input id="em-u" name="username" maxlength="200" value="${esc(st.username)}" placeholder="sysattack@empresa.com.br" autocomplete="off"></div>
          <div class="field" id="em-w-f"><label for="em-w">Senha${st.has_password ? ' (guardada — deixe em branco para manter)' : ''}</label><input id="em-w" name="password" type="password" maxlength="400" autocomplete="new-password" placeholder="${st.has_password ? '••••••••' : ''}"></div>
          <div class="field oa" id="em-ti-f"><label for="em-ti">ID do diretório (locatário)</label><input id="em-ti" name="tenant_id" maxlength="100" value="${esc(st.tenant_id || '')}" placeholder="00000000-0000-0000-0000-000000000000" autocomplete="off"></div>
          <div class="field oa" id="em-ci-f"><label for="em-ci">ID do aplicativo (cliente)</label><input id="em-ci" name="client_id" maxlength="100" value="${esc(st.client_id || '')}" placeholder="00000000-0000-0000-0000-000000000000" autocomplete="off"></div>
          <div class="field oa" id="em-cs-f"><label for="em-cs">Segredo do cliente <span class="opt">(o <b>Valor</b>, não o ID)</span>${st.has_secret ? ' (guardado — deixe em branco para manter)' : ''}</label><input id="em-cs" name="client_secret" type="password" maxlength="400" autocomplete="new-password" placeholder="${st.has_secret ? '••••••••' : ''}"></div>
          <div class="field"><label for="em-f">E-mail do remetente</label><input id="em-f" name="from_email" maxlength="200" value="${esc(st.from_email)}" placeholder="igual ao usuário, se vazio"></div>
          <div class="field"><label for="em-n">Nome do remetente</label><input id="em-n" name="from_name" maxlength="120" value="${esc(st.from_name)}"></div>
          <div class="field" style="justify-content:flex-end"><label style="display:flex;gap:8px;align-items:center;font-weight:600"><input type="checkbox" name="enabled" ${st.enabled ? 'checked' : ''} style="width:auto;min-height:0"> Envio ativo</label></div>
          <p class="hint" style="grid-column:1/-1" id="em-help"></p>
          <div class="form-error" hidden role="alert" style="grid-column:1/-1"></div>
          <div class="form-actions" style="grid-column:1/-1;justify-content:flex-start"><button type="submit" class="btn btn-primary">Salvar configuração</button>
            ${st.configured ? '<button type="button" class="btn btn-danger" data-action="em-remove">Remover</button>' : ''}</div></form>
        ${st.configured ? `<hr style="border:0;border-top:1px solid var(--line);margin:24px 0">
          <div class="field" style="max-width:520px"><label for="em-t">Enviar e-mail de teste para</label><div class="ai-row"><input id="em-t" type="email" placeholder="deixe vazio para enviar para você">
            <button class="btn btn-sm btn-primary" data-action="em-test">Enviar teste</button></div></div>
          <p style="margin:16px 0 0"><b>Último teste:</b> ${test}${tlogs.length ? ` <a class="btn btn-sm" href="api/email/settings/test-logs/${tlogs[0].id}/download" download>Baixar log</a>` : ''}</p>
          ${testLogsTable(tlogs, st.log_dir)}` : ''}</section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Gatilhos de e-mail (todos os projetos)</h2>
        <button class="btn btn-primary btn-sm" data-action="em-new-rule" data-global="1">+ Novo gatilho</button></div>${rulesTable(rules, true)}</section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Histórico de envios</h2></div>${logTable(log, true)}</section>`;
    const form = document.getElementById('em-form'), err = form.querySelector('.form-error');
    const BASICO = 'Office 365: <code>smtp.office365.com</code>, 587, STARTTLS. Gmail/Workspace: <code>smtp.gmail.com</code>, 587, STARTTLS, com <b>senha de app</b>. A senha é guardada <b>criptografada</b> e nunca é exibida de novo.';
    const OAUTH = 'No <b>Microsoft Entra ID</b>, registre um aplicativo, dê a ele a permissão <b>SMTP.SendAsApp</b> (Office 365 Exchange Online) com consentimento do administrador e gere um segredo do cliente — copie a coluna <b>Valor</b>, não o <b>ID secreto</b>. No PowerShell do Exchange: <code>New-ServicePrincipal</code> (use o <b>ID do objeto da Aplicação Empresarial</b>), <code>Add-MailboxPermission</code> na caixa remetente e <code>Set-CASMailbox -SmtpClientAuthenticationDisabled $false</code>. O <b>Usuário</b> tem de ser a caixa remetente. O segredo é guardado <b>criptografado</b> e nunca é exibido de novo.';
    const amSync = () => {
      const oauth = form.auth_mode.value === 'OAUTH2';
      document.getElementById('em-w-f').style.display = oauth ? 'none' : '';
      form.querySelectorAll('.oa').forEach(e => { e.style.display = oauth ? '' : 'none'; });
      document.getElementById('em-u-h').textContent = oauth ? ' (caixa remetente)' : '';
      document.getElementById('em-help').innerHTML = oauth ? OAUTH : BASICO;
    };
    form.auth_mode.addEventListener('change', amSync); amSync();
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const b = form.querySelector('[type=submit]');
      b.disabled = true; err.hidden = true;
      try {
        await api('PUT', 'email/settings', { host: form.host.value.trim(), port: Number(form.port.value), security: form.security.value, username: form.username.value.trim(),
          password: form.password.value, from_email: form.from_email.value.trim(), from_name: form.from_name.value.trim(), enabled: form.enabled.checked,
          auth_mode: form.auth_mode.value, tenant_id: form.tenant_id.value.trim(), client_id: form.client_id.value.trim(), client_secret: form.client_secret.value });
        A().toast('Configuração salva. Envie um e-mail de teste.'); await A().route();
      } catch (ex) { err.textContent = ex.message; err.hidden = false; b.disabled = false; }
    });
  }

  // ------------------------------------------------------------------ aba do projeto
  async function projectTab(host, pid) {
    projectId = pid;
    await load();
    const [rules, log] = await Promise.all([api('GET', 'email/rules?project_id=' + pid), api('GET', 'email/log?project_id=' + pid)]);
    host.innerHTML = `<p class="hint" style="margin-bottom:16px">Gatilhos avisam pessoas por e-mail quando algo acontece neste projeto. O envio usa o e-mail de saída configurado em <a href="#/email">Configurações de E-mail</a>.</p>
      ${rulesTable(rules, false)}<h3 style="margin:24px 0 8px;font-size:15px">Últimos envios</h3>${logTable(log.slice(0, 15), false)}`;
  }

  async function ruleForm(id, global) {
    const r = id ? (await api('GET', 'email/rules')).find(x => x.id === id) : { project_id: global ? null : projectId, event: events[0].key, recipients: '', include_responsible: false, subject: '', message: '', enabled: true };
    const pid = r.project_id;
    A().openDialog(id ? 'Editar gatilho' : 'Novo gatilho de e-mail', `
      <div class="field full"><label for="rp">Projeto</label><select id="rp" name="project_id"><option value="">Todos os projetos</option>${projects.map(p => `<option value="${p.id}"${p.id === pid ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}</select></div>
      <div class="field full"><label for="re">Quando acontecer</label><select id="re" name="event">${events.map(e => `<option value="${e.key}"${e.key === r.event ? ' selected' : ''}>${esc(e.label)}</option>`).join('')}</select></div>
      <div class="field full"><label for="rr">Enviar para (e-mails separados por vírgula)</label><input id="rr" name="recipients" maxlength="1000" value="${esc(r.recipients)}" placeholder="gestor@empresa.com, pmo@empresa.com"></div>
      <div class="field full"><label style="display:flex;gap:8px;align-items:center;font-weight:600"><input type="checkbox" name="include_responsible" ${r.include_responsible ? 'checked' : ''} style="width:auto;min-height:0">
        Incluir também o responsável (se o nome dele for igual ao de um usuário ativo do sistema)</label></div>
      <div class="field full"><label for="rs">Assunto (opcional)</label><input id="rs" name="subject" maxlength="200" value="${esc(r.subject)}" placeholder="[SysAttack] {evento} · {projeto}"></div>
      <div class="field full"><label for="rm">Mensagem extra (opcional)</label><textarea id="rm" name="message" maxlength="1000">${esc(r.message)}</textarea></div>
      <div class="field full"><label style="display:flex;gap:8px;align-items:center;font-weight:600"><input type="checkbox" name="enabled" ${r.enabled ? 'checked' : ''} style="width:auto;min-height:0"> Gatilho ativo</label></div>
      <p class="hint full">No assunto, use <code>{evento}</code>, <code>{projeto}</code> e <code>{item}</code> (tarefa, lançamento ou documento). “Tarefa atrasada” é verificado a cada 30 minutos e avisa uma vez por tarefa.</p>`,
      async d => {
        await api(id ? 'PUT' : 'POST', id ? 'email/rules/' + id : 'email/rules', { ...d, include_responsible: d.include_responsible === 'on', enabled: d.enabled === 'on', project_id: d.project_id || null });
        A().toast('Gatilho salvo.');
      });
  }

  async function action(el) {
    const id = Number(el.dataset.id);
    switch (el.dataset.action) {
      case 'em-new-rule': if (!events.length) await load(); await ruleForm(null, !!el.dataset.global); break;
      case 'em-edit-rule': await ruleForm(id); break;
      case 'em-del-rule': if (confirm('Excluir este gatilho?')) { await api('DELETE', 'email/rules/' + id); A().toast('Gatilho excluído.'); await A().route(); } break;
      case 'em-test': {
        el.disabled = true;
        try { const r = await api('POST', 'email/settings/test', { to: document.getElementById('em-t').value.trim() }); A().toast(r.last_test_ok ? 'Teste enviado. Log baixado.' : 'Teste falhou: ' + r.last_test_msg + ' (log baixado)');
          // baixa o log da tentativa que acabou de ser feita
          const logs = await api('GET', 'email/settings/test-logs');
          if (logs.length) { const a = document.createElement('a'); a.href = 'api/email/settings/test-logs/' + logs[0].id + '/download'; a.download = logs[0].file_name; document.body.appendChild(a); a.click(); a.remove(); }
          await A().route(); }
        finally { el.disabled = false; }
        break;
      }
      case 'em-remove': if (confirm('Remover a configuração de e-mail do banco? (o config.properties, se existir, volta a ser usado)')) { await api('DELETE', 'email/settings'); A().toast('Configuração removida.'); await A().route(); } break;
    }
  }

  return { view, projectTab, action };
})();
