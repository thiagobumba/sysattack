/* SysAttack — Rede PERT / caminho crítico (CPM): tarefas ligadas por dependência, folga e sequência que define o prazo. */
const Pert = (() => {
  const { esc, fmt, dayNum, isoOf } = SA;
  const day = v => (v ? String(v).slice(0, 10) : null);
  const ctx = { host: null, project: null, tasks: [], only: false, fit: false };

  function compute() {
    const all = ctx.tasks, byId = new Map(all.map(t => [t.id, t]));
    const kids = new Map();
    all.forEach(t => { if (t.parent_id) (kids.get(t.parent_id) || kids.set(t.parent_id, []).get(t.parent_id)).push(t); });
    const live = t => t.status !== 'CANCELADA';
    const leaves = all.filter(t => !t.child_count && live(t));
    const leafIds = new Set(leaves.map(t => t.id));
    // uma dependência em tarefa-pai vale para todas as filhas dela
    const resolve = id => {
      const t = byId.get(id); if (!t) return [];
      if (t.child_count) return (kids.get(id) || []).filter(k => leafIds.has(k.id)).map(k => k.id);
      return leafIds.has(id) ? [id] : [];
    };
    const preds = new Map(leaves.map(t => [t.id, new Set()]));
    leaves.forEach(t => {
      [t.depends_on, t.parent_id && byId.get(t.parent_id) ? byId.get(t.parent_id).depends_on : null]
        .filter(Boolean).forEach(d => resolve(d).forEach(p => { if (p !== t.id) preds.get(t.id).add(p); }));
    });
    const succs = new Map(leaves.map(t => [t.id, new Set()]));
    preds.forEach((ps, id) => ps.forEach(p => succs.get(p).add(id)));
    const dur = t => (t.is_milestone ? 0 : Math.max(1, dayNum(day(t.end_date)) - dayNum(day(t.start_date)) + 1));
    // ordem topológica (Kahn); o que sobrar em ciclo fica de fora
    const indeg = new Map(leaves.map(t => [t.id, preds.get(t.id).size]));
    const order = [], q = leaves.filter(t => !indeg.get(t.id)).map(t => t.id);
    while (q.length) { const id = q.shift(); order.push(id); succs.get(id).forEach(s => { indeg.set(s, indeg.get(s) - 1); if (!indeg.get(s)) q.push(s); }); }
    const cyc = leaves.length - order.length;
    const N = new Map();
    order.forEach(id => {
      const t = byId.get(id); let es = 0, lvl = 0;
      preds.get(id).forEach(p => { if (N.has(p)) { es = Math.max(es, N.get(p).ef); lvl = Math.max(lvl, N.get(p).lvl + 1); } });
      N.set(id, { id, t, d: dur(t), es, ef: es + dur(t), lvl });
    });
    const linked = id => preds.get(id).size || succs.get(id).size;
    const net = order.filter(linked), isolated = order.filter(id => !linked(id));
    const end = net.length ? Math.max(...net.map(id => N.get(id).ef)) : 0;
    [...net].reverse().forEach(id => {
      const n = N.get(id); let lf = end;
      succs.get(id).forEach(s => { if (N.has(s)) lf = Math.min(lf, N.get(s).ls); });
      n.lf = lf; n.ls = lf - n.d; n.slack = n.ls - n.es; n.crit = n.slack === 0;
    });
    return { N, preds, succs, net, isolated, end, cyc, leaves: leaves.length };
  }

  const NW = 168, NH = 66, GX = 52, GY = 16, PAD = 16;

  function diagram(m) {
    const ids = ctx.only ? m.net.filter(id => m.N.get(id).crit) : m.net;
    const set = new Set(ids), levels = new Map();
    ids.forEach(id => { const n = m.N.get(id); (levels.get(n.lvl) || levels.set(n.lvl, []).get(n.lvl)).push(n); });
    const keys = [...levels.keys()].sort((a, b) => a - b), pos = new Map();
    let maxRows = 0;
    keys.forEach((k, ci) => {
      const col = levels.get(k).sort((a, b) => a.es - b.es || a.id - b.id);
      maxRows = Math.max(maxRows, col.length);
      col.forEach((n, r) => pos.set(n.id, { x: PAD + ci * (NW + GX), y: PAD + r * (NH + GY) }));
    });
    const W = PAD * 2 + keys.length * (NW + GX) - GX, H = PAD * 2 + maxRows * (NH + GY) - GY;
    let edges = '';
    ids.forEach(id => m.succs.get(id).forEach(s => {
      if (!set.has(s)) return;
      const a = pos.get(id), b = pos.get(s), x1 = a.x + NW, y1 = a.y + NH / 2, x2 = b.x, y2 = b.y + NH / 2, mx = (x1 + x2) / 2;
      const crit = m.N.get(id).crit && m.N.get(s).crit && m.N.get(s).es === m.N.get(id).ef;
      edges += `<path d="M${x1},${y1}C${mx},${y1} ${mx},${y2} ${x2 - 5},${y2}" class="pt-edge${crit ? ' crit' : ''}" marker-end="url(#pt-arr${crit ? 'c' : ''})"/>`;
    }));
    const nodes = ids.map(id => {
      const n = m.N.get(id), p = pos.get(id), t = n.t, nm = t.name.length > 24 ? t.name.slice(0, 23) + '…' : t.name;
      const tip = Tip.attr(t.name, [t.responsible ? `Responsável: ${t.responsible}` : 'Sem responsável',
        `Duração: ${n.d} dia(s)${t.is_milestone ? ' (marco)' : ''}`,
        `• Início mais cedo: dia ${n.es} · Término mais cedo: dia ${n.ef}`, `• Início mais tarde: dia ${n.ls} · Término mais tarde: dia ${n.lf}`,
        `= folga: ${n.slack} dia(s)`, n.crit ? '# Caminho crítico: qualquer atraso aqui atrasa o projeto.' : `# Pode atrasar até ${n.slack} dia(s) sem afetar o prazo.`]);
      return `<g class="pt-node${n.crit ? ' crit' : ''}" transform="translate(${p.x},${p.y})"${tip}>
        <rect width="${NW}" height="${NH}" rx="3"/><text x="8" y="18" class="pt-name">${esc(nm)}</text>
        <line x1="0" x2="${NW}" y1="26" y2="26"/>
        <text x="8" y="42" class="pt-n">ES ${n.es}</text><text x="${NW / 2}" y="42" text-anchor="middle" class="pt-n">dur ${n.d}</text><text x="${NW - 8}" y="42" text-anchor="end" class="pt-n">EF ${n.ef}</text>
        <text x="8" y="58" class="pt-n">LS ${n.ls}</text><text x="${NW / 2}" y="58" text-anchor="middle" class="pt-n b">folga ${n.slack}</text><text x="${NW - 8}" y="58" text-anchor="end" class="pt-n">LF ${n.lf}</text></g>`;
    }).join('');
    return `<div class="pt-wrap"><svg class="pt-svg" ${ctx.fit ? 'style="width:100%;height:auto"' : `width="${W}" height="${H}"`} viewBox="0 0 ${W} ${H}" role="img" aria-label="Rede PERT do projeto">
      <defs><marker id="pt-arr" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0L8,4L0,8z" fill="#8a8a8a"/></marker>
      <marker id="pt-arrc" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0L8,4L0,8z" fill="#b3261e"/></marker></defs>${edges}${nodes}</svg></div>`;
  }

  function draw() {
    const host = ctx.host, m = compute(), p = ctx.project;
    if (!m.net.length) {
      host.innerHTML = `<div class="empty"><strong>Nenhuma dependência entre tarefas</strong>O caminho crítico nasce das dependências ("Depende de" no cadastro da tarefa). ${m.leaves} tarefa(s) do projeto ainda não têm ligação com outra. Cadastre as dependências para ver a rede.${m.cyc ? ` Há ${m.cyc} tarefa(s) em ciclo de dependência.` : ''}</div>`;
      return;
    }
    const crit = m.net.filter(id => m.N.get(id).crit).sort((a, b) => m.N.get(a).es - m.N.get(b).es);
    const planned = p.start_date && p.end_date ? dayNum(day(p.end_date)) - dayNum(day(p.start_date)) + 1 : null;
    const startIso = day(p.start_date), endIso = startIso ? isoOf(dayNum(startIso) + m.end - 1) : null;
    const kpi = (l, v, tt, tl, cls = '') => `<div class="mini ${cls}">${Tip.wrap(`<span>${l}</span><b>${v}</b>`, tt, tl)}</div>`;
    const over = planned != null && m.end > planned;
    const rows = [...m.net].sort((a, b) => m.N.get(a).slack - m.N.get(b).slack || m.N.get(a).es - m.N.get(b).es).slice(0, 40).map(id => {
      const n = m.N.get(id); return `<tr class="${n.crit ? 'pt-crit' : ''}"><td>${esc(n.t.name)}</td><td>${esc(n.t.responsible || '—')}</td><td>${n.d}</td><td>${n.es}</td><td>${n.ef}</td><td>${n.slack}</td></tr>`;
    }).join('');
    host.innerHTML = `<div class="mini-kpis wrapv">
        ${kpi('Duração pela rede', m.end + ' dias', 'Duração pela rede', ['Soma dos dias do caminho mais longo entre as tarefas ligadas por dependência.', 'É o menor prazo possível se nada atrasar e houver gente para tudo em paralelo.', endIso ? `Terminaria em ${fmt(endIso)} se começasse em ${fmt(startIso)}.` : ''], over ? 'st-bad' : '')}
        ${kpi('Prazo planejado', planned != null ? planned + ' dias' : '—', 'Prazo planejado', ['Dias entre o início e o fim cadastrados no projeto.', planned != null ? `= ${m.end <= planned ? 'folga' : 'estouro'} de ${Math.abs(planned - m.end)} dia(s) frente à rede.` : ''])}
        ${kpi('Tarefas críticas', `${crit.length} de ${m.net.length}`, 'Tarefas críticas', ['Têm folga zero: se atrasarem um dia, o projeto atrasa um dia.', 'Formam o caminho crítico (em vermelho).'])}
        ${kpi('Sem dependência', String(m.isolated.length), 'Tarefas sem ligação', ['Não dependem de nenhuma tarefa e nenhuma depende delas, então ficam fora da rede.', 'Quanto mais ligações cadastradas, mais confiável é o caminho crítico.'])}
      </div>
      <div class="bd-bar"><label><input type="checkbox" id="pt-only"${ctx.only ? ' checked' : ''}> Mostrar só o caminho crítico</label>
        <button type="button" class="btn btn-sm" id="pt-fit">${ctx.fit ? 'Tamanho real' : 'Ajustar à largura'}</button>
        <span class="small" style="margin-left:auto">Dias contados a partir do início do projeto (dia 0). ES/EF: mais cedo · LS/LF: mais tarde.</span></div>
      ${diagram(m)}
      <h3 class="pt-h">Folgas (as 40 mais apertadas)</h3>
      <table class="tbl pt-tbl"><thead><tr><th>Tarefa</th><th>Responsável</th><th>Dur.</th><th>ES</th><th>EF</th><th>Folga</th></tr></thead><tbody>${rows}</tbody></table>
      <div class="bd-notes small"><p>Todas as dependências são do tipo término-início (a sucessora começa quando a anterior termina). Marcos têm duração zero; tarefas-pai e canceladas ficam de fora; dependência em tarefa-pai vale para todas as filhas.</p>
        ${m.cyc ? `<p class="bd-warn">⚠ ${m.cyc} tarefa(s) em ciclo de dependência foram ignoradas.</p>` : ''}</div>`;
    const c = host.querySelector('#pt-only'); if (c) c.onchange = () => { ctx.only = c.checked; draw(); };
    const f = host.querySelector('#pt-fit'); if (f) f.onclick = () => { ctx.fit = !ctx.fit; draw(); };
  }

  function render(host, project, tasks) { ctx.host = host; ctx.project = project; ctx.tasks = tasks; draw(); }
  return { render };
})();
