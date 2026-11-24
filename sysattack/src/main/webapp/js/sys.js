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
    LINK_SENHA_ENVIADO: 'Link de nova senha enviado', '2FA_ATIVADO': '2FA ativado', '2FA_DESATIVADO': '2FA desativado', '2FA_CODIGOS_NOVOS': 'Novos códigos de recuperação', '2FA_REDEFINIDO': '2FA redefinido', CRONOGRAMA_IMPORTADO: 'Cronograma importado',
    BACKUP_IMPORTADO: 'Backup enviado para importar', BACKUP_IMPORT_CANCELADO: 'Importação cancelada'
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

  /** Resumo do que o arquivo enviado traz (tabelas do manifest, documentos e chave de criptografia). */
  function importContent(p) {
    const t = p.tables && typeof p.tables === 'object' ? p.tables : null;
    const rows = t ? Object.keys(t).filter(k => Number(t[k]) > 0).map(k => `${esc(k)} <b>${Number(t[k])}</b>`) : [];
    return `<p class="hint" style="margin:12px 0 0">Conteúdo do arquivo: <b>${Number(p.docs) || 0}</b> documento(s)${p.h2_version ? `, banco H2 ${esc(p.h2_version)}` : ''}${p.origin_dir ? `, gerado em <code>${esc(p.origin_dir)}</code>` : ''}.
      ${rows.length ? `<br>Registros: ${rows.join(' · ')}` : ''}
      ${p.has_ai_key === false ? '<br><b>Este backup não traz a <code>ai.key</code>.</b> A chave atual é mantida, e segredos gravados no banco importado (senha do SMTP, segredo do OAuth, 2FA) podem não abrir.' : ''}</p>`;
  }

  function importPanel(st) {
    const p = st.restore_pending, l = st.restore_last;
    const lastLine = !l ? '' : `<p class="hint" style="margin:0 0 16px">${l.ok
      ? `<span class="badge b-green">Importado</span> Importação aplicada em ${when(l.at)}${l.file ? ` (<code>${esc(l.file)}</code>)` : ''}: ${Number(l.docs) || 0} documento(s). O estado anterior ficou em <code>${esc(l.kept_at || '')}</code>, na pasta de dados.`
      : `<span class="badge b-red">Falhou</span> A importação de ${when(l.at)} não foi aplicada e o sistema voltou ao estado anterior: ${esc(l.error || '')}`}</p>`;

    if (p) {
      return `<section class="panel"><div class="panel-head"><h2 class="panel-title">Importar backup</h2>
        <button class="btn btn-sm btn-danger" data-action="sys-imp-cancel">Cancelar importação</button></div>
        ${lastLine}
        <div class="form-error" role="alert"><strong>Arquivo aceito — falta reiniciar o Tomcat</strong>
          <p style="margin:8px 0 0"><code>${esc(p.file || 'backup.zip')}</code> · ${size(p.size)} · backup de ${when(p.created_at)}. Enviado por ${esc(p.requested_by || '—')} em ${when(p.requested_at)}.</p>
          <p style="margin:8px 0 0">No próximo início do Tomcat, o banco, os documentos${p.with_config ? ', a chave <code>ai.key</code> e o <code>config.properties</code>' : ' e a chave <code>ai.key</code>'} passam a ser os do arquivo. O que existe hoje vai para <code>antes-da-restauracao-&lt;data&gt;</code> na pasta de dados — nada é apagado.</p>
          <p style="margin:8px 0 0"><b>Os usuários, as senhas e o 2FA passam a ser os do backup</b>, e a sua sessão termina. Se algo der errado, o sistema volta sozinho ao estado anterior.</p>
        </div>
        ${importContent(p)}</section>`;
    }
    return `<section class="panel"><div class="panel-head"><h2 class="panel-title">Importar backup</h2></div>
      ${lastLine}
      <p class="hint" style="margin:0 0 16px">Para levar esta instalação para outra máquina: envie o <code>.zip</code> de um backup e ele é conferido na hora (CRC, SHA-256 e abertura do banco da cópia); se não passar, não é aceito. A troca acontece no <b>próximo início do Tomcat</b>, antes de o banco abrir — é a única hora em que o arquivo do banco pode ser trocado com segurança. O estado atual é guardado em <code>antes-da-restauracao-&lt;data&gt;</code> e nada é apagado.</p>
      <form id="sy-imp" class="fgrid" novalidate>
        <div class="field full"><label for="sy-imp-file">Arquivo do backup <span class="opt">(zip, até 1 GB)</span></label>
          <input id="sy-imp-file" type="file" accept=".zip,application/zip">
          <small class="fhint">Os arquivos ficam na pasta de backups do servidor de origem (<code>sysattack-AAAAMMDD-HHMMSS-&lt;tipo&gt;.zip</code>) e podem ser baixados na lista acima.</small></div>
        <div class="field full"><label class="check"><input id="sy-imp-cfg" type="checkbox"> <span>Trazer também o <code>config.properties</code> do backup (endereço nos e-mails, SMTP, pastas). Deixe desmarcado quando o novo local tiver endereço ou pastas próprios.</span></label></div>
      </form>
      <div style="display:flex;gap:8px;align-items:center;margin-top:16px;flex-wrap:wrap">
        <button class="btn btn-primary btn-sm" data-action="sys-import">Enviar e conferir</button>
        <span class="small" style="display:inline" id="sy-imp-res"></span>
      </div></section>`;
  }

  /** Envia o zip como corpo cru, com o progresso do upload (o arquivo é grande). */
  function sendBackup(file, withConfig, onProgress) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open('POST', 'api/backup/import', true);
      x.withCredentials = true;
      x.setRequestHeader('Content-Type', 'application/octet-stream');
      x.setRequestHeader('X-Requested-With', 'SysAttack');
      x.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
      x.setRequestHeader('X-With-Config', withConfig ? '1' : '0');
      x.upload.onprogress = e => { if (e.lengthComputable) onProgress(Math.round(e.loaded * 100 / e.total)); };
      x.onload = () => {
        if (x.status === 401) { location.replace('login.html'); return; }
        let d = null;
        try { d = JSON.parse(x.responseText); } catch (e) { /* resposta sem JSON */ }
        if (x.status >= 200 && x.status < 300 && d) resolve(d);
        else reject(new Error((d && d.error) || 'Falha ao enviar o arquivo (' + x.status + ').'));
      };
      x.onerror = () => reject(new Error('A conexão caiu durante o envio.'));
      x.send(file);
    });
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
          <dt>Endereço nos e-mails</dt><dd>${st.app_url ? `<code>${esc(st.app_url)}</code>${st.app_url_source ? ` <span class="small" style="display:inline">(${esc(st.app_url_source)})</span>` : ''}` : '<span class="small" style="display:inline">não definido (usa o endereço do navegador)</span>'} ${st.app_url_ok ? '<span class="badge b-green">Acessível por outras máquinas</span>' : st.app_url ? '<span class="badge b-red">Só abre na máquina do servidor</span>' : '<span class="badge b-red">Defina app.url no config.properties</span>'}
            <button class="btn btn-sm" data-action="sys-addr-test" style="margin-left:8px">Testar</button><span id="sy-addr-res" class="small" style="display:inline"></span></dd>
          <dt>Você está acessando por</dt><dd><code>${esc(st.browser_url || '—')}</code></dd>
          <dt>Pasta dos backups</dt><dd><code>${esc(st.backup_dir)}</code></dd>
          <dt>Segunda cópia</dt><dd>${st.mirror_dir ? `<code>${esc(st.mirror_dir)}</code>` : '<span class="badge b-red">Não configurada</span>'}</dd>
          <dt>Banco</dt><dd>H2 ${esc(st.h2_version)} · id <code>${esc(st.data_id)}</code></dd>
        </dl>
        ${st.browser_url && st.app_url_matches === false ? `<p class="hint late-txt" style="margin:12px 0 0"><b>Os dois endereços estão diferentes.</b> Os links dos e-mails (convite, troca de senha e relatórios) apontam para <code>${esc(st.app_url)}</code>, não para o endereço que você está usando. Se o certo agora é <code>${esc(st.browser_url)}</code>, ajuste a linha abaixo no <code>config.properties</code> da pasta de dados — vale na hora, sem reiniciar o Tomcat:<br><code>app.url=${esc(st.browser_url)}</code></p>` : ''}
        <p class="hint" style="margin:8px 0 0">Cada backup é um único arquivo com o banco (cópia física e script SQL), os documentos, a chave <code>ai.key</code> e o <code>config.properties</code>. É verificado ao terminar. Retenção: 1 por dia (14 dias), 1 por semana (8 semanas), 1 por mês (12 meses). Como o arquivo contém a chave de criptografia, guarde-o como informação confidencial. Para trazer um backup para esta instalação, use <b>Importar backup</b> abaixo; com o Tomcat parado também vale o <code>restaurar-backup.ps1</code> (veja o README).</p>
      </section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Backups disponíveis</h2></div>${backupsTable(st.backups)}</section>
      ${importPanel(st)}
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
      case 'sys-import': {
        const f = document.getElementById('sy-imp-file').files[0];
        const out = document.getElementById('sy-imp-res');
        if (!f) { out.innerHTML = '<span class="badge b-red">Escolha o arquivo do backup</span>'; break; }
        const cfg = document.getElementById('sy-imp-cfg').checked;
        el.disabled = true;
        out.textContent = ' enviando…';
        try {
          await sendBackup(f, cfg, pct => { out.textContent = pct < 100 ? ` enviando… ${pct}%` : ' conferindo o arquivo…'; });
          A().toast('Arquivo aceito. A importação acontece no próximo início do Tomcat.');
          await A().route();
        } catch (e) {
          out.innerHTML = ` <span class="badge b-red">não aceito</span> ${esc(e.message)}`;
          el.disabled = false;
        }
        break;
      }
      case 'sys-imp-cancel': {
        if (!confirm('Cancelar a importação? O arquivo enviado é apagado e nada muda ao reiniciar o Tomcat.')) break;
        el.disabled = true;
        try {
          await api('DELETE', 'backup/import');
          A().toast('Importação cancelada.');
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
