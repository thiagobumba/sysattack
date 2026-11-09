/* SysAttack — Matriz de prioridades (Eisenhower): tarefas abertas em quatro quadrantes, urgente × importante. */
const Prio = (() => {
  const { api, esc, fmt, ic } = SA;
  const A = () => window.App;

  // importante = marcada à mão, ou o projeto é Meta Empresa / prioridade Crítica ou Alta
  // urgente    = vence em até N dias (inclui as atrasadas)
  const QUAD = {
    agora: { label: 'Fazer agora', sub: 'Importante e urgente', cls: 'q-now',
      tip: ['Importante e com prazo curto ou vencido.', 'O que fazer: execute primeiro, hoje. Se este quadrante vive cheio, o time está apagando incêndio: revise o planejamento.'] },
    agendar: { label: 'Agendar', sub: 'Importante, não urgente', cls: 'q-plan',
      tip: ['Importante, mas com prazo ainda folgado.', 'O que fazer: reserve um horário na agenda antes que vire urgente. É onde o trabalho rende mais e dá menos estresse.'] },
    delegar: { label: 'Delegar', sub: 'Urgente, não importante', cls: 'q-delegate',
      tip: ['Tem prazo curto, mas não é de projeto prioritário.', 'O que fazer: peça a outra pessoa para assumir ou resolva da forma mais rápida possível. Não deixe consumir o tempo das tarefas importantes.'] },
    eliminar: { label: 'Eliminar ou adiar', sub: 'Nem urgente, nem importante', cls: 'q-drop',
      tip: ['Sem prazo próximo e sem prioridade.', 'O que fazer: reagende ou, se já não faz sentido, mude a situação para "Cancelada". Tarefas canceladas saem de todos os cálculos.'] }
  };

  const st = { days: 7, who: 'ALL', tasks: [] };
  try { const d = Number(localStorage.getItem('sa.prio.days')); if ([3, 7, 14, 30].includes(d)) st.days = d; } catch (e) { /* sem armazenamento */ }

  const reasonsOf = t => {
    const r = [];
    if (t.is_important) r.push('Marcada como importante');
    if (t.project_goal) r.push('Meta Empresa');
    else if (t.project_priority === 'CRITICA') r.push('Projeto de prioridade Crítica');
    else if (t.project_priority === 'ALTA') r.push('Projeto de prioridade Alta');
    return r;
  };

  async function view() {
    st.tasks = await api('GET', 'tasks');
    const a = A();
    a.view.tasks = st.tasks; // para o botão de editar tarefa funcionar aqui
    a.view.projects = await api('GET', 'projects');
    a.main.innerHTML = a.pageHead(['SysAttack', 'Prioridades'], 'Matriz de prioridades', 'Tarefas em aberto organizadas por urgência e importância (matriz de Eisenhower).') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">O que priorizar</h2></div><div id="pr-host"></div></section>`;
    mount(document.getElementById('pr-host'));
  }

  // monta filtros, quadrantes e legenda dentro de um contêiner (tela Prioridades ou aba do projeto)
  function mount(host) {
    const a = A();
    host.innerHTML = `
        <div class="filters"><label class="small" for="pr-who" style="align-self:center">Responsável</label><select id="pr-who" aria-label="Responsável"></select>
          <label class="small" for="pr-days" style="align-self:center">Urgente = vence em até</label>
          <select id="pr-days" aria-label="Limite de urgência">${[3, 7, 14, 30].map(n => `<option value="${n}"${n === st.days ? ' selected' : ''}>${n} dias</option>`).join('')}</select></div>
        <div id="pr-body"></div>
        <p class="hint" style="margin-top:14px"><b>Importante</b> = tarefa marcada como importante, ou de projeto Meta Empresa ou de prioridade Crítica/Alta. <b>Urgente</b> = vence dentro do prazo escolhido ou já está atrasada. Entram só tarefas abertas (sem as concluídas, canceladas e tarefas-pai) de projetos que não estão concluídos nem pausados. Clique na ★ para marcar ou desmarcar uma tarefa como importante.</p>`;

    const people = new Set();
    st.tasks.forEach(t => (t.responsible || '').split(',').map(x => x.trim()).filter(Boolean).forEach(x => people.add(x)));
    const me = a.me && a.me.name ? a.me.name : '';
    document.getElementById('pr-who').innerHTML = `<option value="ALL">Todos</option>${me ? '<option value="ME">Minhas tarefas</option>' : ''}<option value="NONE">Sem responsável</option>`
      + [...people].sort((x, y) => x.localeCompare(y)).map(p => `<option value="p:${esc(p)}">${esc(p)}</option>`).join('');
    document.getElementById('pr-who').value = st.who;
    if (document.getElementById('pr-who').value !== st.who) { st.who = 'ALL'; document.getElementById('pr-who').value = 'ALL'; }
    document.getElementById('pr-who').addEventListener('change', e => { st.who = e.target.value; draw(); });
    document.getElementById('pr-days').addEventListener('change', e => {
      st.days = Number(e.target.value);
      try { localStorage.setItem('sa.prio.days', String(st.days)); } catch (x) { /* ok */ }
      draw();
    });
    draw();
  }

  function mine(t, who) {
    const names = (t.responsible || '').split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
    if (who === 'ALL') return true;
    if (who === 'NONE') return names.length === 0;
    const target = who === 'ME' ? (A().me.name || '').toLowerCase() : who.slice(2).toLowerCase();
    return names.includes(target);
  }

  function dueText(t) {
    const n = A().dayDiff(t.end_date);
    if (n < 0) return { txt: `Atrasada há ${-n} dia(s)`, cls: 'late' };
    if (n === 0) return { txt: 'Vence hoje', cls: 'soon' };
    if (n === 1) return { txt: 'Vence amanhã', cls: 'soon' };
    return { txt: `Vence em ${n} dias`, cls: n <= st.days ? 'soon' : '' };
  }

  function card(t) {
    const d = dueText(t), why = reasonsOf(t), star = !!t.is_important;
    return `<article class="pq-card">
      <div class="pq-main"><button type="button" class="pq-name" data-action="edit-task" data-id="${t.id}" title="Abrir a tarefa">${t.is_milestone ? '◆ ' : ''}${esc(t.name)}</button>
        <span class="small">${esc(t.project_name)} · ${esc(t.responsible || 'Sem responsável')}</span>
        <span class="pq-meta"><b class="pq-due ${d.cls}">${d.txt}</b> <span class="small">(${fmt(t.end_date)})</span>${why.map(w => `<span class="pq-why">${esc(w)}</span>`).join('')}</span></div>
      <button type="button" class="icon-btn pq-star${star ? ' on' : ''}" data-action="prio-star" data-id="${t.id}" aria-pressed="${star}" title="${star ? 'Desmarcar como importante' : 'Marcar como importante'}" aria-label="${star ? 'Desmarcar como importante' : 'Marcar como importante'}">${star ? '★' : '☆'}</button></article>`;
  }

  function draw() {
    const open = st.tasks.filter(t => !t.child_count && t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA'
      && t.project_status !== 'CONCLUIDO' && t.project_status !== 'PAUSADO' && mine(t, st.who));
    const q = { agora: [], agendar: [], delegar: [], eliminar: [] };
    open.forEach(t => {
      const imp = reasonsOf(t).length > 0, urg = A().dayDiff(t.end_date) <= st.days;
      q[imp ? (urg ? 'agora' : 'agendar') : (urg ? 'delegar' : 'eliminar')].push(t);
    });
    Object.values(q).forEach(l => l.sort((a, b) => a.end_date.localeCompare(b.end_date) || a.id - b.id));
    const box = k => {
      const info = QUAD[k], list = q[k];
      return `<section class="pq ${info.cls}"><header>${Tip.wrap(`<b>${info.label}</b>`, info.label + ' — ' + info.sub, info.tip)}<span class="cnt">${list.length}</span><span class="small">${info.sub}</span></header>
        <div class="pq-list">${list.length ? list.map(card).join('') : '<p class="pq-empty">Nenhuma tarefa aqui.</p>'}</div></section>`;
    };
    document.getElementById('pr-body').innerHTML = open.length
      ? `<div class="pq-axis"><span>Urgente</span><span>Não urgente</span></div><div class="pq-grid">
          <div class="pq-side">Importante</div>${box('agora')}${box('agendar')}
          <div class="pq-side">Não importante</div>${box('delegar')}${box('eliminar')}</div>`
      : `<div class="empty"><strong>Nenhuma tarefa em aberto para este filtro</strong>Troque o responsável ou cadastre tarefas nos projetos.</div>`;
  }

  async function action(el) {
    if (el.dataset.action === 'prio-star') {
      const id = Number(el.dataset.id), t = st.tasks.find(x => x.id === id);
      const upd = await api('PUT', `tasks/${id}/important`, { important: !t.is_important });
      Object.assign(t, upd);
      draw();
    }
  }
  // aba do projeto: só as tarefas dele
  function tab(host, tasks) {
    st.tasks = tasks; st.who = 'ALL';
    mount(host);
  }
  return { view, tab, action };
})();
