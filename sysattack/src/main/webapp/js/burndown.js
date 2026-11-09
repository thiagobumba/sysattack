/* SysAttack — Burndown: trabalho restante do projeto ao longo do tempo, comparado à linha ideal. */
const Burn = (() => {
  const { esc, fmt, todayIso, dayNum, isoOf } = SA;
  const ctx = { host: null, project: null, tasks: [], unit: 'days' };

  const day = v => (v ? String(v).slice(0, 10) : null);
  const span = t => Math.max(1, dayNum(day(t.end_date)) - dayNum(day(t.start_date)) + 1);
  const pct = n => (Math.round(n * 10) / 10).toLocaleString('pt-BR');

  function model() {
    const p = ctx.project, today = todayIso(), todayN = dayNum(today);
    const leaves = ctx.tasks.filter(t => !t.child_count && !t.is_milestone && t.status !== 'CANCELADA');
    const excluded = { ms: ctx.tasks.filter(t => t.is_milestone).length, cancel: ctx.tasks.filter(t => t.status === 'CANCELADA' && !t.child_count).length };
    if (!leaves.length) return { empty: true, excluded };
    const w = t => (ctx.unit === 'count' ? 1 : span(t));
    const total = leaves.reduce((s, t) => s + w(t), 0);
    const d0 = Math.min(dayNum(day(p.start_date)), ...leaves.map(t => dayNum(day(t.start_date))));
    const d1 = dayNum(day(p.end_date));
    const axisEnd = Math.max(d1, todayN, ...leaves.map(t => dayNum(day(t.end_date))));
    let estimated = 0;
    const doneAt = new Map(); // dia -> peso concluído nele
    let doneW = 0, doneCnt = 0;
    leaves.filter(t => t.status === 'CONCLUIDA').forEach(t => {
      let n;
      if (t.completed_at) n = dayNum(day(t.completed_at));
      else { n = Math.min(dayNum(day(t.end_date)), todayN); estimated++; }
      n = Math.max(n, d0);
      doneAt.set(n, (doneAt.get(n) || 0) + w(t)); doneW += w(t); doneCnt++;
    });
    const real = []; let acc = 0;
    for (let n = d0; n <= Math.min(axisEnd, todayN); n++) { acc += doneAt.get(n) || 0; real.push({ n, rem: total - acc }); }
    const idealAt = n => (n >= d1 ? 0 : total * (1 - (n - d0) / Math.max(1, d1 - d0)));
    const remNow = total - doneW, ideal = Math.max(0, idealAt(Math.min(todayN, axisEnd)));
    const elapsed = Math.max(1, todayN - d0 + 1), pace = doneW / elapsed;
    let projN = null;
    if (remNow <= 0) projN = null;
    else if (pace > 0 && todayN >= d0) projN = todayN + Math.ceil(remNow / pace);
    return { total, d0, d1, axisEnd, todayN, real, remNow, ideal, doneW, doneCnt, leafCnt: leaves.length, estimated, pace, projN, idealAt, excluded, notStarted: todayN < d0 };
  }

  const unitName = () => (ctx.unit === 'count' ? 'tarefas' : 'dias de trabalho');

  const short = iso => { const d = fmt(iso); return d.slice(0, 6) + d.slice(8); }; // dd/mm/aa
  const niceStep = (max, n) => { const raw = max / n, mag = Math.pow(10, Math.floor(Math.log10(raw))), k = raw / mag; return (k <= 1 ? 1 : k <= 2 ? 2 : k <= 5 ? 5 : 10) * mag; };

  // O desenho usa a largura real da tela (fontes sempre em 12px, nada é esticado nem cortado).
  function chart(m) {
    const W = Math.max(300, Math.floor(ctx.host.clientWidth) - 2), H = W < 560 ? 300 : 380;
    ctx.w = ctx.host.clientWidth;
    const L = 46, R = 28, T = 46, B = 34, pw = W - L - R, ph = H - T - B;
    const last = Math.max(m.axisEnd, m.projN && m.projN <= m.axisEnd + 400 ? m.projN : m.axisEnd);
    const span0 = Math.max(1, last - m.d0);
    const step = niceStep(m.total, 4), yMax = Math.max(step, Math.ceil(m.total / step) * step);
    const x = n => L + (n - m.d0) / span0 * pw, y = v => T + (1 - v / yMax) * ph;
    const tip = (title, lines) => Tip.attr(title, lines);
    const iso = n => isoOf(n), tw = s => s.length * 6.8 + 8;
    let g = '';
    for (let v = 0; v <= yMax + 1e-9; v += step) g += `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" class="bd-grid"/><text x="${L - 8}" y="${y(v) + 4}" text-anchor="end" class="bd-ax">${pct(v)}</text>`;
    const ticks = Math.max(2, Math.min(8, Math.floor(pw / 110)));
    for (let i = 0; i <= ticks; i++) {
      const n = Math.round(m.d0 + span0 * i / ticks);
      g += `<line x1="${x(n)}" x2="${x(n)}" y1="${T + ph}" y2="${T + ph + 4}" class="bd-grid dark"/><text x="${x(n)}" y="${H - 10}" text-anchor="${i === ticks ? 'end' : 'middle'}" class="bd-ax">${short(iso(n))}</text>`;
    }
    g += `<text x="${L - 8}" y="${T - 12}" text-anchor="end" class="bd-ax unit">${ctx.unit === 'count' ? 'tarefas' : 'dias'}</text>`;
    // marcadores verticais (hoje, prazo) com rótulo na faixa de cima, em linhas diferentes quando ficam perto
    const marks = [];
    if (m.todayN >= m.d0 && m.todayN <= last) marks.push({ n: m.todayN, text: 'Hoje ' + short(iso(m.todayN)), cls: 'bd-today', lab: 'bd-lab-today' });
    marks.push({ n: m.d1, text: 'Prazo ' + short(iso(m.d1)), cls: 'bd-dead', lab: 'bd-lab-dead' });
    marks.sort((a, b) => a.n - b.n);
    const used = [[], []]; let mk = '';
    marks.forEach(k => {
      const px = x(k.n), w = tw(k.text), flip = px + 6 + w > W - 4, x0 = flip ? px - 6 - w : px + 6;
      let row = used[0].some(r => x0 < r[1] && x0 + w > r[0]) ? 1 : 0;
      used[row].push([x0, x0 + w]);
      mk += `<line x1="${px}" x2="${px}" y1="${T - 6 - (row ? 0 : 0)}" y2="${T + ph}" class="${k.cls}"/><text x="${flip ? px - 6 : px + 6}" y="${T - 22 + row * 15 + 12}" text-anchor="${flip ? 'end' : 'start'}" class="bd-lab ${k.lab}">${k.text}</text>`;
    });
    const ideal = `<line x1="${x(m.d0)}" y1="${y(m.total)}" x2="${x(m.d1)}" y2="${y(0)}" class="bd-ideal"/>`;
    let path = '';
    m.real.forEach((pt, i) => { path += (i ? `H${x(pt.n)}V${y(pt.rem)}` : `M${x(pt.n)},${y(pt.rem)}`); });
    const real = m.real.length ? `<path d="${path}" class="bd-real"/>` : '';
    // faixa de passagem do mouse: um dia por faixa, de cima a baixo, para ler o valor em qualquer ponto do gráfico
    const bw = Math.max(3, pw / span0);
    const bands = m.real.map(pt => {
      const id = m.idealAt(pt.n), diff = pt.rem - id;
      return `<rect x="${x(pt.n) - bw / 2}" y="${T}" width="${bw}" height="${ph}" class="bd-band"${tip(fmt(iso(pt.n)), [
        `Restante real: ${pct(pt.rem)} ${unitName()}`, `Restante ideal: ${pct(id)} ${unitName()}`,
        diff > 0.05 ? `# ${pct(diff)} acima do ideal (atrasado)` : diff < -0.05 ? `# ${pct(-diff)} abaixo do ideal (adiantado)` : '# Exatamente no ritmo ideal'])}/>`;
    }).join('');
    let proj = '', now = '';
    if (m.real.length) {
      const lp = m.real[m.real.length - 1], flip = x(lp.n) - 90 < L;
      now = `<circle cx="${x(lp.n)}" cy="${y(lp.rem)}" r="4.5" class="bd-now"/>${W < 560 ? '' : `<text x="${x(lp.n) + (flip ? 8 : -8)}" y="${y(lp.rem) - 9}" text-anchor="${flip ? 'start' : 'end'}" class="bd-lab">Restam ${pct(lp.rem)}</text>`}`;
    }
    if (m.projN) {
      const px = x(m.projN), txt = 'Previsão ' + short(iso(m.projN)), flip = px + 8 + tw(txt) > W - 2;
      proj = `<line x1="${x(m.todayN)}" y1="${y(m.remNow)}" x2="${px}" y2="${y(0)}" class="bd-proj"/><circle cx="${px}" cy="${y(0)}" r="5" class="bd-pend"${tip('Previsão de término', [fmt(iso(m.projN)), `Mantendo o ritmo atual de ${pct(m.pace)} ${unitName()} por dia.`, m.projN > m.d1 ? `# ${m.projN - m.d1} dia(s) depois do prazo (${fmt(iso(m.d1))})` : '# Dentro do prazo'])}/>
        ${W < 560 ? '' : `<text x="${flip ? px - 8 : px + 8}" y="${y(0) - 24}" text-anchor="${flip ? 'end' : 'start'}" class="bd-lab bd-lab-proj">${txt}</text>`}`;
    }
    return `<svg class="bd-svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="Gráfico de burndown do projeto">${g}${mk}${ideal}${proj}${real}${now}${bands}</svg>`;
  }

  function summary(m) {
    const u = unitName(), done = m.remNow <= 0;
    const parts = [];
    if (m.notStarted) parts.push('O projeto ainda não começou.');
    else if (done) parts.push('Todo o trabalho foi concluído.');
    else {
      parts.push(`Faltam <b>${pct(m.remNow)} ${u}</b> (${pct(100 - m.doneW / m.total * 100)}% do trabalho).`);
      const d = m.remNow - m.ideal;
      parts.push(Math.abs(d) <= m.total * 0.03 ? 'Estamos no ritmo previsto.' : d > 0 ? `Hoje deveriam restar ${pct(m.ideal)}: estamos <b>${pct(d)} atrás</b> do plano.` : `Hoje deveriam restar ${pct(m.ideal)}: estamos <b>${pct(-d)} à frente</b> do plano.`);
      if (m.projN) { const late = m.projN - m.d1; parts.push(`No ritmo atual o término seria em <b>${fmt(isoOf(m.projN))}</b>, ${late > 0 ? `<b>${late} dia(s) depois</b> do prazo (${fmt(isoOf(m.d1))})` : 'dentro do prazo'}.`); }
      else parts.push('Ainda não há conclusões para projetar o término.');
    }
    return parts.join(' ');
  }

  function paint() { const c = ctx.host.querySelector('#bd-chart'); if (c) c.innerHTML = chart(ctx.m); }

  function draw() {
    const host = ctx.host, m = model(); ctx.m = m;
    if (ctx.ro) { ctx.ro.disconnect(); ctx.ro = null; }
    if (m.empty) { host.innerHTML = `<div class="empty"><strong>Sem tarefas para o burndown</strong>O burndown usa as tarefas do projeto (sem marcos e sem canceladas). Cadastre ou importe tarefas para acompanhar o trabalho restante.</div>`; return; }
    const diff = m.remNow - m.ideal, tol = m.total * 0.03;
    const state = m.notStarted ? ['grey', 'Ainda não começou'] : m.remNow <= 0 ? ['ok', 'Concluído'] : diff > tol ? ['bad', 'Atrás do ideal'] : diff < -tol ? ['ok', 'Adiantado'] : ['ok', 'No ritmo'];
    const proj = m.remNow <= 0 ? '—' : m.projN ? fmt(isoOf(m.projN)) : 'sem ritmo ainda';
    const late = m.projN && m.projN > m.d1;
    const kpi = (label, val, tipT, tipL, cls = '') => `<div class="mini ${cls}">${Tip.wrap(`<span>${label}</span><b>${val}</b>`, tipT, tipL)}</div>`;
    host.innerHTML = `<div class="bd-sum ${state[0]}" role="status">${summary(m)}</div>
      <div class="bd-bar"><label for="bd-unit">Medir por</label>
        <select id="bd-unit"><option value="days"${ctx.unit === 'days' ? ' selected' : ''}>Duração (dias)</option><option value="count"${ctx.unit === 'count' ? ' selected' : ''}>Quantidade de tarefas</option></select>
        <span class="small bd-help">Passe o mouse no gráfico para ver o valor de cada dia.</span></div>
      <div class="mini-kpis wrapv">
        ${kpi('Situação', state[1], 'Situação frente ao ideal', ['Compara o trabalho restante real com o que deveria restar hoje se o projeto andasse em ritmo constante do início ao prazo.', `• Real hoje: ${pct(m.remNow)} ${unitName()}`, `• Ideal hoje: ${pct(m.ideal)} ${unitName()}`, '# Tolerância de 3% do total.'], 'st-' + state[0])}
        ${kpi('Restante', pct(m.remNow) + (ctx.unit === 'count' ? ' tarefas' : ' dias'), 'Trabalho restante', [`Soma do que falta concluir, em ${unitName()}.`, `= total (${pct(m.total)}) − concluído (${pct(m.doneW)})`, `${m.leafCnt - m.doneCnt} de ${m.leafCnt} tarefa(s) ainda abertas.`])}
        ${kpi('Concluído', pct(m.doneW / m.total * 100) + '%', 'Concluído', [`${m.doneCnt} de ${m.leafCnt} tarefa(s) com status Concluída.`, 'Tarefa conta inteira ao ser concluída (sem progresso parcial).'])}
        ${kpi('Ritmo', pct(m.pace) + (ctx.unit === 'count' ? ' tarefas por dia' : ' dias por dia'), 'Ritmo atual', ['Quanto se conclui por dia, em média, desde o início do projeto.', `= concluído (${pct(m.doneW)}) ÷ dias decorridos`])}
        ${kpi('Término previsto', proj, 'Término previsto', ['Se o ritmo atual se mantiver, é quando o trabalho restante acaba.', `= hoje + restante ÷ ritmo`, `Prazo do projeto: ${fmt(isoOf(m.d1))}`], late ? 'st-bad' : '')}
      </div>
      <div class="bd-legend"><span><i class="l-ideal"></i>Ideal (ritmo constante até o prazo)</span><span><i class="l-real"></i>Real (concluído até hoje)</span><span><i class="l-proj"></i>Projeção (se o ritmo atual continuar)</span></div>
      <div id="bd-chart" class="bd-chart"></div>
      <div class="bd-notes small">
        ${m.estimated ? `<p class="bd-warn"><b>Histórico aproximado.</b> ${m.estimated} de ${m.doneCnt} tarefas concluídas ainda não têm o dia real da conclusão e usam o fim planejado, então a parte antiga do gráfico pode não ser exata. A partir de agora cada conclusão grava o dia real.</p>` : ''}
        <p>Não entram no cálculo: ${m.excluded.ms} marco(s), ${m.excluded.cancel} tarefa(s) cancelada(s) e as tarefas-pai (elas resumem as filhas).</p>
      </div>`;
    paint();
    const sel = host.querySelector('#bd-unit');
    if (sel) sel.onchange = () => { ctx.unit = sel.value; draw(); };
    if (window.ResizeObserver) {
      ctx.ro = new ResizeObserver(() => {
        if (!document.body.contains(host)) { ctx.ro.disconnect(); return; }
        if (Math.abs(host.clientWidth - ctx.w) > 8) paint();
      });
      ctx.ro.observe(host);
    }
  }

  function render(host, project, tasks) { ctx.host = host; ctx.project = project; ctx.tasks = tasks; draw(); }
  return { render };
})();
