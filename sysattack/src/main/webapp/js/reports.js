/* SysAttack — Relatórios & Dashs (projetos e orçamentária) e a aba Changes dos projetos. Spot não entra nestes relatórios. */
const Rep = (() => {
  const { api, esc, fmt, ic, dayNum, isoOf } = SA;
  const A = () => window.App;
  const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
  const money = n => BRL.format(Number(n) || 0);
  const pct = n => Math.round(n) + '%';
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const CH = { PENDENTE: ['Pendente', 'b-orange'], APROVADA: ['Aprovada', 'b-green'], REJEITADA: ['Rejeitada', 'b-grey'] };
  const badge = (map, k) => `<span class="badge ${map[k][1]}">${map[k][0]}</span>`;

  let st = { tab: 'projetos', project: 'all', data: null };
  let changeCtx = { projectId: null, list: [] };

  // ------------------------------------------------------------------ cálculos
  function leafTasks(d, pid) { return d.tasks.filter(t => t.project_id === pid && !t.child_count); }

  /** Previsto = quanto do prazo de cada tarefa já passou; realizado = progresso informado. Ponderado pela duração. */
  function plannedActual(d, pid) {
    const today = dayNum(d.today);
    let ts = leafTasks(d, pid).filter(t => !t.is_milestone);
    if (!ts.length) ts = leafTasks(d, pid);
    let w = 0, pv = 0, rv = 0;
    ts.forEach(t => {
      const s = dayNum(t.start_date), e = dayNum(t.end_date), dur = Math.max(1, e - s + 1);
      const prev = Math.min(1, Math.max(0, (today - s + 1) / dur)) * 100;
      w += dur; pv += prev * dur; rv += t.progress * dur;
    });
    return w ? { planned: pv / w, actual: rv / w, tasks: ts.length } : { planned: 0, actual: 0, tasks: 0 };
  }

  function visibleProjects(d) { return st.project === 'all' ? d.projects : d.projects.filter(p => String(p.id) === st.project); }

  function hbar(value, max, color) {
    const w = max > 0 ? Math.min(100, value / max * 100) : 0;
    return `<div class="rbar"><div class="rbar-fill" style="width:${w}%;background:${color}"></div></div>`;
  }

  // ------------------------------------------------------------------ relatórios de projetos
  function repTimeline(d, projects) {
    const today = dayNum(d.today);
    let rows = [];
    if (projects.length === 1) {
      const p = projects[0];
      d.tasks.filter(t => t.project_id === p.id && !t.parent_id).forEach(t => rows.push({ label: t.name, sub: t.responsible, s: t.start_date, e: t.end_date, ms: !!t.is_milestone, progress: t.progress, late: t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA' && dayNum(t.end_date) < today }));
      if (!rows.length) rows.push({ label: p.name, sub: p.owner, s: p.start_date, e: p.end_date, ms: false, progress: 0, late: false });
    } else {
      projects.forEach(p => {
        const pa = plannedActual(d, p.id);
        rows.push({ label: p.name, sub: p.owner, s: p.start_date, e: p.end_date, ms: false, progress: pa.actual, late: false, project: true });
        d.tasks.filter(t => t.project_id === p.id && t.is_milestone).forEach(t => rows.push({ label: '◆ ' + t.name, sub: '', s: t.start_date, e: t.end_date, ms: true, progress: t.progress, late: t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA' && dayNum(t.end_date) < today }));
      });
    }
    if (!rows.length) return empty('Sem dados para a linha do tempo', 'Cadastre projetos e tarefas.');
    let min = Math.min(...rows.map(r => dayNum(r.s)), today), max = Math.max(...rows.map(r => dayNum(r.e)), today);
    min -= 3; max += 3;
    const span = max - min, x = n => ((n - min) / span * 100);
    const ticks = [];
    const c = new Date(min * 86400000); c.setUTCDate(1); c.setUTCMonth(c.getUTCMonth() + 1);
    for (; c.getTime() / 86400000 < max; c.setUTCMonth(c.getUTCMonth() + 1)) {
      ticks.push({ pos: x(c.getTime() / 86400000), label: c.toLocaleDateString('pt-BR', { month: 'short', year: '2-digit', timeZone: 'UTC' }) });
    }
    const grid = ticks.map(t => `<i class="tl-tick" style="left:${t.pos}%"></i>`).join('');
    const body = rows.map(r => {
      const l = x(dayNum(r.s)), w = Math.max(0.6, x(dayNum(r.e) + 1) - l);
      const mark = r.ms ? `<b class="tl-ms ${r.late ? 'late' : ''}" style="left:${l}%" title="${esc(r.label)} · ${fmt(r.e)}"></b>`
        : `<div class="tl-bar ${r.project ? 'proj' : ''} ${r.late ? 'late' : ''}" style="left:${l}%;width:${w}%" title="${esc(r.label)}: ${fmt(r.s)} a ${fmt(r.e)}"><span style="width:${Math.round(r.progress)}%"></span></div>`;
      return `<div class="tl-row"><div class="tl-name"><b>${esc(r.label)}</b>${r.sub ? `<small>${esc(r.sub)}</small>` : ''}</div><div class="tl-track">${grid}${mark}<i class="tl-today" style="left:${x(today)}%"></i></div></div>`;
    }).join('');
    const head = `<div class="tl-row tl-head"><div class="tl-name"></div><div class="tl-track">${ticks.map(t => `<span class="tl-label" style="left:${t.pos}%">${esc(t.label)}</span>`).join('')}</div></div>`;
    return `<div class="tl">${head}${body}</div><p class="hint" style="margin-top:10px">Barra escura = progresso. Linha laranja = hoje (${fmt(d.today)}). Losango = marco. Vermelho = atrasado.</p>`;
  }

  function repLate(d, projects) {
    const ids = new Set(projects.map(p => p.id)), today = dayNum(d.today), pn = Object.fromEntries(d.projects.map(p => [p.id, p.name]));
    const late = d.tasks.filter(t => ids.has(t.project_id) && !t.child_count && t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA' && dayNum(t.end_date) < today)
      .map(t => ({ ...t, days: today - dayNum(t.end_date) })).sort((a, b) => b.days - a.days);
    if (!late.length) return empty('Nenhum item em atraso', 'Todas as tarefas e marcos abertos estão dentro do prazo.');
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Item</th><th>Projeto</th><th>Responsável</th><th>Prazo</th><th class="num">Dias de atraso</th><th class="num">Progresso</th></tr></thead><tbody>
      ${late.map(t => `<tr class="click" data-go="#/projeto/${t.project_id}"><td class="strong">${t.is_milestone ? '◆ ' : ''}${esc(t.name)}</td><td>${esc(pn[t.project_id])}</td><td>${esc(t.responsible || '—')}</td>
        <td class="late-txt">${fmt(t.end_date)}</td><td class="num strong late-txt">${t.days}</td><td class="num">${t.progress}%</td></tr>`).join('')}</tbody></table></div>
      <p class="hint" style="margin-top:10px">${late.length} item(ns) em atraso. Tarefas pai não entram: elas são calculadas a partir das filhas.</p>`;
  }

  function repPlanned(d, projects) {
    if (!projects.length) return empty('Sem projetos', '');
    return `<div class="rrows">${projects.map(p => {
      const a = plannedActual(d, p.id), diff = a.actual - a.planned;
      return `<div class="rrow"><div class="rlabel"><b>${esc(p.name)}</b><small>${a.tasks} tarefa(s) · ${fmt(p.start_date)} a ${fmt(p.end_date)}</small></div>
        <div class="rbars"><div class="rline"><span>Previsto</span>${hbar(a.planned, 100, '#1b1b1b')}<em>${pct(a.planned)}</em></div>
        <div class="rline"><span>Realizado</span>${hbar(a.actual, 100, diff < -5 ? 'var(--red)' : 'var(--orange)')}<em>${pct(a.actual)}</em></div></div>
        <div class="rdiff ${diff < -5 ? 'neg' : ''}">${diff >= 0 ? '+' : ''}${Math.round(diff)} p.p.<small>${diff < -5 ? 'atrás do previsto' : diff > 5 ? 'à frente' : 'no ritmo'}</small></div></div>`;
    }).join('')}</div>
    <p class="hint" style="margin-top:10px">Previsto = parte do prazo de cada tarefa que já passou até hoje; realizado = progresso informado. Média ponderada pela duração das tarefas (marcos ficam de fora).</p>`;
  }

  function repChangeCount(d, projects) {
    const rows = projects.map(p => {
      const c = d.changes.filter(x => x.project_id === p.id);
      return { p, total: c.length, ap: c.filter(x => x.status === 'APROVADA').length, pe: c.filter(x => x.status === 'PENDENTE').length, re: c.filter(x => x.status === 'REJEITADA').length };
    });
    const max = Math.max(1, ...rows.map(r => r.total));
    if (!rows.some(r => r.total)) return empty('Nenhuma change registrada', 'Registre as mudanças na aba “Changes” de cada projeto.');
    return `<div class="rrows">${rows.map(r => `<div class="rrow"><div class="rlabel"><b>${esc(r.p.name)}</b><small>${r.total} change(s)</small></div>
      <div class="rbars"><div class="rline"><span></span><div class="rbar stack" title="Aprovadas ${r.ap} · Pendentes ${r.pe} · Rejeitadas ${r.re}">
        <div style="width:${r.ap / max * 100}%;background:var(--green)"></div><div style="width:${r.pe / max * 100}%;background:var(--orange)"></div><div style="width:${r.re / max * 100}%;background:#b5b5b5"></div></div><em>${r.total}</em></div></div>
      <div class="rdiff"><small>${r.ap} aprov. · ${r.pe} pend. · ${r.re} rej.</small></div></div>`).join('')}</div>
      <p class="hint" style="margin-top:10px"><i class="dot-k" style="background:var(--green)"></i> Aprovadas <i class="dot-k" style="background:var(--orange)"></i> Pendentes <i class="dot-k" style="background:#b5b5b5"></i> Rejeitadas</p>`;
  }

  function repChangeDelay(d, projects) {
    const rows = projects.map(p => {
      const c = d.changes.filter(x => x.project_id === p.id && x.status === 'APROVADA');
      return { p, days: c.reduce((a, x) => a + x.impact_days, 0), n: c.filter(x => x.impact_days > 0).length, cost: c.reduce((a, x) => a + Number(x.impact_cost), 0) };
    });
    const max = Math.max(1, ...rows.map(r => r.days));
    const list = d.changes.filter(x => projects.some(p => p.id === x.project_id) && x.status === 'APROVADA' && x.impact_days > 0).sort((a, b) => b.impact_days - a.impact_days);
    if (!list.length) return empty('Nenhum atraso gerado por changes', 'Só contam changes aprovadas com impacto em dias maior que zero.');
    return `<div class="rrows">${rows.map(r => `<div class="rrow"><div class="rlabel"><b>${esc(r.p.name)}</b><small>${r.n} change(s) com impacto</small></div>
      <div class="rbars"><div class="rline"><span></span>${hbar(r.days, max, 'var(--red)')}<em>${r.days} dia(s)</em></div></div>
      <div class="rdiff"><small>${r.cost > 0 ? money(r.cost) : ''}</small></div></div>`).join('')}</div>
      <div class="table-wrap" style="margin-top:18px"><table class="tbl"><thead><tr><th>Change</th><th>Projeto</th><th>Data</th><th class="num">Atraso (dias)</th><th class="num">Impacto R$</th></tr></thead><tbody>
      ${list.map(x => `<tr><td class="strong">${esc(x.title)}${x.reason ? `<span class="small">${esc(x.reason)}</span>` : ''}</td><td>${esc(x.project_name)}</td><td>${fmt(x.change_date)}</td>
        <td class="num strong late-txt">${x.impact_days}</td><td class="num">${Number(x.impact_cost) > 0 ? money(x.impact_cost) : '—'}</td></tr>`).join('')}</tbody></table></div>`;
  }

  // ------------------------------------------------------------------ orçamentária
  function costOf(d, pid) {
    const f = d.finance.filter(x => x.project_id === pid), sum = fn => f.filter(fn).reduce((a, x) => a + Number(x.total), 0);
    return { nf: sum(x => x.kind === 'NF_SERVICO'), inv: sum(x => x.kind === 'FATURA'), total: sum(() => true), paid: sum(x => x.status === 'PAGO') };
  }

  function repBudget(d, projects) {
    if (!projects.length) return empty('Sem projetos', '');
    const rows = projects.map(p => ({ p, b: Number(p.budget) || 0, c: costOf(d, p.id) }));
    const max = Math.max(1, ...rows.map(r => Math.max(r.b, r.c.total)));
    return `<div class="rrows">${rows.map(r => {
      const over = r.b > 0 && r.c.total > r.b, saldo = r.b - r.c.total;
      return `<div class="rrow"><div class="rlabel"><b>${esc(r.p.name)}</b><small>${r.b > 0 ? pct(r.c.total / r.b * 100) + ' do orçamento' : 'sem orçamento definido'}</small></div>
        <div class="rbars"><div class="rline"><span>Previsto</span>${hbar(r.b, max, '#1b1b1b')}<em>${r.b > 0 ? money(r.b) : '—'}</em></div>
        <div class="rline"><span>Realizado</span>${hbar(r.c.total, max, over ? 'var(--red)' : 'var(--orange)')}<em>${money(r.c.total)}</em></div></div>
        <div class="rdiff ${over ? 'neg' : ''}">${r.b > 0 ? (saldo >= 0 ? 'Saldo ' : 'Estouro ') + money(Math.abs(saldo)) : '—'}<small>${over ? 'acima do orçamento' : r.b > 0 ? 'dentro do orçamento' : ''}</small></div></div>`;
    }).join('')}</div>
    <p class="hint" style="margin-top:10px">Previsto = orçamento do projeto. Realizado = soma das notas fiscais de serviço e faturas lançadas na aba Financeiro.</p>`;
  }

  function repCost(d, projects) {
    const rows = projects.map(p => ({ p, c: costOf(d, p.id) })).sort((a, b) => b.c.total - a.c.total);
    const t = rows.reduce((a, r) => ({ nf: a.nf + r.c.nf, inv: a.inv + r.c.inv, total: a.total + r.c.total, paid: a.paid + r.c.paid }), { nf: 0, inv: 0, total: 0, paid: 0 });
    if (!rows.length) return empty('Sem projetos', '');
    return `<div class="mini-kpis"><div class="mini accent"><span>Custo total</span><b>${money(t.total)}</b></div><div class="mini"><span>Notas fiscais de serviço</span><b>${money(t.nf)}</b></div>
      <div class="mini"><span>Faturas</span><b>${money(t.inv)}</b></div><div class="mini"><span>Pago</span><b>${money(t.paid)}</b></div><div class="mini"><span>A pagar</span><b>${money(t.total - t.paid)}</b></div></div>
      <div class="table-wrap"><table class="tbl"><thead><tr><th>Projeto</th><th class="num">Notas fiscais</th><th class="num">Faturas</th><th class="num">Custo total</th><th class="num">Pago</th><th class="num">A pagar</th><th>Participação</th></tr></thead><tbody>
      ${rows.map(r => `<tr class="click" data-go="#/projeto/${r.p.id}"><td class="strong">${esc(r.p.name)}</td><td class="num">${money(r.c.nf)}</td><td class="num">${money(r.c.inv)}</td><td class="num strong">${money(r.c.total)}</td>
        <td class="num">${money(r.c.paid)}</td><td class="num">${money(r.c.total - r.c.paid)}</td><td style="min-width:140px">${hbar(r.c.total, t.total, 'var(--orange)')}<span class="small">${t.total > 0 ? pct(r.c.total / t.total * 100) : '0%'}</span></td></tr>`).join('')}
      <tr><td class="strong">Total</td><td class="num strong">${money(t.nf)}</td><td class="num strong">${money(t.inv)}</td><td class="num strong">${money(t.total)}</td><td class="num strong">${money(t.paid)}</td><td class="num strong">${money(t.total - t.paid)}</td><td></td></tr></tbody></table></div>`;
  }

  // ------------------------------------------------------------------ página
  const panel = (title, sub, body) => `<section class="panel"><div class="panel-head"><div><h2 class="panel-title">${title}</h2>${sub ? `<p class="hint" style="margin:4px 0 0">${sub}</p>` : ''}</div></div>${body}</section>`;

  function repEac(projects) {
    const rows = projects.filter(p => p.status !== 'PLANEJAMENTO');
    if (!rows.length) return empty('Sem projetos em andamento', 'A projeção aparece quando o projeto sai do planejamento.');
    const idx = v => v == null ? '—' : (v < 1 ? (Math.floor(v * 100) / 100) : Number(v)).toFixed(2).replace('.', ',');
    const cls = v => v == null ? '' : v >= 1 ? '' : v >= 0.9 ? 'warn-txt' : 'late-txt';
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Projeto</th><th class="num">Orçamento</th><th class="num">Custo lançado</th><th class="num"><span class="has-tip"${Tip.attr('SPI — desempenho de prazo', ['= SPI = valor realizado ÷ valor previsto até hoje', '1,00 ou mais: no ritmo ou à frente. 0,90 a 0,99: levemente atrasado. Abaixo de 0,90: atrasado.'])}>SPI</span></th><th class="num"><span class="has-tip"${Tip.attr('CPI — desempenho de custo', ['= CPI = valor realizado ÷ custo lançado', 'Quanto de trabalho foi entregue para cada R$ 1,00 gasto. Abaixo de 1,00 o projeto está gastando mais do que entrega.'])}>CPI</span></th><th class="num"><span class="has-tip"${Tip.attr('EAC — custo previsto no término', ['= EAC = orçamento ÷ CPI', 'Quanto o projeto deve custar no fim se o ritmo de gasto atual continuar. Vermelho quando passa do orçamento.'])}>Projeção no término (EAC)</span></th><th class="num"><span class="has-tip"${Tip.attr('Desvio no término (VAC)', ['= Desvio = orçamento − EAC', 'Negativo: estouro projetado. Positivo: folga no orçamento.'])}>Desvio</span></th></tr></thead><tbody>
      ${rows.map(p => { const e = p.evm || {};
        if (!e.available) return `<tr><td class="strong">${esc(p.name)}</td><td colspan="6" class="small" style="color:var(--muted)">${e.reason === 'NO_BUDGET' ? 'Sem orçamento definido.' : 'Sem tarefas para calcular.'}</td></tr>`;
        const vac = e.vac;
        return `<tr><td class="strong">${esc(p.name)}${e.reliable ? '' : '<span class="small">Progresso abaixo de 10%: projeção pouco confiável</span>'}</td>
          <td class="num">${money(e.bac)}</td><td class="num">${money(e.ac)}</td><td class="num ${cls(e.spi)}">${idx(e.spi)}</td><td class="num ${e.ac > 0 ? cls(e.cpi) : ''}">${e.ac > 0 ? idx(e.cpi) : '—'}</td>
          <td class="num ${e.eac > e.bac ? 'late-txt' : ''}">${e.eac == null ? '—' : money(e.eac)}</td>
          <td class="num ${vac < 0 ? 'late-txt' : ''}">${vac == null ? '—' : (vac < 0 ? '−' : '+') + money(Math.abs(vac))}</td></tr>`; }).join('')}</tbody></table></div>
      <p class="hint" style="margin-top:10px">SPI = valor realizado ÷ valor previsto até hoje (prazo). CPI = valor realizado ÷ custo lançado. EAC = orçamento ÷ CPI. Desvio = orçamento − EAC. Valor realizado e previsto = orçamento × progresso (ponderado pela duração das tarefas). Abaixo de 1: atraso (SPI) ou gasto acima do entregue (CPI).</p>`;
  }

  async function view() {
    if (!st.data || !st.fresh) st.data = await api('GET', 'reports');
    st.fresh = false;
    const d = st.data, projects = visibleProjects(d);
    const tab = (k, l) => `<button class="tab ${st.tab === k ? 'active' : ''}" data-action="rp-tab" data-tab="${k}">${l}</button>`;
    const filter = `<div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap"><label for="rp-p" class="hint">Projeto</label>
      <select id="rp-p" style="min-height:36px;border:1px solid #cfcfcf;padding:0 10px;background:#fff"><option value="all">Todos os projetos</option>${d.projects.map(p => `<option value="${p.id}"${String(p.id) === st.project ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}</select>
      <button class="btn btn-sm" data-action="rp-print">Imprimir / salvar PDF</button></div>`;
    const body = st.tab === 'projetos'
      ? panel('Timeline do Projeto', st.project === 'all' ? 'Visão de todos os projetos com seus marcos.' : 'Fases e tarefas de nível principal do projeto.', repTimeline(d, projects))
        + panel('Itens em atraso', 'Tarefas e marcos abertos com o prazo vencido.', repLate(d, projects))
        + panel('Previsto × Realizado', 'Progresso esperado pelo cronograma contra o progresso informado.', repPlanned(d, projects))
        + panel('Quantidade de changes por projeto', 'Solicitações de mudança registradas, por situação.', repChangeCount(d, projects))
        + panel('Atrasos gerados por changes', 'Dias somados das changes aprovadas com impacto no prazo.', repChangeDelay(d, projects))
      : panel('Previsto × Realizado (orçamento)', 'Orçamento do projeto contra o que já foi lançado, em R$.', repBudget(d, projects))
        + panel('Projeção de custo no término', 'Quanto cada projeto deve custar no fim, mantido o ritmo atual (valor agregado).', repEac(projects))
        + panel('Custo Projeto Total', 'Notas fiscais de serviço e faturas por projeto, em R$.', repCost(d, projects));
    A().main.innerHTML = A().pageHead(['SysAttack', 'Relatórios & Dashs'], 'Relatórios & Dashs', 'Indicadores dos projetos (os projetos spot não entram aqui).') +
      `<section class="panel"><div class="panel-head"><div class="tabs" style="margin:0;border:0;flex:1">${tab('projetos', 'Projetos')}${tab('orcamentaria', 'Orçamentária')}</div>${filter}</div></section>` + body;
    document.getElementById('rp-p').addEventListener('change', e => { st.project = e.target.value; view(); });
  }

  // ------------------------------------------------------------------ aba Changes do projeto
  async function changesTab(host, pid) {
    changeCtx.projectId = pid;
    const list = changeCtx.list = await api('GET', 'changes?project_id=' + pid);
    const days = list.filter(c => c.status === 'APROVADA').reduce((a, c) => a + c.impact_days, 0);
    host.innerHTML = `<div class="mini-kpis"><div class="mini"><span>Changes</span><b>${list.length}</b></div><div class="mini"><span>Aprovadas</span><b>${list.filter(c => c.status === 'APROVADA').length}</b></div>
      <div class="mini ${days > 0 ? 'neg' : ''}"><span>Atraso por changes aprovadas</span><b>${days} dia(s)</b></div></div>
      ${list.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Data</th><th>Change</th><th>Solicitante</th><th class="num">Impacto (dias)</th><th class="num">Impacto R$</th><th>Situação</th><th></th></tr></thead><tbody>
      ${list.map(c => `<tr><td>${fmt(c.change_date)}</td><td class="strong">${esc(c.title)}${c.reason ? `<span class="small">${esc(c.reason)}</span>` : ''}</td><td>${esc(c.requested_by || '—')}</td>
        <td class="num">${c.impact_days}</td><td class="num">${Number(c.impact_cost) > 0 ? money(c.impact_cost) : '—'}</td><td>${badge(CH, c.status)}</td>
        <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar change" data-action="rp-edit-change" data-id="${c.id}">${ic('edit')}</button>
        <button class="icon-btn del" title="Excluir" aria-label="Excluir change" data-action="rp-del-change" data-id="${c.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`
        : empty('Nenhuma change registrada', 'Registre aqui as mudanças de escopo, prazo ou custo pedidas durante o projeto.')}`;
  }

  function changeForm(id) {
    const c = id ? changeCtx.list.find(x => x.id === id) : { title: '', reason: '', requested_by: '', change_date: SA.todayIso(), impact_days: 0, impact_cost: '', status: 'PENDENTE' };
    A().openDialog(id ? 'Editar change' : 'Nova change', `
      <div class="field full"><label for="ct">Título</label><input id="ct" name="title" maxlength="200" value="${esc(c.title)}" required></div>
      <div class="field"><label for="cd">Data</label><input id="cd" name="change_date" type="date" value="${c.change_date}" required></div>
      <div class="field"><label for="cs">Situação</label><select id="cs" name="status">${Object.entries(CH).map(([k, v]) => `<option value="${k}"${k === c.status ? ' selected' : ''}>${v[0]}</option>`).join('')}</select></div>
      <div class="field"><label for="ci">Impacto no prazo (dias)</label><input id="ci" name="impact_days" type="number" min="0" max="3650" value="${c.impact_days}"></div>
      <div class="field"><label for="cc">Impacto em custo (R$)</label>${SA.moneyInput('cc', 'impact_cost', Number(c.impact_cost) > 0 ? c.impact_cost : '')}</div>
      <div class="field full"><label for="cr">Solicitante</label><input id="cr" name="requested_by" maxlength="120" value="${esc(c.requested_by)}"></div>
      <div class="field full"><label for="cx">Motivo / descrição</label><textarea id="cx" name="reason" maxlength="2000">${esc(c.reason)}</textarea></div>
      <p class="hint full">Só changes <b>aprovadas</b> entram no relatório “Atrasos gerados por changes”.</p>`,
      async d => { await api(id ? 'PUT' : 'POST', id ? 'changes/' + id : 'changes', { ...d, project_id: changeCtx.projectId }); A().toast('Change salva.'); });
  }

  async function action(el) {
    const id = Number(el.dataset.id);
    switch (el.dataset.action) {
      case 'rp-tab': st.tab = el.dataset.tab; await view(); break;
      case 'rp-print': window.print(); break;
      case 'rp-new-change': changeForm(); break;
      case 'rp-edit-change': changeForm(id); break;
      case 'rp-del-change': if (confirm('Excluir esta change?')) { await api('DELETE', 'changes/' + id); A().toast('Change excluída.'); await A().route(); } break;
    }
  }

  return { view: () => { st.fresh = true; return view(); }, changesTab, action };
})();
