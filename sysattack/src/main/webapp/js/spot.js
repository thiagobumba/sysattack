/* SysAttack — Projetos Spot: horas, demandas, ações e relatórios gerados com o Claude. */
const Spot = (() => {
  const { esc, fmt, todayIso, ic } = SA;
  // projetos encerrados: só administrador altera, e toda alteração exige justificativa (vai no cabeçalho e fica no histórico)
  const closedIds = new Set();
  // vigência do contrato (início até data final): todas as telas do projeto olham para ela
  const vigRange = p => ({ s: p.start_date, e: p.end_date || '' });
  const clampDate = (iso, p) => (iso < p.start_date ? p.start_date : p.end_date && iso > p.end_date ? p.end_date : iso);
  const vigText = p => p.end_date ? `${fmt(p.start_date)} a ${fmt(p.end_date)}` : `${fmt(p.start_date)} em diante`;
  const vigLine = () => `<p class="hint" style="margin:0 0 14px"><b>Vigência do contrato:</b> ${vigText(st.data)}</p>`;
  const closedScope = () => !!(st.data && st.data.closed_scope);
  const isAdmin = () => A().me && A().me.role === 'ADMIN';
  const locked = () => !!(st.data && st.data.status === 'ENCERRADO' && !isAdmin());
  function askJustification(title) {
    return new Promise(resolve => {
      const d = document.createElement('dialog');
      d.className = 'just';
      d.innerHTML = `<form method="dialog" class="fgrid" style="padding:24px 28px;min-width:min(520px,90vw)"><h2 style="margin:0;font-size:22px;font-weight:300;color:var(--orange)" class="full">${esc(title)}</h2>
        <p class="hint full">Este projeto está encerrado. Informe o motivo da alteração (mínimo de 10 caracteres); ele fica registrado no histórico.</p>
        <div class="field full"><label for="jt">Justificativa</label><textarea id="jt" maxlength="1000" required></textarea></div>
        <div class="form-error full" hidden role="alert"></div>
        <div class="form-actions full"><button type="button" class="btn" data-c="1">Cancelar</button><button type="submit" class="btn btn-primary">Confirmar alteração</button></div></form>`;
      document.body.appendChild(d);
      const ta = d.querySelector('textarea'), err = d.querySelector('.form-error');
      const done = v => { d.close(); d.remove(); resolve(v); };
      d.querySelector('[data-c]').addEventListener('click', () => done(null));
      d.addEventListener('cancel', e => { e.preventDefault(); done(null); });
      d.querySelector('form').addEventListener('submit', e => {
        e.preventDefault();
        if (ta.value.trim().length < 10) { err.textContent = 'Descreva o motivo com pelo menos 10 caracteres.'; err.hidden = false; return; }
        done(ta.value.trim());
      });
      d.showModal();
      ta.focus();
    });
  }
  /** Id do projeto spot que a chamada altera (para saber se está encerrado). */
  function spotOf(url) {
    const m = /^spot\/(\d+)/.exec(url);
    if (m) return Number(m[1]);
    return /^spot\/(hours|demands|actions|reports|files|invoices)\//.test(url) ? st.id : null;
  }
  async function api(method, url, body) {
    if (method !== 'GET' && !/\/send$/.test(url)) {
      const sid = spotOf(url);
      if (sid != null && closedIds.has(sid)) {
        const why = await askJustification('Alterar projeto encerrado');
        if (!why) throw new Error('Alteração cancelada.');
        return SA.api(method, url, body, { 'X-Justification': encodeURIComponent(why) });
      }
    }
    return SA.api(method, url, body);
  }
  const A = () => window.App;
  const D_STATUS = { EM_ANALISE: ['Em análise', 'b-grey'], APROVADO: ['Aprovado', 'b-dark'], EM_ANDAMENTO: ['Em andamento', 'b-orange'], EM_HOMOLOGACAO: ['Em homologação', 'b-orange'], EM_ESPERA: ['Em espera', 'b-grey'], CONCLUIDA: ['Concluída', 'b-green'], CANCELADA: ['Cancelada', 'b-red'] };
  const A_STATUS = { PENDENTE: ['Pendente', 'b-red'], A_CONFIRMAR: ['A confirmar', 'b-orange'], PARCIAL: ['Parcial', 'b-orange'], CONCLUIDO: ['Concluído', 'b-green'] };
  const S_STATUS = { ATIVO: ['Ativo', 'b-green'], PAUSADO: ['Pausado', 'b-orange'], ENCERRADO: ['Encerrado', 'b-grey'] };
  const MODEL = { MENSAL: 'Franquia mensal', PACOTE: 'Pacote de horas' };
  const KIND = { ACAO: 'Ação do último comitê', PENDENCIA: 'Pendência' };

  let st = { id: null, tab: 'resumo', from: null, to: null, data: null, hours: [], demands: [], actions: [], reports: [] };

  const h = n => (Math.round((Number(n) || 0) * 10) / 10).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + 'h';
  const badge = (map, k) => map[k] ? `<span class="badge ${map[k][1]}">${map[k][0]}</span>` : esc(k);
  const opts = (map, sel) => Object.entries(map).map(([k, v]) => `<option value="${k}"${k === sel ? ' selected' : ''}>${Array.isArray(v) ? v[0] : v}</option>`).join('');
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const iconBtns = (kind, id) => locked() ? '<td></td>' : `<td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar" data-action="sp-edit-${kind}" data-id="${id}">${ic('edit')}</button>
    <button class="icon-btn del" title="Excluir" aria-label="Excluir" data-action="sp-del-${kind}" data-id="${id}">${ic('trash')}</button></td>`;

  function kpiCards(k, label) {
    const neg = Number(k.balance) < 0, projNeg = Number(k.projected) < 0;
    return `<div class="mini-kpis">
      <div class="mini"><span>${esc(label || 'Horas fixadas')}</span><b>${h(k.contracted)}</b></div>
      <div class="mini accent"><span>Consumidas</span><b>${h(k.consumed)}</b></div>
      <div class="mini"><span>Reservadas (aprovado a realizar)</span><b>${h(k.reserved)}</b></div>
      <div class="mini ${neg ? 'neg' : ''}"><span>Saldo atual</span><b>${h(k.balance)}</b></div>
      <div class="mini ${projNeg ? 'neg' : ''}"><span>Saldo projetado</span><b>${h(k.projected)}</b></div></div>`;
  }

  /** Escopo fechado: a demanda só é "entregue" quando tem apontamento vinculado a ela. */
  function scopeCards(sc) {
    return `<div class="mini-kpis">
      <div class="mini"><span>Demandas do escopo</span><b>${sc.total}</b></div>
      <div class="mini accent"><span>Entregues (com apontamento)</span><b>${sc.delivered}</b></div>
      <div class="mini"><span>Sem apontamento</span><b>${sc.no_entry}</b></div>
      <div class="mini ${sc.done_no_entry ? 'neg' : ''}"><span>Concluídas sem apontamento</span><b>${sc.done_no_entry}</b></div>
      <div class="mini"><span>Entregue do escopo</span><b>${sc.pct}%</b></div></div>`;
  }
  const brl = n => Number(n || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  const I_STATUS = { EMITIDA: ['Emitida', 'b-orange'], PAGA: ['Paga', 'b-green'], CANCELADA: ['Cancelada', 'b-grey'] };
  function financeCards(f) {
    return `<div class="mini-kpis">
      <div class="mini accent"><span>Valor Projeto (R$)</span><b>${brl(f.project_value)}</b></div>
      <div class="mini"><span>Faturado</span><b>${brl(f.invoiced)}</b></div>
      <div class="mini ${Number(f.to_invoice) < 0 ? 'neg' : ''}"><span>A faturar</span><b>${brl(f.to_invoice)}</b></div>
      <div class="mini"><span>Recebido</span><b>${brl(f.paid)}</b></div>
      <div class="mini"><span>Faturado do valor</span><b>${f.pct_invoiced}%</b></div></div>`;
  }
  const entryBadge = d => Number(d.entries) > 0 ? '<span class="badge b-green">Entregue</span>' : '<span class="badge b-grey">Sem apontamento</span>';

  // ------------------------------------------------------------------ vigência
  const overdue = p => p.end_date && p.status === 'ATIVO' && p.end_date < todayIso();
  const vigencia = p => p.end_date ? `<span class="small ${overdue(p) ? 'late-txt' : ''}">até ${fmt(p.end_date)}${overdue(p) ? ' · vigência encerrada' : ''}</span>` : '';
  const endedNote = p => overdue(p) ? '<span class="small late-txt">Vigência encerrada: encerre o projeto ou ajuste a data.</span>' : '';

  // ------------------------------------------------------------------ lista (guias por tipo de projeto)
  const KINDS = { CONTRATO: 'Contrato', ESCOPO: 'Escopo fechado', AVULSO: 'Avulsos' };
  const kindOf = p => p.closed_scope ? 'ESCOPO' : p.recurring ? 'CONTRATO' : 'AVULSO';
  let listTab = 'CONTRATO';
  const pct = p => `<div class="pbar"><div class="pbar-track"><div class="pbar-fill${p >= 100 ? ' done' : ''}" style="width:${p}%"></div></div><span>${p}%</span></div>`;

  async function viewList() {
    const list = await api('GET', 'spot');
    list.forEach(p => { if (p.status === 'ENCERRADO') closedIds.add(p.id); else closedIds.delete(p.id); });
    const count = k => list.filter(p => kindOf(p) === k).length;
    if (!count(listTab) && listTab !== 'CONTRATO' && listTab !== 'ESCOPO') listTab = 'CONTRATO';
    const kinds = ['CONTRATO', 'ESCOPO'].concat(count('AVULSO') || listTab === 'AVULSO' ? ['AVULSO'] : []);
    const rows = list.filter(p => kindOf(p) === listTab);
    const acts = p => `<td class="acts">${p.recurring && !p.closed_scope ? `<button class="icon-btn" title="Copiar projeto (novo período)" aria-label="Copiar projeto spot" data-action="sp-copy-project" data-id="${p.id}">${ic('copy')}</button>` : ''}${p.status === 'ENCERRADO' && !isAdmin() ? '' : `<button class="icon-btn" title="Editar" aria-label="Editar projeto spot" data-action="sp-edit-project" data-id="${p.id}">${ic('edit')}</button>
          <button class="icon-btn del" title="Excluir" aria-label="Excluir projeto spot" data-action="sp-del-project" data-id="${p.id}">${ic('trash')}</button>`}</td>`;
    const name = p => `<td class="strong">${esc(p.name)}${p.recurring ? ' <span class="badge b-dark rec">Recorrente</span>' : ''}<span class="small">${esc(p.client || '—')}</span></td>`;
    const tableHours = `<table class="tbl"><thead><tr><th>Projeto</th><th>Modelo</th><th class="num">Fixadas</th><th class="num">Consumidas</th><th class="num">Saldo atual</th><th>Último relatório</th><th>Situação</th><th></th></tr></thead><tbody>
        ${rows.map(p => `<tr class="click" data-go="#/spot/${p.id}">${name(p)}
          <td>${MODEL[p.hours_model]}<span class="small">${esc(p.period_label || '')}</span>${vigencia(p)}</td>
          <td class="num">${h(p.kpi.contracted)}</td><td class="num">${h(p.kpi.consumed)}</td>
          <td class="num strong ${Number(p.kpi.balance) < 0 ? 'late-txt' : ''}">${h(p.kpi.balance)}</td>
          <td>${p.last_report ? fmt(String(p.last_report).slice(0, 10)) : '—'}</td><td>${badge(S_STATUS, p.status)}</td>${acts(p)}</tr>`).join('')}</tbody></table>`;
    const tableScope = `<table class="tbl"><thead><tr><th>Projeto</th><th>Vigência</th><th>Entregas</th><th class="num">Financeiro</th><th class="num">Arquivos</th><th>Situação</th><th></th></tr></thead><tbody>
        ${rows.map(p => `<tr class="click" data-go="#/spot/${p.id}">${name(p)}
          <td>${fmt(p.start_date)}${p.end_date ? ' a ' + fmt(p.end_date) : ' em diante'}${vigencia(p)}</td>
          <td>${pct(p.scope.pct)}<span class="small">${p.scope.delivered} de ${p.scope.total} demanda(s) entregue(s)</span></td>
          <td class="num strong">${brl(p.project_value)}<span class="small">${brl(p.finance.invoiced)} faturado (${p.finance.pct_invoiced}%)</span></td>
          <td class="num">${p.scope.files}</td><td>${badge(S_STATUS, p.status)}</td>${acts(p)}</tr>`).join('')}</tbody></table>`;
    A().main.innerHTML = A().pageHead(['SysAttack', 'Projetos Spot'], 'Projetos Spot', 'Projetos ágeis por horas (contrato) ou de escopo fechado: consumo, demandas, entregas e relatórios de acompanhamento.') + `
      <section class="panel"><div class="panel-head"><div class="tabs" style="margin:0;border:0;flex:1">
        ${kinds.map(k => `<button class="tab ${listTab === k ? 'active' : ''}" data-action="sp-list-tab" data-kind="${k}">${KINDS[k]} (${count(k)})</button>`).join('')}</div>
        <button class="btn btn-primary btn-sm" data-action="sp-new">+ Novo projeto spot</button></div>
        <div class="table-wrap">${rows.length ? (listTab === 'ESCOPO' ? tableScope : tableHours)
        : empty('Nenhum projeto nesta guia', listTab === 'ESCOPO' ? 'Cadastre um projeto marcando “Escopo fechado”: não controla horas, só escopo, demandas e entregas.' : 'Cadastre o projeto com as horas fixadas, lance as horas consumidas e gere o relatório semanal com o Claude.')}</div></section>`;
  }

  // ------------------------------------------------------------------ detalhe
  async function viewDetail(id) {
    if (st.id !== id) st = { id, tab: 'resumo', from: null, to: null, data: null, hours: [], demands: [], actions: [], reports: [] };
    const p = await api('GET', 'spot/' + id);
    st.data = p;
    if (p.status === 'ENCERRADO') closedIds.add(p.id); else closedIds.delete(p.id);
    const s = p.summary;
    const tab = (k, label) => `<button class="tab ${st.tab === k ? 'active' : ''}" data-action="sp-tab" data-tab="${k}">${label}</button>`;
    A().main.innerHTML = A().pageHead(['SysAttack', { t: 'Projetos Spot', href: '#/spot' }, p.name], p.name, [p.client, (p.closed_scope ? 'Escopo fechado' : MODEL[p.hours_model]) + ' · ' + s.period_label].filter(Boolean).join(' · ')) + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Visão do período</h2><div class="bar-actions">
 <a class="btn btn-sm" href="#/spot">← Voltar</a> ${p.recurring && !p.closed_scope ? `<button class="btn btn-sm" data-action="sp-copy-project" data-id="${p.id}">Copiar projeto</button>` : ''}${locked() ? '' : `<button class="btn btn-sm" data-action="sp-edit-project" data-id="${p.id}">Editar projeto</button>`}</div></div>
        ${p.status === 'ENCERRADO' ? `<p class="hint ${locked() ? 'late-txt' : ''}" style="margin:0 0 14px"><b>Projeto encerrado.</b> ${locked() ? 'Somente um administrador pode alterá-lo.' : 'Como administrador, toda alteração exige uma justificativa e fica registrada no histórico.'}</p>` : ''}
        ${p.closed_scope ? scopeCards(p.scope) + financeCards(p.finance) : kpiCards(s.kpi, p.hours_model === 'MENSAL' ? 'Franquia' : 'Pacote')}
        <dl class="meta-grid"><div><dt>Situação</dt><dd>${badge(S_STATUS, p.status)}</dd></div><div><dt>Fornecedor / equipe</dt><dd>${esc(p.provider || '—')}</dd></div>
        <div><dt>Início</dt><dd>${fmt(p.start_date)}</dd></div><div><dt>Data final</dt><dd>${p.end_date ? fmt(p.end_date) : '—'}${endedNote(p)}</dd></div><div><dt>Tipo</dt><dd>${p.closed_scope ? '<span class="badge b-dark">Escopo fechado</span>' : p.recurring ? '<span class="badge b-dark">Contrato recorrente</span>' : 'Avulso'}</dd></div><div class="wide"><dt>Participantes do comitê</dt><dd>${esc(p.participants || '—')}</dd></div>
        <div class="wide"><dt>Destinatários</dt><dd>${esc(String(p.recipients || '—').split(/\s*[,;]\s*/).join(', '))}</dd></div></dl>
        ${p.description ? `<p class="scope-text">${esc(p.description)}</p>` : ''}</section>
      <section class="panel"><div class="panel-head"><div class="tabs" style="margin:0;border:0;flex:1;flex-wrap:wrap">
        ${tab('resumo', 'Resumo')}${p.closed_scope ? tab('escopo', 'Escopo') + tab('financeiro', 'Financeiro') : ''}${tab('horas', p.closed_scope ? 'Apontamentos' : 'Apontamentos de horas')}${tab('demandas', 'Demandas')}${tab('acoes', 'Ações e pendências')}${p.closed_scope ? tab('tradicional', 'Relatório tradicional') : ''}${tab('relatorios', 'Relatórios com Claude')}</div>
        <div class="bar-actions" id="sp-actions"></div></div><div id="sp-body"></div></section>`;
    await renderTab();
  }

  async function renderTab() {
    const host = document.getElementById('sp-body'), act = document.getElementById('sp-actions');
    if (!host) return;
    const b = (a, l, primary = true) => `<button class="btn ${primary ? 'btn-primary ' : ''}btn-sm" data-action="${a}">${l}</button>`;
    act.innerHTML = locked() ? '' : { resumo: '', horas: closedScope() ? b('sp-new-hour', '+ Novo apontamento') : b('sp-import', 'Importar relatório (HTML)', false) + b('sp-new-hour', '+ Lançar horas'), escopo: b('sp-new-file', '+ Anexar arquivo do escopo'), financeiro: b('sp-new-invoice', '+ Lançar nota'), demandas: b('sp-import-demand', 'Importar relatório (HTML)', false) + b('sp-new-demand', '+ Nova demanda'), acoes: b('sp-import-action', 'Importar relatório (HTML)', false) + b('sp-new-action', '+ Nova ação / pendência'), tradicional: b('sp-new-manual', '+ Novo relatório'), relatorios: b('sp-import-report', 'Importar relatório (HTML)', false) + b('sp-new-report', '✦ Gerar relatório') }[st.tab];
    const id = st.id;
    if (st.tab === 'resumo') {
      const s = st.data.summary;
      if (closedScope()) {
        const sc = st.data.scope;
        st.demands = await api('GET', `spot/${id}/demands`);
        const live = st.demands.filter(d => d.status !== 'CANCELADA');
        host.innerHTML = `${vigLine()}<h3 style="margin:0 0 10px;font-size:15px">Entrega do escopo</h3>${pct(sc.pct)}
          <p class="hint" style="margin:10px 0 14px">Cada demanda só conta como entregue quando existe um apontamento vinculado a ela (aba Apontamentos). Este projeto não controla horas.</p>
          ${live.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Demanda</th><th>Situação</th><th class="num">Apontamentos</th><th>Entrega</th></tr></thead><tbody>
          ${live.map(d => `<tr><td class="strong">${esc(d.name)}</td><td>${badge(D_STATUS, d.status)}</td><td class="num">${d.entries}</td>
            <td>${entryBadge(d)}${d.status === 'CONCLUIDA' && !Number(d.entries) ? '<span class="small late-txt">Concluída, mas sem apontamento</span>' : ''}</td></tr>`).join('')}</tbody></table></div>`
          : empty('Nenhuma demanda cadastrada', 'Cadastre as demandas do escopo na aba Demandas.')}
          <p class="hint" style="margin-top:14px">${sc.files} arquivo(s) do escopo anexado(s) na aba “Escopo”.</p>`;
      } else
      host.innerHTML = `${vigLine()}${s.by_item.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Frente</th><th>Atividade</th><th class="num">Horas</th></tr></thead><tbody>
        ${s.by_item.map(i => `<tr><td>${esc(i.front || '—')}</td><td class="strong">${esc(i.label)}</td><td class="num">${h(i.hours)}</td></tr>`).join('')}</tbody></table></div>`
        : empty('Sem horas no período', 'Lance as horas consumidas na aba “Apontamentos de horas”.')}
        ${st.data.billing_note ? `<p class="hint" style="margin-top:14px"><b>Faturamento:</b> ${esc(st.data.billing_note)}</p>` : ''}`;
      const audit = await api('GET', `spot/${id}/audit`).catch(() => []);
      if (audit.length) {
        host.insertAdjacentHTML('beforeend', `<h3 style="margin:22px 0 8px;font-size:15px">Histórico de alterações após o encerramento</h3><div class="table-wrap"><table class="tbl"><thead><tr><th>Quando</th><th>Quem</th><th>Justificativa</th></tr></thead><tbody>
          ${audit.map(x => `<tr><td>${esc(String(x.created_at).slice(0, 16).replace('T', ' '))}</td><td>${esc(x.user_name)}</td><td class="strong">${esc(x.justification)}<span class="small">${esc(x.action)}</span></td></tr>`).join('')}</tbody></table></div>`);
      }
    } else if (st.tab === 'horas') {
      const v = vigRange(st.data);
      const from = st.from || v.s, to = st.to || v.e;
      st.hours = await api('GET', `spot/${id}/hours?from=${from}&to=${to || '2999-12-31'}`);
      const total = st.hours.reduce((a, x) => a + Number(x.hours), 0), cs = closedScope();
      const inp = 'min-height:36px;border:1px solid #cfcfcf;padding:0 10px';
      host.innerHTML = `${vigLine()}<div style="display:flex;gap:12px;align-items:center;margin-bottom:14px;flex-wrap:wrap"><label for="sp-f" class="hint">De</label>
        <input id="sp-f" type="date" value="${from}" min="${v.s}"${v.e ? ` max="${v.e}"` : ''} style="${inp}"><label for="sp-t" class="hint">Até</label>
        <input id="sp-t" type="date" value="${to}" min="${v.s}"${v.e ? ` max="${v.e}"` : ''} style="${inp}">
        <button class="btn btn-sm" data-action="sp-vig-all">Vigência completa</button><span class="hint">${st.hours.length} ${cs ? 'apontamento(s) · cada um entrega uma demanda' : `lançamento(s) · <b>${h(total)}</b>`}</span></div>
        ${st.hours.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Data</th><th>Frente</th><th>Atividade</th><th>Demanda</th>${cs ? '' : '<th class="num">Horas</th>'}<th></th></tr></thead><tbody>
        ${st.hours.map(x => `<tr><td>${fmt(x.work_date)}</td><td>${esc(x.front || '—')}</td><td class="strong">${esc(x.activity)}</td><td>${esc(x.demand_name || '—')}</td>${cs ? '' : `<td class="num">${h(x.hours)}</td>`}${iconBtns('hour', x.id)}</tr>`).join('')}</tbody></table></div>`
        : cs ? empty('Nenhum apontamento neste filtro', 'Use “+ Novo apontamento” e vincule cada apontamento à demanda que ele entrega.') : empty('Nenhum lançamento neste filtro', 'Use “+ Lançar horas” para registrar o consumo (ex.: horas exportadas do Salesforce).')}`;
      const rangeChanged = () => {
        const f = document.getElementById('sp-f').value, t = document.getElementById('sp-t').value;
        if (f && t && f > t) { A().toast('A data inicial não pode ser depois da final.'); return; }
        st.from = f || null; st.to = t || null; renderTab();
      };
      document.getElementById('sp-f').addEventListener('change', rangeChanged);
      document.getElementById('sp-t').addEventListener('change', rangeChanged);
    } else if (st.tab === 'escopo') {
      st.files = await api('GET', `spot/${id}/files`);
      const fsz = n => n < 1024 * 1024 ? Math.max(1, Math.round(n / 1024)) + ' KB' : (n / 1024 / 1024).toFixed(1).replace('.', ',') + ' MB';
      host.innerHTML = `${vigLine()}${st.data.description ? `<p class="scope-text" style="margin-bottom:18px">${esc(st.data.description)}</p>` : ''}
        <p class="hint" style="margin:0 0 12px">Arquivos do escopo (PDF, proposta, contrato…) guardados no banco somente para registro.</p>
        ${st.files.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Arquivo</th><th>Tamanho</th><th>Enviado por</th><th></th></tr></thead><tbody>
        ${st.files.map(f => `<tr><td class="strong">${esc(f.title)}<span class="small">${esc(f.file_name)}${f.description ? ' · ' + esc(f.description) : ''}</span></td><td>${fsz(f.size)}</td>
          <td>${esc(f.uploaded_by)}<span class="small">${esc(String(f.uploaded_at).slice(0, 16).replace('T', ' '))}</span></td>
          <td class="acts"><a class="btn btn-sm" href="api/spot/files/${f.id}/download" target="_blank" rel="noopener">Abrir</a>${locked() ? '' : `<button class="icon-btn del" title="Excluir" aria-label="Excluir arquivo" data-action="sp-del-file" data-id="${f.id}">${ic('trash')}</button>`}</td></tr>`).join('')}</tbody></table></div>`
        : empty('Nenhum arquivo do escopo', 'Use “+ Anexar arquivo do escopo” para guardar o PDF ou o arquivo do projeto.')}`;
    } else if (st.tab === 'financeiro') {
      st.invoices = await api('GET', `spot/${id}/invoices`);
      const f = st.data.finance;
      host.innerHTML = `${financeCards(f)}<p class="hint" style="margin:14px 0 12px">Notas fiscais do escopo fechado. Notas canceladas não entram no total faturado.</p>
        ${st.invoices.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Nota</th><th>Emissão</th><th>Vencimento</th><th class="num">Valor</th><th>Situação</th><th>Arquivo</th><th></th></tr></thead><tbody>
        ${st.invoices.map(n => `<tr><td class="strong">${esc(n.number)}${n.description ? `<span class="small">${esc(n.description)}</span>` : ''}</td><td>${fmt(n.issue_date)}</td><td>${n.due_date ? fmt(n.due_date) : '—'}</td>
          <td class="num strong">${brl(n.amount)}</td><td>${badge(I_STATUS, n.status)}</td>
          <td>${n.file_name ? `<a class="btn btn-sm" href="api/spot/invoices/${n.id}/file" target="_blank" rel="noopener" title="${esc(n.file_name)}">Abrir</a>` : '—'}</td>${iconBtns('invoice', n.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nenhuma nota lançada', 'Use “+ Lançar nota” para registrar a nota fiscal e anexar o PDF.')}`;
    } else if (st.tab === 'demandas') {
      st.demands = await api('GET', `spot/${id}/demands`);
      host.innerHTML = vigLine() + (st.demands.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Demanda</th><th>Situação</th><th>Próximo passo</th>${closedScope() ? '<th class="num">Apontamentos</th><th>Entrega</th>' : '<th class="num">Aprovado</th><th class="num">Realizado</th>'}<th></th></tr></thead><tbody>
        ${st.demands.map(d => `<tr><td class="strong">${esc(d.name)}${d.notes ? `<span class="small">${esc(d.notes)}</span>` : ''}</td><td>${badge(D_STATUS, d.status)}</td><td>${esc(d.next_step || '—')}</td>
          ${closedScope() ? `<td class="num">${d.entries}</td><td>${entryBadge(d)}</td>` : `<td class="num">${Number(d.approved_hours) > 0 ? h(d.approved_hours) : '—'}</td>
          <td class="num ${Number(d.approved_hours) > 0 && Number(d.hours_total) > Number(d.approved_hours) ? 'late-txt' : ''}">${h(d.hours_total)}</td>`}${iconBtns('demand', d.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nenhuma demanda', closedScope() ? 'Cadastre as demandas do escopo e acompanhe a entrega de cada uma.' : 'Cadastre as demandas (com horas aprovadas) para comparar realizado × aprovado.'));
    } else if (st.tab === 'acoes') {
      st.actions = await api('GET', `spot/${id}/actions`);
      host.innerHTML = vigLine() + (st.actions.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Tipo</th><th>Descrição</th><th>Responsável</th><th>Situação</th><th></th></tr></thead><tbody>
        ${st.actions.map(a => `<tr><td>${KIND[a.kind]}</td><td class="strong">${esc(a.description)}${a.note ? `<span class="small">${esc(a.note)}</span>` : ''}</td><td>${esc(a.responsible || '—')}</td><td>${badge(A_STATUS, a.status)}</td>${iconBtns('action', a.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nada registrado', 'Registre as ações do último comitê e as pendências (da Momesso ou do cliente).'));
    } else if (st.tab === 'tradicional') {
      st.reports = await api('GET', `spot/${id}/reports?kind=manual`);
      host.innerHTML = `${vigLine()}<p class="hint" style="margin:0 0 12px">Relatório preenchido por você na tela, sem o Claude, no mesmo formato do Status Report da Fábrica. Abra, edite, imprima ou envie por e-mail.</p>
        ${st.reports.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Relatório</th><th>Referência</th><th>Criado por</th><th>Envio</th><th></th></tr></thead><tbody>
        ${st.reports.map(r => `<tr class="click" data-action="sp-open-report" data-id="${r.id}"><td class="strong">${esc(r.title)}</td><td>${fmt(r.ref_date)}</td>
          <td>${esc(r.created_by)}<span class="small">${esc(String(r.created_at).slice(0, 16).replace('T', ' '))}</span></td>
          <td>${r.sent_at ? `<span class="badge b-green">Enviado</span><span class="small">${fmt(String(r.sent_at).slice(0, 10))}</span>` : '<span class="badge b-grey">Não enviado</span>'}</td>
          <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar relatório" data-action="sp-edit-manual" data-id="${r.id}">${ic('edit')}</button><button class="icon-btn del" title="Excluir" aria-label="Excluir relatório" data-action="sp-del-report" data-id="${r.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`
        : empty('Nenhum relatório tradicional', 'Clique em “+ Novo relatório”: a tela já vem com os dados do projeto e você completa o restante.')}`;
    } else {
      st.reports = await api('GET', `spot/${id}/reports`);
      const ai = await api('GET', 'ai-status').catch(() => ({ connected: false }));
      host.innerHTML = `${vigLine()}${ai.connected ? '' : '<p class="hint late-txt" style="margin-bottom:14px">O Claude ainda não está conectado. Um administrador precisa configurar a chave em “Configurações IA Agent”.</p>'}
        ${st.reports.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Relatório</th><th>Referência</th><th>Gerado por</th><th>Envio</th><th></th></tr></thead><tbody>
        ${st.reports.map(r => `<tr class="click" data-action="sp-open-report" data-id="${r.id}"><td class="strong">${esc(r.title)}</td><td>${fmt(r.ref_date)}</td>
          <td>${esc(r.created_by)}<span class="small">${esc(String(r.created_at).slice(0, 16).replace('T', ' '))}</span></td>
          <td>${r.sent_at ? `<span class="badge b-green">Enviado</span><span class="small">${fmt(String(r.sent_at).slice(0, 10))}</span>` : '<span class="badge b-grey">Não enviado</span>'}</td>
          <td class="acts"><button class="icon-btn del" title="Excluir" aria-label="Excluir relatório" data-action="sp-del-report" data-id="${r.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`
        : empty('Nenhum relatório ainda', 'Clique em “Gerar relatório”: o sistema calcula os números e o Claude escreve o texto de acompanhamento.')}`;
    }
  }

  // ------------------------------------------------------------------ formulários
  /** Cópia de um contrato recorrente: leva só o cabeçalho; início, final e observação ficam em branco, situação volta a Ativo. */
  async function copyTemplate(fromId) {
    const o = await api('GET', 'spot/' + fromId);
    if (!o.recurring) throw new Error('Só é possível copiar projetos do tipo contrato recorrente.');
    return { name: o.name, client: o.client, provider: o.provider, description: '', hours_model: o.hours_model, contracted_hours: o.contracted_hours,
      start_date: '', end_date: '', recurring: true, closed_scope: false, status: 'ATIVO', participants: o.participants, recipients: o.recipients, billing_note: '' };
  }

  function projectForm(id, copyFrom) {
    return (async () => {
      const p = id ? await api('GET', 'spot/' + id) : copyFrom ? await copyTemplate(copyFrom) : { name: '', client: '', provider: '', description: '', hours_model: 'MENSAL', contracted_hours: '', start_date: todayIso(), end_date: '', recurring: listTab === 'CONTRATO', closed_scope: listTab === 'ESCOPO', status: 'ATIVO', participants: '', recipients: '', billing_note: '' };
      A().openDialog(id ? 'Editar projeto spot' : copyFrom ? 'Copiar projeto spot' : 'Novo projeto spot', `
        <div class="field full"><label for="sn">Nome</label><input id="sn" name="name" maxlength="160" value="${esc(p.name)}" required></div>
        <div class="field"><label for="sc">Cliente</label><input id="sc" name="client" maxlength="160" value="${esc(p.client)}"></div>
        <div class="field"><label for="sp">Fornecedor / equipe</label><input id="sp" name="provider" maxlength="160" value="${esc(p.provider)}"></div>
        <div class="field"><label for="sm">Modelo de horas</label><select id="sm" name="hours_model">${opts(MODEL, p.hours_model)}</select></div>
        <div class="field"><label for="sh">Horas fixadas</label><input id="sh" name="contracted_hours" type="number" min="0" step="0.5" value="${p.contracted_hours}" required></div>
        <div class="field" id="sv-f"><label for="sv">Valor do projeto (R$)</label><input id="sv" name="project_value" type="number" min="0" step="0.01" value="${p.project_value || ''}"></div>
        <div class="field"><label for="sd">Início</label><input id="sd" name="start_date" type="date" value="${p.start_date}" required></div>
        <div class="field"><label for="se">Data final <span class="opt">(opcional)</span></label><input id="se" name="end_date" type="date" value="${p.end_date || ''}"></div>
        <div class="field"><label for="ss">Situação</label><select id="ss" name="status">${opts(S_STATUS, p.status)}</select></div>
        <div class="field full"><label class="check"><input type="checkbox" name="closed_scope" ${p.closed_scope ? 'checked' : ''}> <b>Escopo fechado</b> — não controla horas; controla só o escopo, o que foi feito e entregue (demandas e apontamentos) e guarda o arquivo do escopo.</label></div>
        <div class="field full"><label class="check"><input type="checkbox" name="recurring" ${p.recurring ? 'checked' : ''}> <b>Contrato recorrente</b> — repete a cada período (ex.: franquia mensal). O cadastro continua o mesmo; muda só o que é registrado nas demandas.</label></div>
        <div class="field full"><label for="spa">Participantes do comitê</label><input id="spa" name="participants" maxlength="1000" value="${esc(p.participants)}" placeholder="Nomes separados por vírgula"></div>
        <div class="field full"><label for="sr">Destinatários dos relatórios (e-mails)</label><input id="sr" name="recipients" maxlength="1000" value="${esc(p.recipients)}" placeholder="gestor1@empresa.com, gestor2@empresa.com"></div>
        <div class="field full"><label for="sb">Observação de faturamento</label><input id="sb" name="billing_note" maxlength="1000" value="${esc(p.billing_note)}" placeholder="Ex.: excedentes de agosto serão faturados à parte"></div>
        <div class="field full"><label for="sx">Descrição</label><textarea id="sx" name="description" maxlength="2000">${esc(p.description)}</textarea></div>
        ${copyFrom ? '<p class="hint full"><b>Cópia do contrato anterior.</b> Informe o início e a data final do novo período. Demandas, horas, ações e relatórios não são copiados.</p>' : ''}
        <p class="hint full">Franquia mensal: o consumo conta do dia 1º do mês de referência até a data. Pacote: conta desde o início do projeto.</p>`,
        async d => { const cs = !!d.closed_scope; const s = await api(id ? 'PUT' : 'POST', id ? 'spot/' + id : 'spot', { ...d, closed_scope: cs, project_value: cs ? d.project_value : 0, recurring: cs ? false : !!d.recurring, hours_model: cs ? 'PACOTE' : d.hours_model, contracted_hours: cs ? 0 : d.contracted_hours, end_date: d.end_date || null }); A().toast('Projeto spot salvo.'); if (!id) location.hash = '#/spot/' + s.id; });
      // tipo do projeto: escopo fechado não controla horas; contrato e escopo fechado são excludentes
      const f = document.querySelector('#dlg form'), cs = f.elements.closed_scope, rc = f.elements.recurring;
      const sync = () => {
        for (const n of ['hours_model', 'contracted_hours']) { f.elements[n].disabled = cs.checked; f.elements[n].required = !cs.checked && n === 'contracted_hours'; }
        if (cs.checked) rc.checked = false;
        rc.disabled = cs.checked;
        f.elements.project_value.disabled = !cs.checked; f.elements.project_value.required = cs.checked;
        document.getElementById('sv-f').style.display = cs.checked ? '' : 'none';
      };
      cs.addEventListener('change', sync);
      sync();
    })();
  }

  async function hourForm(id) {
    const demands = await api('GET', `spot/${st.id}/demands`);
    const x = id ? st.hours.find(r => r.id === id) : { work_date: clampDate(todayIso(), st.data), front: '', activity: '', demand_id: '', hours: '' };
    A().openDialog(id ? (closedScope() ? 'Editar apontamento' : 'Editar lançamento') : (closedScope() ? 'Novo apontamento' : 'Lançar horas'), `
      <div class="field"><label for="hd">Data</label><input id="hd" name="work_date" type="date" value="${x.work_date}" min="${st.data.start_date}"${st.data.end_date ? ` max="${st.data.end_date}"` : ''} required></div>
      ${closedScope() ? '' : `<div class="field"><label for="hh">Horas</label><input id="hh" name="hours" type="number" min="0.1" step="0.1" value="${x.hours}" required></div>`}
      <div class="field"><label for="hf">Frente</label><input id="hf" name="front" maxlength="80" value="${esc(x.front)}" placeholder="Ex.: Suporte, Chatbot"></div>
      <div class="field${closedScope() ? ' full' : ''}"><label for="hm">${closedScope() ? 'Qual demanda este apontamento entrega?' : 'Demanda'}</label><select id="hm" name="demand_id"${closedScope() ? ' required' : ''}><option value="">${closedScope() ? '— escolha a demanda —' : '— nenhuma —'}</option>${demands.filter(d => !closedScope() || d.status !== 'CANCELADA').map(d => `<option value="${d.id}"${d.id === x.demand_id ? ' selected' : ''}>${esc(d.name)}</option>`).join('')}</select></div>
      <div class="field full"><label for="ha">Atividade</label><input id="ha" name="activity" maxlength="300" value="${esc(x.activity)}" required></div>`,
      async d => { await api(id ? 'PUT' : 'POST', id ? 'spot/hours/' + id : `spot/${st.id}/hours`, d); A().toast(closedScope() ? 'Apontamento salvo.' : 'Horas salvas.'); });
  }

  function demandForm(id) {
    const x = id ? st.demands.find(r => r.id === id) : { name: '', status: 'EM_ANALISE', next_step: '', approved_hours: '', notes: '' };
    A().openDialog(id ? 'Editar demanda' : 'Nova demanda', `
      <div class="field full"><label for="dn">Demanda</label><input id="dn" name="name" maxlength="200" value="${esc(x.name)}" required></div>
      <div class="field"><label for="ds">Situação</label><select id="ds" name="status">${opts(D_STATUS, x.status)}</select></div>
      ${closedScope() ? '' : `<div class="field"><label for="dh">Horas aprovadas</label><input id="dh" name="approved_hours" type="number" min="0" step="0.5" value="${Number(x.approved_hours) > 0 ? x.approved_hours : ''}" placeholder="Opcional"></div>`}
      <div class="field full"><label for="dp">Próximo passo</label><input id="dp" name="next_step" maxlength="500" value="${esc(x.next_step)}"></div>
      <div class="field full"><label for="do">Observações</label><textarea id="do" name="notes" maxlength="1000">${esc(x.notes)}</textarea></div>`,
      async d => { await api(id ? 'PUT' : 'POST', id ? 'spot/demands/' + id : `spot/${st.id}/demands`, d); A().toast('Demanda salva.'); });
  }

  function actionForm(id) {
    const x = id ? st.actions.find(r => r.id === id) : { kind: 'ACAO', description: '', responsible: '', status: 'PENDENTE', note: '' };
    A().openDialog(id ? 'Editar item' : 'Nova ação / pendência', `
      <div class="field"><label for="ak">Tipo</label><select id="ak" name="kind">${opts(KIND, x.kind)}</select></div>
      <div class="field"><label for="as">Situação</label><select id="as" name="status">${opts(A_STATUS, x.status)}</select></div>
      <div class="field full"><label for="ad">Descrição</label><input id="ad" name="description" maxlength="500" value="${esc(x.description)}" required></div>
      <div class="field full"><label for="ar">Responsável</label><input id="ar" name="responsible" maxlength="120" value="${esc(x.responsible)}"></div>
      <div class="field full"><label for="an">Observação</label><input id="an" name="note" maxlength="500" value="${esc(x.note)}"></div>`,
      async d => { await api(id ? 'PUT' : 'POST', id ? 'spot/actions/' + id : `spot/${st.id}/actions`, d); A().toast('Item salvo.'); });
  }

  function reportForm() {
    const p = st.data;
    const f = A().openDialog('Gerar relatório com o Claude', `
      <div class="field"><label for="rd">Data de referência</label><input id="rd" name="ref_date" type="date" min="${p.start_date}" max="${p.end_date && p.end_date < todayIso() ? p.end_date : todayIso()}" value="${clampDate(todayIso(), p)}" required></div>
      <div class="field"><label for="rt">Título</label><input id="rt" name="title" maxlength="200" value="Status Report · ${esc(p.name)}"></div>
      <div class="field full"><label for="ri">Instruções adicionais (opcional)</label><textarea id="ri" name="instructions" maxlength="600" placeholder="Ex.: destaque o atraso do Chatbot; tom mais direto."></textarea></div>
      <p class="hint full">Os números são calculados pelo SysAttack; o Claude escreve apenas o texto. A geração leva alguns segundos.</p>`,
      async d => {
        const r = await api('POST', `spot/${st.id}/reports/generate`, d);
        A().toast('Relatório gerado.');
        setTimeout(() => openReport(r.id), 300);
      }, 'Gerar');
    return f;
  }

  async function openReport(id) {
    const r = await api('GET', 'spot/reports/' + id);
    const dlg = document.getElementById('dlg');
    dlg.className = 'wide';
    dlg.innerHTML = `<div class="dlg-head"><h2>${esc(r.title)}</h2><button class="icon-btn" data-action="close" aria-label="Fechar">${ic('x')}</button></div><div class="dlg-body">
      <iframe class="preview" id="sp-frame" src="api/spot/reports/${r.id}/html" title="${esc(r.title)}" style="height:60vh"></iframe>
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px;flex-wrap:wrap">
        ${r.model === 'importado' ? '' : r.model === 'manual' ? `<button class="btn btn-sm" data-action="sp-edit-manual" data-id="${r.id}">Editar relatório</button>` : `<button class="btn btn-sm" data-action="sp-edit-report" data-id="${r.id}">Editar texto</button>`}
        <button class="btn btn-sm" data-action="sp-print-report">Imprimir / salvar PDF</button>
        <button class="btn btn-sm btn-primary" data-action="sp-send-report" data-id="${r.id}">Enviar por e-mail</button></div></div>`;
    dlg.showModal();
  }

  async function editReport(id) {
    const r = await api('GET', 'spot/reports/' + id), n = r.narrative;
    A().openDialog('Editar texto do relatório', `
      <div class="field full"><label for="et">Título</label><input id="et" name="title" maxlength="200" value="${esc(r.title)}" required></div>
      <div class="field full"><label for="ed">Destaques</label><textarea id="ed" name="destaques" maxlength="800">${esc(n.destaques)}</textarea></div>
      <div class="field full"><label for="ea">Alertas (um por linha)</label><textarea id="ea" name="alertas">${esc((n.alertas || []).join('\n'))}</textarea></div>
      <div class="field full"><label for="eo">Observação sobre as horas</label><textarea id="eo" name="observacao_horas" maxlength="600">${esc(n.observacao_horas)}</textarea></div>
      <div class="field full"><label for="ee">Mensagem de abertura do e-mail</label><textarea id="ee" name="email_mensagem" maxlength="1500">${esc(n.email_mensagem)}</textarea></div>
      ${(r.demands || []).map(d => `<div class="field full"><label>${esc(d.name)}</label><input data-dem="${d.id}" maxlength="300" value="${esc(((n.demandas || []).find(x => x.id === d.id) || {}).resumo || '')}" placeholder="Resumo desta demanda"></div>`).join('')}`,
      async d => {
        const dem = [...document.querySelectorAll('#dlg [data-dem]')].filter(i => i.value.trim()).map(i => ({ id: Number(i.dataset.dem), resumo: i.value }));
        const narrative = { destaques: d.destaques, alertas: d.alertas.split('\n').map(s => s.trim()).filter(Boolean), demandas: dem, observacao_horas: d.observacao_horas, email_mensagem: d.email_mensagem };
        await api('PUT', 'spot/reports/' + id, { title: d.title, narrative });
        A().toast('Texto atualizado.');
        setTimeout(() => openReport(id), 300);
      });
  }

  async function sendReport(id) {
    const r = await api('GET', 'spot/reports/' + id);
    A().openDialog('Enviar relatório por e-mail', `
      <div class="field full"><label for="to">Para</label><input id="to" name="to" maxlength="1000" value="${esc(String(st.data.recipients || '').split(/\s*[,;]\s*/).filter(Boolean).join('; '))}" placeholder="email1@empresa.com; email2@empresa.com" required></div>
      <div class="field full"><label for="cc">CC (cópia)</label><input id="cc" name="cc" maxlength="1000" placeholder="Opcional. Separe vários e-mails com ponto e vírgula (;)"></div>
      <div class="field full"><label for="bcc">CCO (cópia oculta)</label><input id="bcc" name="bcc" maxlength="1000" placeholder="Opcional. Os demais destinatários não veem estes endereços"></div>
      <p class="hint full">Separe vários e-mails com ponto e vírgula (;). O relatório vai no corpo do e-mail. Requer SMTP configurado (veja o README).</p>`,
      async d => {
        const res = await api('POST', `spot/reports/${id}/send`, d);
        A().toast(res.delivered ? 'E-mail enviado para ' + res.to : 'Modo de teste (smtp.dev): e-mail não enviado de fato.');
      }, 'Enviar');
  }


  // ------------------------------------------------------------------ importação do Status Report (HTML da Fábrica)
  /** Extrai o literal de array `s.<nome>=[...]` do script do relatório (sem eval: só JSON.parse). */
  function literalText(src, name) {
    const m = new RegExp('\\bs\\.' + name + '\\s*=\\s*\\[').exec(src);
    if (!m) return '';
    const start = m.index + m[0].length - 1;
    let depth = 0, inStr = false;
    for (let i = start; i < src.length; i++) {
      const c = src[i];
      if (inStr) { if (c === '\\') i++; else if (c === '"') inStr = false; continue; }
      if (c === '"') inStr = true;
      else if (c === '[') depth++;
      else if (c === ']' && --depth === 0) return src.slice(start, i + 1);
    }
    return '';
  }
  function literalArray(src, name) {
    try { const v = JSON.parse(literalText(src, name)); return Array.isArray(v) ? v : []; } catch (e) { return []; }
  }
  /** Texto de um campo do relatório: `s.chave="..."` (conteúdo) ou o padrão `chave:"..."` do cabeçalho HD. */
  function strProp(src, key) {
    const q = '"((?:[^"\\\\]|\\\\.)*)"';
    const m = new RegExp('\\bs\\.' + key + '\\s*=\\s*' + q).exec(src) || new RegExp('[{,]\\s*' + key + '\\s*:\\s*' + q).exec(src);
    if (!m) return '';
    try { return JSON.parse('"' + m[1] + '"'); } catch (e) { return m[1]; }
  }
  const num = v => { const n = parseFloat(String(v).replace(/\./g, '').replace(',', '.')); return isFinite(n) ? n : 0; };
  const norm = t => String(t || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

  function demandStatus(text, fallback) {
    const t = norm(text);
    if (t.includes('homologacao')) return 'EM_HOMOLOGACAO';
    if (t.includes('aprovacao prevista') || t.includes('investigacao') || t.includes('estimativa')) return 'EM_ANALISE';
    if (t.includes('aprovad')) return 'APROVADO';
    if (t.includes('aguarda') || t.includes('licenca')) return 'EM_ESPERA';
    return fallback;
  }

  function parseReport(src) {
    const consumo = literalArray(src, 'consumo'), chat = literalArray(src, 'chat');
    const andamento = literalArray(src, 'andamento'), previsao = literalArray(src, 'previsao');
    const acoesRaw = literalArray(src, 'acoes'), defsRaw = literalArray(src, 'defs');
    if (!consumo.length) throw new Error('Não encontrei a tabela “Consumo por demanda” neste arquivo. Use o HTML do Status Report da Fábrica.');
    const meta = (/\bs\.meta\s*=\s*"([^"]*)"/.exec(src) || [])[1] || '';
    const year = (/(\d{2})\/(\d{2})\/(\d{4})/.exec(meta) || [])[3] || '2026';
    const until = /Consumido[^"]*\(até (\d{1,2})\/(\d{1,2})\)/.exec(src);
    const date = until ? `${year}-${until[2].padStart(2, '0')}-${until[1].padStart(2, '0')}` : `${year}-10-31`;
    const kp = /n:"(\d+)h \/ (\d+)h"/.exec(src);
    const chatApproved = kp && kp[2] ? Number(kp[2]) : 0;
    const chatTotal = chat.reduce((a, r) => a + num(r[1]), 0);
    // “Chatbot (n alterações)” é a soma do detalhamento: troca a linha agregada pelas linhas detalhadas
    const hours = [];
    consumo.forEach(r => {
      const isChat = norm(r[0]).startsWith('chatbot') && chat.length && Math.abs(num(r[1]) - chatTotal) < 0.01;
      if (isChat) chat.forEach(c => hours.push({ activity: c[0], hours: num(c[1]), front: 'Chatbot', demand: 'Chatbot' }));
      else hours.push({ activity: r[0], hours: num(r[1]), front: '', demand: '' });
    });
    const demands = [];
    andamento.forEach(r => demands.push({ name: r[0], next_step: r[1], status: demandStatus(r[0] + ' ' + r[1], 'EM_ANDAMENTO'), approved: r[0] === 'Chatbot' ? chatApproved : 0, notes: '' }));
    previsao.forEach(r => {
      const hrs = /^(\d+(?:,\d+)?)h\b/.exec(r[1]);
      demands.push({ name: r[0], next_step: r[1], status: demandStatus(r[1], 'EM_ANALISE'), approved: 0, notes: 'Previsão para o próximo mês' + (hrs ? ` (estimativa ${hrs[1]}h)` : '') });
    });
    // vincula horas a demandas pelo nome quando não foi vinculado de forma explícita
    hours.forEach(x => {
      if (x.demand) return;
      const a = norm(x.activity);
      const d = demands.find(d => { const n = norm(d.name); return n.length >= 6 && (a === n || a.startsWith(n)); });
      if (d) x.demand = d.name;
    });
    const total = (/s\.kpis\s*=[^;]*?n:"(\d+(?:,\d+)?)h"[^;]*?n:"(\d+(?:,\d+)?)h"/.exec(src) || [])[2];
    const lb = (() => { const m = /\bvar LB\s*=\s*(\[[^\]]*\])/.exec(src); try { return JSON.parse(m[1]); } catch (e) { return []; } })();
    const ns = [...literalText(src, 'kpis').matchAll(/n:\s*"([^"]*)"\s*,\s*l:\s*(?:LB\[(\d+)\]|"([^"]*)")/g)];
    const kpis = ns.map(k => ({ n: k[1], l: k[3] != null ? k[3] : (lb[Number(k[2])] || '') }));
    const full = { ref_date: (/(\d{2})\/(\d{2})\/(\d{4})/.exec(meta) || []).slice(1).reverse().join('-') || date, kpis,
      consumo: consumo.map(r => ({ d: r[0], h: r[1] })), chat: chat.map(r => ({ d: r[0], h: r[1] })),
      acoes: acoesRaw.map(r => ({ a: r[0], r: r[1], s: r[2], t: r[3] || '' })),
      andamento: andamento.map(r => ({ t: r[0], d: r[1] })), previsao: previsao.map(r => ({ t: r[0], d: r[1] })), defs: defsRaw.map(r => ({ t: r[0], d: r[1] })) };
    ['titulo', 'meta', 'destaques', 'hConsumo', 'hChat', 'hAcoes', 'hAnd', 'hPrev', 'hDefs', 'totLbl1', 'totLbl2', 'pillChat', 'notaChat', 'notaPrev', 'rodape'].forEach(k => { full[k] = strProp(src, k); });
    const ST = { 'Concluído': 'CONCLUIDO', Parcial: 'PARCIAL', 'A confirmar': 'A_CONFIRMAR', Pendente: 'PENDENTE', 'Em andamento': 'PARCIAL', Bloqueado: 'PENDENTE' };
    const actions = [
      ...acoesRaw.map(r => ({ kind: 'ACAO', description: r[0], responsible: r[1], status: ST[r[2]] || 'PENDENTE', note: r[3] || '' })),
      ...defsRaw.map(r => ({ kind: 'PENDENCIA', description: (r[0] + (r[1] ? ': ' + r[1] : '')).slice(0, 500), responsible: 'Momesso', status: 'PENDENTE', note: '' }))];
    return { date, hours, demands, actions, full, reportTotal: total ? num(total) : null };
  }

  function importDialog(rep, mode) {
    const isH = mode === 'horas';
    const dl = rep.demands.map((d, i) => `<tr><td><input type="checkbox" name="dc${i}" checked aria-label="Importar demanda"></td>
      <td class="strong">${esc(d.name)}<span class="small">${esc(d.next_step)}</span></td>
      <td><select name="ds${i}" aria-label="Situação">${opts(D_STATUS, d.status)}</select></td>
      <td class="num">${d.approved ? h(d.approved) : '—'}</td></tr>`).join('');
    const hl = rep.hours.map((x, i) => `<tr><td><input type="checkbox" name="hc${i}" checked aria-label="Importar lançamento"></td>
      <td class="strong">${esc(x.activity)}</td><td>${esc(x.front || '—')}</td><td>${esc(x.demand || '—')}</td><td class="num">${h(x.hours)}</td></tr>`).join('');
    const total = rep.hours.reduce((a, x) => a + x.hours, 0);
    const warn = rep.reportTotal != null && Math.abs(rep.reportTotal - total) > 0.01
      ? `<p class="hint late-txt full">Atenção: o consumo do relatório é ${h(rep.reportTotal)}, mas as linhas somam ${h(total)}. Confira antes de importar.</p>` : '';
    const body = isH ? `
      <p class="hint full">Revise o que será lançado. Horas já lançadas (mesma data, atividade e valor) são ignoradas. As horas são ligadas às demandas já cadastradas quando o nome bate; por isso importe as demandas antes, na aba Demandas.</p>
      <div class="field"><label for="imp-d">Data dos lançamentos</label><input id="imp-d" name="date" type="date" value="${clampDate(rep.date, st.data)}" min="${st.data.start_date}"${st.data.end_date ? ` max="${st.data.end_date}"` : ''} required></div>
      <div class="field"><label>Total de horas</label><div class="strong" style="padding:10px 0">${h(total)}</div></div>${warn}
      <div class="full"><div class="table-wrap"><table class="tbl"><thead><tr><th></th><th>Atividade</th><th>Frente</th><th>Demanda</th><th class="num">Horas</th></tr></thead><tbody>${hl}</tbody></table></div></div>`
      : `
      <p class="hint full">Revise as demandas. As que já existem com o mesmo nome são atualizadas (situação, próximo passo e horas aprovadas); as demais são criadas.</p>
      <div class="full"><div class="table-wrap"><table class="tbl"><thead><tr><th></th><th>Demanda</th><th>Situação</th><th class="num">Aprovado</th></tr></thead><tbody>${dl}</tbody></table></div></div>`;
    const f = A().openDialog(isH ? 'Importar apontamentos do relatório' : 'Importar demandas do relatório', body, async d => {
      if (isH) {
        const hours = rep.hours.map((x, i) => d['hc' + i] ? { work_date: d.date, front: x.front, activity: x.activity, hours: x.hours, demand_name: x.demand } : null).filter(Boolean);
        if (!hours.length) throw new Error('Marque ao menos um lançamento.');
        const r = await api('POST', `spot/${st.id}/import`, { hours });
        st.from = null; st.to = null;
        A().toast(`Importado: ${r.hours_created} lançamento(s)` + (r.hours_skipped ? `; ${r.hours_skipped} já existia(m).` : '.'));
      } else {
        const demands = rep.demands.map((x, i) => d['dc' + i] ? { name: x.name, status: d['ds' + i], next_step: x.next_step.slice(0, 500), approved_hours: x.approved || 0, notes: x.notes } : null).filter(Boolean);
        if (!demands.length) throw new Error('Marque ao menos uma demanda.');
        const r = await api('POST', `spot/${st.id}/import`, { demands });
        A().toast(`Importado: ${r.demands_created} demanda(s) nova(s), ${r.demands_updated} atualizada(s).`);
      }
    }, 'Importar');
    document.getElementById('dlg').className = 'wide';
    return f;
  }

  function actionsDialog(rep) {
    const rows = rep.actions.map((a, i) => `<tr><td><input type="checkbox" name="ac${i}" checked aria-label="Importar item"></td>
      <td>${KIND[a.kind]}</td><td class="strong">${esc(a.description)}${a.note ? `<span class="small">${esc(a.note)}</span>` : ''}</td><td>${esc(a.responsible || '—')}</td>
      <td><select name="as${i}" aria-label="Situação">${opts(A_STATUS, a.status)}</select></td></tr>`).join('');
    if (!rep.actions.length) throw new Error('Não encontrei ações nem definições neste arquivo.');
    A().openDialog('Importar ações e pendências do relatório', `
      <p class="hint full">Revise os itens. As ações vêm de “Ações do último comitê” e as pendências de “Definições necessárias da Momesso”. Itens com a mesma descrição são atualizados; os demais são criados.</p>
      <div class="full"><div class="table-wrap"><table class="tbl"><thead><tr><th></th><th>Tipo</th><th>Descrição</th><th>Responsável</th><th>Situação</th></tr></thead><tbody>${rows}</tbody></table></div></div>`, async d => {
      const actions = rep.actions.map((a, i) => d['ac' + i] ? { ...a, status: d['as' + i] } : null).filter(Boolean);
      if (!actions.length) throw new Error('Marque ao menos um item.');
      const r = await api('POST', `spot/${st.id}/import`, { actions });
      A().toast(`Importado: ${r.actions_created} item(ns) novo(s), ${r.actions_updated} atualizado(s).`);
    }, 'Importar');
    document.getElementById('dlg').className = 'wide';
  }

  function reportDialog(rep) {
    const f = rep.full, count = (a, t) => `${a.length} ${t}`;
    A().openDialog('Importar relatório completo', `
      <p class="hint full">O relatório será salvo na lista, com o mesmo conteúdo e a mesma organização do arquivo, sem usar o Claude. Depois você pode abrir, imprimir ou enviar por e-mail.</p>
      <div class="field full"><label for="ir-t">Título</label><input id="ir-t" name="titulo" maxlength="200" value="${esc(f.titulo)}" required></div>
      <div class="field"><label for="ir-d">Data de referência</label><input id="ir-d" name="ref_date" type="date" value="${esc(clampDate(f.ref_date, st.data))}" min="${st.data.start_date}"${st.data.end_date ? ` max="${st.data.end_date}"` : ''} required></div>
      <div class="field"><label>Conteúdo</label><div class="small" style="padding:10px 0">${[count(f.kpis, 'indicadores'), count(f.consumo, 'linhas de consumo'), count(f.chat, 'alterações do chatbot'), count(f.acoes, 'ações'), count(f.andamento, 'em andamento'), count(f.previsao, 'previstas'), count(f.defs, 'definições')].join(' · ')}</div></div>
      <div class="field full"><label>Meta</label><div class="small">${esc(f.meta)}</div></div>`, async d => {
      const r = await api('POST', `spot/${st.id}/reports/import`, { ...f, titulo: d.titulo, ref_date: d.ref_date });
      A().toast('Relatório importado.');
      setTimeout(() => openReport(r.id), 300);
    }, 'Importar');
  }

  function pickReport(mode) {
    const inp = document.createElement('input');
    inp.type = 'file'; inp.accept = '.html,.htm,text/html'; inp.id = 'sp-imp-file'; inp.hidden = true;
    document.body.appendChild(inp);
    inp.addEventListener('change', async () => {
      try {
        const f = inp.files[0];
        if (!f) return;
        if (f.size > 3 * 1024 * 1024) throw new Error('Arquivo grande demais para um relatório.');
        const rep = parseReport(await f.text());
        if (mode === 'acoes') actionsDialog(rep); else if (mode === 'relatorio') reportDialog(rep); else importDialog(rep, mode);
      } catch (e) { A().toast(e.message); }
      inp.remove();
    });
    inp.click();
  }

  // ------------------------------------------------------------------ arquivo do escopo (somente registro)
  function fileForm() {
    A().openDialog('Anexar arquivo do escopo', `
      <div class="field full"><label for="ff">Arquivo (PDF, Word, Excel, imagem…)</label><input id="ff" name="file" type="file" required></div>
      <div class="field full"><label for="ft">Título (opcional)</label><input id="ft" name="title" maxlength="160" placeholder="Ex.: Proposta técnica v3"></div>
      <div class="field full"><label for="fd">Descrição (opcional)</label><input id="fd" name="description" maxlength="500"></div>
      <p class="hint full">O arquivo é guardado no banco de dados somente para registro (até 25 MB).</p>`, async d => {
      const file = d.file;
      if (!file || !file.name) throw new Error('Selecione um arquivo.');
      if (file.size > 25 * 1024 * 1024) throw new Error('O arquivo é maior que 25 MB.');
      const headers = { 'X-Requested-With': 'SysAttack', 'Content-Type': 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name),
        'X-Doc-Title': encodeURIComponent(d.title || ''), 'X-Doc-Description': encodeURIComponent(d.description || '') };
      if (closedIds.has(st.id)) { // projeto encerrado: justificativa obrigatória
        const why = await askJustification('Alterar projeto encerrado');
        if (!why) throw new Error('Alteração cancelada.');
        headers['X-Justification'] = encodeURIComponent(why);
      }
      const r = await fetch(`api/spot/${st.id}/files`, { method: 'POST', credentials: 'same-origin', body: file, headers });
      const data = await r.json().catch(() => null);
      if (r.status === 401) { location.replace('login.html'); return; }
      if (!r.ok) throw new Error((data && data.error) || 'Falha ao enviar o arquivo.');
      A().toast('Arquivo anexado.');
    }, 'Anexar');
  }

  // ------------------------------------------------------------------ nota fiscal (financeiro do escopo fechado)
  function invoiceForm(id) {
    const x = id ? st.invoices.find(r => r.id === id) : { number: '', issue_date: todayIso(), due_date: '', amount: '', status: 'EMITIDA', description: '', file_name: '' };
    A().openDialog(id ? 'Editar nota' : 'Lançar nota', `
      <div class="field"><label for="in">Número da nota</label><input id="in" name="number" maxlength="60" value="${esc(x.number)}" required></div>
      <div class="field"><label for="ia">Valor (R$)</label><input id="ia" name="amount" type="number" min="0.01" step="0.01" value="${x.amount}" required></div>
      <div class="field"><label for="ie">Emissão</label><input id="ie" name="issue_date" type="date" value="${x.issue_date}" required></div>
      <div class="field"><label for="iv">Vencimento <span class="opt">(opcional)</span></label><input id="iv" name="due_date" type="date" value="${x.due_date || ''}"></div>
      <div class="field"><label for="is">Situação</label><select id="is" name="status">${opts(Object.fromEntries(Object.entries(I_STATUS).map(([k, v]) => [k, v[0]])), x.status)}</select></div>
      <div class="field full"><label for="id">Descrição <span class="opt">(opcional)</span></label><input id="id" name="description" maxlength="500" value="${esc(x.description)}" placeholder="Ex.: 1ª parcela — kickoff"></div>
      <div class="field full"><label for="if">Arquivo da nota (PDF) <span class="opt">(opcional)</span></label><input id="if" name="file" type="file" accept=".pdf,.png,.jpg,.jpeg,.xml">${x.file_name ? `<span class="small">Atual: ${esc(x.file_name)}. Escolha outro para substituir.</span>` : ''}</div>
      <p class="hint full">O arquivo é guardado no banco de dados (até 25 MB).</p>`, async d => {
      const file = d.file;
      if (file && file.name && file.size > 25 * 1024 * 1024) throw new Error('O arquivo é maior que 25 MB.');
      const body = { number: d.number, amount: d.amount, issue_date: d.issue_date, due_date: d.due_date || null, status: d.status, description: d.description || '' };
      const n = await api(id ? 'PUT' : 'POST', id ? 'spot/invoices/' + id : `spot/${st.id}/invoices`, body);
      if (file && file.name) {
        const headers = { 'X-Requested-With': 'SysAttack', 'Content-Type': 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name) };
        if (closedIds.has(st.id)) {
          const why = await askJustification('Alterar projeto encerrado');
          if (!why) throw new Error('A nota foi salva, mas o arquivo não foi anexado (cancelado).');
          headers['X-Justification'] = encodeURIComponent(why);
        }
        const r = await fetch(`api/spot/invoices/${n.id}/file`, { method: 'POST', credentials: 'same-origin', body: file, headers });
        const data = await r.json().catch(() => null);
        if (r.status === 401) { location.replace('login.html'); return; }
        if (!r.ok) throw new Error('A nota foi salva, mas o arquivo falhou: ' + ((data && data.error) || 'erro ao enviar.'));
      }
      A().toast('Nota salva.');
    });
  }

  // ------------------------------------------------------------------ relatório tradicional (escopo fechado, preenchido na tela, sem Claude)
  const MR_INV = ['Emitida', 'Paga', 'Cancelada'];
  const MR_LISTS = {
    kpis: { add: '+ Adicionar indicador', cols: [['n', 'Valor', 'mr-s'], ['l', 'Descrição', 'mr-w']] },
    info: { add: '+ Adicionar informação', cols: [['t', 'Campo', 'mr-m'], ['d', 'Valor', 'mr-w']] },
    fin: { add: '+ Adicionar indicador', cols: [['n', 'Valor', 'mr-s'], ['l', 'Descrição', 'mr-w']] },
    notas: { add: '+ Adicionar nota', cols: [['n', 'Nota', 'mr-m'], ['e', 'Emissão', 'mr-s'], ['v', 'Vencimento', 'mr-s'], ['a', 'Valor', 'mr-m'], ['s', 'Situação', 'mr-s', MR_INV], ['d', 'Descrição', 'mr-w']] }
  };
  function mrRow(list, row) {
    return `<div class="mr-row mr-${list}">${MR_LISTS[list].cols.map(([f, ph, cls, opts]) => opts
      ? `<select class="${cls}" data-f="${f}" aria-label="${ph}">${opts.map(o => `<option${o === row[f] ? ' selected' : ''}>${o}</option>`).join('')}</select>`
      : `<input class="${cls}" data-f="${f}" maxlength="400" placeholder="${ph}" aria-label="${ph}" value="${esc(row[f] || '')}">`).join('')}<button type="button" class="icon-btn del" data-mr-rm title="Remover linha" aria-label="Remover linha">${ic('trash')}</button></div>`;
  }
  const mrList = (list, rows) => `<div class="mr-list" data-list="${list}">${rows.map(r => mrRow(list, r)).join('')}</div><button type="button" class="btn btn-sm mr-add" data-mr-add="${list}">${MR_LISTS[list].add}</button>`;
  function mrDemand(x) {
    return `<div class="mr-dem"><div class="mr-row"><input class="mr-w" data-f="d" maxlength="400" placeholder="Demanda" aria-label="Demanda" value="${esc(x.d || '')}">
      <input class="mr-m" data-f="s" maxlength="60" placeholder="Situação" aria-label="Situação" value="${esc(x.s || '')}"><input class="mr-m" data-f="e" maxlength="60" placeholder="Entrega" aria-label="Entrega" value="${esc(x.e || '')}">
      <button type="button" class="icon-btn del" data-mr-rm title="Remover demanda" aria-label="Remover demanda">${ic('trash')}</button></div>
      <textarea data-f="ap" maxlength="5000" rows="${Math.max(2, Math.min(7, String(x.ap || '').split('\n').length))}" placeholder="Apontamentos desta demanda (um por linha)" aria-label="Apontamentos">${esc(x.ap || '')}</textarea></div>`;
  }

  /** Dados iniciais de um relatório novo: tudo o que a visão do projeto, as demandas, os apontamentos e o financeiro já têm. */
  async function manualDefaults() {
    const p = st.data, sc = p.scope, f = p.finance, today = fmt(todayIso());
    const [dem, hrs, inv] = await Promise.all([api('GET', `spot/${st.id}/demands`), api('GET', `spot/${st.id}/hours?from=${p.start_date}&to=${p.end_date || '2999-12-31'}`), api('GET', `spot/${st.id}/invoices`)]);
    const vig = fmt(p.start_date) + (p.end_date ? ' a ' + fmt(p.end_date) : ' em diante');
    return {
      titulo: p.name,
      meta: [p.client, 'Escopo fechado', 'Vigência ' + vig, 'Emitido em ' + today].filter(Boolean).join(' · '), destaques: '', rodape: (p.provider ? p.provider + ' · ' : '') + 'Relatório do projeto de escopo fechado.',
      kpis: [['Demandas do escopo', sc.total], ['Entregues (com apontamento)', sc.delivered], ['Sem apontamento', sc.no_entry], ['Concluídas sem apontamento', sc.done_no_entry], ['Entregue do escopo', sc.pct + '%']].map(([l, n]) => ({ n: String(n), l })),
      info: [['Situação', S_STATUS[p.status][0]], ['Fornecedor / equipe', p.provider], ['Início', fmt(p.start_date)], ['Data final', p.end_date ? fmt(p.end_date) : '—'], ['Tipo', 'Escopo fechado'], ['Participantes do comitê', p.participants], ['Destinatários', p.recipients], ['Descrição', p.description]]
        .filter(x => x[1]).map(([t, d]) => ({ t, d: String(d) })),
      demandas: dem.filter(d => d.status !== 'CANCELADA').map(d => ({ d: d.name, s: D_STATUS[d.status][0], e: Number(d.entries) > 0 ? 'Entregue' : 'Sem apontamento',
        ap: hrs.filter(x => x.demand_id === d.id).sort((a, b) => a.work_date < b.work_date ? -1 : 1).map(x => `${fmt(x.work_date)} · ${x.activity}${x.front ? ' (' + x.front + ')' : ''}`).join('\n') })),
      fin: [['Valor Projeto (R$)', brl(f.project_value)], ['Faturado', brl(f.invoiced)], ['A faturar', brl(f.to_invoice)], ['Recebido', brl(f.paid)], ['Faturado do valor', f.pct_invoiced + '%']].map(([l, n]) => ({ n, l })),
      notas: inv.map(n => ({ n: n.number, e: fmt(n.issue_date), v: n.due_date ? fmt(n.due_date) : '', a: brl(n.amount), s: I_STATUS[n.status][0], d: n.description || '' }))
    };
  }

  async function manualForm(id) {
    let d, r = null;
    if (id) { r = await api('GET', 'spot/reports/' + id); d = r.data; } else d = await manualDefaults();
    const f = A().openDialog(id ? 'Editar relatório tradicional' : 'Novo relatório tradicional', `
      <div class="field full mr">
        <div class="mr-top">
          <div class="field"><label>Título (nome do projeto)</label><input data-k="titulo" maxlength="200" value="${esc(d.titulo)}"></div>
          ${id ? '<div></div>' : `<div class="field"><label>Data de referência</label><input data-ref type="date" min="${st.data.start_date}" max="${st.data.end_date && st.data.end_date < todayIso() ? st.data.end_date : todayIso()}" value="${clampDate(todayIso(), st.data)}"></div>`}
          <div class="field"><label>Subtítulo</label><input data-k="meta" maxlength="1000" value="${esc(d.meta)}"></div>
          <div class="field"><label>Destaques</label><input data-k="destaques" maxlength="1000" value="${esc(d.destaques)}" placeholder="Principais destaques do período"></div>
        </div>
        <div class="mr-tabs" role="tablist"><button type="button" class="mr-tab active" data-mr-tab="visao">Visão do projeto</button><button type="button" class="mr-tab" data-mr-tab="dem">Demandas e apontamentos</button><button type="button" class="mr-tab" data-mr-tab="fin">Financeiro</button></div>
        <div class="mr-pane" data-pane="visao"><p class="hint">Dados vindos do projeto. Ajuste o que quiser; linhas em branco não aparecem no relatório.</p>
          <section class="mr-card"><h4>Indicadores</h4>${mrList('kpis', d.kpis)}</section><section class="mr-card"><h4>Informações do projeto</h4>${mrList('info', d.info)}</section></div>
        <div class="mr-pane" data-pane="dem" hidden><p class="hint">Cada demanda traz os apontamentos vinculados a ela (um por linha).</p>
          <div class="mr-list" data-list="demandas">${d.demandas.map(mrDemand).join('')}</div><button type="button" class="btn btn-sm mr-add" data-mr-add="demandas">+ Adicionar demanda</button></div>
        <div class="mr-pane" data-pane="fin" hidden><p class="hint">Valor do projeto e notas lançadas na guia Financeiro.</p>
          <section class="mr-card"><h4>Indicadores financeiros</h4>${mrList('fin', d.fin)}</section><section class="mr-card"><h4>Notas</h4>${mrList('notas', d.notas)}</section></div>
        <div class="field" style="margin-top:14px"><label>Rodapé</label><input data-k="rodape" maxlength="1000" value="${esc(d.rodape)}"></div>
      </div>`, async () => {
      const out = {};
      host.querySelectorAll('[data-k]').forEach(i => { out[i.dataset.k] = i.value.trim(); });
      host.querySelectorAll('[data-list]').forEach(l => {
        out[l.dataset.list] = [...l.querySelectorAll(':scope > .mr-row, :scope > .mr-dem')].map(row => Object.fromEntries([...row.querySelectorAll('[data-f]')].map(i => [i.dataset.f, i.value.trim()])))
          .filter(row => l.dataset.list === 'demandas' ? row.d : Object.entries(row).some(([k, v]) => v && k !== 's'));
      });
      if (!out.titulo) throw new Error('Informe o título do relatório.');
      let rep;
      if (id) rep = await api('PUT', 'spot/reports/' + id, out);
      else rep = await api('POST', `spot/${st.id}/reports/manual`, { ...out, ref_date: host.querySelector('[data-ref]').value });
      A().toast('Relatório salvo.');
      setTimeout(() => openReport(rep.id), 300);
    }, 'Salvar e visualizar');
    const dlg = document.getElementById('dlg'), host = f.querySelector('.mr');
    dlg.className = 'wide';
    host.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') e.preventDefault(); });
    host.addEventListener('click', e => {
      const tab = e.target.closest('[data-mr-tab]'), add = e.target.closest('[data-mr-add]'), rm = e.target.closest('[data-mr-rm]');
      if (tab) {
        host.querySelectorAll('[data-mr-tab]').forEach(t => t.classList.toggle('active', t === tab));
        host.querySelectorAll('[data-pane]').forEach(p => { p.hidden = p.dataset.pane !== tab.dataset.mrTab; });
      } else if (add) {
        const k = add.dataset.mrAdd, l = host.querySelector(`[data-list="${k}"]`);
        l.insertAdjacentHTML('beforeend', k === 'demandas' ? mrDemand({}) : mrRow(k, k === 'notas' ? { s: 'Emitida' } : {}));
        l.lastElementChild.querySelector('input').focus();
      } else if (rm) rm.closest('.mr-row, .mr-dem').remove();
    });
  }

  // ------------------------------------------------------------------ ações
  async function action(el) {
    const id = Number(el.dataset.id), a = el.dataset.action, dlg = document.getElementById('dlg');
    const del = async (path, msg) => { if (confirm(msg)) { await api('DELETE', path); A().toast('Excluído.'); await A().route(); } };
    switch (a) {
      case 'sp-new': await projectForm(); break;
      case 'sp-copy-project': await projectForm(null, id); break;
      case 'sp-edit-project': await projectForm(id); break;
      case 'sp-del-project': await del('spot/' + id, 'Excluir este projeto spot com todas as horas, demandas e relatórios?'); break;
      case 'sp-tab': st.tab = el.dataset.tab; document.querySelectorAll('[data-action="sp-tab"]').forEach(b => b.classList.toggle('active', b === el)); await renderTab(); break;
      case 'sp-vig-all': st.from = null; st.to = null; await renderTab(); break;
      case 'sp-new-hour': await hourForm(); break;
      case 'sp-new-manual': await manualForm(); break;
      case 'sp-edit-manual': dlg.close(); await manualForm(id); break;
      case 'sp-new-invoice': invoiceForm(); break;
      case 'sp-edit-invoice': invoiceForm(id); break;
      case 'sp-del-invoice': await del('spot/invoices/' + id, 'Excluir esta nota?'); break;
      case 'sp-new-file': fileForm(); break;
      case 'sp-del-file': await del('spot/files/' + id, 'Excluir este arquivo do escopo?'); break;
      case 'sp-list-tab': listTab = el.dataset.kind; await viewList(); break;
      case 'sp-import': pickReport('horas'); break;
      case 'sp-import-demand': pickReport('demandas'); break;
      case 'sp-import-action': pickReport('acoes'); break;
      case 'sp-import-report': pickReport('relatorio'); break;
      case 'sp-edit-hour': await hourForm(id); break;
      case 'sp-del-hour': await del('spot/hours/' + id, 'Excluir este lançamento de horas?'); break;
      case 'sp-new-demand': demandForm(); break;
      case 'sp-edit-demand': demandForm(id); break;
      case 'sp-del-demand': await del('spot/demands/' + id, 'Excluir esta demanda? As horas lançadas nela permanecem, sem demanda.'); break;
      case 'sp-new-action': actionForm(); break;
      case 'sp-edit-action': actionForm(id); break;
      case 'sp-del-action': await del('spot/actions/' + id, 'Excluir este item?'); break;
      case 'sp-new-report': reportForm(); break;
      case 'sp-open-report': await openReport(id); break;
      case 'sp-del-report': await del('spot/reports/' + id, 'Excluir este relatório?'); break;
      case 'sp-edit-report': dlg.close(); await editReport(id); break;
      case 'sp-send-report': dlg.close(); await sendReport(id); break;
      case 'sp-print-report': { const f = document.getElementById('sp-frame'); if (f && f.contentWindow) f.contentWindow.print(); break; }
    }
  }

  const view = arg => arg ? viewDetail(Number(arg)) : viewList();
  return { view, action };
})();
