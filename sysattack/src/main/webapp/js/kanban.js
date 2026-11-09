/* SysAttack — Quadro Kanban do projeto: as colunas são as situações das tarefas; arrastar um cartão muda a situação. */
const Kan = (() => {
  const { esc, fmt } = SA;
  const A = () => window.App;

  const COLS = [
    { k: 'A_FAZER', label: 'A fazer', cls: '', tip: 'Ainda não começou.' },
    { k: 'EM_ANDAMENTO', label: 'Em andamento', cls: 'o', tip: 'Em execução agora.' },
    { k: 'BLOQUEADA', label: 'Bloqueada', cls: 'r', tip: 'Parada por algum impedimento (cliente, fornecedor, decisão…).' },
    { k: 'CONCLUIDA', label: 'Concluída', cls: 'g', tip: 'Terminada. Mover para cá leva o progresso a 100%.' }
  ];
  const st = { who: 'ALL', parent: 'ALL', showCanceled: false, moreDone: false, host: null, drag: null };
  const DONE_LIMIT = 12;

  const initials = n => (n || '?').split(/[\s,]+/).filter(Boolean).slice(0, 2).map(x => x[0].toUpperCase()).join('') || '?';
  const people = t => (t.responsible || '').split(',').map(x => x.trim()).filter(Boolean);

  function mine(t) {
    if (st.who === 'ALL') return true;
    const names = people(t).map(x => x.toLowerCase());
    if (st.who === 'NONE') return names.length === 0;
    const target = st.who === 'ME' ? ((A().me && A().me.name) || '').toLowerCase() : st.who.slice(2).toLowerCase();
    return names.includes(target);
  }

  function chips(t, byId) {
    const out = [];
    const d = A().dayDiff(t.end_date);
    if (t.status === 'CONCLUIDA') out.push(`<span class="kb-t ok">✔ ${fmt(t.completed_at || t.end_date)}</span>`);
    else if (t.status !== 'CANCELADA') out.push(d < 0 ? `<span class="kb-t late">atrasada ${-d} d</span>` : `<span class="kb-t${d <= 3 ? ' soon' : ''}">${d === 0 ? 'vence hoje' : 'até ' + fmt(t.end_date)}</span>`);
    const dep = t.depends_on && byId[t.depends_on];
    if (dep && t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA' && dep.status !== 'CONCLUIDA' && dep.status !== 'CANCELADA')
      out.push(`<span class="kb-t lock" title="Só deve começar depois que “${esc(dep.name)}” terminar">🔒 aguarda “${esc(dep.name)}”</span>`);
    const par = t.parent_id && byId[t.parent_id];
    if (par) out.push(`<span class="kb-t par" title="Tarefa filha de ${esc(par.name)}">${esc(par.name)}</span>`);
    if (t.is_important) out.push('<span class="kb-t imp" title="Marcada como importante">★</span>');
    return out.join('');
  }

  function card(t, byId, idx) {
    const who = people(t);
    const prev = COLS[idx - 1], next = COLS[idx + 1];
    return `<article class="kb-card${t.status === 'CANCELADA' ? ' canceled' : ''}" draggable="true" tabindex="0" data-id="${t.id}" data-st="${t.status}">
      <button type="button" class="kb-name" data-action="edit-task" data-id="${t.id}" title="Abrir a tarefa">${t.is_milestone ? '<i class="dia-ic" title="Marco"></i>' : ''}${esc(t.name)}</button>
      <div class="kb-meta">${who.length ? `<span class="kb-av" title="${esc(t.responsible)}">${esc(initials(who[0]))}${who.length > 1 ? `<small>+${who.length - 1}</small>` : ''}</span>` : '<span class="kb-av ph" title="Sem responsável">?</span>'}${chips(t, byId)}</div>
      ${t.status === 'EM_ANDAMENTO' ? `<div class="kb-pb" title="Progresso ${t.progress}%"><i style="width:${t.progress}%"></i></div>` : ''}
      <div class="kb-mv">${prev ? `<button type="button" data-action="kb-move" data-id="${t.id}" data-to="${prev.k}" title="Mover para ${prev.label}" aria-label="Mover para ${prev.label}">‹</button>` : '<span></span>'}${next ? `<button type="button" data-action="kb-move" data-id="${t.id}" data-to="${next.k}" title="Mover para ${next.label}" aria-label="Mover para ${next.label}">›</button>` : ''}</div>
    </article>`;
  }

  function mount(host) {
    st.host = host;
    const a = A(), tasks = a.view.tasks;
    const names = new Set(); tasks.forEach(t => people(t).forEach(x => names.add(x)));
    const parents = tasks.filter(t => t.child_count);
    host.innerHTML = `<div class="kb-tools"><label class="small" for="kb-who">Responsável</label><select id="kb-who" aria-label="Responsável"><option value="ALL">Todos</option>${a.me && a.me.name ? '<option value="ME">Minhas tarefas</option>' : ''}<option value="NONE">Sem responsável</option>${[...names].sort((x, y) => x.localeCompare(y)).map(n => `<option value="p:${esc(n)}">${esc(n)}</option>`).join('')}</select>
      ${parents.length ? `<label class="small" for="kb-par">Tarefa pai</label><select id="kb-par" aria-label="Tarefa pai"><option value="ALL">Todas</option>${parents.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select>` : ''}
      <label class="small kb-chk"><input type="checkbox" id="kb-can"> Mostrar canceladas</label></div>
      <div id="kb-board"></div>
      <p class="hint" style="margin-top:12px">Arraste um cartão para outra coluna (ou use ‹ ›, ou as setas do teclado com o cartão selecionado) para mudar a situação. Tarefas-pai não aparecem como cartão: a situação delas é calculada pelas filhas.</p>`;
    const w = document.getElementById('kb-who'), p = document.getElementById('kb-par'), c = document.getElementById('kb-can');
    w.value = st.who; if (w.value !== st.who) { st.who = 'ALL'; w.value = 'ALL'; }
    if (p) { p.value = st.parent; if (p.value !== st.parent) { st.parent = 'ALL'; p.value = 'ALL'; } p.addEventListener('change', e => { st.parent = e.target.value; draw(); }); }
    c.checked = st.showCanceled;
    w.addEventListener('change', e => { st.who = e.target.value; draw(); });
    c.addEventListener('change', e => { st.showCanceled = e.target.checked; draw(); });
    draw();
  }

  function draw() {
    const board = document.getElementById('kb-board'); if (!board) return;
    const tasks = A().view.tasks, byId = Object.fromEntries(tasks.map(t => [t.id, t]));
    const leaf = tasks.filter(t => !t.child_count && mine(t) && (st.parent === 'ALL' || String(t.parent_id) === st.parent));
    if (!leaf.length) { board.innerHTML = '<div class="empty"><strong>Nenhuma tarefa para mostrar</strong>Cadastre tarefas na aba Tarefas ou troque os filtros.</div>'; return; }
    const cols = COLS.map((c, i) => {
      let list = leaf.filter(t => t.status === c.k).sort((x, y) => c.k === 'CONCLUIDA' ? String(y.completed_at || y.end_date).localeCompare(String(x.completed_at || x.end_date)) : x.end_date.localeCompare(y.end_date) || x.id - y.id);
      const total = list.length;
      if (c.k === 'CONCLUIDA' && !st.moreDone) list = list.slice(0, DONE_LIMIT);
      return `<section class="kb-col" data-col="${c.k}" aria-label="${c.label}"><header class="kb-ch ${c.cls}" title="${esc(c.tip)}"><b>${c.label}</b><span class="cnt">${total}</span></header>
        <div class="kb-cards">${list.map(t => card(t, byId, i)).join('') || '<p class="kb-empty">Solte um cartão aqui.</p>'}
        ${c.k === 'CONCLUIDA' && total > DONE_LIMIT ? `<button type="button" class="kb-more" data-action="kb-more">${st.moreDone ? 'Mostrar só as mais recentes' : `Ver as outras ${total - DONE_LIMIT}`}</button>` : ''}</div></section>`;
    }).join('');
    const canceled = st.showCanceled ? leaf.filter(t => t.status === 'CANCELADA') : [];
    board.innerHTML = `<div class="kb-board">${cols}</div>` + (canceled.length ? `<h4 class="kb-sub">Canceladas (${canceled.length})</h4><div class="kb-cancel">${canceled.map(t => card(t, byId, -1)).join('')}</div>` : '');
  }

  async function move(id, to) {
    const a = A(), t = a.view.tasks.find(x => x.id === id);
    if (!t || t.status === to) return;
    const byId = Object.fromEntries(a.view.tasks.map(x => [x.id, x]));
    const dep = t.depends_on && byId[t.depends_on];
    if (dep && to !== 'A_FAZER' && to !== 'BLOQUEADA' && dep.status !== 'CONCLUIDA' && dep.status !== 'CANCELADA'
      && !confirm(`Esta tarefa depende de “${dep.name}”, que ainda não terminou. Mover mesmo assim?`)) return;
    let progress = t.progress;
    if (to !== 'CONCLUIDA' && progress === 100) progress = to === 'A_FAZER' ? 0 : 50; // reaberta: não pode ficar com 100%
    if (to === 'A_FAZER' && t.status === 'CANCELADA') progress = Math.min(progress, 99);
    const body = { name: t.name, description: t.description || '', responsible: t.responsible || '', start_date: t.start_date, end_date: t.end_date,
      progress, status: to, depends_on: t.depends_on || null, parent_id: t.parent_id || null, is_milestone: !!t.is_milestone, is_important: !!t.is_important };
    try {
      await a.api('PUT', 'tasks/' + id, body);
      a.view.tasks = await a.api('GET', `projects/${a.view.projectId}/tasks`);
      a.toast(`“${t.name}” movida para ${COLS.find(c => c.k === to).label}.`);
    } catch (ex) { a.toast(ex.message || 'Não foi possível mover a tarefa.'); }
    draw();
  }

  function onClick(e) {
    const el = e.target.closest('[data-action]'); if (!el) return false;
    if (el.dataset.action === 'kb-move') { e.stopPropagation(); move(Number(el.dataset.id), el.dataset.to); return true; }
    if (el.dataset.action === 'kb-more') { st.moreDone = !st.moreDone; draw(); return true; }
    return false;
  }

  // arrastar e soltar (delegação no documento; só age dentro do quadro)
  document.addEventListener('dragstart', e => {
    const c = e.target.closest && e.target.closest('.kb-card'); if (!c) return;
    st.drag = Number(c.dataset.id); c.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move'; try { e.dataTransfer.setData('text/plain', String(st.drag)); } catch (x) { /* ok */ }
  });
  document.addEventListener('dragend', () => { st.drag = null; document.querySelectorAll('.kb-card.dragging,.kb-col.over').forEach(x => x.classList.remove('dragging', 'over')); });
  document.addEventListener('dragover', e => {
    const col = e.target.closest && e.target.closest('.kb-col'); if (!col || st.drag == null) return;
    e.preventDefault(); e.dataTransfer.dropEffect = 'move';
    document.querySelectorAll('.kb-col.over').forEach(x => { if (x !== col) x.classList.remove('over'); }); col.classList.add('over');
  });
  document.addEventListener('drop', e => {
    const col = e.target.closest && e.target.closest('.kb-col'); if (!col || st.drag == null) return;
    e.preventDefault(); const id = st.drag; st.drag = null; col.classList.remove('over'); move(id, col.dataset.col);
  });
  // teclado: com o cartão selecionado, ← → mudam a coluna
  document.addEventListener('keydown', e => {
    const c = e.target.closest && e.target.closest('.kb-card');
    if (!c || e.target !== c || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
    const i = COLS.findIndex(x => x.k === c.dataset.st), j = i + (e.key === 'ArrowRight' ? 1 : -1);
    if (i >= 0 && COLS[j]) { e.preventDefault(); move(Number(c.dataset.id), COLS[j].k); }
  });

  return { mount, onClick };
})();
