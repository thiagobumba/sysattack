/* SysAttack — Segurança e dados: backups, estado do banco e trilha de auditoria. Somente administradores. */
const Sys = (() => {
  const { api, esc, ic } = SA;
  const A = () => window.App;
  const KIND = { auto: ['Automático', 'b-grey'], manual: ['Manual', 'b-dark'], 'pre-update': ['Antes de atualizar', 'b-grey'] };
  const when = iso => esc(String(iso || '').slice(0, 16).replace('T', ' '));
  const size = n => {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1).replace('.', ',') + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1).replace('.', ',') + ' MB';
    return (n / 1073741824).toFixed(2).replace('.', ',') + ' GB';
  };
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const ACTION = {
    LOGIN: 'Entrou', LOGOUT: 'Saiu', LOGIN_FALHA: 'Falha de login', LOGIN_BLOQUEADO: 'Login recusado', SETUP: 'Primeiro acesso', SETUP_BLOQUEADO: 'Primeiro acesso recusado',
    SENHA_ALTERADA: 'Senha alterada', USUARIO_CRIADO: 'Usuário criado', USUARIO_ALTERADO: 'Usuário alterado', USUARIO_EXCLUIDO: 'Usuário excluído',
    USUARIO_BLOQUEADO: 'Usuário bloqueado', USUARIO_DESBLOQUEADO: 'Usuário desbloqueado', BACKUP_MANUAL: 'Backup manual', BACKUP_DOWNLOAD: 'Download de backup',
    LOGIN_SENHA_OK: 'Senha ok, aguardando 2FA', LOGIN_2FA_FALHA: 'Falha no código 2FA', SENHA_DEFINIDA: 'Senha criada pelo convite', CONVITE_REENVIADO: 'Convite reenviado',
    LINK_SENHA_ENVIADO: 'Link de nova senha enviado', '2FA_ATIVADO': '2FA ativado', '2FA_DESATIVADO': '2FA desativado', '2FA_CODIGOS_NOVOS': 'Novos códigos de recuperação', '2FA_REDEFINIDO': '2FA redefinido', CRONOGRAMA_IMPORTADO: 'Cronograma importado'
  };

  function warnings(list) {
    if (!list.length) return `<p class="hint" style="margin:0 0 16px"><span class="badge b-green">Tudo certo</span> Nenhum alerta sobre os backups.</p>`;
    return `<div class="form-error" role="alert" style="margin-bottom:16px"><strong>Atenção</strong><ul style="margin:8px 0 0 16px;padding:0">${list.map(w => `<li>${esc(w)}</li>`).join('')}</ul></div>`;
  }

  function backupsTable(list) {
    if (!list.length) return empty('Nenhum backup ainda', 'Clique em “Fazer backup agora”. Os automáticos rodam todo dia às 02:00.');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Data</th><th>Tipo</th><th>Tamanho</th><th></th></tr></thead><tbody>
      ${list.map(b => `<tr><td class="strong">${when(b.at)}<span class="small">${esc(b.name)}</span></td>
        <td><span class="badge ${(KIND[b.kind] || KIND.auto)[1]}">${(KIND[b.kind] || KIND.auto)[0]}</span></td><td>${size(b.size)}</td>
        <td class="acts"><button class="btn btn-sm" data-action="sys-verify" data-name="${esc(b.name)}">Verificar</button>
        <a class="btn btn-sm" href="api/backup/download/${encodeURIComponent(b.name)}" download>Baixar</a></td></tr>`).join('')}</tbody></table></div>`;
  }

  function historyTable(list) {
    if (!list.length) return empty('Sem histórico', 'Cada tentativa de backup fica registrada aqui.');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th><th>Resultado</th><th>Verificação</th><th>Espelho</th><th>Duração</th></tr></thead><tbody>
      ${list.map(h => `<tr><td>${when(h.at)}<span class="small">${esc((KIND[h.kind] || [h.kind])[0])}</span></td>
        <td>${h.ok ? '<span class="badge b-green">Concluído</span>' : '<span class="badge b-red">Falhou</span>'}${h.error ? `<span class="small">${esc(h.error)}</span>` : `<span class="small">${size(h.size)}</span>`}</td>
        <td>${h.ok ? (h.verified ? '<span class="badge b-green">Íntegro</span>' : '<span class="badge b-red">Falhou</span>') : '—'}${h.verify_message ? `<span class="small">${esc(h.verify_message)}</span>` : ''}</td>
        <td>${esc(h.mirror || '—')}</td><td>${h.seconds != null ? esc(String(h.seconds).replace('.', ',')) + ' s' : '—'}</td></tr>`).join('')}</tbody></table></div>`;
  }

  function auditTable(list) {
    if (!list.length) return empty('Sem registros', 'Logins, trocas de senha, mudanças de usuário e backups aparecem aqui (guardados por 1 ano).');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th><th>Quem</th><th>Ação</th><th>Detalhe</th><th>Origem</th></tr></thead><tbody>
      ${list.map(a => `<tr><td>${when(a.at)}</td><td>${esc(a.actor || '—')}</td>
        <td><span class="badge ${a.ok ? 'b-grey' : 'b-red'}">${esc(ACTION[a.action] || a.action)}</span></td><td>${esc(a.detail)}</td><td>${esc(a.ip)}</td></tr>`).join('')}</tbody></table></div>`;
  }

  async function view() {
    const [st, sec] = await Promise.all([api('GET', 'backup'), api('GET', 'security')]);
    const last = st.last_ok;
    A().main.innerHTML = A().pageHead(['SysAttack', 'Segurança e dados'], 'Segurança e dados', 'Backups do banco e dos arquivos, saúde do armazenamento e registro de acessos.') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Backups</h2>
        <button class="btn btn-primary btn-sm" data-action="sys-run">Fazer backup agora</button></div>
        ${warnings(st.warnings)}
        <div class="mini-kpis">
          <div class="mini"><span>Último backup</span><strong>${last ? when(last.at) : '—'}</strong></div>
          <div class="mini"><span>Banco de dados</span><strong>${size(st.db_size)}</strong></div>
          <div class="mini"><span>Documentos (${st.docs_count})</span><strong>${size(st.docs_size)}</strong></div>
          <div class="mini"><span>Espaço livre no destino</span><strong>${size(st.free_space)}</strong></div>
        </div>
        <dl class="kv" style="margin:16px 0 4px;display:grid;grid-template-columns:max-content 1fr;gap:8px 16px">
          <dt>Pasta de dados</dt><dd><code>${esc(st.data_dir)}</code> <span class="small" style="display:inline">(${esc(st.data_source)})</span></dd>
          <dt>Endereço nos e-mails</dt><dd>${st.app_url ? `<code>${esc(st.app_url)}</code>${st.app_url_source ? ` <span class="small" style="display:inline">(${esc(st.app_url_source)})</span>` : ''}` : '<span class="small" style="display:inline">não definido (usa o endereço do navegador)</span>'} ${st.app_url_ok ? '<span class="badge b-green">Acessível por outras máquinas</span>' : '<span class="badge b-red">Defina app.url no config.properties</span>'}
            <button class="btn btn-sm" data-action="sys-addr-test" style="margin-left:8px">Testar</button><span id="sy-addr-res" class="small" style="display:inline"></span></dd>
          <dt>Você está acessando por</dt><dd><code>${esc(st.browser_url || '—')}</code></dd>
          <dt>Pasta dos backups</dt><dd><code>${esc(st.backup_dir)}</code></dd>
          <dt>Segunda cópia</dt><dd>${st.mirror_dir ? `<code>${esc(st.mirror_dir)}</code>` : '<span class="badge b-red">Não configurada</span>'}</dd>
          <dt>Banco</dt><dd>H2 ${esc(st.h2_version)} · id <code>${esc(st.data_id)}</code></dd>
        </dl>
        ${st.browser_url && st.app_url_matches === false ? `<p class="hint late-txt" style="margin:12px 0 0"><b>Os dois endereços estão diferentes.</b> Os links dos e-mails (convite, troca de senha e relatórios) apontam para <code>${esc(st.app_url)}</code>, não para o endereço que você está usando. Se o certo agora é <code>${esc(st.browser_url)}</code>, ajuste a linha abaixo no <code>config.properties</code> da pasta de dados — vale na hora, sem reiniciar o Tomcat:<br><code>app.url=${esc(st.browser_url)}</code></p>` : ''}
        <p class="hint" style="margin:8px 0 0">Cada backup é um único arquivo com o banco (cópia física e script SQL), os documentos, a chave <code>ai.key</code> e o <code>config.properties</code>. É verificado ao terminar. Retenção: 1 por dia (14 dias), 1 por semana (8 semanas), 1 por mês (12 meses). Como o arquivo contém a chave de criptografia, guarde-o como informação confidencial. Para restaurar, use <code>restaurar-backup.ps1</code> (veja o README).</p>
      </section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Backups disponíveis</h2></div>${backupsTable(st.backups)}</section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Histórico de execuções</h2></div>${historyTable(st.history)}</section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Registro de acessos e alterações</h2></div>${auditTable(sec.audit)}</section>`;
  }

  async function action(el) {
    if (el.dataset.action === 'sys-addr-test') {
      const out = document.getElementById('sy-addr-res');
      out.textContent = ' testando…'; el.disabled = true;
      try {
        const r = await api('GET', 'backup/address-test');
        out.innerHTML = ` <span class="badge ${r.ok ? 'b-green' : 'b-red'}">${r.ok ? 'OK' : esc(String(r.step || 'falha'))}</span> ${esc(r.message || '')}${r.resolved ? ` <span class="small" style="display:inline">(resolve para ${esc(r.resolved)})</span>` : ''}`;
      } catch (e) { out.innerHTML = ` <span class="badge b-red">erro</span> ${esc(e.message)}`; }
      el.disabled = false;
      return;
    }
    switch (el.dataset.action) {
      case 'sys-run': {
        el.disabled = true; el.textContent = 'Gerando…';
        try {
          const r = await api('POST', 'backup/run');
          A().toast(r.ok ? (r.verified ? 'Backup concluído e verificado.' : 'Backup gerado, mas a verificação falhou.') : 'O backup falhou: ' + r.error);
        } finally { await A().route(); }
        break;
      }
      case 'sys-verify': {
        el.disabled = true;
        try {
          const r = await api('POST', 'backup/verify/' + encodeURIComponent(el.dataset.name));
          A().toast((r.ok ? 'Íntegro. ' : 'PROBLEMA: ') + r.message);
        } finally { el.disabled = false; }
        break;
      }
    }
  }

  return { view, action };
})();
