/* Gráfico de Gantt (DOM + SVG, sem bibliotecas): árvore à esquerda + linha do tempo à direita. */
const Gantt = (() => {
  const { esc, dayNum, todayIso, fmt } = SA;
  const ROW_H = 30, NAMES_W = 300;
  const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
  const MONTHS_LONG = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];
  const prefs = {}; // preferências por gráfico (sobrevivem ao recarregar a tela)

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const ymd = n => { const d = new Date(n * 86400000); return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate() }; };
  const monthStart = n => { const x = ymd(n); return Math.round(Date.UTC(x.y, x.m, 1) / 86400000); };
  const nextMonth = n => { const x = ymd(n); return Math.round(Date.UTC(x.y, x.m + 1, 1) / 86400000); };
  const dow = n => (n + 3) % 7; // 0 = segunda ... 6 = domingo
  const shortName = s => { const p = (s || '').trim().split(/\s+/).filter(Boolean); return p.length > 1 ? `${p[0]} ${p[p.length - 1][0]}.` : (p[0] || ''); };
  const days = n => `${n} ${n === 1 ? 'dia' : 'dias'}`;

  const BTN = {
    zin: '<svg class="ic" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="M20 20l-4.5-4.5M11 8v6M8 11h6"/></svg>',
    zout: '<svg class="ic" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="M20 20l-4.5-4.5M8 11h6"/></svg>',
    print: '<svg class="ic" viewBox="0 0 24 24"><path d="M7 9V4h10v5M7 17H5a1 1 0 01-1-1v-5a2 2 0 012-2h12a2 2 0 012 2v5a1 1 0 01-1 1h-2"/><rect x="7" y="14" width="10" height="6"/></svg>',
    gear: '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 00-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 00-2-1.2L14.2 3h-4l-.4 2.6a7 7 0 00-2 1.2l-2.3-.9-2 3.4 2 1.5A7 7 0 005 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-.9c.6.5 1.3.9 2 1.2l.4 2.6h4l.4-2.6c.7-.3 1.4-.7 2-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z"/></svg>'
  };

  function render(host, rows, opts = {}) {
    host._g = { rows, opts };
    draw(host, 'init');
  }

  function draw(host, reason) {
    const { rows, opts } = host._g;
    const key = opts.key || 'default';
    const P = prefs[key] || (prefs[key] = { ppd: null, collapsed: new Set(), showDone: true, labels: true, deps: true, menu: false });
    if (!rows.length) {
      host.innerHTML = '<div class="empty"><strong>Nenhuma tarefa para exibir</strong>Cadastre tarefas com datas de início e fim para montar o cronograma.</div>';
      return;
    }
    const old = host.querySelector('.gantt');
    const keep = old && reason !== 'init' && reason !== 'zoom' ? { l: old.scrollLeft, t: old.scrollTop } : null;

    // ---- linhas visíveis (respeita recolher/expandir e "mostrar concluídas")
    const byKey = new Map(rows.map(r => [r.key, r]));
    const vis = rows.filter(r => {
      if (r.kind !== 'project' && !P.showDone && r.status === 'CONCLUIDA') return false;
      let p = r.parentKey;
      while (p) { if (P.collapsed.has(p)) return false; const pr = byKey.get(p); p = pr && pr.parentKey; }
      return true;
    });
    const idx = new Map(vis.map((r, i) => [r.key, i]));

    // ---- escala de tempo
    const first = Math.min(...vis.map(r => dayNum(r.start))), last = Math.max(...vis.map(r => dayNum(r.end)));
    const today = dayNum(todayIso());
    const avail = Math.max(600, (host.clientWidth || 1000) - NAMES_W - 24);
    const side = P.labels ? 230 : 30;
    const ppd = P.ppd || clamp((avail - side * 2) / (last - first + 10), 1.2, 28);
    P._ppd = ppd;
    let lo = first - Math.ceil(side / ppd) - 5, hi = last + Math.ceil(side / ppd) + 10;
    if (today >= lo - 90 && today <= hi + 90) { lo = Math.min(lo, today - 3); hi = Math.max(hi, today + 10); }
    lo = monthStart(lo); hi = nextMonth(hi) - 1;
    const W = Math.round((hi - lo + 1) * ppd), X = n => (n - lo) * ppd;

    // ---- cabeçalho
    let top = '', bottom = '';
    if (ppd >= 6) {
      for (let n = lo; n <= hi;) { const nx = nextMonth(n), w = (nx - n) * ppd, x = ymd(n);
        top += `<div class="g-h" style="width:${w}px">${w > 90 ? `${MONTHS_LONG[x.m]} ${x.y}` : `${MONTHS[x.m]} ${x.y}`}</div>`; n = nx; }
      if (ppd >= 20) {
        for (let n = lo; n <= hi; n++) bottom += `<div class="g-h2${dow(n) > 4 ? ' we' : ''}" style="width:${ppd}px">${ymd(n).d}</div>`;
      } else {
        for (let n = lo; n <= hi;) { const nx = Math.min(hi + 1, n + 7 - dow(n)), w = (nx - n) * ppd;
          bottom += `<div class="g-h2" style="width:${w}px">${w >= 22 ? ymd(n).d : ''}</div>`; n = nx; }
      }
    } else {
      for (let n = lo; n <= hi;) { const x = ymd(n), nx = Math.round(Date.UTC(x.y + 1, 0, 1) / 86400000), w = (Math.min(nx, hi + 1) - n) * ppd;
        top += `<div class="g-h" style="width:${w}px">${x.y}</div>`; n = nx; }
      for (let n = lo; n <= hi;) { const nx = nextMonth(n), w = (nx - n) * ppd;
        bottom += `<div class="g-h2" style="width:${w}px">${w >= 22 ? MONTHS[ymd(n).m] : ''}</div>`; n = nx; }
    }

    // ---- grade vertical
    let grid = '';
    for (let n = lo; n <= hi; n = nextMonth(n)) grid += `<div class="g-vl${ymd(n).m === 0 ? ' yr' : ''}" style="left:${X(n)}px"></div>`;
    if (ppd >= 6) for (let n = lo; n <= hi; n++) if (dow(n) === 5) grid += `<div class="g-we" style="left:${X(n)}px;width:${Math.min(2, hi - n + 1) * ppd}px"></div>`;
    if (ppd >= 6 && ppd < 20) for (let n = lo; n <= hi; n++) if (dow(n) === 0) grid += `<div class="g-wl" style="left:${X(n)}px"></div>`;
    const bg = [`repeating-linear-gradient(to bottom, transparent 0, transparent ${ROW_H - 1}px, #efefef ${ROW_H - 1}px, #efefef ${ROW_H}px)`];
    if (ppd >= 20) bg.push(`repeating-linear-gradient(to right, transparent 0, transparent ${ppd - 1}px, #f0f0f0 ${ppd - 1}px, #f0f0f0 ${ppd}px)`);

    // ---- linhas: nomes à esquerda e barras à direita
    let names = '', bands = '', marks = '';
    const geo = []; // geometria por linha (para as setas de dependência)
    vis.forEach((r, i) => {
      const s = dayNum(r.start), e = dayNum(r.end), cy = i * ROW_H + ROW_H / 2;
      const hasKids = r.hasChildren, collapsed = P.collapsed.has(r.key);
      const dia = r.kind === 'milestone' ? '<i class="g-dia" aria-hidden="true"></i>' : '';
      names += `<div class="g-name k-${r.kind}" data-i="${i}" style="padding-left:${8 + (r.level || 0) * 18}px">`
        + (hasKids ? `<button type="button" class="g-chev${collapsed ? '' : ' open'}" data-toggle="${esc(r.key)}" aria-label="${collapsed ? 'Expandir' : 'Recolher'}"></button>` : '<span class="g-chev sp"></span>')
        + `${dia}<span class="g-nm" title="${esc(r.name)}">${esc(r.name)}</span><span class="g-pc">${r.progress}%</span></div>`;
      if (r.kind === 'project') bands += `<div class="g-band" style="top:${i * ROW_H}px;height:${ROW_H}px"></div>`;

      const dur = e - s + 1, late = r.late, cls = r.status === 'CANCELADA' ? 'cancel' : r.status === 'CONCLUIDA' ? 'done' : late ? 'late' : '';
      const who = shortName(r.sub);
      const tip = `${r.name} · ${fmt(r.start)}${s === e ? '' : ' a ' + fmt(r.end)} · ${r.progress}%${late ? ' · atrasada' : ''}`;
      if (r.kind === 'milestone') {
        const cx = X(e + 0.5);
        geo.push({ l: cx - 9, r: cx + 9, cy });
        marks += `<div class="g-ms ${cls}" data-i="${i}" title="${esc(tip)}" style="left:${cx - 8}px;top:${cy - 8}px"></div>`;
        if (P.labels) {
          marks += `<span class="g-lbl l" style="right:${W - cx + 15}px;top:${cy - 9}px">${esc(r.name)}</span>`
                 + `<span class="g-lbl r" style="left:${cx + 15}px;top:${cy - 9}px">${esc(who)}${who ? ' · ' : ''}${fmt(r.end)}</span>`;
        }
      } else if (r.kind === 'project' || r.kind === 'parent') {
        const x = X(s), w = Math.max(dur * ppd, 6);
        geo.push({ l: x, r: x + w, cy });
        marks += `<div class="g-sum ${r.kind}" data-i="${i}" title="${esc(tip)}" style="left:${x}px;top:${cy - (r.kind === 'project' ? 6 : 4)}px;width:${w}px"></div>`;
        if (P.labels && r.kind === 'parent') marks += `<span class="g-lbl l" style="right:${W - x + 6}px;top:${cy - 9}px">${esc(r.name)}</span>`
          + `<span class="g-lbl r" style="left:${x + w + 6}px;top:${cy - 9}px">${esc(who)}${who ? ' ' : ''}(${days(dur)})</span>`;
      } else {
        const x = X(s), w = Math.max(dur * ppd, 4);
        geo.push({ l: x, r: x + w, cy });
        marks += `<div class="g-bar ${cls}" data-i="${i}" title="${esc(tip)}" style="left:${x}px;top:${cy - 8}px;width:${w}px">`
          + `<div class="g-fill" style="width:${r.progress}%"></div>${w > 46 && r.progress ? `<span class="g-pct">${r.progress}%</span>` : ''}</div>`;
        if (P.labels) marks += `<span class="g-lbl l" style="right:${W - x + 6}px;top:${cy - 9}px">${esc(r.name)}</span>`
          + `<span class="g-lbl r" style="left:${x + w + 6}px;top:${cy - 9}px">${esc(who)}${who ? ' ' : ''}(${days(dur)})</span>`;
      }
    });

    // ---- setas de dependência
    let deps = '';
    if (P.deps) vis.forEach((r, i) => {
      const j = r.dep != null ? idx.get(r.dep) : undefined;
      if (j === undefined) return;
      const a = geo[j], b = geo[i], x1 = a.r, y1 = a.cy, x2 = b.l, y2 = b.cy;
      const path = x2 >= x1 + 12
        ? `M${x1},${y1} H${x1 + 8} V${y2} H${x2 - 1}`
        : `M${x1},${y1} H${x1 + 8} V${y1 + (y2 > y1 ? 1 : -1) * ROW_H / 2} H${x2 - 10} V${y2} H${x2 - 1}`;
      deps += `<path d="${path}"/><polygon points="${x2},${y2} ${x2 - 6},${y2 - 3.5} ${x2 - 6},${y2 + 3.5}"/>`;
    });

    const todayLine = today >= lo && today <= hi ? `<div class="g-today" style="left:${X(today + 0.5)}px"></div>` : '';
    const H = vis.length * ROW_H;
    const hasParents = rows.some(r => r.hasChildren && r.kind !== 'project');
    const anyCollapsed = [...P.collapsed].some(k => byKey.has(k));

    host.innerHTML =
      `<div class="g-tools"><div class="g-menu-wrap">
        <button type="button" class="gbtn" data-g="menu" aria-expanded="${P.menu}">${BTN.gear}Configurações</button>
        ${P.menu ? `<div class="g-menu" role="group" aria-label="Configurações do gráfico">
          <label><input type="checkbox" data-opt="labels" ${P.labels ? 'checked' : ''}> Rótulos ao lado das barras</label>
          <label><input type="checkbox" data-opt="deps" ${P.deps ? 'checked' : ''}> Setas de dependência</label>
          <label><input type="checkbox" data-opt="showDone" ${P.showDone ? 'checked' : ''}> Mostrar tarefas concluídas</label></div>` : ''}
        </div>
        <button type="button" class="gbtn" data-g="done">${P.showDone ? 'Ocultar concluídos' : 'Mostrar concluídos'}</button>
        ${hasParents ? `<button type="button" class="gbtn" data-g="fold">${anyCollapsed ? 'Expandir tudo' : 'Recolher tudo'}</button>` : ''}
        <button type="button" class="gbtn icon" data-g="zin" title="Aproximar" aria-label="Aproximar">${BTN.zin}</button>
        <button type="button" class="gbtn icon" data-g="zout" title="Afastar" aria-label="Afastar">${BTN.zout}</button>
        <button type="button" class="gbtn" data-g="fit">Mostrar tudo</button>
        <button type="button" class="gbtn icon" data-g="print" title="Imprimir" aria-label="Imprimir">${BTN.print}</button></div>
      <div class="gantt" tabindex="0" aria-label="Gráfico de Gantt" style="--row-h:${ROW_H}px;--names-w:${NAMES_W}px"><div class="g-inner">
        <div class="g-head"><div class="g-corner"><b>${esc(opts.title || 'Cronograma')}</b><small>${esc(opts.subtitle || '')}</small></div>
          <div class="g-scale" style="width:${W}px"><div class="g-r1">${top}</div><div class="g-r2">${bottom}</div></div></div>
        <div class="g-body"><div class="g-names">${names}</div>
          <div class="g-chart" style="width:${W}px;height:${H}px;background-image:${bg.join(',')}">${grid}${bands}${todayLine}
            <svg class="g-deps" width="${W}" height="${H}">${deps}</svg>${marks}</div></div>
      </div></div>
      <div class="g-legend"><span><i style="background:#f9cdb0;border-left:6px solid var(--orange)"></i>Em andamento</span>
        <span><i style="background:var(--ink)"></i>Concluída</span><span><i style="background:var(--red)"></i>Atrasada</span>
        <span><i class="dia"></i>Marco</span><span><i style="background:#7d7d7d;height:5px;vertical-align:2px"></i>Resumo (projeto / tarefa pai)</span></div>`;

    // ---- eventos
    const again = why => draw(host, why);
    host.querySelectorAll('[data-g]').forEach(b => b.onclick = () => {
      switch (b.dataset.g) {
        case 'menu': P.menu = !P.menu; return again('menu');
        case 'done': P.showDone = !P.showDone; return again('opt');
        case 'fold':
          if (anyCollapsed) P.collapsed.clear(); else rows.forEach(r => { if (r.hasChildren && r.kind !== 'project') P.collapsed.add(r.key); });
          return again('opt');
        case 'zin': P.ppd = clamp(P._ppd * 1.5, 0.8, 48); return again('zoom');
        case 'zout': P.ppd = clamp(P._ppd / 1.5, 0.8, 48); return again('zoom');
        case 'fit': P.ppd = null; return again('zoom');
        case 'print': return window.print();
      }
    });
    host.querySelectorAll('[data-opt]').forEach(c => c.onchange = () => { P[c.dataset.opt] = c.checked; again('opt'); });
    host.querySelectorAll('[data-toggle]').forEach(b => b.onclick = e => {
      e.stopPropagation();
      const k = b.dataset.toggle; P.collapsed.has(k) ? P.collapsed.delete(k) : P.collapsed.add(k); again('fold');
    });
    host.querySelectorAll('.g-name[data-i], .g-bar[data-i], .g-ms[data-i], .g-sum[data-i]').forEach(el =>
      el.onclick = () => opts.onRow && opts.onRow(vis[+el.dataset.i]));

    const box = host.querySelector('.gantt');
    if (keep) { box.scrollLeft = keep.l; box.scrollTop = keep.t; }
    else box.scrollLeft = Math.max(0, X(first) - side - 20);
  }

  return { render };
})();
