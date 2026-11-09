/* SysAttack — Burndown: trabalho restante do projeto ao longo do tempo, comparado à linha ideal. */
const Burn = (() => {
  const { esc, fmt, todayIso, dayNum, isoOf } = SA;
  const ctx = { host: null, project: null, tasks: [], unit: 'days' };

  const day = v => (v ? String(v).slice(0, 10) : null);
  const span = t => Math.max(1, dayNum(day(t.end_date)) - dayNum(day(t.start_date)) + 1);
  const pct = n => Math.round(n * 10) / 10;

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

  function chart(m) {
    const W = 900, H = 330, L = 54, R = 44, T = 18, B = 40, pw = W - L - R, ph = H - T - B;
    const last = Math.max(m.axisEnd, m.projN && m.projN <= m.axisEnd + 400 ? m.projN : m.axisEnd);
    const span0 = Math.max(1, last - m.d0);
    const x = n => L + (n - m.d0) / span0 * pw, y = v => T + (1 - v / m.total) * ph;
    const tip = (title, lines) => Tip.attr(title, lines);
    const iso = n => isoOf(n);
    // grade e eixo Y
    let g = '';
    for (let i = 0; i <= 4; i++) {
      const v = m.total * i / 4;
      g += `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" class="bd-grid"/><text x="${L - 8}" y="${y(v) + 4}" text-anchor="end" class="bd-ax">${pct(v)}</text>`;
    }
    const ticks = 6;
    for (let i = 0; i <= ticks; i++) {
      const n = Math.round(m.d0 + span0 * i / ticks);
      g += `<text x="${x(n)}" y="${H - 14}" text-anchor="middle" class="bd-ax">${fmt(iso(n))}</text>`;
    }
    const ideal = `<line x1="${x(m.d0)}" y1="${y(m.total)}" x2="${x(m.d1)}" y2="${y(0)}" class="bd-ideal"/>`;
    // linha real em degraus
    let path = '';
    m.real.forEach((pt, i) => { path += (i ? `H${x(pt.n)}V${y(pt.rem)}` : `M${x(pt.n)},${y(pt.rem)}`); });
    const real = m.real.length ? `<path d="${path}" class="bd-real"/>` : '';
    // pontos com tooltip (um por dia, invisíveis, para dar hover em qualquer ponto)
    const hits = m.real.map(pt => {
      const id = m.idealAt(pt.n);
      const diff = pt.rem - id;
      return `<circle cx="${x(pt.n)}" cy="${y(pt.rem)}" r="7" class="bd-hit"${tip(fmt(iso(pt.n)), [
        `Restante real: ${pct(pt.rem)} ${unitName()}`, `Restante ideal: ${pct(id)} ${unitName()}`,
        diff > 0.05 ? `# ${pct(diff)} acima do ideal (atrasado)` : diff < -0.05 ? `# ${pct(-diff)} abaixo do ideal (adiantado)` : '# Exatamente no ritmo ideal'])}/>`;
    }).join('');
    let proj = '';
    if (m.projN) {
      const px = x(m.projN), cut = m.projN > last;
      proj = `<line x1="${x(m.todayN)}" y1="${y(m.remNow)}" x2="${px}" y2="${y(0)}" class="bd-proj"/><circle cx="${px}" cy="${y(0)}" r="5" class="bd-pend"${cut ? '' : ''}${tip('Previsão de término', [`${fmt(iso(m.projN))}`, `Mantendo o ritmo atual de ${pct(m.pace)} ${unitName()} por dia.`, m.projN > m.d1 ? `# ${m.projN - m.d1} dia(s) depois do prazo (${fmt(iso(m.d1))})` : '# Dentro do prazo'])}/>`;
    }
    const todayLine = m.todayN >= m.d0 && m.todayN <= last ? `<line x1="${x(m.todayN)}" x2="${x(m.todayN)}" y1="${T}" y2="${T + ph}" class="bd-today"/><text x="${x(m.todayN) + 4}" y="${T + 11}" class="bd-ax">hoje</text>` : '';
    const dl = `<line x1="${x(m.d1)}" x2="${x(m.d1)}" y1="${T}" y2="${T + ph}" class="bd-dead"/><text x="${x(m.d1) - 4}" y="${T + 11}" text-anchor="end" class="bd-ax">prazo</text>`;
    return `<svg class="bd-svg" viewBox="0 0 ${W} ${H}" role="img" aria-label="Gráfico de burndown do projeto">${g}${dl}${todayLine}${ideal}${proj}${real}${hits}</svg>`;
  }

  function draw() {
    const host = ctx.host, m = model();
    if (m.empty) { host.innerHTML = `<div class="empty"><strong>Sem tarefas para o burndown</strong>O burndown usa as tarefas do projeto (sem marcos e sem canceladas). Cadastre ou importe tarefas para acompanhar o trabalho restante.</div>`; return; }
    const diff = m.remNow - m.ideal, tol = m.total * 0.03;
    const state = m.notStarted ? ['grey', 'Projeto ainda não começou'] : m.remNow <= 0 ? ['ok', 'Trabalho concluído'] : diff > tol ? ['bad', 'Atrás do ideal'] : diff < -tol ? ['ok', 'Adiantado'] : ['ok', 'No ritmo'];
    const proj = m.remNow <= 0 ? '—' : m.projN ? fmt(isoOf(m.projN)) : 'sem ritmo ainda';
    const late = m.projN && m.projN > m.d1;
    const kpi = (label, val, tipT, tipL, cls = '') => `<div class="mini ${cls}">${Tip.wrap(`<span>${label}</span><b>${val}</b>`, tipT, tipL)}</div>`;
    host.innerHTML = `<div class="bd-bar"><label for="bd-unit">Medir por</label>
        <select id="bd-unit"><option value="days"${ctx.unit === 'days' ? ' selected' : ''}>Duração (dias)</option><option value="count"${ctx.unit === 'count' ? ' selected' : ''}>Quantidade de tarefas</option></select></div>
      <div class="mini-kpis">
        ${kpi('Situação', state[1], 'Situação frente ao ideal', ['Compara o trabalho restante real com o que deveria restar hoje se o projeto andasse em ritmo constante do início ao prazo.', `• Real hoje: ${pct(m.remNow)} ${unitName()}`, `• Ideal hoje: ${pct(m.ideal)} ${unitName()}`, '# Tolerância de 3% do total.'], 'st-' + state[0])}
        ${kpi('Restante', pct(m.remNow) + (ctx.unit === 'count' ? '' : ' d'), 'Trabalho restante', [`Soma do que falta concluir, em ${unitName()}.`, `= total (${pct(m.total)}) − concluído (${pct(m.doneW)})`, `${m.leafCnt - m.doneCnt} de ${m.leafCnt} tarefa(s) ainda abertas.`])}
        ${kpi('Concluído', pct(m.doneW / m.total * 100) + '%', 'Concluído', [`${m.doneCnt} de ${m.leafCnt} tarefa(s) com status Concluída.`, 'Tarefa conta inteira ao ser concluída (sem progresso parcial).'])}
        ${kpi('Ritmo', pct(m.pace) + (ctx.unit === 'count' ? ' tar/dia' : ' d/dia'), 'Ritmo atual', ['Quanto se conclui por dia, em média, desde o início do projeto.', `= concluído (${pct(m.doneW)}) ÷ dias decorridos`])}
        ${kpi('Término previsto', proj, 'Término previsto', ['Se o ritmo atual se mantiver, é quando o trabalho restante acaba.', `= hoje + restante ÷ ritmo`, `Prazo do projeto: ${fmt(isoOf(m.d1))}`], late ? 'st-bad' : '')}
      </div>
      <div class="bd-legend"><span><i class="l-ideal"></i>Ideal</span><span><i class="l-real"></i>Real</span><span><i class="l-proj"></i>Projeção</span></div>
      ${chart(m)}
      <div class="bd-notes small">
        ${m.estimated ? `<p class="bd-warn">⚠ ${m.estimated} tarefa(s) concluída(s) antes do registro da data de conclusão usam o <b>fim planejado</b> como aproximação; o histórico passado pode não ser exato. Novas conclusões registram o dia real.</p>` : ''}
        <p>Não entram no cálculo: ${m.excluded.ms} marco(s) e ${m.excluded.cancel} tarefa(s) cancelada(s). Tarefas-pai são resumos das filhas e também ficam de fora.</p>
      </div>`;
    const sel = host.querySelector('#bd-unit');
    if (sel) sel.onchange = () => { ctx.unit = sel.value; draw(); };
  }

  function render(host, project, tasks) { ctx.host = host; ctx.project = project; ctx.tasks = tasks; draw(); }
  return { render };
})();
