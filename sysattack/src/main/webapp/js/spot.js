/* SysAttack — Projetos Spot: horas, demandas, ações e relatórios gerados com o Claude. */
const Spot = (() => {
  const { api, esc, fmt, todayIso, ic } = SA;
  const A = () => window.App;
  const D_STATUS = { EM_ANALISE: ['Em análise', 'b-grey'], APROVADO: ['Aprovado', 'b-dark'], EM_ANDAMENTO: ['Em andamento', 'b-orange'], EM_HOMOLOGACAO: ['Em homologação', 'b-orange'], EM_ESPERA: ['Em espera', 'b-grey'], CONCLUIDA: ['Concluída', 'b-green'], CANCELADA: ['Cancelada', 'b-red'] };
  const A_STATUS = { PENDENTE: ['Pendente', 'b-red'], A_CONFIRMAR: ['A confirmar', 'b-orange'], PARCIAL: ['Parcial', 'b-orange'], CONCLUIDO: ['Concluído', 'b-green'] };
  const S_STATUS = { ATIVO: ['Ativo', 'b-green'], PAUSADO: ['Pausado', 'b-orange'], ENCERRADO: ['Encerrado', 'b-grey'] };
  const MODEL = { MENSAL: 'Franquia mensal', PACOTE: 'Pacote de horas' };
  const KIND = { ACAO: 'Ação do último comitê', PENDENCIA: 'Pendência' };

  let st = { id: null, tab: 'resumo', month: todayIso().slice(0, 7), data: null, hours: [], demands: [], actions: [], reports: [] };

  const h = n => (Math.round((Number(n) || 0) * 10) / 10).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + 'h';
  const badge = (map, k) => map[k] ? `<span class="badge ${map[k][1]}">${map[k][0]}</span>` : esc(k);
  const opts = (map, sel) => Object.entries(map).map(([k, v]) => `<option value="${k}"${k === sel ? ' selected' : ''}>${Array.isArray(v) ? v[0] : v}</option>`).join('');
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const iconBtns = (kind, id) => `<td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar" data-action="sp-edit-${kind}" data-id="${id}">${ic('edit')}</button>
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

  // ------------------------------------------------------------------ vigência
  const overdue = p => p.end_date && p.status === 'ATIVO' && p.end_date < todayIso();
  const vigencia = p => p.end_date ? `<span class="small ${overdue(p) ? 'late-txt' : ''}">até ${fmt(p.end_date)}${overdue(p) ? ' · vigência encerrada' : ''}</span>` : '';
  const endedNote = p => overdue(p) ? '<span class="small late-txt">Vigência encerrada: encerre o projeto ou ajuste a data.</span>' : '';

  // ------------------------------------------------------------------ lista
  async function viewList() {
    const list = await api('GET', 'spot');
    A().main.innerHTML = A().pageHead(['SysAttack', 'Projetos Spot'], 'Projetos Spot', 'Projetos ágeis por horas: consumo, demandas e relatórios de acompanhamento.') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Carteira spot</h2>
        <button class="btn btn-primary btn-sm" data-action="sp-new">+ Novo projeto spot</button></div>
        <div class="table-wrap">${list.length ? `<table class="tbl"><thead><tr><th>Projeto</th><th>Modelo</th><th class="num">Fixadas</th><th class="num">Consumidas</th><th class="num">Saldo projetado</th><th>Último relatório</th><th>Situação</th><th></th></tr></thead><tbody>
        ${list.map(p => `<tr class="click" data-go="#/spot/${p.id}"><td class="strong">${esc(p.name)}${p.recurring ? ' <span class="badge b-dark rec">Recorrente</span>' : ''}<span class="small">${esc(p.client || '—')}</span></td>
          <td>${MODEL[p.hours_model]}<span class="small">${esc(p.period_label || '')}</span>${vigencia(p)}</td>
          <td class="num">${h(p.kpi.contracted)}</td><td class="num">${h(p.kpi.consumed)}</td>
          <td class="num strong ${Number(p.kpi.projected) < 0 ? 'late-txt' : ''}">${h(p.kpi.projected)}</td>
          <td>${p.last_report ? fmt(String(p.last_report).slice(0, 10)) : '—'}</td><td>${badge(S_STATUS, p.status)}</td>
          <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar projeto spot" data-action="sp-edit-project" data-id="${p.id}">${ic('edit')}</button>
          <button class="icon-btn del" title="Excluir" aria-label="Excluir projeto spot" data-action="sp-del-project" data-id="${p.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table>`
        : empty('Nenhum projeto spot ainda', 'Cadastre o projeto com as horas fixadas, lance as horas consumidas e gere o relatório semanal com o Claude.')}</div></section>`;
  }

  // ------------------------------------------------------------------ detalhe
  async function viewDetail(id) {
    if (st.id !== id) st = { id, tab: 'resumo', month: todayIso().slice(0, 7), data: null, hours: [], demands: [], actions: [], reports: [] };
    const p = await api('GET', 'spot/' + id);
    st.data = p;
    const s = p.summary;
    const tab = (k, label) => `<button class="tab ${st.tab === k ? 'active' : ''}" data-action="sp-tab" data-tab="${k}">${label}</button>`;
    A().main.innerHTML = A().pageHead(['SysAttack', { t: 'Projetos Spot', href: '#/spot' }, p.name], p.name, [p.client, MODEL[p.hours_model] + ' · ' + s.period_label].filter(Boolean).join(' · ')) + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Visão do período</h2><div class="bar-actions">
        <button class="btn btn-sm" data-action="sp-edit-project" data-id="${p.id}">Editar projeto</button></div></div>
        ${kpiCards(s.kpi, p.hours_model === 'MENSAL' ? 'Franquia' : 'Pacote')}
        <dl class="meta-grid"><div><dt>Situação</dt><dd>${badge(S_STATUS, p.status)}</dd></div><div><dt>Fornecedor / equipe</dt><dd>${esc(p.provider || '—')}</dd></div>
        <div><dt>Início</dt><dd>${fmt(p.start_date)}</dd></div><div><dt>Data final</dt><dd>${p.end_date ? fmt(p.end_date) : '—'}${endedNote(p)}</dd></div><div><dt>Contrato</dt><dd>${p.recurring ? '<span class="badge b-dark">Recorrente</span>' : 'Avulso'}</dd></div><div><dt>Participantes do comitê</dt><dd>${esc(p.participants || '—')}</dd></div>
        <div><dt>Destinatários</dt><dd>${esc(p.recipients || '—')}</dd></div></dl>
        ${p.description ? `<p class="scope-text">${esc(p.description)}</p>` : ''}</section>
      <section class="panel"><div class="panel-head"><div class="tabs" style="margin:0;border:0;flex:1;flex-wrap:wrap">
        ${tab('resumo', 'Resumo')}${tab('horas', 'Apontamentos de horas')}${tab('demandas', 'Demandas')}${tab('acoes', 'Ações e pendências')}${tab('relatorios', 'Relatórios com Claude')}</div>
        <div class="bar-actions" id="sp-actions"></div></div><div id="sp-body"></div></section>`;
    await renderTab();
  }

  async function renderTab() {
    const host = document.getElementById('sp-body'), act = document.getElementById('sp-actions');
    if (!host) return;
    const b = (a, l, primary = true) => `<button class="btn ${primary ? 'btn-primary ' : ''}btn-sm" data-action="${a}">${l}</button>`;
    act.innerHTML = { resumo: '', horas: b('sp-import', 'Importar relatório (HTML)', false) + b('sp-new-hour', '+ Lançar horas'), demandas: b('sp-new-demand', '+ Nova demanda'), acoes: b('sp-new-action', '+ Nova ação / pendência'), relatorios: b('sp-new-report', '✦ Gerar relatório') }[st.tab];
    const id = st.id;
    if (st.tab === 'resumo') {
      const s = st.data.summary;
      host.innerHTML = `${s.by_item.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Frente</th><th>Atividade</th><th class="num">Horas</th></tr></thead><tbody>
        ${s.by_item.map(i => `<tr><td>${esc(i.front || '—')}</td><td class="strong">${esc(i.label)}</td><td class="num">${h(i.hours)}</td></tr>`).join('')}</tbody></table></div>`
        : empty('Sem horas no período', 'Lance as horas consumidas na aba “Apontamentos de horas”.')}
        ${st.data.billing_note ? `<p class="hint" style="margin-top:14px"><b>Faturamento:</b> ${esc(st.data.billing_note)}</p>` : ''}`;
    } else if (st.tab === 'horas') {
      st.hours = await api('GET', `spot/${id}/hours?month=${st.month}`);
      const total = st.hours.reduce((a, x) => a + Number(x.hours), 0);
      host.innerHTML = `<div style="display:flex;gap:12px;align-items:center;margin-bottom:14px;flex-wrap:wrap"><label for="sp-m" class="hint">Mês</label>
        <input id="sp-m" type="month" value="${st.month === 'all' ? '' : st.month}" style="min-height:36px;border:1px solid #cfcfcf;padding:0 10px">
        <button class="btn btn-sm" data-action="sp-month-all">Ver todos</button><span class="hint">${st.hours.length} lançamento(s) · <b>${h(total)}</b></span></div>
        ${st.hours.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Data</th><th>Frente</th><th>Atividade</th><th>Demanda</th><th class="num">Horas</th><th></th></tr></thead><tbody>
        ${st.hours.map(x => `<tr><td>${fmt(x.work_date)}</td><td>${esc(x.front || '—')}</td><td class="strong">${esc(x.activity)}</td><td>${esc(x.demand_name || '—')}</td><td class="num">${h(x.hours)}</td>${iconBtns('hour', x.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nenhum lançamento neste filtro', 'Use “+ Lançar horas” para registrar o consumo (ex.: horas exportadas do Salesforce).')}`;
      const m = document.getElementById('sp-m');
      m.addEventListener('change', () => { st.month = m.value || 'all'; renderTab(); });
    } else if (st.tab === 'demandas') {
      st.demands = await api('GET', `spot/${id}/demands`);
      host.innerHTML = st.demands.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Demanda</th><th>Situação</th><th>Próximo passo</th><th class="num">Aprovado</th><th class="num">Realizado</th><th></th></tr></thead><tbody>
        ${st.demands.map(d => `<tr><td class="strong">${esc(d.name)}${d.notes ? `<span class="small">${esc(d.notes)}</span>` : ''}</td><td>${badge(D_STATUS, d.status)}</td><td>${esc(d.next_step || '—')}</td>
          <td class="num">${Number(d.approved_hours) > 0 ? h(d.approved_hours) : '—'}</td>
          <td class="num ${Number(d.approved_hours) > 0 && Number(d.hours_total) > Number(d.approved_hours) ? 'late-txt' : ''}">${h(d.hours_total)}</td>${iconBtns('demand', d.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nenhuma demanda', 'Cadastre as demandas (com horas aprovadas) para comparar realizado × aprovado.');
    } else if (st.tab === 'acoes') {
      st.actions = await api('GET', `spot/${id}/actions`);
      host.innerHTML = st.actions.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Tipo</th><th>Descrição</th><th>Responsável</th><th>Situação</th><th></th></tr></thead><tbody>
        ${st.actions.map(a => `<tr><td>${KIND[a.kind]}</td><td class="strong">${esc(a.description)}${a.note ? `<span class="small">${esc(a.note)}</span>` : ''}</td><td>${esc(a.responsible || '—')}</td><td>${badge(A_STATUS, a.status)}</td>${iconBtns('action', a.id)}</tr>`).join('')}</tbody></table></div>`
        : empty('Nada registrado', 'Registre as ações do último comitê e as pendências (da Momesso ou do cliente).');
    } else {
      st.reports = await api('GET', `spot/${id}/reports`);
      const ai = await api('GET', 'ai-status').catch(() => ({ connected: false }));
      host.innerHTML = `${ai.connected ? '' : '<p class="hint late-txt" style="margin-bottom:14px">O Claude ainda não está conectado. Um administrador precisa configurar a chave em “Configurações IA Agent”.</p>'}
        ${st.reports.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Relatório</th><th>Referência</th><th>Gerado por</th><th>Envio</th><th></th></tr></thead><tbody>
        ${st.reports.map(r => `<tr class="click" data-action="sp-open-report" data-id="${r.id}"><td class="strong">${esc(r.title)}</td><td>${fmt(r.ref_date)}</td>
          <td>${esc(r.created_by)}<span class="small">${esc(String(r.created_at).slice(0, 16).replace('T', ' '))}</span></td>
          <td>${r.sent_at ? `<span class="badge b-green">Enviado</span><span class="small">${fmt(String(r.sent_at).slice(0, 10))}</span>` : '<span class="badge b-grey">Não enviado</span>'}</td>
          <td class="acts"><button class="icon-btn del" title="Excluir" aria-label="Excluir relatório" data-action="sp-del-report" data-id="${r.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`
        : empty('Nenhum relatório ainda', 'Clique em “Gerar relatório”: o sistema calcula os números e o Claude escreve o texto de acompanhamento.')}`;
    }
  }

  // ------------------------------------------------------------------ formulários
  function projectForm(id) {
    return (async () => {
      const p = id ? await api('GET', 'spot/' + id) : { name: '', client: '', provider: '', description: '', hours_model: 'MENSAL', contracted_hours: '', start_date: todayIso(), end_date: '', recurring: false, status: 'ATIVO', participants: '', recipients: '', billing_note: '' };
      A().openDialog(id ? 'Editar projeto spot' : 'Novo projeto spot', `
        <div class="field full"><label for="sn">Nome</label><input id="sn" name="name" maxlength="160" value="${esc(p.name)}" required></div>
        <div class="field"><label for="sc">Cliente</label><input id="sc" name="client" maxlength="160" value="${esc(p.client)}"></div>
        <div class="field"><label for="sp">Fornecedor / equipe</label><input id="sp" name="provider" maxlength="160" value="${esc(p.provider)}"></div>
        <div class="field"><label for="sm">Modelo de horas</label><select id="sm" name="hours_model">${opts(MODEL, p.hours_model)}</select></div>
        <div class="field"><label for="sh">Horas fixadas</label><input id="sh" name="contracted_hours" type="number" min="0" step="0.5" value="${p.contracted_hours}" required></div>
        <div class="field"><label for="sd">Início</label><input id="sd" name="start_date" type="date" value="${p.start_date}" required></div>
        <div class="field"><label for="se">Data final <span class="opt">(opcional)</span></label><input id="se" name="end_date" type="date" value="${p.end_date || ''}"></div>
        <div class="field"><label for="ss">Situação</label><select id="ss" name="status">${opts(S_STATUS, p.status)}</select></div>
        <div class="field full"><label class="check"><input type="checkbox" name="recurring" ${p.recurring ? 'checked' : ''}> <b>Contrato recorrente</b> — repete a cada período (ex.: franquia mensal). O cadastro continua o mesmo; muda só o que é registrado nas demandas.</label></div>
        <div class="field full"><label for="spa">Participantes do comitê</label><input id="spa" name="participants" maxlength="1000" value="${esc(p.participants)}" placeholder="Nomes separados por vírgula"></div>
        <div class="field full"><label for="sr">Destinatários dos relatórios (e-mails)</label><input id="sr" name="recipients" maxlength="1000" value="${esc(p.recipients)}" placeholder="gestor1@empresa.com, gestor2@empresa.com"></div>
        <div class="field full"><label for="sb">Observação de faturamento</label><input id="sb" name="billing_note" maxlength="1000" value="${esc(p.billing_note)}" placeholder="Ex.: excedentes de agosto serão faturados à parte"></div>
        <div class="field full"><label for="sx">Descrição</label><textarea id="sx" name="description" maxlength="2000">${esc(p.description)}</textarea></div>
        <p class="hint full">Franquia mensal: o consumo conta do dia 1º do mês de referência até a data. Pacote: conta desde o início do projeto.</p>`,
        async d => { const s = await api(id ? 'PUT' : 'POST', id ? 'spot/' + id : 'spot', { ...d, recurring: !!d.recurring, end_date: d.end_date || null }); A().toast('Projeto spot salvo.'); if (!id) location.hash = '#/spot/' + s.id; });
    })();
  }

  async function hourForm(id) {
    const demands = await api('GET', `spot/${st.id}/demands`);
    const x = id ? st.hours.find(r => r.id === id) : { work_date: todayIso(), front: '', activity: '', demand_id: '', hours: '' };
    A().openDialog(id ? 'Editar lançamento' : 'Lançar horas', `
      <div class="field"><label for="hd">Data</label><input id="hd" name="work_date" type="date" value="${x.work_date}" required></div>
      <div class="field"><label for="hh">Horas</label><input id="hh" name="hours" type="number" min="0.1" step="0.1" value="${x.hours}" required></div>
      <div class="field"><label for="hf">Frente</label><input id="hf" name="front" maxlength="80" value="${esc(x.front)}" placeholder="Ex.: Suporte, Chatbot"></div>
      <div class="field"><label for="hm">Demanda</label><select id="hm" name="demand_id"><option value="">— nenhuma —</option>${demands.map(d => `<option value="${d.id}"${d.id === x.demand_id ? ' selected' : ''}>${esc(d.name)}</option>`).join('')}</select></div>
      <div class="field full"><label for="ha">Atividade</label><input id="ha" name="activity" maxlength="300" value="${esc(x.activity)}" required></div>`,
      async d => { await api(id ? 'PUT' : 'POST', id ? 'spot/hours/' + id : `spot/${st.id}/hours`, d); A().toast('Horas salvas.'); });
  }

  function demandForm(id) {
    const x = id ? st.demands.find(r => r.id === id) : { name: '', status: 'EM_ANALISE', next_step: '', approved_hours: '', notes: '' };
    A().openDialog(id ? 'Editar demanda' : 'Nova demanda', `
      <div class="field full"><label for="dn">Demanda</label><input id="dn" name="name" maxlength="200" value="${esc(x.name)}" required></div>
      <div class="field"><label for="ds">Situação</label><select id="ds" name="status">${opts(D_STATUS, x.status)}</select></div>
      <div class="field"><label for="dh">Horas aprovadas</label><input id="dh" name="approved_hours" type="number" min="0" step="0.5" value="${Number(x.approved_hours) > 0 ? x.approved_hours : ''}" placeholder="Opcional"></div>
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
      <div class="field"><label for="rd">Data de referência</label><input id="rd" name="ref_date" type="date" max="${todayIso()}" value="${todayIso()}" required></div>
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
        <button class="btn btn-sm" data-action="sp-edit-report" data-id="${r.id}">Editar texto</button>
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
      <div class="field full"><label for="to">Destinatários</label><input id="to" name="to" maxlength="1000" value="${esc(st.data.recipients || '')}" placeholder="email1@empresa.com, email2@empresa.com" required></div>
      <p class="hint full">O relatório vai no corpo do e-mail, com a mensagem de abertura do texto. Requer SMTP configurado (veja o README).</p>`,
      async d => {
        const res = await api('POST', `spot/reports/${id}/send`, d);
        A().toast(res.delivered ? 'E-mail enviado para ' + res.to : 'Modo de teste (smtp.dev): e-mail não enviado de fato.');
      }, 'Enviar');
  }


  // ------------------------------------------------------------------ importação do Status Report (HTML da Fábrica)
  /** Extrai o literal de array `s.<nome>=[...]` do script do relatório (sem eval: só JSON.parse). */
  function literalArray(src, name) {
    const m = new RegExp('\\bs\\.' + name + '\\s*=\\s*\\[').exec(src);
    if (!m) return [];
    const start = m.index + m[0].length - 1;
    let depth = 0, inStr = false;
    for (let i = start; i < src.length; i++) {
      const c = src[i];
      if (inStr) { if (c === '\\') i++; else if (c === '"') inStr = false; continue; }
      if (c === '"') inStr = true;
      else if (c === '[') depth++;
      else if (c === ']' && --depth === 0) {
        try { const v = JSON.parse(src.slice(start, i + 1)); return Array.isArray(v) ? v : []; } catch (e) { return []; }
      }
    }
    return [];
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
    return { date, hours, demands, reportTotal: total ? num(total) : null };
  }

  function importDialog(rep) {
    const dl = rep.demands.map((d, i) => `<tr><td><input type="checkbox" name="dc${i}" checked aria-label="Importar demanda"></td>
      <td class="strong">${esc(d.name)}<span class="small">${esc(d.next_step)}</span></td>
      <td><select name="ds${i}" aria-label="Situação">${opts(D_STATUS, d.status)}</select></td>
      <td class="num">${d.approved ? h(d.approved) : '—'}</td></tr>`).join('');
    const hl = rep.hours.map((x, i) => `<tr><td><input type="checkbox" name="hc${i}" checked aria-label="Importar lançamento"></td>
      <td class="strong">${esc(x.activity)}</td><td>${esc(x.front || '—')}</td><td>${esc(x.demand || '—')}</td><td class="num">${h(x.hours)}</td></tr>`).join('');
    const total = rep.hours.reduce((a, x) => a + x.hours, 0);
    const warn = rep.reportTotal != null && Math.abs(rep.reportTotal - total) > 0.01
      ? `<p class="hint late-txt full">Atenção: o consumo do relatório é ${h(rep.reportTotal)}, mas as linhas somam ${h(total)}. Confira antes de importar.</p>` : '';
    const f = A().openDialog('Importar relatório da Fábrica', `
      <p class="hint full">Revise o que será lançado. Demandas com o mesmo nome são atualizadas; horas já lançadas (mesma data, atividade e valor) são ignoradas.</p>
      <div class="field"><label for="imp-d">Data dos lançamentos</label><input id="imp-d" name="date" type="date" value="${rep.date}" required></div>
      <div class="field"><label>Total de horas</label><div class="strong" style="padding:10px 0">${h(total)}</div></div>${warn}
      <div class="full"><h3 style="margin:0 0 8px;font-size:15px">Horas consumidas</h3><div class="table-wrap"><table class="tbl"><thead><tr><th></th><th>Atividade</th><th>Frente</th><th>Demanda</th><th class="num">Horas</th></tr></thead><tbody>${hl}</tbody></table></div></div>
      <div class="full"><h3 style="margin:0 0 8px;font-size:15px">Demandas</h3><div class="table-wrap"><table class="tbl"><thead><tr><th></th><th>Demanda</th><th>Situação</th><th class="num">Aprovado</th></tr></thead><tbody>${dl}</tbody></table></div></div>`,
      async d => {
        const demands = rep.demands.map((x, i) => d['dc' + i] ? { name: x.name, status: d['ds' + i], next_step: x.next_step.slice(0, 500), approved_hours: x.approved || 0, notes: x.notes } : null).filter(Boolean);
        const kept = new Set(demands.map(x => x.name));
        const hours = rep.hours.map((x, i) => d['hc' + i] ? { work_date: d.date, front: x.front, activity: x.activity, hours: x.hours, demand_name: kept.has(x.demand) ? x.demand : '' } : null).filter(Boolean);
        const r = await api('POST', `spot/${st.id}/import`, { demands, hours });
        st.month = d.date.slice(0, 7); st.tab = 'horas';
        A().toast(`Importado: ${r.hours_created} lançamento(s), ${r.demands_created} demanda(s) nova(s), ${r.demands_updated} atualizada(s)` + (r.hours_skipped ? `; ${r.hours_skipped} já existia(m).` : '.'));
      }, 'Importar');
    document.getElementById('dlg').className = 'wide';
    return f;
  }

  function pickReport() {
    const inp = document.createElement('input');
    inp.type = 'file'; inp.accept = '.html,.htm,text/html'; inp.id = 'sp-imp-file'; inp.hidden = true;
    document.body.appendChild(inp);
    inp.addEventListener('change', async () => {
      try {
        const f = inp.files[0];
        if (!f) return;
        if (f.size > 3 * 1024 * 1024) throw new Error('Arquivo grande demais para um relatório.');
        importDialog(parseReport(await f.text()));
      } catch (e) { A().toast(e.message); }
      inp.remove();
    });
    inp.click();
  }

  // ------------------------------------------------------------------ ações
  async function action(el) {
    const id = Number(el.dataset.id), a = el.dataset.action, dlg = document.getElementById('dlg');
    const del = async (path, msg) => { if (confirm(msg)) { await api('DELETE', path); A().toast('Excluído.'); await A().route(); } };
    switch (a) {
      case 'sp-new': await projectForm(); break;
      case 'sp-edit-project': await projectForm(id); break;
      case 'sp-del-project': await del('spot/' + id, 'Excluir este projeto spot com todas as horas, demandas e relatórios?'); break;
      case 'sp-tab': st.tab = el.dataset.tab; document.querySelectorAll('[data-action="sp-tab"]').forEach(b => b.classList.toggle('active', b === el)); await renderTab(); break;
      case 'sp-month-all': st.month = 'all'; await renderTab(); break;
      case 'sp-new-hour': await hourForm(); break;
      case 'sp-import': pickReport(); break;
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
