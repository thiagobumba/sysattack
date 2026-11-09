/* SysAttack — telas, rotas e formulários. */
(() => {
  const { api, esc, fmt, todayIso, ic, duration, addDays, dayNum } = SA;
  const main = document.getElementById('main');
  const dlg = document.getElementById('dlg');

  const P_STATUS = { PLANEJAMENTO: ['Planejamento', 'b-grey'], EM_ANDAMENTO: ['Em andamento', 'b-orange'], CONCLUIDO: ['Concluído', 'b-green'], PAUSADO: ['Pausado', 'b-dark'] };
  const PRIORITY = { CRITICA: ['Crítica', 'b-red'], ALTA: ['Alta', 'b-orange'], MEDIA: ['Média', 'b-grey'], BAIXA: ['Baixa', 'b-light'] };
  const PRIO_RANK = { CRITICA: 0, ALTA: 1, MEDIA: 2, BAIXA: 3 };
  const H_RANK = { VERMELHO: 0, AMARELO: 1, VERDE: 2, NAO_INICIADO: 3, PAUSADO: 4, CONCLUIDO: 5, CONCLUIDO_ESTOURO: 5 };
  const H_CLASS = { VERMELHO: 'h-red', AMARELO: 'h-amber', VERDE: 'h-green', NAO_INICIADO: 'h-grey', PAUSADO: 'h-grey', CONCLUIDO: 'h-done', CONCLUIDO_ESTOURO: 'h-red' };
  // ---- explicações detalhadas (balão ao passar o mouse)
  const P_STATUS_TIP = {
    PLANEJAMENTO: ['O projeto ainda não começou a ser executado.', '• A saúde aparece como "Não iniciado".', '• Prazo, custo e marcos ainda não geram alertas.', 'Mude para "Em andamento" quando a execução começar.'],
    EM_ANDAMENTO: ['O projeto está em execução.', '• É a única situação em que a saúde é calculada (prazo, custo, marcos e mudanças).', '• Tarefas e marcos vencidos geram alertas e e-mails de atraso.'],
    CONCLUIDO: ['O projeto foi encerrado.', '• A saúde deixa de ser calculada.', '• Se o custo lançado passou do orçamento, aparece "Concluído c/ estouro" em vermelho.', '• Fica na aba "Concluído" da carteira.'],
    PAUSADO: ['O projeto está suspenso temporariamente.', '• Fica fora dos alertas de saúde e de atraso até voltar para "Em andamento".', '• Os dados e tarefas são mantidos.']
  };
  const pStatusBadge = st => Tip.wrap(badge(P_STATUS, st), 'Situação: ' + P_STATUS[st][0], P_STATUS_TIP[st]);
  const PRIO_TIP = { CRITICA: 'Urgência máxima entre os projetos comuns. Aparece logo depois dos projetos Meta Empresa.', ALTA: 'Importante e com prazo que exige atenção. Vem depois dos críticos.', MEDIA: 'Prioridade padrão: segue o fluxo normal do portfólio.', BAIXA: 'Pode esperar; é o último a ser atendido quando há disputa por pessoas e tempo.' };
  const ORDER_TIP = 'Ordem da carteira: Meta Empresa primeiro, depois Crítica, Alta, Média e Baixa. Dentro da mesma prioridade, os projetos com pior saúde aparecem antes.';
  const goalBadge = p => p.company_goal ? Tip.wrap('<span class="badge b-goal">★ Meta Empresa</span>', 'Meta Empresa',
    ['Projeto do plano de desenvolvimento da empresa.', 'Tem prioridade acima de "Crítica": sempre aparece primeiro na carteira, qualquer que seja a prioridade ou a saúde dos demais.', ORDER_TIP]) + ' ' : '';
  const prioBadge = p => goalBadge(p) + Tip.wrap(badge(PRIORITY, p.priority || 'MEDIA'), 'Prioridade: ' + PRIORITY[p.priority || 'MEDIA'][0], [PRIO_TIP[p.priority || 'MEDIA'], ORDER_TIP]);
  const LVL_WORD = { VERDE: 'saudável', AMARELO: 'atenção', VERMELHO: 'crítico', NA: 'não avaliado' };
  const healthTip = h => {
    if (!h) return [];
    if (h.level === 'CONCLUIDO') return ['Projeto concluído: a saúde não é mais calculada.', 'Se o custo lançado passar do orçamento, o sistema mostra "Concluído c/ estouro".'];
    if (h.level === 'CONCLUIDO_ESTOURO') return ['O projeto foi concluído, mas gastou mais do que o orçamento aprovado.', ...(h.reasons || []).map(r => '• ' + r), 'Vale registrar a lição aprendida e revisar as estimativas de projetos parecidos.'];
    if (h.level === 'NAO_INICIADO') return ['Projeto em planejamento: ainda não começou, então prazo, custo e marcos não são avaliados.'];
    if (h.level === 'PAUSADO') return ['Projeto pausado: fica fora da avaliação de saúde até voltar para "Em andamento".'];
    const dims = h.dims || [];
    return ['A cor é sempre a PIOR entre quatro verificações:',
      ...dims.map(d => `• ${d.label} (${LVL_WORD[d.level] || d.level}): ${d.detail}`),
      h.planned != null ? `Previsto pelo cronograma até hoje: ${h.planned}% · realizado: ${h.actual}%.` : '',
      '# Como cada verificação é avaliada',
      '• Prazo: atenção se o progresso estiver mais de 5 pontos percentuais abaixo do previsto ou houver tarefa atrasada; crítico acima de 15 pontos ou com a data final do projeto vencida.',
      '• Custo: atenção quando o custo lançado passa de 90% do orçamento (ou quando a projeção de término ultrapassa o orçamento); crítico acima de 100%.',
      '• Marcos: 1 marco atrasado = atenção; 2 ou mais = crítico.',
      '• Mudanças: atenção com change pendente há mais de 7 dias ou changes aprovadas que somam dias de atraso; crítico se a pendente tem impacto em dias.'];
  };
  const healthChip = (h, withReason) => h ? `<span class="health ${H_CLASS[h.level] || 'h-grey'}"${Tip.attr('Saúde: ' + h.label, healthTip(h))}><i></i>${esc(h.label)}</span>${withReason && h.reasons && h.reasons.length ? `<span class="small">${esc(h.reasons[0])}</span>` : ''}` : '—';
  const byUrgency = (a, b) => ((b.company_goal ? 1 : 0) - (a.company_goal ? 1 : 0)) || (PRIO_RANK[a.priority || 'MEDIA'] - PRIO_RANK[b.priority || 'MEDIA']) || ((H_RANK[a.health.level] ?? 9) - (H_RANK[b.health.level] ?? 9)) || a.name.localeCompare(b.name);
  const T_STATUS = { A_FAZER: ['A fazer', 'b-grey'], EM_ANDAMENTO: ['Em andamento', 'b-orange'], CONCLUIDA: ['Concluída', 'b-green'], BLOQUEADA: ['Bloqueada', 'b-dark'], CANCELADA: ['Cancelada', 'b-light'] };
  const ROLES = { ADMIN: 'Administrador', USER: 'Usuário' };
  const FIN_KIND = { NF_SERVICO: ['Nota fiscal de serviço', 'b-dark'], FATURA: ['Fatura', 'b-grey'] };
  const DOC_CAT = { ESPECIFICACAO: 'Especificação', CONTRATO: 'Contrato', PROPOSTA: 'Proposta', DESENHO: 'Desenho / projeto', NOTA_FISCAL: 'Nota fiscal', OUTRO: 'Outro' };
  const PREVIEW = ['pdf', 'png', 'jpg', 'jpeg', 'gif', 'webp', 'txt'];
  const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

  let me = null;
  let view = { name: '', projectId: null, tab: 'gantt', tasks: [], projects: [], project: null, users: [], msTab: 'all', msRender: null, fin: [], docs: [] };

  const money = n => BRL.format(Number(n) || 0);
  const isAdmin = () => me.role === 'ADMIN';
  const isLate = t => t.status !== 'CONCLUIDA' && t.status !== 'CANCELADA' && t.end_date < todayIso();
  const badge = (map, k) => `<span class="badge ${map[k][1]}">${map[k][0]}</span>`;
  const T_STATUS_TIP = {
    A_FAZER: ['A tarefa ainda não começou.', 'Fica "Atrasada" automaticamente se a data final passar sem ela ser concluída.'],
    EM_ANDAMENTO: ['A tarefa está sendo executada.', 'Ao chegar a 100% de progresso, vira "Concluída". Se a data final passar, aparece como "Atrasada".'],
    CONCLUIDA: ['A tarefa foi finalizada (100%).', 'Conta como entregue no progresso do projeto e nos indicadores de prazo.'],
    BLOQUEADA: ['A tarefa está impedida de avançar (dependência externa, aprovação, material...).', 'Continua contando como pendente: se passar da data final, aparece como "Atrasada".'],
    CANCELADA: ['A tarefa deixou de fazer sentido durante o projeto e foi cancelada.', '• Não conta como atrasada.', '• Fica fora do progresso, da saúde, do painel e dos e-mails de atraso.', '• Numa tarefa-pai, as filhas canceladas são ignoradas.']
  };
  const taskBadge = t => isLate(t)
    ? Tip.wrap('<span class="badge b-red">Atrasada</span>', 'Atrasada', [`O prazo final (${fmt(t.end_date)}) já passou e a tarefa não está concluída nem cancelada.`, 'Para sair deste estado: conclua a tarefa, ajuste a data final ou cancele a tarefa se ela não faz mais sentido.'])
    : Tip.wrap(badge(T_STATUS, t.status), 'Situação: ' + T_STATUS[t.status][0], T_STATUS_TIP[t.status]);
  const pbar = (p, done) => `<div class="pbar"><div class="pbar-track"><div class="pbar-fill${done ? ' done' : ''}" style="width:${p}%"></div></div><span>${p}%</span></div>`;
  const empty = (t, s) => `<div class="empty"><strong>${t}</strong>${s}</div>`;
  const fileSize = n => n < 1024 * 1024 ? Math.max(1, Math.round(n / 1024)) + ' KB' : (n / 1024 / 1024).toFixed(1).replace('.', ',') + ' MB';
  const extOf = name => (name.split('.').pop() || '').toLowerCase();

  function toast(msg) {
    const t = document.createElement('div');
    t.className = 'toast'; t.textContent = msg; t.setAttribute('role', 'status');
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 3500);
  }

  function pageHead(crumbs, title, sub) {
    const c = crumbs.map((x, i) => x.href && i < crumbs.length - 1 ? `<a href="${x.href}">${esc(x.t)}</a>` : esc(x.t || x)).join(' / ');
    return `<header class="page-head"><div><nav class="crumbs" aria-label="Navegação">${c}</nav><h1>${esc(title)}</h1>${sub ? `<p class="sub">${esc(sub)}</p>` : ''}</div>
      <div class="head-meta"><span class="hello">Olá, <b>${esc(me.name)}</b></span><button class="btn btn-sm" data-action="logout">Sair</button>
      <span><i class="dot"></i>Referência ${fmt(todayIso())}</span></div></header>`;
  }

  // ------------------------------------------------------------------ hierarquia de tarefas
  const byStart = (a, b) => a.start_date.localeCompare(b.start_date) || a.id - b.id;
  function treeOrder(tasks) {
    const ids = new Set(tasks.map(t => t.id));
    const out = [];
    tasks.filter(t => !t.parent_id || !ids.has(t.parent_id)).sort(byStart).forEach(r => {
      out.push({ t: r, level: 0 });
      tasks.filter(k => k.parent_id === r.id).sort(byStart).forEach(k => out.push({ t: k, level: 1 }));
    });
    return out;
  }

  function ganttRows(projects, tasks) {
    const rows = [];
    projects.forEach(p => {
      const pk = 'p' + p.id;
      rows.push({ key: pk, kind: 'project', id: p.id, name: p.name, sub: p.owner, start: p.start_date, end: p.end_date, progress: p.progress,
        status: p.status === 'CONCLUIDO' ? 'CONCLUIDA' : '', level: 0, hasChildren: true });
      treeOrder(tasks.filter(t => t.project_id === p.id)).forEach(({ t, level }) => rows.push({
        key: 't' + t.id, kind: t.is_milestone ? 'milestone' : t.child_count > 0 ? 'parent' : 'task', id: t.id, name: t.name, sub: t.responsible,
        start: t.start_date, end: t.end_date, progress: t.progress, status: t.status, late: isLate(t), dep: t.depends_on ? 't' + t.depends_on : null,
        parentKey: t.parent_id ? 't' + t.parent_id : pk, level: level + 1, hasChildren: t.child_count > 0 }));
    });
    return rows;
  }

  // ------------------------------------------------------------------ indicadores de custo e prazo (EVM)
  const idxClass = v => v == null ? '' : v >= 1 ? 'ok' : v >= 0.9 ? 'warn' : 'bad';
  // abaixo de 1 nunca arredonda para 1,00 (0,996 aparece como 0,99)
  const idxFmt = v => v == null ? '—' : (v < 1 ? (Math.floor(v * 100) / 100) : Number(v)).toFixed(2).replace('.', ',');
  function evmPanel(p) {
    const e = p.evm;
    if (!e) return '';
    if (!e.available) {
      const msg = e.reason === 'NO_BUDGET' ? 'Informe o orçamento do projeto para ver a projeção de custo.' : 'Cadastre tarefas para calcular os indicadores de prazo e custo.';
      return `<div class="evm"><div class="evm-head"><b>Indicadores de custo e prazo</b></div><p class="hint" style="margin:6px 0 0">${msg}</p></div>`;
    }
    const over = e.eac != null && e.eac > e.bac, noCost = !(e.ac > 0);
    const eacTxt = e.eac == null ? 'Sem projeção' : money(e.eac);
    const spiTxt = e.spi == null ? 'Ainda sem previsto' : e.spi >= 1 ? 'No ritmo ou à frente' : e.spi >= 0.9 ? 'Levemente atrasado' : 'Atrasado';
    const cpiTxt = noCost ? 'Nenhum custo lançado' : e.ac > e.bac ? 'Custo lançado acima do orçamento' : e.cpi >= 1 ? 'Dentro do orçamento' : e.cpi >= 0.9 ? 'Levemente acima' : 'Gastando mais que entrega';
    const base = [`Valor previsto = orçamento × ${e.planned_pct}% (o que o cronograma previa estar pronto hoje) = ${money(e.pv)}.`, `Valor realizado = orçamento × ${e.actual_pct}% (o que de fato está pronto) = ${money(e.ev)}.`,
      'Os percentuais são médias ponderadas pela duração das tarefas; marcos e tarefas canceladas ficam de fora.'];
    const tipSpi = ['Mede se o projeto está andando no ritmo que o cronograma previa.', '= SPI = valor realizado ÷ valor previsto até hoje', e.spi == null ? '= Ainda não há valor previsto (o cronograma ainda não começou).' : `= ${money(e.ev)} ÷ ${money(e.pv)} = ${idxFmt(e.spi)}`,
      ...base, '# Como ler', '• 1,00 ou mais (verde): no ritmo ou à frente do cronograma.', '• 0,90 a 0,99 (amarelo): levemente atrasado.', '• Abaixo de 0,90 (vermelho): atrasado.',
      `Hoje: ${spiTxt}.`, e.spi != null && e.spi < 1 ? 'Para recuperar: reveja as tarefas atrasadas, replaneje datas ou reforce o time.' : ''];
    const tipCpi = noCost ? ['Mede quanto trabalho foi entregue para cada real gasto.', '= CPI = valor realizado ÷ custo lançado', 'Ainda não há lançamentos financeiros no projeto, então o índice não pode ser calculado. Lance os custos na aba Financeiro.']
      : ['Mede quanto trabalho foi entregue para cada real gasto.', '= CPI = valor realizado ÷ custo lançado', `= ${money(e.ev)} ÷ ${money(e.ac)} = ${idxFmt(e.cpi)}`,
      `Cada R$ 1,00 gasto entregou R$ ${idxFmt(e.cpi)} de trabalho. O custo lançado é a soma dos lançamentos da aba Financeiro.`, '# Como ler', '• 1,00 ou mais (verde): dentro do orçamento.', '• 0,90 a 0,99 (amarelo): gastando um pouco acima do entregue.', '• Abaixo de 0,90 (vermelho): gastando bem mais do que entrega.',
      e.ac > e.bac ? `Atenção: o custo lançado (${money(e.ac)}) já passou do orçamento (${money(e.bac)}), então o cartão fica vermelho mesmo com índice próximo de 1.` : `Hoje: ${cpiTxt}.`];
    const tipEac = ['Estimativa do que o projeto vai custar quando terminar, se o ritmo de gasto atual continuar.', '= EAC = orçamento ÷ CPI', noCost ? '= Sem custo lançado: a projeção é o próprio orçamento.' : `= ${money(e.bac)} ÷ ${idxFmt(e.cpi)} = ${eacTxt}`,
      e.etc != null ? `Falta gastar (ETC): ${money(e.etc)}, ou seja, EAC menos o custo lançado de ${money(e.ac)}.` : '', over ? `Vermelho: a projeção passa do orçamento de ${money(e.bac)}.` : `Verde: a projeção fica dentro do orçamento de ${money(e.bac)}.`,
      e.reliable ? '' : 'Atenção: com menos de 10% de progresso a projeção ainda é pouco confiável.'];
    const tipVac = ['Quanto sobra (ou falta) do orçamento ao final do projeto, pela projeção.', '= Desvio = orçamento − EAC', e.vac == null ? '' : `= ${money(e.bac)} − ${eacTxt} = ${(e.vac < 0 ? '−' : '+') + money(Math.abs(e.vac))}`,
      '• Negativo (vermelho): estouro projetado do orçamento.', '• Positivo ou zero (verde): dentro do orçamento, com folga.',
      e.vac != null && e.vac < 0 ? `Para evitar o estouro, é preciso cortar ${money(Math.abs(e.vac))} do custo restante ou aprovar mais orçamento (change).` : ''];
    return `<div class="evm"><div class="evm-head"><b>Indicadores de custo e prazo</b><span>${e.reliable ? '' : 'Projeção ainda pouco confiável: o progresso está abaixo de 10%.'}</span></div>
      <div class="evm-grid">
        <div class="evm-card ${idxClass(e.spi)}"${Tip.attr('Prazo (SPI) — Índice de desempenho de prazo', tipSpi)}><small>Prazo (SPI)</small><b>${idxFmt(e.spi)}</b><span>${spiTxt}</span></div>
        <div class="evm-card ${noCost ? '' : e.ac > e.bac ? 'bad' : idxClass(e.cpi)}"${Tip.attr('Custo (CPI) — Índice de desempenho de custo', tipCpi)}><small>Custo (CPI)</small><b>${noCost ? '—' : idxFmt(e.cpi)}</b><span>${cpiTxt}</span></div>
        <div class="evm-card ${over ? 'bad' : 'ok'}"${Tip.attr('Custo previsto no término (EAC)', tipEac)}><small>Custo previsto no término (EAC)</small><b>${eacTxt}</b><span>Orçamento ${money(e.bac)}</span></div>
        <div class="evm-card ${e.vac != null && e.vac < 0 ? 'bad' : 'ok'}"${Tip.attr('Desvio no término (VAC)', tipVac)}><small>Desvio no término</small><b>${e.vac == null ? '—' : (e.vac < 0 ? '−' : '+') + money(Math.abs(e.vac))}</b><span>${e.vac == null ? '' : e.vac < 0 ? 'Estouro projetado' : 'Dentro do orçamento'}</span></div>
      </div>
      <p class="hint" style="margin:10px 0 0">Previsto até hoje ${money(e.pv)} (${e.planned_pct}%) · Realizado ${money(e.ev)} (${e.actual_pct}%) · Custo lançado ${money(e.ac)}${over && e.reliable ? ' · <b class="late-txt">Atenção: a projeção passa do orçamento.</b>' : ''}</p></div>`;
  }

  // ------------------------------------------------------------------ visão geral
  function portfolioPanel(list) {
    const n = lv => list.filter(p => p.health.level === lv).length;
    const others = list.filter(p => !['VERMELHO', 'AMARELO', 'VERDE'].includes(p.health.level)).length;
    const act = list.filter(p => p.health.level === 'VERMELHO' || p.health.level === 'AMARELO').sort(byUrgency);
    const tile = (lv, label, cls, filter) => `<a class="ptile ${cls}" href="#/projetos" data-health="${filter}"><b>${lv}</b><span>${label}</span></a>`;
    return `<section class="panel"><div class="panel-head"><div><h2 class="panel-title">Saúde do portfólio</h2><p class="panel-sub">Cada projeto recebe a pior cor entre prazo, custo, marcos e mudanças.</p></div>
      <a class="btn btn-sm" href="#/projetos">Ver projetos →</a></div>
      <div class="ptiles">${tile(n('VERMELHO'), 'Críticos', 'h-red', 'VERMELHO')}${tile(n('AMARELO'), 'Em atenção', 'h-amber', 'AMARELO')}${tile(n('VERDE'), 'Saudáveis', 'h-green', 'VERDE')}${tile(others, 'Não iniciados, pausados ou concluídos', 'h-grey', 'OUTROS')}</div>
      ${act.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Projeto que precisa de ação</th><th>Saúde</th><th>Prioridade</th><th>Motivo</th></tr></thead><tbody>
        ${act.map(p => `<tr class="click" data-go="#/projeto/${p.id}"><td class="strong">${esc(p.name)}<span class="small">${esc(p.owner || 'Sem responsável')}</span></td><td>${healthChip(p.health)}</td><td>${prioBadge(p)}</td>
          <td class="wrap">${esc(p.health.reasons.join(' · '))}</td></tr>`).join('')}</tbody></table></div>`
        : list.length ? '<p class="hint" style="margin:0">Nenhum projeto em atenção ou crítico no momento.</p>' : ''}</section>`;
  }

  async function viewDashboard() {
    const d = await api('GET', 'dashboard'), k = d.kpi;
    const max = Math.max(1, ...d.by_responsible.map(r => r.total));
    main.innerHTML = pageHead(['SysAttack', { t: 'Gestão de projetos', href: '#/projetos' }, 'Visão geral'], 'Visão geral', 'Cada projeto conectado ao próximo prazo.') + `
      <section class="bar"><strong>Projetos no SysAttack</strong><div class="bar-actions">
        <a class="btn" href="#/projetos">Acompanhar projetos</a>
        <button class="btn btn-primary" data-action="new-project">+ Novo projeto</button></div></section>
      ${portfolioPanel(d.portfolio || [])}
      <section class="kpis">
        <div class="kpi"><div class="kpi-top"><span class="kpi-label">Projetos em<br>andamento</span>${ic('box')}</div><div class="kpi-value">${k.projects_active}</div><div class="kpi-sub">${k.projects_total} projetos cadastrados</div></div>
        <div class="kpi"><div class="kpi-top"><span class="kpi-label">Tarefas<br>abertas</span>${ic('list')}</div><div class="kpi-value">${k.tasks_total - k.tasks_done}</div><div class="kpi-sub">${k.tasks_done} concluídas de ${k.tasks_total}</div></div>
        <div class="kpi accent"><div class="kpi-top"><span class="kpi-label">Tarefas<br>atrasadas</span>${ic('bars')}</div><div class="kpi-value">${k.tasks_late}</div><div class="kpi-sub">Prazo final vencido e não concluídas</div></div>
        <div class="kpi"><div class="kpi-top"><span class="kpi-label">Progresso<br>médio</span>${ic('people')}</div><div class="kpi-value">${k.avg_progress}%</div><div class="kpi-sub">Média das tarefas</div></div>
      </section>
      <div class="notice"><b>Prazo com contexto.</b> O cronograma usa as datas planejadas de início e fim de cada tarefa; o progresso é informado por quem executa. Tarefas pai são calculadas a partir das filhas.</div>
      <div class="grid-2">
        <section class="panel"><div class="panel-head"><div><h2 class="panel-title">Fila de atenção</h2><p class="panel-sub">Tarefas com os prazos mais próximos ou vencidos.</p></div>
          <a class="btn btn-sm" href="#/cronograma">Ver cronograma →</a></div>
          <div class="table-wrap">${d.attention.length ? `<table class="tbl"><thead><tr><th>Tarefa</th><th>Projeto</th><th>Prazo</th><th>Situação</th></tr></thead><tbody>
          ${d.attention.map(t => `<tr class="click" data-go="#/projeto/${t.project_id}"><td class="strong">${t.is_milestone ? '<i class="dia-ic"></i>' : ''}${esc(t.name)}<span class="small">${esc(t.responsible || 'Sem responsável')}</span></td>
            <td>${esc(t.project_name)}</td><td class="${isLate(t) ? 'late-txt' : ''}">${fmt(t.end_date)}</td><td>${taskBadge(t)}</td></tr>`).join('')}</tbody></table>`
            : empty('Tudo em dia', 'Não há tarefas abertas no momento.')}</div></section>
        <section class="panel"><div class="panel-head"><div><h2 class="panel-title">Distribuição das demandas</h2><p class="panel-sub">Quantidade de tarefas por responsável.</p></div></div>
          ${d.by_responsible.length ? `<div class="dist">${d.by_responsible.map(r => `<div class="dist-row"><div class="dist-top"><b>${esc(r.responsible)}</b><span>${r.total - r.done} abertas · ${r.done} concluídas</span></div>
            <div class="dist-track" style="width:${(r.total / max) * 100}%"><div class="dist-bar"><div class="dist-done" style="width:${r.done / r.total * 100}%"></div><div class="dist-open" style="width:${(r.total - r.done) / r.total * 100}%"></div></div></div></div>`).join('')}</div>
            <div class="legend"><span><i style="background:var(--ink)"></i>Concluídas</span><span><i style="background:var(--orange)"></i>Abertas</span></div>` : empty('Sem tarefas', 'Cadastre tarefas para ver a distribuição.')}
        </section></div>`;
  }

  // ------------------------------------------------------------------ projetos
  async function viewProjects() {
    const list = await api('GET', 'projects');
    view.projects = list;
    const f = view.pf || (view.pf = { health: 'all', prio: 'all' });
    if (!f.status) f.status = 'EM_ANDAMENTO'; // aba padrão
    const ST_TABS = ['PLANEJAMENTO', 'EM_ANDAMENTO', 'CONCLUIDO', 'PAUSADO'];
    const healthOk = (p, h) => h === 'all' || (h === 'OUTROS' ? !['VERMELHO', 'AMARELO', 'VERDE'].includes(p.health.level) : p.health.level === h);
    if (view.pendingHealth) {
      f.health = view.pendingHealth; f.prio = 'all'; view.pendingHealth = null;
      // vindo do painel: abre a primeira aba que tem projetos com essa saúde
      const hit = ['EM_ANDAMENTO', 'PLANEJAMENTO', 'PAUSADO', 'CONCLUIDO'].find(st => list.some(p => p.status === st && healthOk(p, f.health)));
      if (hit) f.status = hit;
    }
    main.innerHTML = pageHead(['SysAttack', 'Projetos'], 'Projetos', 'Escopo, prazos, orçamento e andamento de cada projeto.') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Carteira de projetos</h2>
        <button class="btn btn-primary btn-sm" data-action="new-project">+ Novo projeto</button></div>
        <div class="ms-tabs" id="pf-tabs" role="tablist">${ST_TABS.map(st => `<button type="button" class="ms-tab" role="tab" data-st="${st}">${P_STATUS[st][0]} <span class="cnt">${list.filter(p => p.status === st).length}</span></button>`).join('')}</div>
        <div class="filters"><select id="pf-h" aria-label="Filtrar por saúde"><option value="all">Todas as saúdes</option><option value="VERMELHO">Crítico</option><option value="AMARELO">Atenção</option><option value="VERDE">Saudável</option><option value="OUTROS">Não iniciado, pausado ou concluído</option></select>
          <select id="pf-p" aria-label="Filtrar por prioridade"><option value="all">Todas as prioridades</option><option value="META">Meta Empresa</option>${Object.entries(PRIORITY).map(([k, v]) => `<option value="${k}">${v[0]}</option>`).join('')}</select></div>
        <div class="table-wrap" id="pf-body"></div>
        <p class="hint" style="margin-top:12px">Saúde = pior resultado entre prazo, custo, marcos e mudanças. Passe o mouse sobre a cor para ver o motivo. Ordenação: Meta Empresa primeiro, depois a prioridade e, dentro dela, os projetos mais críticos primeiro.</p></section>`;
    const hs = document.getElementById('pf-h'), ps = document.getElementById('pf-p');
    hs.value = f.health; ps.value = f.prio;
    const draw = () => {
      f.health = hs.value; f.prio = ps.value;
      document.querySelectorAll('#pf-tabs .ms-tab').forEach(b => { const on = b.dataset.st === f.status; b.classList.toggle('active', on); b.setAttribute('aria-selected', on); });
      const rows = list.filter(p => p.status === f.status && (f.prio === 'all' || (f.prio === 'META' ? p.company_goal : (p.priority || 'MEDIA') === f.prio))
        && healthOk(p, f.health)).sort(byUrgency);
      document.getElementById('pf-body').innerHTML = rows.length ? `<table class="tbl"><thead><tr><th>Projeto</th><th>Saúde</th><th>Prioridade</th><th>Período</th><th>Progresso</th><th class="num">Orçamento</th><th>Situação</th><th></th></tr></thead><tbody>
        ${rows.map(p => `<tr class="click" data-go="#/projeto/${p.id}"><td class="strong">${esc(p.name)}<span class="small">${esc(p.owner || 'Sem responsável')} · ${p.task_count} tarefa(s)${p.late_count ? ` · <b class="late-txt">${p.late_count} atrasada(s)</b>` : ''}</span></td>
          <td>${healthChip(p.health)}</td><td>${prioBadge(p)}</td>
          <td class="nowrap">${fmt(p.start_date)} – ${fmt(p.end_date)}<span class="small">${duration(p.start_date, p.end_date)} dias</span></td>
          <td>${pbar(p.progress, p.status === 'CONCLUIDO')}</td>
          <td class="num">${Number(p.budget) > 0 ? money(p.budget) : '—'}</td>
          <td>${pStatusBadge(p.status)}</td>
          <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar projeto" data-action="edit-project" data-id="${p.id}">${ic('edit')}</button>
          <button class="icon-btn del" title="Excluir" aria-label="Excluir projeto" data-action="del-project" data-id="${p.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table>`
        : list.some(p => p.status === f.status) ? empty('Nenhum projeto com esse filtro', 'Troque a saúde ou a prioridade para ver os demais.')
        : list.length ? empty(`Nenhum projeto em "${P_STATUS[f.status][0]}"`, 'Use as abas acima para ver os projetos nas outras situações.') : empty('Nenhum projeto ainda', 'Crie o primeiro projeto com escopo, início e fim.');
    };
    hs.addEventListener('change', draw); ps.addEventListener('change', draw);
    document.querySelectorAll('#pf-tabs .ms-tab').forEach(b => b.addEventListener('click', () => { f.status = b.dataset.st; draw(); }));
    draw();
  }

  async function viewProject(id) {
    const [p, tasks] = await Promise.all([api('GET', 'projects/' + id), api('GET', `projects/${id}/tasks`)]);
    if (view.projectId !== id) view.tab = 'gantt';
    view.project = p; view.tasks = tasks; view.projectId = id; view.projects = [p];
    const leaf = tasks.filter(t => !t.child_count).length, ms = tasks.filter(t => t.is_milestone).length;
    view.nav = [
      { k: 'plan', label: 'Cronograma e gráficos', tabs: [['gantt', 'Cronograma (Gantt)', 'Barras de tarefas no tempo, com dependências e marcos.'], ['burndown', 'Burndown', 'Quanto trabalho falta, comparado ao ritmo ideal até o prazo.'], ['pert', 'PERT / Caminho crítico', 'Rede de dependências: quais tarefas, se atrasarem, atrasam o projeto.'], ['prio', 'Prioridades (Eisenhower)', 'Tarefas em aberto por urgência e importância: o que fazer agora, agendar, delegar ou adiar.'], ['ishikawa', 'Ishikawa', 'Investigue as causas de um problema (espinha de peixe).']] },
      { k: 'work', label: 'Tarefas e marcos', tabs: [['tasks', `Tarefas (${leaf})`, 'Lista e cadastro das tarefas do projeto.'], ['marcos', `Marcos (${ms})`, 'Entregas e datas-chave do projeto.']] },
      { k: 'money', label: 'Financeiro e documentos', tabs: [['finance', `Financeiro (${p.fin_count})`, 'Notas fiscais e faturas lançadas no projeto.'], ['docs', `Documentos (${p.doc_count})`, 'Arquivos e anexos do projeto.']] },
      { k: 'quality', label: 'Mudanças', tabs: [['changes', 'Changes', 'Mudanças de escopo, prazo ou custo e seu impacto.']] },
      ...(isAdmin() ? [{ k: 'auto', label: 'Automação', tabs: [['triggers', 'Gatilhos de e-mail', 'Regras que enviam e-mail automaticamente.']] }] : [])
    ];
    const tab = () => '';
    main.innerHTML = pageHead(['SysAttack', { t: 'Projetos', href: '#/projetos' }, p.name], p.name, '') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Escopo inicial</h2><div class="bar-actions">
        <button class="btn btn-sm" data-action="edit-project" data-id="${p.id}">Editar projeto</button></div></div>
        <p class="scope-text">${p.scope ? esc(p.scope) : 'Escopo ainda não informado.'}</p>
        <dl class="meta-grid"><div><dt>Situação</dt><dd>${pStatusBadge(p.status)}</dd></div><div><dt>Saúde</dt><dd>${healthChip(p.health)}</dd></div><div><dt>Prioridade</dt><dd>${prioBadge(p)}</dd></div>
        <div><dt>Responsável</dt><dd>${Tip.wrap(esc(p.owner || '—'), 'Responsável', ['Pessoa responsável pelo projeto: quem responde pelo andamento e é cobrada nos alertas de prazo e custo.', 'Para trocar, use "Editar projeto".'])}</dd></div>
        <div><dt>Início</dt><dd>${Tip.wrap(fmt(p.start_date), 'Início planejado', ['Data em que o projeto deve começar. As tarefas devem começar a partir dela; ao importar um cronograma você pode ajustar esta data.'])}</dd></div>
        <div><dt>Fim</dt><dd>${Tip.wrap(fmt(p.end_date), 'Fim planejado', ['Data limite do projeto.', 'Com o projeto "Em andamento", passar desta data deixa a saúde de prazo em "Crítico".'])}</dd></div>
        <div><dt>Duração</dt><dd>${Tip.wrap(duration(p.start_date, p.end_date) + ' dias', 'Duração', [`Dias corridos entre o início (${fmt(p.start_date)}) e o fim (${fmt(p.end_date)}), contando os dois dias.`, 'Inclui fins de semana e feriados.'])}</dd></div>
        <div><dt>Progresso</dt><dd>${Tip.wrap(p.progress + '%', 'Progresso do projeto', ['Média do progresso das tarefas principais do projeto.', '• Tarefa-pai: calculada pelas filhas, ponderada pela duração de cada uma.', '• Tarefas canceladas não entram na conta.', '• Marcos não pesam no cálculo.', `Resultado atual: ${p.progress}% (${p.task_count} tarefa(s) ativas, ${p.late_count || 0} atrasada(s)).`])}</dd></div>
        <div><dt>Orçamento</dt><dd>${Tip.wrap(Number(p.budget) > 0 ? money(p.budget) : '—', 'Orçamento (BAC)', ['Valor total aprovado para o projeto. É a base de todos os indicadores de custo.',
          p.evm && p.evm.available ? `Custo lançado até agora: ${money(p.evm.ac)} (${Math.round(p.evm.ac / p.evm.bac * 100)}% do orçamento), somando os lançamentos da aba Financeiro.` : 'Sem custo lançado ou sem tarefas: os indicadores de custo ainda não podem ser calculados.',
          p.evm && p.evm.available ? `Projeção de custo no término: ${money(p.evm.eac)} (${p.evm.eac > p.evm.bac ? 'acima' : 'dentro'} do orçamento).` : ''])}</dd></div></dl>
        ${p.health && p.health.dims && p.health.dims.length ? `<div class="hdims">${p.health.dims.map(d => `<div class="hdim ${d.level === 'NA' ? 'h-grey' : H_CLASS[d.level]}"><i></i><div><b>${esc(d.label)}</b><span>${esc(d.detail)}</span></div></div>`).join('')}</div>` : ''}
        ${evmPanel(p)}</section>
      <section class="panel"><div id="proj-nav"></div>
        <div class="bar-actions" id="tab-actions" style="margin-bottom:16px"></div><div id="tab-body"></div></section>`;
    drawNav();
    await renderTab();
  }

  function drawNav() {
    const nav = document.getElementById('proj-nav'); if (!nav) return;
    const g = view.nav.find(x => x.tabs.some(t => t[0] === view.tab)) || view.nav[0];
    const cur = g.tabs.find(t => t[0] === view.tab) || g.tabs[0];
    view.last = view.last || {}; view.last[g.k] = cur[0];
    nav.innerHTML = `<div class="tabs proj-tabs pn-groups" role="tablist" aria-label="Áreas do projeto">${view.nav.map(x => `<button type="button" role="tab" class="tab${x === g ? ' active' : ''}" aria-selected="${x === g}" data-action="tab" data-tab="${(view.last[x.k] || x.tabs[0][0])}">${x.label}</button>`).join('')}</div>
      <div class="pn-sub" role="tablist" aria-label="${esc(g.label)}">${g.tabs.map(t => `<button type="button" role="tab" class="pn-pill${t[0] === cur[0] ? ' active' : ''}" aria-selected="${t[0] === cur[0]}" data-action="tab" data-tab="${t[0]}">${t[1]}</button>`).join('')}<span class="pn-hint">${esc(cur[2])}</span></div>`;
  }

  const btn = (action, label, extra = '', primary = true) => `<button class="btn ${primary ? 'btn-primary ' : ''}btn-sm" data-action="${action}" ${extra}>${label}</button>`;

  async function renderTab() {
    const host = document.getElementById('tab-body'), act = document.getElementById('tab-actions');
    if (!host) return;
    const t = view.tab;
    act.innerHTML = t === 'finance' ? btn('new-fin', '+ Nota fiscal', 'data-kind="NF_SERVICO"') + btn('new-fin', '+ Fatura', 'data-kind="FATURA"', false)
      : t === 'docs' ? btn('new-doc', '+ Adicionar documento')
      : t === 'changes' ? btn('rp-new-change', '+ Nova change')
      : t === 'burndown' || t === 'pert' || t === 'prio' ? ''
      : t === 'ishikawa' ? btn('ish-new', '+ Novo diagrama')
      : t === 'triggers' ? btn('em-new-rule', '+ Novo gatilho')
      : t === 'marcos' ? btn('new-milestone', '+ Novo marco')
      : btn('new-task', '+ Nova tarefa') + btn('new-milestone', '+ Novo marco', '', false) + btn('mpp-open', 'Importar', `data-project="${view.projectId}"`, false);
    if (t === 'gantt') {
      const p = view.project;
      Gantt.render(host, ganttRows([p], view.tasks), { key: 'project-' + p.id, title: p.name, subtitle: p.owner || '', onRow: onGanttRow });
    } else if (t === 'tasks') host.innerHTML = taskTable(view.tasks, { tree: true });
    else if (t === 'marcos') renderMilestones(host, view.tasks.filter(x => x.is_milestone), false);
    else if (t === 'burndown') Burn.render(host, view.project, view.tasks);
    else if (t === 'pert') Pert.render(host, view.project, view.tasks);
    else if (t === 'prio') Prio.tab(host, view.tasks);
    else if (t === 'finance') await renderFinance(host);
    else if (t === 'docs') await renderDocs(host);
    else if (t === 'changes') await Rep.changesTab(host, view.projectId);
    else if (t === 'ishikawa') await Ish.tab(host, view.projectId);
    else if (t === 'triggers') await Mail.projectTab(host, view.projectId);
  }

  function onGanttRow(r) {
    if (r.kind === 'project') { if (view.name === 'projeto') openProjectForm(r.id); else location.hash = '#/projeto/' + r.id; }
    else openTaskForm(view.tasks, r.id);
  }

  // ------------------------------------------------------------------ tabela de tarefas
  function taskTable(list, { tree = false, showProject = false } = {}) {
    if (!list.length) return empty('Nenhuma tarefa', 'Use “Nova tarefa” para quebrar o escopo em atividades com prazo. Crie tarefas pai e, dentro delas, tarefas filhas.');
    const byId = Object.fromEntries(list.map(t => [t.id, t]));
    const rows = tree ? treeOrder(list) : list.map(t => ({ t, level: 0 }));
    return `<div class="table-wrap"><table class="tbl"><thead><tr><th>Tarefa</th>${showProject ? '<th>Projeto</th>' : ''}<th>Início</th><th>Fim</th><th>Progresso</th><th>Situação</th><th></th></tr></thead><tbody>
      ${rows.map(({ t, level }) => `<tr><td class="strong">${'<span class="t-ind"></span>'.repeat(level)}${t.is_milestone ? '<i class="dia-ic" title="Marco"></i>' : ''}${esc(t.name)}
        <span class="small">${'<span class="t-ind"></span>'.repeat(level)}${esc(t.responsible || 'Sem responsável')}${!tree && t.parent_id && byId[t.parent_id] ? ` · filha de ${esc(byId[t.parent_id].name)}` : ''}${t.child_count ? ` · ${t.child_count} filha(s), calculada` : ''}</span></td>
        ${showProject ? `<td><a href="#/projeto/${t.project_id}">${esc(t.project_name)}</a></td>` : ''}
        <td>${t.is_milestone ? '—' : fmt(t.start_date)}</td><td class="${isLate(t) ? 'late-txt' : ''}">${fmt(t.end_date)}</td><td>${pbar(t.progress, t.status === 'CONCLUIDA')}</td><td>${taskBadge(t)}</td>
        <td class="acts">${!t.parent_id ? `<button class="btn btn-sm" style="min-height:28px;padding:0 10px" title="Criar tarefa filha" data-action="new-subtask" data-id="${t.id}">+ Filha</button>` : ''}
        <button class="icon-btn" title="Editar" aria-label="Editar tarefa" data-action="edit-task" data-id="${t.id}">${ic('edit')}</button>
        <button class="icon-btn del" title="Excluir" aria-label="Excluir tarefa" data-action="del-task" data-id="${t.id}">${ic('trash')}</button></td></tr>`).join('')}</tbody></table></div>`;
  }

  // ------------------------------------------------------------------ marcos
  const dayDiff = iso => dayNum(iso) - dayNum(todayIso());
  function renderMilestones(host, list, showProject) {
    view.msRender = () => renderMilestones(host, list, showProject);
    const late = list.filter(isLate), done = list.filter(t => t.status === 'CONCLUIDA');
    const tab = view.msTab;
    const shown = (tab === 'late' ? late : tab === 'done' ? done : list).slice().sort((a, b) => a.end_date.localeCompare(b.end_date) || a.id - b.id);
    const T = (k, label, n, red) => `<button class="ms-tab ${tab === k ? 'active' : ''}" data-action="ms-tab" data-tab="${k}">${label} <span class="cnt${red && n ? ' red' : ''}">${n}</span></button>`;
    host.innerHTML = `<div class="ms-tabs">${T('all', 'Todos', list.length)}${T('late', 'Atrasados', late.length, true)}${T('done', 'Concluídos', done.length)}</div>` +
      (shown.length ? shown.map(t => {
        const d = new Date(t.end_date + 'T00:00:00Z'), n = dayDiff(t.end_date), isDone = t.status === 'CONCLUIDA', l = isLate(t);
        const mon = d.toLocaleDateString('pt-BR', { month: 'short', timeZone: 'UTC' }).replace('.', '').toUpperCase();
        let wd = d.toLocaleDateString('pt-BR', { weekday: 'long', timeZone: 'UTC' }); wd = wd[0].toUpperCase() + wd.slice(1);
        const pill = isDone ? '<span class="pill ok">Concluído</span>' : n < 0 ? `<span class="pill late">${-n} ${-n === 1 ? 'dia atrás' : 'dias atrás'}</span>` : n === 0 ? '<span class="pill soon">Hoje</span>' : `<span class="pill${n <= 7 ? ' soon' : ''}">em ${n} ${n === 1 ? 'dia' : 'dias'}</span>`;
        return `<article class="ms ${l ? 'late' : ''} ${isDone ? 'done' : ''}">
          <div class="ms-date"><div class="ms-mon">${mon}${d.getUTCFullYear() !== new Date().getFullYear() ? ' ' + d.getUTCFullYear() : ''}</div><div class="ms-day">${d.getUTCDate()}</div><div class="ms-wd">${wd}</div></div>
          <button class="ms-check" title="${isDone ? 'Reabrir marco' : 'Concluir marco'}" aria-label="${isDone ? 'Reabrir marco' : 'Concluir marco'}" data-action="ms-toggle" data-id="${t.id}" data-done="${isDone ? 1 : 0}" ${t.child_count ? 'disabled' : ''}>${ic('check')}</button>
          <div><h3>${esc(t.name)}</h3><p>${showProject ? `<a href="#/projeto/${t.project_id}"><b>${esc(t.project_name)}</b></a> · ` : ''}Responsável: ${esc(t.responsible || '—')}</p>${pbar(t.progress, isDone)}</div>
          ${pill}<button class="icon-btn" title="Editar" aria-label="Editar marco" data-action="edit-task" data-id="${t.id}">${ic('edit')}</button></article>`;
      }).join('') : empty('Nenhum marco aqui', 'Marcos são entregas ou pontos de controle. Marque uma tarefa como marco ou use “Novo marco”.'));
  }

  async function viewMilestones() {
    const [tasks, projects] = await Promise.all([api('GET', 'tasks'), api('GET', 'projects')]);
    view.tasks = tasks; view.projects = projects;
    main.innerHTML = pageHead(['SysAttack', 'Marcos'], 'Marcos', 'Entregas e pontos de controle de todos os projetos.') + `
      <section class="panel"><div class="panel-head"><div class="filters" style="margin:0">
        <select id="m-p" aria-label="Projeto"><option value="">Todos os projetos</option>${projects.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></div>
        ${btn('new-milestone', '+ Novo marco')}</div><div id="m-host"></div></section>`;
    const draw = () => { const pj = document.getElementById('m-p').value;
      renderMilestones(document.getElementById('m-host'), tasks.filter(t => t.is_milestone && (!pj || String(t.project_id) === pj)), true); };
    document.getElementById('m-p').addEventListener('input', draw); draw();
  }

  // ------------------------------------------------------------------ tarefas (todas) e cronograma geral
  async function viewTasks() {
    const [tasks, projects] = await Promise.all([api('GET', 'tasks'), api('GET', 'projects')]);
    view.tasks = tasks; view.projects = projects;
    main.innerHTML = pageHead(['SysAttack', 'Tarefas'], 'Tarefas', 'Todas as atividades de todos os projetos.') + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Minha fila</h2>${btn('new-task', '+ Nova tarefa')}</div>
        <div class="filters"><input id="f-q" type="search" placeholder="Buscar por tarefa ou responsável" aria-label="Buscar">
        <select id="f-p" aria-label="Projeto"><option value="">Todos os projetos</option>${projects.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select>
        <select id="f-s" aria-label="Situação"><option value="">Todas as situações</option><option value="LATE">Atrasadas</option>
        ${Object.entries(T_STATUS).map(([k, v]) => `<option value="${k}">${v[0]}</option>`).join('')}</select></div><div id="t-list"></div></section>`;
    const apply = () => {
      const q = document.getElementById('f-q').value.toLowerCase(), pj = document.getElementById('f-p').value, st = document.getElementById('f-s').value;
      const f = tasks.filter(t => (!pj || String(t.project_id) === pj) && (!st || (st === 'LATE' ? isLate(t) : t.status === st))
        && (!q || (t.name + ' ' + t.responsible).toLowerCase().includes(q)));
      document.getElementById('t-list').innerHTML = taskTable(f, { showProject: true });
    };
    ['f-q', 'f-p', 'f-s'].forEach(i => document.getElementById(i).addEventListener('input', apply));
    apply();
  }

  async function viewSchedule() {
    const [tasks, projects] = await Promise.all([api('GET', 'tasks'), api('GET', 'projects')]);
    view.tasks = tasks; view.projects = projects;
    main.innerHTML = pageHead(['SysAttack', 'Cronograma'], 'Cronograma', 'Todos os projetos na linha do tempo. Clique em uma barra para abrir.') + '<section class="panel" id="g-host"></section>';
    Gantt.render(document.getElementById('g-host'), ganttRows(projects, tasks), { key: 'all', title: 'Todos os projetos', subtitle: `${projects.length} projeto(s)`, onRow: onGanttRow });
  }

  // ------------------------------------------------------------------ financeiro
  async function renderFinance(host) {
    const f = await api('GET', `projects/${view.projectId}/finance`);
    view.fin = f.entries;
    const s = f.summary, budget = Number(f.budget) || 0, total = Number(s.total), paid = Number(s.paid), saldo = budget - total;
    const pct = budget > 0 ? Math.min(100, Math.round(total / budget * 100)) : 0;
    host.innerHTML = `<div class="mini-kpis">
        <div class="mini"><span>Orçamento</span><b>${budget > 0 ? money(budget) : '—'}</b></div>
        <div class="mini accent"><span>Lançado</span><b>${money(total)}</b></div>
        <div class="mini"><span>Pago</span><b>${money(paid)}</b></div>
        <div class="mini"><span>A pagar</span><b>${money(total - paid)}</b></div>
        <div class="mini ${budget > 0 && saldo < 0 ? 'neg' : ''}"><span>Saldo do orçamento</span><b>${budget > 0 ? money(saldo) : '—'}</b></div>
        <div class="mini"><span>Notas · Faturas</span><b style="font-size:16px">${money(s.nf_total)} · ${money(s.invoice_total)}</b></div></div>
      ${budget > 0 ? `<div class="budget-bar"><div class="pbar"><div class="pbar-track"><div class="pbar-fill ${saldo < 0 ? '' : ''}" style="width:${pct}%;${saldo < 0 ? 'background:var(--red)' : ''}"></div></div><span style="width:auto">${total > 0 ? Math.round(total / budget * 100) : 0}% do orçamento</span></div></div>` : ''}
      ${f.entries.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th>Tipo</th><th>Número</th><th>Prestador / fornecedor</th><th>Emissão</th><th>Vencimento</th><th class="num">Valor</th><th>Situação</th><th></th></tr></thead><tbody>
      ${f.entries.map(e => { const over = e.status !== 'PAGO' && e.due_date && e.due_date < todayIso();
        return `<tr><td>${badge(FIN_KIND, e.kind)}</td><td class="strong">${esc(e.number || '—')}</td>
        <td>${esc(e.party)}${e.description ? `<span class="small">${esc(e.description)}</span>` : ''}</td><td>${fmt(e.issue_date)}</td>
        <td class="${over ? 'late-txt' : ''}">${e.due_date ? fmt(e.due_date) : '—'}</td><td class="num strong">${money(e.amount)}</td>
        <td>${e.status === 'PAGO' ? `<span class="badge b-green">Pago</span><span class="small">${fmt(e.paid_date)}</span>` : over ? '<span class="badge b-red">Vencido</span>' : '<span class="badge b-orange">Pendente</span>'}</td>
        <td class="acts"><button class="icon-btn" title="Editar" aria-label="Editar lançamento" data-action="edit-fin" data-id="${e.id}">${ic('edit')}</button>
        <button class="icon-btn del" title="Excluir" aria-label="Excluir lançamento" data-action="del-fin" data-id="${e.id}">${ic('trash')}</button></td></tr>`; }).join('')}</tbody></table></div>`
        : empty('Nenhum lançamento', 'Lance as notas fiscais de serviço e faturas deste projeto para acompanhar os gastos contra o orçamento.')}`;
  }

  function openFinanceForm(id, kind) {
    const e = id ? view.fin.find(x => x.id === id) : { kind: kind || 'NF_SERVICO', number: '', party: '', description: '', issue_date: todayIso(), due_date: '', amount: '', status: 'PENDENTE', paid_date: '' };
    const form = openDialog(id ? 'Editar lançamento' : (e.kind === 'FATURA' ? 'Lançar fatura' : 'Lançar nota fiscal de serviço'), `
      <div class="field"><label for="fk">Tipo</label><select id="fk" name="kind">${Object.entries(FIN_KIND).map(([k, v]) => `<option value="${k}"${k === e.kind ? ' selected' : ''}>${v[0]}</option>`).join('')}</select></div>
      <div class="field"><label for="fn">Número</label><input id="fn" name="number" maxlength="60" value="${esc(e.number)}" placeholder="Ex.: NF 1523"></div>
      <div class="field full"><label for="fp">Prestador / fornecedor</label><input id="fp" name="party" maxlength="160" value="${esc(e.party)}" required></div>
      <div class="field full"><label for="fd">Descrição</label><input id="fd" name="description" maxlength="500" value="${esc(e.description)}" placeholder="O que foi contratado / faturado"></div>
      <div class="field"><label for="fi">Emissão</label><input id="fi" name="issue_date" type="date" value="${e.issue_date}" required></div>
      <div class="field"><label for="fv">Vencimento</label><input id="fv" name="due_date" type="date" value="${e.due_date || ''}"></div>
      <div class="field"><label for="fa">Valor (R$)</label>${SA.moneyInput('fa', 'amount', e.amount, { required: true })}</div>
      <div class="field"><label for="fs">Situação</label><select id="fs" name="status"><option value="PENDENTE"${e.status === 'PENDENTE' ? ' selected' : ''}>Pendente</option><option value="PAGO"${e.status === 'PAGO' ? ' selected' : ''}>Pago</option></select></div>
      <div class="field full" id="g-paid"><label for="fg">Data do pagamento</label><input id="fg" name="paid_date" type="date" value="${e.paid_date || todayIso()}"></div>`,
      async d => {
        const body = { ...d, project_id: view.projectId };
        await api(id ? 'PUT' : 'POST', id ? 'finance/' + id : 'finance', body);
        toast(id ? 'Lançamento salvo.' : 'Lançamento criado.');
      });
    const sync = () => { form.querySelector('#g-paid').hidden = form.elements.status.value !== 'PAGO'; };
    form.elements.status.addEventListener('change', sync); sync();
  }

  // ------------------------------------------------------------------ documentos
  async function renderDocs(host) {
    const docs = await api('GET', `projects/${view.projectId}/documents`);
    view.docs = docs;
    host.innerHTML = docs.length ? `<div class="docs">${docs.map(d => { const ext = extOf(d.file_name);
      const canDel = isAdmin() || d.uploaded_by_id === me.id;
      return `<div class="doc"><div class="doc-ic ${ext === 'pdf' ? 'pdf' : ''}">${esc(ext.toUpperCase())}</div>
        <div><b>${esc(d.title)}</b><small>${DOC_CAT[d.category] || 'Outro'} · ${esc(d.file_name)} · ${fileSize(d.size)} · enviado por ${esc(d.uploaded_by)} em ${fmt(d.uploaded_at.slice(0, 10))}</small>
        ${d.description ? `<small style="display:block;margin-top:2px">${esc(d.description)}</small>` : ''}</div>
        <div class="doc-acts">${PREVIEW.includes(ext) ? `<button class="btn btn-sm" data-action="doc-view" data-id="${d.id}">Visualizar</button>` : ''}
        <a class="btn btn-sm" href="api/documents/${d.id}/file?download=1">Baixar</a>
        ${canDel ? `<button class="icon-btn" title="Editar" aria-label="Editar documento" data-action="edit-doc" data-id="${d.id}">${ic('edit')}</button>
        <button class="icon-btn del" title="Excluir" aria-label="Excluir documento" data-action="del-doc" data-id="${d.id}">${ic('trash')}</button>` : ''}</div></div>`; }).join('')}</div>`
      : empty('Nenhum documento', 'Guarde aqui PDFs, especificações, contratos, desenhos e outros arquivos do projeto (até 25 MB cada).');
  }

  function openDocForm(doc) {
    const cats = Object.entries(DOC_CAT).map(([k, v]) => `<option value="${k}"${doc && doc.category === k ? ' selected' : ''}>${v}</option>`).join('');
    openDialog(doc ? 'Editar documento' : 'Adicionar documento', (doc
      ? `<p class="hint full" style="margin:0">Arquivo: <b>${esc(doc.file_name)}</b> (para trocar o arquivo, envie um novo documento).</p>
      <div class="field"><label for="dt">Título</label><input id="dt" name="title" maxlength="160" required value="${esc(doc.title)}"></div>`
      : `<div class="field full"><label for="df">Arquivo (até 25 MB)</label><input id="df" name="file" type="file" required accept=".pdf,.png,.jpg,.jpeg,.gif,.webp,.txt,.csv,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.zip,.dwg,.dxf"></div>
      <div class="field"><label for="dt">Título (opcional)</label><input id="dt" name="title" maxlength="160" placeholder="Se vazio, usa o nome do arquivo"></div>`) + `
      <div class="field"><label for="dc">Categoria</label><select id="dc" name="category">${cats}</select></div>
      <div class="field full"><label for="dd">Descrição (opcional)</label><textarea id="dd" name="description" maxlength="500" style="min-height:70px">${doc ? esc(doc.description || '') : ''}</textarea></div>`,
      async d => {
        if (doc) {
          await api('PUT', 'documents/' + doc.id, { title: (d.title || '').trim(), category: d.category, description: d.description || '' });
          toast('Documento atualizado.');
          return;
        }
        const file = d.file;
        if (!file || !file.name) throw new Error('Selecione um arquivo.');
        if (file.size > 25 * 1024 * 1024) throw new Error('O arquivo é maior que 25 MB.');
        const r = await fetch(`api/projects/${view.projectId}/documents`, { method: 'POST', credentials: 'same-origin', body: file, headers: {
          'X-Requested-With': 'SysAttack', 'Content-Type': 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name),
          'X-Doc-Title': encodeURIComponent(d.title || ''), 'X-Doc-Category': d.category, 'X-Doc-Description': encodeURIComponent(d.description || '') } });
        const data = await r.json().catch(() => null);
        if (r.status === 401) { location.replace('login.html'); return; }
        if (!r.ok) throw new Error((data && data.error) || 'Falha ao enviar o arquivo.');
        toast('Documento enviado.');
      }, doc ? 'Salvar' : 'Enviar');
  }

  function openPreview(d) {
    const ext = extOf(d.file_name), url = `api/documents/${d.id}/file`;
    dlg.className = 'wide';
    dlg.innerHTML = `<div class="dlg-head"><h2>${esc(d.title)}</h2><button class="icon-btn" data-action="close" aria-label="Fechar">${ic('x')}</button></div><div class="dlg-body">
      ${ext === 'pdf' || ext === 'txt' ? `<iframe class="preview" src="${url}" title="${esc(d.title)}"></iframe>` : `<img class="preview" src="${url}" alt="${esc(d.title)}">`}
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><a class="btn btn-sm" href="${url}" target="_blank" rel="noopener">Abrir em nova aba</a>
      <a class="btn btn-sm btn-primary" href="${url}?download=1">Baixar</a></div></div>`;
    dlg.showModal();
  }

  // ------------------------------------------------------------------ Configurações IA Agent (Claude) — administrador
  async function viewPrivacy() {
    const r = await fetch('privacidade-texto.html', { cache: 'no-store' });
    if (!r.ok) throw new Error('Não foi possível carregar o texto da política.');
    main.innerHTML = pageHead(['SysAttack', 'Política de Privacidade'], 'Política de Privacidade', 'Como o SysAttack trata dados pessoais.') +
      `<section class="panel policy">${await r.text()}</section>`;
  }

  async function viewAI() {
    const st = await api('GET', 'ai');
    const head = pageHead(['SysAttack', 'Configurações IA Agent'], 'Configurações IA Agent', 'Conecte uma conta Anthropic para acoplar o Claude ao SysAttack.');
    const security = `<section class="panel"><div class="panel-head"><h2 class="panel-title">Segurança e custos</h2></div><ul class="ai-list">
      <li>A chave é guardada <b>criptografada</b> no servidor e nunca é exibida de novo — só a versão mascarada.</li>
      <li>Somente <b>administradores</b> veem e alteram esta página.</li>
      <li>O uso da API é <b>cobrado na conta do Claude Console</b>, separada de qualquer assinatura do Claude.ai. Use uma <b>chave exclusiva</b> para o SysAttack, com validade, e defina limite de gastos no workspace.</li>
      <li>O servidor do SysAttack precisa de acesso à internet (<code>api.anthropic.com</code>, porta 443). Se a rede usa proxy, avise a TI.</li></ul></section>`;
    if (!st.connected) {
      main.innerHTML = head + `<section class="panel"><div class="panel-head"><h2 class="panel-title">Conexão com o Claude</h2><span class="ai-status"><i></i>Desconectado</span></div>
        <div class="notice" style="margin-bottom:18px"><b>Por que chave de API e não “entrar com a conta”?</b> A Anthropic não permite usar o login do Claude.ai (Free, Pro ou Max) dentro de sistemas de terceiros.
          A conexão oficial é feita com uma chave de API criada na conta da empresa no <b>Claude Console</b>.</div>
        <ol class="steps"><li>Entre em <b>platform.claude.com/settings/keys</b> com a conta da empresa (com faturamento ou créditos ativos).</li>
          <li>Clique em <b>Create key</b>, dê o nome <b>SysAttack</b> e, se possível, defina uma data de expiração.</li>
          <li>Copie a chave (começa com <code>sk-ant-</code>) — ela só aparece uma vez — e cole abaixo.</li></ol>
        <form id="ai-form" class="fgrid ai-form" novalidate autocomplete="off">
          <div class="field full"><label for="ai-k">Chave de API</label><input id="ai-k" name="api_key" type="password" autocomplete="off" spellcheck="false" placeholder="sk-ant-…" required></div>
          <div class="field full"><label for="ai-m">Modelo</label><select id="ai-m" name="model">${st.default_models.map((m, i) => `<option value="${esc(m)}"${i === 0 ? ' selected' : ''}>${esc(m)}${i === 0 ? ' (recomendado)' : ''}</option>`).join('')}</select></div>
          <details><summary>Opções avançadas</summary><div class="field"><label for="ai-w">ID do workspace (opcional)</label><input id="ai-w" name="workspace_id" placeholder="wrkspc_… — só se a chave não for de um workspace" maxlength="80"></div></details>
          <div class="form-error" hidden role="alert"></div>
          <div class="form-actions" style="justify-content:flex-start"><button type="submit" class="btn btn-primary">Conectar</button></div></form></section>` + security;
      const form = document.getElementById('ai-form'), err = form.querySelector('.form-error');
      form.addEventListener('submit', async e => {
        e.preventDefault();
        const b = form.querySelector('[type=submit]');
        b.disabled = true; b.textContent = 'Validando na Anthropic…'; err.hidden = true;
        try {
          await api('POST', 'ai/connect', { api_key: form.api_key.value.trim(), model: form.model.value, workspace_id: form.workspace_id.value.trim() });
          form.api_key.value = '';
          toast('Claude conectado.'); await route();
        } catch (ex) { err.textContent = ex.message; err.hidden = false; b.disabled = false; b.textContent = 'Conectar'; }
      });
      return;
    }
    const test = st.last_test_at ? `${st.last_test_ok ? '<span class="badge b-green">OK</span>' : '<span class="badge b-red">Falhou</span>'} <span class="small" style="display:inline">${esc(st.last_test_at.slice(0, 16))} · ${esc(st.last_test_msg || '')}</span>` : '<span class="small" style="display:inline">Ainda não testado</span>';
    main.innerHTML = head + `<section class="panel"><div class="panel-head"><h2 class="panel-title">Conexão com o Claude</h2><span class="ai-status on"><i></i>Conectado</span></div>
      <dl class="meta-grid" style="margin-top:0;padding-top:0;border:0">
        <div><dt>Chave</dt><dd><code>${esc(st.key_hint)}</code></dd></div>
        <div><dt>Conectado por</dt><dd>${esc(st.connected_by || '—')}</dd></div>
        <div><dt>Em</dt><dd>${esc((st.connected_at || '').slice(0, 16))}</dd></div>
        ${st.workspace_id ? `<div><dt>Workspace</dt><dd>${esc(st.workspace_id)}</dd></div>` : ''}</dl>
      <div class="field" style="max-width:560px;margin-top:24px"><label for="ai-m">Modelo usado pelo assistente</label>
        <div class="ai-row"><select id="ai-m" aria-label="Modelo"><option value="${esc(st.model)}" selected>${esc(st.model)}</option></select>
        <button class="btn btn-sm" data-action="ai-save-model">Salvar modelo</button></div></div>
      <p style="margin:22px 0 6px"><b>Último teste:</b> ${test}</p>
      <div class="bar-actions" style="margin-top:18px"><button class="btn btn-primary" data-action="ai-test">Testar conexão</button>
        <button class="btn btn-danger" data-action="ai-disconnect">Desconectar</button></div>
      <p class="hint" style="margin-top:14px">“Desconectar” apaga a chave do SysAttack. Para encerrar o acesso por completo, desative ou exclua também a chave no Claude Console.</p></section>` + security;
    api('GET', 'ai/models').then(r => {   // lista os modelos que a chave realmente enxerga
      const sel = document.getElementById('ai-m'); if (!sel) return;
      const ids = r.models.includes(st.model) ? r.models : [st.model, ...r.models];
      sel.innerHTML = ids.map(m => `<option value="${esc(m)}"${m === st.model ? ' selected' : ''}>${esc(m)}</option>`).join('');
    }).catch(() => {});
  }

  // ------------------------------------------------------------------ usuários
  async function viewUsers() {
    const list = await api('GET', 'users');
    view.users = list;
    const sub = isAdmin() ? 'Quem pode entrar no sistema. Bloqueie ou ative acessos quando necessário.'
                          : 'Seus dados de acesso. Para trocar a senha, enviaremos um código de confirmação ao seu e-mail.';
    const status = u => (u.active ? '<span class="badge b-green">Ativo</span>' : '<span class="badge b-red">Bloqueado</span>')
      + (u.invite_pending ? ' <span class="badge b-orange" title="Convite enviado; a pessoa ainda não criou a senha">Aguardando senha</span>' : '')
      + (u.totp_enabled ? ' <span class="badge b-grey" title="Verificação em duas etapas ativa">2FA ativo</span>'
        : u.totp_required ? ' <span class="badge b-orange" title="Exigido, mas ainda não configurado">2FA exigido</span>' : '');
    main.innerHTML = pageHead(['SysAttack', 'Usuários'], isAdmin() ? 'Usuários' : 'Meu usuário', sub) + `
      <section class="panel"><div class="panel-head"><h2 class="panel-title">${isAdmin() ? 'Acessos cadastrados' : 'Dados do acesso'}</h2>
        ${isAdmin() ? '<button class="btn btn-primary btn-sm" data-action="new-user">+ Novo usuário</button>' : ''}</div>
        <div class="table-wrap"><table class="tbl"><thead><tr><th>Nome</th><th>Cargo</th><th>E-mail</th><th>Perfil</th><th>Situação</th><th></th></tr></thead><tbody>
        ${list.map(u => { const self = u.id === me.id;
          return `<tr><td class="strong">${esc(u.name)}${self ? '<span class="small">Você</span>' : ''}</td><td>${esc(u.job_title || '—')}</td><td>${esc(u.email)}</td>
          <td><span class="badge ${u.role === 'ADMIN' ? 'b-dark' : 'b-grey'}">${ROLES[u.role]}</span></td>
          <td>${status(u)}</td>
          <td class="acts">
            ${self ? `<button class="icon-btn" title="Alterar senha" aria-label="Alterar senha" data-action="change-password">${ic('key')}</button>` : ''}
            ${isAdmin() ? `<button class="icon-btn" title="Editar" aria-label="Editar usuário" data-action="edit-user" data-id="${u.id}">${ic('edit')}</button>
              ${self ? '' : `<button class="icon-btn" title="${u.invite_pending ? 'Reenviar convite por e-mail' : 'Enviar link para redefinir a senha'}" aria-label="Enviar e-mail de acesso" data-action="invite-user" data-id="${u.id}">${ic('mail')}</button>
              ${u.totp_enabled ? `<button class="icon-btn" title="Redefinir o 2FA (perdeu o celular)" aria-label="Redefinir 2FA" data-action="reset-2fa" data-id="${u.id}">${ic('shield')}</button>` : ''}
              <button class="icon-btn" title="${u.active ? 'Bloquear acesso' : 'Ativar acesso'}" aria-label="${u.active ? 'Bloquear' : 'Ativar'} usuário" data-action="toggle-user" data-id="${u.id}" data-active="${u.active ? 1 : 0}">${ic(u.active ? 'lock' : 'unlock')}</button>
              <button class="icon-btn del" title="Excluir" aria-label="Excluir usuário" data-action="del-user" data-id="${u.id}">${ic('trash')}</button>`}` : ''}
          </td></tr>`; }).join('')}</tbody></table></div></section>
      <section class="panel" id="sec-panel"><div class="panel-head"><h2 class="panel-title">Segurança da conta</h2></div><div id="sec-body"><p class="hint">Carregando…</p></div></section>`;
    renderSecurity();
  }

  // ---- verificação em duas etapas (própria conta)
  async function renderSecurity() {
    const box = document.getElementById('sec-body'); if (!box) return;
    let st;
    try { st = await api('GET', 'me/2fa'); } catch (ex) { box.innerHTML = `<p class="hint msg-err">${esc(ex.message)}</p>`; return; }
    box.innerHTML = `<p><b>Verificação em duas etapas (2FA):</b> ${st.enabled ? '<span class="badge b-green">Ativa</span>' : '<span class="badge b-grey">Desativada</span>'}
      ${st.required ? '<span class="badge b-orange">Exigida pelo administrador</span>' : ''}</p>
      <p class="hint">Além da senha, o login pede um código de 6 dígitos de um aplicativo autenticador (Google Authenticator, Microsoft Authenticator, Authy).${st.enabled ? ` Códigos de recuperação restantes: <b>${st.recovery_left}</b>.` : ''}</p>
      <p style="margin-top:14px">${st.enabled
        ? `<button class="btn btn-sm" data-action="2fa-codes">Gerar novos códigos de recuperação</button> ${st.required ? '' : '<button class="btn btn-sm" data-action="2fa-off">Desativar</button>'}`
        : '<button class="btn btn-primary btn-sm" data-action="2fa-on">Ativar verificação em duas etapas</button>'}</p>`;
  }

  function recoveryHtml(codes) {
    return `<div class="full"><p><b>Guarde estes códigos de recuperação.</b> Cada um vale uma única vez e substitui o código do aplicativo se você perder o celular. Eles não serão mostrados de novo.</p>
      <pre class="codes" style="font-size:16px;line-height:1.8;background:#f4f4f4;padding:12px 16px;user-select:all">${codes.map(esc).join('\n')}</pre></div>`;
  }

  // ativação do 2FA; `forced` = diálogo sem opção de fechar (exigência do administrador)
  async function open2faSetup(forced) {
    const r = await api('POST', 'me/2fa/start', {});
    const form = openDialog('Ativar verificação em duas etapas', `
      <div class="full"><p class="hint"><b>1.</b> No aplicativo autenticador, escaneie o QR code (ou digite a chave).</p>
        <div class="qr-box">
          <div class="qr-frame">${QR.svg(r.uri, { scale: 4, margin: 0 })}</div>
          <div class="qr-key"><p class="hint">Chave manual:</p><code style="user-select:all">${esc(r.secret.replace(/(.{4})/g, '$1 ').trim())}</code></div></div>
        <p class="hint"><b>2.</b> Digite o código de 6 dígitos que o aplicativo mostra.</p></div>
      <div class="field full"><label for="tf">Código de 6 dígitos</label><input id="tf" name="code" inputmode="numeric" maxlength="7" autocomplete="one-time-code" required></div>`,
      async d => {
        const res = await api('POST', 'me/2fa/enable', { code: d.code });
        dlg.close();
        const f2 = openDialog('Códigos de recuperação', recoveryHtml(res.recovery_codes), async () => {}, 'Guardei os códigos');
        if (forced) lockDialog(f2);
        throw Object.assign(new Error(''), { silent: true });
      }, 'Ativar');
    if (forced) lockDialog(form);
  }

  // diálogo que não pode ser fechado (Esc e botões de fechar/cancelar removidos)
  function lockDialog(form) {
    dlg.addEventListener('cancel', blockEsc);
    dlg.querySelectorAll('[data-action="close"]').forEach(x => x.remove());
    dlg.dataset.locked = '1';
  }
  function blockEsc(e) { if (dlg.dataset.locked) e.preventDefault(); }
  // o navegador só deixa cancelar o Esc após uma interação do usuário; se fechar mesmo assim, reabre
  dlg.addEventListener('close', () => { if (dlg.dataset.locked && !dlg.open) dlg.showModal(); });

  function openFirstPassword() {
    const form = openDialog('Crie a sua senha', `
      <div class="full"><p class="hint">Este é o seu primeiro acesso com uma senha temporária. Por segurança, defina agora uma senha só sua para continuar.</p></div>
      <div class="field full"><label for="fc">Senha temporária (a que você recebeu)</label><input id="fc" name="current" type="password" autocomplete="current-password" required></div>
      <div class="field full"><label for="fn">Nova senha</label><input id="fn" name="password" type="password" autocomplete="new-password" required></div>
      <div class="full">${SA.rulesHtml()}</div>
      <div class="field full"><label for="fr">Repita a nova senha</label><input id="fr" name="repeat" type="password" autocomplete="new-password" required></div>`,
      async d => {
        if (d.password !== d.repeat) throw new Error('As senhas não conferem.');
        await api('POST', 'me/password-first', { current: d.current, password: d.password });
        location.reload();
        throw Object.assign(new Error(''), { silent: true });
      }, 'Salvar nova senha');
    SA.bindRules(form.elements.password, form);
    lockDialog(form);
  }

  function openUserForm(id) {
    const u = id ? view.users.find(x => x.id === id) : { name: '', email: '', role: 'USER', job_title: '', totp_required: false };
    const cargos = [...new Set((view.users || []).map(x => x.job_title).filter(Boolean))].sort();
    const form = openDialog(id ? 'Editar usuário' : 'Novo usuário', `
      <div class="field full"><label for="un">Nome completo</label><input id="un" name="name" maxlength="120" value="${esc(u.name)}" required></div>
      <div class="field"><label for="ue">E-mail (usado no login)</label><input id="ue" name="email" type="email" maxlength="120" value="${esc(u.email)}" ${id ? 'disabled' : 'required'}></div>
      <div class="field"><label for="uj">Cargo</label><input id="uj" name="job_title" maxlength="80" list="cargos" value="${esc(u.job_title || '')}" placeholder="Ex.: Engenheiro de projetos"><datalist id="cargos">${cargos.map(c => `<option value="${esc(c)}">`).join('')}</datalist></div>
      <div class="field"><label for="ur">Perfil</label><select id="ur" name="role"><option value="USER"${u.role === 'USER' ? ' selected' : ''}>Usuário</option><option value="ADMIN"${u.role === 'ADMIN' ? ' selected' : ''}>Administrador</option></select></div>
      ${id ? `<div class="field full"><label style="display:flex;align-items:center;gap:10px;cursor:pointer"><input type="checkbox" name="totp_required" style="width:auto;margin:0" ${u.totp_required ? 'checked' : ''}> Exigir verificação em duas etapas (2FA)</label><p class="hint">A pessoa terá de configurar o aplicativo autenticador no próximo acesso.</p></div>` : ''}
      ${id ? `<div class="field full"><label for="up">Nova senha (deixe em branco para manter)</label><input id="up" name="password" type="password" autocomplete="new-password"></div>
        <div class="full">${SA.rulesHtml()}<p class="hint">Ao definir uma senha aqui, a pessoa será obrigada a trocá-la no próximo acesso. Para que ela mesma crie a senha, use o botão de e-mail na lista.</p></div>`
      : '<div class="full"><p class="hint">A pessoa receberá um e-mail de boas-vindas com o endereço do sistema, as políticas de uso e um link para criar a própria senha (válido por 72 horas).</p></div>'}`,
      async d => {
        d.totp_required = d.totp_required === 'on';
        if (id) { delete d.email; if (!d.password) delete d.password; }
        const res = await api(id ? 'PUT' : 'POST', id ? 'users/' + id : 'users', d);
        if (id) toast('Usuário salvo.');
        else if (res.invite && res.invite.sent) toast('Usuário criado. Convite enviado por e-mail.');
        else { toast('Usuário criado, mas o e-mail NÃO foi enviado: ' + ((res.invite && res.invite.error) || 'falha desconhecida') + ' Use o botão de e-mail na lista para reenviar.'); }
      });
    if (id) SA.bindRules(form.elements.password, form);
  }

  function openPasswordForm() {
    const form = openDialog('Alterar senha', `
      <div class="full"><p class="hint">Por segurança, enviaremos um código de 6 dígitos para <b>${esc(me.email)}</b>. Ele vale por 15 minutos.</p></div>
      <div class="field full"><label for="pc">Código recebido por e-mail</label>
        <div class="code-row"><input id="pc" name="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" required>
        <button type="button" class="btn btn-sm" id="send-code">Enviar código</button></div>
        <p class="hint" id="code-msg" aria-live="polite"></p></div>
      <div class="field full"><label for="pn">Nova senha</label><input id="pn" name="password" type="password" autocomplete="new-password" required></div>
      <div class="full">${SA.rulesHtml()}</div>
      <div class="field full"><label for="pr">Repita a nova senha</label><input id="pr" name="repeat" type="password" autocomplete="new-password" required></div>`,
      async d => {
        if (d.password !== d.repeat) throw new Error('As senhas não conferem.');
        await api('POST', 'me/password', { code: d.code.trim(), password: d.password });
        toast('Senha alterada.');
      });
    SA.bindRules(form.elements.password, form);
    const b = form.querySelector('#send-code'), msg = form.querySelector('#code-msg');
    b.addEventListener('click', async () => {
      b.disabled = true; msg.className = 'hint'; msg.textContent = 'Enviando…';
      try {
        const r = await api('POST', 'me/password-code', {});
        msg.className = 'hint msg-ok'; msg.textContent = `Código enviado para ${r.email}. Confira também o spam.`;
        form.elements.code.focus();
        let left = 60;
        const t = setInterval(() => {
          if (!dlg.open || --left <= 0) { clearInterval(t); b.disabled = false; b.textContent = 'Reenviar código'; }
          else b.textContent = `Reenviar em ${left}s`;
        }, 1000);
      } catch (ex) { msg.className = 'hint msg-err'; msg.textContent = ex.message; b.disabled = false; }
    });
  }

  // ------------------------------------------------------------------ formulários: diálogo, projeto, tarefa
  function openDialog(title, bodyHtml, onSubmit, submitLabel = 'Salvar') {
    dlg.className = ''; delete dlg.dataset.locked;
    dlg.innerHTML = `<div class="dlg-head"><h2>${esc(title)}</h2><button class="icon-btn" data-action="close" aria-label="Fechar">${ic('x')}</button></div>
      <div class="dlg-body"><form class="fgrid" novalidate>${bodyHtml}<div class="form-error" hidden role="alert"></div>
      <div class="form-actions"><button type="button" class="btn" data-action="close">Cancelar</button><button type="submit" class="btn btn-primary">${submitLabel}</button></div></form></div>`;
    const form = dlg.querySelector('form'), err = dlg.querySelector('.form-error');
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const b = form.querySelector('[type=submit]');
      b.disabled = true; err.hidden = true;
      try { await onSubmit(SA.moneyFields(form, Object.fromEntries(new FormData(form)))); delete dlg.dataset.locked; dlg.close(); await route(); }
      catch (ex) { if (ex.silent) return; err.textContent = ex.message; err.hidden = false; b.disabled = false; }
    });
    dlg.showModal();
    return form;
  }
  dlg.addEventListener('close', () => { dlg.className = ''; });

  const opts = (map, sel) => Object.entries(map).map(([k, v]) => `<option value="${k}"${k === sel ? ' selected' : ''}>${v[0]}</option>`).join('');

  async function openProjectForm(id) {
    const p = id ? await api('GET', 'projects/' + id) : { name: '', owner: me.name, status: 'PLANEJAMENTO', scope: '', budget: '', start_date: todayIso(), end_date: addDays(todayIso(), 30) };
    openDialog(id ? 'Editar projeto' : 'Novo projeto', `
      <div class="field full"><label for="n">Nome do projeto</label><input id="n" name="name" maxlength="160" value="${esc(p.name)}" required></div>
      <div class="field"><label for="o">Responsável</label><input id="o" name="owner" maxlength="120" value="${esc(p.owner)}"></div>
      <div class="field"><label for="s">Situação</label><select id="s" name="status">${opts(P_STATUS, p.status)}</select></div>
      <div class="field"><label for="pr">Prioridade</label><select id="pr" name="priority">${opts(PRIORITY, p.priority || 'MEDIA')}</select></div>
      <div class="field full"><label style="display:flex;align-items:center;gap:10px;cursor:pointer"><input type="checkbox" name="company_goal" style="width:auto;margin:0" ${p.company_goal ? 'checked' : ''}> ★ Meta Empresa</label><p class="hint">Projeto do plano de desenvolvimento da empresa. Fica acima de qualquer prioridade nas listas e na visão geral.</p></div>
      <div class="field"><label for="a">Início</label><input id="a" name="start_date" type="date" value="${p.start_date}" required></div>
      <div class="field"><label for="b">Fim</label><input id="b" name="end_date" type="date" value="${p.end_date}" required></div>
      <div class="field full"><label for="bu">Orçamento (R$)</label>${SA.moneyInput('bu', 'budget', Number(p.budget) > 0 ? p.budget : '', { placeholder: 'Opcional — usado no controle financeiro' })}</div>
      <div class="field full"><label for="c">Escopo inicial</label><textarea id="c" name="scope" maxlength="4000" placeholder="O que será entregue, o que está fora do escopo e como saberemos que acabou.">${esc(p.scope)}</textarea></div>`,
      async d => {
        d.company_goal = d.company_goal === 'on';
        const saved = await api(id ? 'PUT' : 'POST', id ? 'projects/' + id : 'projects', d);
        toast(id ? 'Projeto salvo.' : 'Projeto criado. Agora adicione as tarefas.');
        if (!id) location.hash = '#/projeto/' + saved.id;
      });
  }

  /** preset: { projectId, parentId, milestone }. allTasks: tarefas conhecidas (para pai e predecessora). */
  function openTaskForm(allTasks, taskId, preset = {}) {
    const t0 = taskId ? allTasks.find(x => x.id === taskId) : null;
    const projects = view.projects || [];
    const projectId = t0 ? t0.project_id : (preset.projectId || null);
    const t = t0 || { name: '', description: '', responsible: '', progress: 0, status: 'A_FAZER', depends_on: null, parent_id: preset.parentId || null,
      is_milestone: !!preset.milestone, project_id: projectId || '' };
    if (!t0) {
      const pr = (view.project && view.project.id === projectId) ? view.project : projects.find(x => x.id === projectId);
      const today = todayIso(), inside = pr && today >= pr.start_date && today <= pr.end_date;
      t.start_date = inside || !pr ? today : pr.start_date;
      t.end_date = addDays(t.start_date, preset.milestone ? 0 : 6);
    }
    const hasKids = !!(t0 && t0.child_count > 0);
    const projSel = !taskId && !projectId
      ? `<div class="field full"><label for="tp">Projeto</label><select id="tp" name="project_id" required>${projects.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></div>` : '';
    const form = openDialog(taskId ? (t.is_milestone ? 'Editar marco' : 'Editar tarefa') : (preset.parentId ? 'Nova tarefa filha' : preset.milestone ? 'Novo marco' : 'Nova tarefa'), `${projSel}
      <div class="field full"><label for="tn">Nome</label><input id="tn" name="name" maxlength="160" value="${esc(t.name)}" required></div>
      <div class="field full"><label class="check"><input type="checkbox" name="is_milestone" ${t.is_milestone ? 'checked' : ''}> Marcar como marco (entrega ou ponto de controle — aparece como losango no Gantt)</label></div>
      <div class="field full"><label class="check"><input type="checkbox" name="is_important" ${t.is_important ? 'checked' : ''}> Marcar como importante (entra no quadrante "importante" da Matriz de prioridades, mesmo que o projeto não seja prioritário)</label></div>
      <div class="field"><label for="tpar">Tarefa pai</label><select id="tpar" name="parent_id" ${hasKids ? 'disabled' : ''}></select></div>
      <div class="field"><label for="td">Depende de</label><select id="td" name="depends_on"></select></div>
      <div class="field"><label for="tr">Responsável</label><input id="tr" name="responsible" maxlength="120" value="${esc(t.responsible)}"></div>
      <div class="field"><label for="ts">Situação</label><select id="ts" name="status" ${hasKids ? 'disabled' : ''}>${opts(T_STATUS, t.status)}</select></div>
      <div class="field"><label for="ta" id="l-start">Início</label><input id="ta" name="start_date" type="date" value="${t.start_date}" ${hasKids ? 'disabled' : 'required'}></div>
      <div class="field" id="g-end"><label for="tb">Fim (prazo)</label><input id="tb" name="end_date" type="date" value="${t.end_date}" ${hasKids ? 'disabled' : 'required'}></div>
      <div class="field full"><label for="tg">Progresso</label><div class="range-row"><input id="tg" name="progress" type="range" min="0" max="100" step="5" value="${t.progress}" ${hasKids ? 'disabled' : ''}><output id="tgo">${t.progress}%</output></div></div>
      ${hasKids ? '<p class="hint full" style="grid-column:1/-1">Esta tarefa tem filhas: início, fim, progresso e situação são calculados automaticamente a partir delas.</p>' : ''}
      <div class="field full"><label for="tx">Descrição</label><textarea id="tx" name="description" maxlength="4000" style="min-height:80px">${esc(t.description)}</textarea></div>`,
      async d => {
        const body = { ...d, is_milestone: !!d.is_milestone, is_important: !!d.is_important, parent_id: d.parent_id || null, depends_on: d.depends_on || null };
        if (taskId) delete body.project_id;
        await api(taskId ? 'PUT' : 'POST', taskId ? 'tasks/' + taskId : 'tasks', body);
        toast(taskId ? 'Salvo.' : 'Criado.');
      });
    const el = form.elements, ms = el.is_milestone, rng = el.progress, st = el.status;
    const currentProject = () => taskId || projectId ? t.project_id || projectId : Number(el.project_id.value);
    const fillSelects = () => {
      const pid = currentProject();
      const same = allTasks.filter(x => x.project_id === pid && x.id !== taskId);
      el.parent_id.innerHTML = '<option value="">Nenhuma (tarefa principal)</option>' + same.filter(x => !x.parent_id)
        .map(x => `<option value="${x.id}"${x.id === t.parent_id ? ' selected' : ''}>${esc(x.name)}</option>`).join('');
      el.depends_on.innerHTML = '<option value="">Nenhuma</option>' + same.map(x => `<option value="${x.id}"${x.id === t.depends_on ? ' selected' : ''}>${esc(x.name)}</option>`).join('');
    };
    fillSelects();
    if (el.project_id && !taskId && !projectId) el.project_id.addEventListener('change', () => { t.parent_id = null; fillSelects(); });
    const syncMs = () => { form.querySelector('#g-end').hidden = ms.checked; form.querySelector('#l-start').textContent = ms.checked ? 'Data do marco' : 'Início'; };
    ms.addEventListener('change', syncMs); syncMs();
    rng.addEventListener('input', () => {
      form.querySelector('#tgo').textContent = rng.value + '%';
      if (st.value === 'CANCELADA') { /* cancelada mantém a situação */ }
      else if (rng.value === '100') st.value = 'CONCLUIDA';
      else if (st.value === 'CONCLUIDA') st.value = 'EM_ANDAMENTO';
      else if (rng.value > 0 && st.value === 'A_FAZER') st.value = 'EM_ANDAMENTO';
    });
    st.addEventListener('change', () => { if (st.value === 'CONCLUIDA') { rng.value = 100; form.querySelector('#tgo').textContent = '100%'; } });
  }

  // ------------------------------------------------------------------ ações (delegação de eventos)
  document.addEventListener('click', async e => {
    const ph = e.target.closest('[data-health]');
    if (ph) view.pendingHealth = ph.dataset.health;
    const go = e.target.closest('[data-go]');
    const el = e.target.closest('[data-action]');
    if (go && !el && !e.target.closest('a')) { location.hash = go.dataset.go; return; }
    if (!el) return;
    const id = Number(el.dataset.id);
    const inProject = view.name === 'projeto';
    try {
      if (el.dataset.action.startsWith('sp-')) { await Spot.action(el); return; }
      if (el.dataset.action.startsWith('em-')) { await Mail.action(el); return; }
      if (el.dataset.action.startsWith('sys-')) { await Sys.action(el); return; }
      if (el.dataset.action.startsWith('rp-')) { await Rep.action(el); return; }
      if (el.dataset.action.startsWith('mpp-')) { await Mpp.action(el); return; }
      if (el.dataset.action.startsWith('ish-')) { await Ish.action(el); return; }
      if (el.dataset.action.startsWith('prio-')) { await Prio.action(el); return; }
      switch (el.dataset.action) {
        case 'logout': await api('POST', 'logout'); location.replace('login.html'); break;
        case 'close': dlg.close(); break;
        case 'change-password': openPasswordForm(); break;
        case 'new-user': openUserForm(); break;
        case 'edit-user': openUserForm(id); break;
        case 'toggle-user': {
          const active = el.dataset.active === '1';
          if (!active || confirm('Bloquear este usuário? Ele perde o acesso imediatamente.')) {
            await api('PUT', `users/${id}/active`, { active: !active });
            toast(active ? 'Usuário bloqueado.' : 'Usuário ativado.'); await route();
          }
          break;
        }
        case 'invite-user': {
          const u = view.users.find(x => x.id === id);
          if (confirm(u.invite_pending ? 'Reenviar o e-mail de boas-vindas com o link para criar a senha?' : 'Enviar um e-mail com link para esta pessoa definir uma nova senha? A senha atual continua valendo até ela criar a nova.')) {
            const r = await api('POST', `users/${id}/invite`, {});
            toast(r.sent ? 'E-mail enviado.' : 'E-mail NÃO enviado: ' + (r.error || 'falha desconhecida'));
            await route();
          }
          break;
        }
        case 'reset-2fa':
          if (confirm('Redefinir a verificação em duas etapas desta pessoa? Ela precisará configurar o aplicativo de novo no próximo acesso.')) {
            await api('POST', `users/${id}/2fa-reset`, {}); toast('2FA redefinido.'); await route();
          }
          break;
        case '2fa-on': open2faSetup(false); break;
        case '2fa-codes': case '2fa-off': {
          const off = el.dataset.action === '2fa-off';
          const f = openDialog(off ? 'Desativar verificação em duas etapas' : 'Novos códigos de recuperação', `
            <div class="full"><p class="hint">${off ? 'Confirme com a sua senha e um código atual.' : 'Os códigos antigos deixam de valer. Confirme com a sua senha e um código atual.'}</p></div>
            <div class="field full"><label for="cp">Senha</label><input id="cp" name="password" type="password" autocomplete="current-password" required></div>
            <div class="field full"><label for="cc">Código do aplicativo (ou de recuperação)</label><input id="cc" name="code" autocomplete="one-time-code" maxlength="20" required></div>`,
            async d => {
              const r = await api('POST', off ? 'me/2fa/disable' : 'me/2fa/recovery', d);
              if (off) { toast('Verificação em duas etapas desativada.'); return; }
              dlg.close();
              openDialog('Códigos de recuperação', recoveryHtml(r.recovery_codes), async () => {}, 'Guardei os códigos');
              throw Object.assign(new Error(''), { silent: true });
            }, off ? 'Desativar' : 'Gerar códigos');
          break;
        }
        case 'del-user':
          if (confirm('Excluir este usuário? Ele perde o acesso imediatamente.')) { await api('DELETE', 'users/' + id); toast('Usuário excluído.'); await route(); }
          break;
        case 'new-project': openProjectForm(); break;
        case 'edit-project': e.stopPropagation(); openProjectForm(id); break;
        case 'del-project':
          e.stopPropagation();
          try {
            const chk = await api('GET', `projects/${id}/links`);
            if (chk && chk.message) { alert(chk.message); break; }
            if (confirm('Tem certeza que deseja excluir este projeto? Esta ação não pode ser desfeita.')) { await api('DELETE', 'projects/' + id); toast('Projeto excluído.'); location.hash = '#/projetos'; await route(); }
          } catch (ex) { alert(ex.message); }
          break;
        case 'new-task': openTaskForm(view.tasks, null, { projectId: inProject ? view.projectId : null }); break;
        case 'new-milestone': openTaskForm(view.tasks, null, { projectId: inProject ? view.projectId : null, milestone: true }); break;
        case 'new-subtask': {
          const parent = view.tasks.find(x => x.id === id);
          openTaskForm(view.tasks, null, { projectId: parent.project_id, parentId: id });
          break;
        }
        case 'edit-task': openTaskForm(view.tasks, id); break;
        case 'del-task': {
          const t = view.tasks.find(x => x.id === id);
          try {
            const chk = await api('GET', `tasks/${id}/links`);
            if (chk && chk.message) { alert(chk.message); break; }
          } catch (ex) { alert(ex.message); break; }
          if (confirm(`Tem certeza que deseja excluir a tarefa "${t ? t.name : ''}"? Esta ação não pode ser desfeita.`)) { await api('DELETE', 'tasks/' + id); toast('Tarefa excluída.'); await route(); }
          break;
        }
        case 'tab': view.tab = el.dataset.tab; drawNav(); await renderTab(); break;
        case 'ms-tab': view.msTab = el.dataset.tab; if (view.msRender) view.msRender(); break;
        case 'ms-toggle': await api('PUT', `tasks/${id}/done`, { done: el.dataset.done !== '1' }); await route(); break;
        case 'new-fin': openFinanceForm(null, el.dataset.kind); break;
        case 'edit-fin': openFinanceForm(id); break;
        case 'del-fin':
          if (confirm('Excluir este lançamento?')) { await api('DELETE', 'finance/' + id); toast('Lançamento excluído.'); await route(); }
          break;
        case 'ai-test': {
          el.disabled = true; el.textContent = 'Testando…';
          try { const r = await api('POST', 'ai/test', {}); toast(`Conexão OK com ${r.model}.`); }
          catch (ex) { toast(ex.message); }
          await route(); break;
        }
        case 'ai-save-model': await api('PUT', 'ai', { model: document.getElementById('ai-m').value }); toast('Modelo salvo.'); await route(); break;
        case 'ai-disconnect':
          if (confirm('Desconectar o Claude do SysAttack? A chave salva será apagada daqui.\n\nPara encerrar o acesso por completo, desative ou exclua também a chave no Claude Console.')) {
            await api('DELETE', 'ai'); toast('Claude desconectado.'); await route();
          }
          break;
        case 'new-doc': openDocForm(); break;
        case 'edit-doc': openDocForm(view.docs.find(x => x.id === id)); break;
        case 'doc-view': openPreview(view.docs.find(x => x.id === id)); break;
        case 'del-doc':
          if (confirm('Excluir este documento?')) { await api('DELETE', 'documents/' + id); toast('Documento excluído.'); await route(); }
          break;
      }
    } catch (ex) { toast(ex.message); }
  });
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });

  // ------------------------------------------------------------------ rotas
  async function route() {
    const [, name = 'visao-geral', arg] = location.hash.split('/');
    document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.route === name || (name === 'projeto' && a.dataset.route === 'projetos')));
    view.name = name;
    const y = window.scrollY;
    try {
      if (name === 'projetos') await viewProjects();
      else if (name === 'projeto') await viewProject(Number(arg));
      else if (name === 'tarefas') await viewTasks();
      else if (name === 'prioridades') await Prio.view();
      else if (name === 'marcos') await viewMilestones();
      else if (name === 'cronograma') await viewSchedule();
      else if (name === 'usuarios') await viewUsers();
      else if (name === 'spot') await Spot.view(arg);
      else if (name === 'relatorios') await Rep.view();
      else if (name === 'privacidade') await viewPrivacy();
      else if ((name === 'email' || name === 'ia' || name === 'sistema') && !isAdmin()) {
        main.innerHTML = pageHead(['SysAttack'], 'Acesso restrito', '') + `<div class="panel">${empty('Esta área é só para administradores', 'Peça a um administrador para ajustar esta configuração.')}</div>`;
      }
      else if (name === 'email') await Mail.view();
      else if (name === 'sistema') await Sys.view();
      else if (name === 'ia') await viewAI();
      else await viewDashboard();
    } catch (ex) {
      main.innerHTML = pageHead(['SysAttack'], 'Algo deu errado', '') + `<div class="panel">${empty('Não foi possível carregar esta tela', esc(ex.message))}</div>`;
    }
    window.scrollTo(0, y);
  }

  window.App = { get me() { return me; }, get main() { return main; }, get view() { return view; }, isLate, T_STATUS, dayDiff, dlg, api, openDialog, toast, pageHead, route };
  window.addEventListener('hashchange', () => { route(); window.scrollTo(0, 0); });
  // menu do celular: abre/fecha, fecha ao navegar e com Esc
  const sidebar = document.querySelector('.sidebar'), menuBtn = document.getElementById('menu-btn');
  if (sidebar && menuBtn) {
    const setMenu = open => { sidebar.classList.toggle('open', open); menuBtn.setAttribute('aria-expanded', String(open)); menuBtn.setAttribute('aria-label', open ? 'Fechar menu' : 'Abrir menu'); };
    menuBtn.addEventListener('click', () => setMenu(!sidebar.classList.contains('open')));
    window.addEventListener('hashchange', () => setMenu(false));
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && sidebar.classList.contains('open')) { setMenu(false); menuBtn.focus(); } });
  }
  (async () => {
    try {
      me = await api('GET', 'me'); // sem sessão (401) o api() já redireciona para login.html
    } catch (ex) {
      if (/Sessão expirada/.test(ex.message)) return;
      main.innerHTML = '<section class="panel"><h2 class="panel-title">Servidor indisponível</h2>' +
        `<p>Não foi possível falar com a API do SysAttack: <b>${esc(ex.message)}</b></p>` +
        '<p class="hint">Confirme que o Tomcat está ativo e que a versão do Tomcat corresponde ao .war gerado (Tomcat 9: <code>mvn clean package</code>; Tomcat 10/11: <code>mvn clean package -Pjakarta</code>).</p>' +
        '<p><a class="btn btn-sm" href="login.html">Ir para o login</a></p></section>';
      return;
    }
    // menus de configuração: só administradores; para os demais o item nem existe na página (a API também recusa)
    document.querySelectorAll('[data-route="ia"], [data-route="email"], [data-route="sistema"]').forEach(a => { if (isAdmin()) a.hidden = false; else a.remove(); });
    if (!isAdmin()) {
      document.querySelector('#g-admin .nav-label').textContent = 'Conta';
      document.querySelector('[data-route="usuarios"] span').textContent = 'Meu usuário';
    }
    const initials = me.name.trim().split(/\s+/).map(p => p[0]).slice(0, 2).join('').toUpperCase();
    document.getElementById('who-av').textContent = initials;
    document.getElementById('who-name').textContent = me.name;
    document.getElementById('who-role').textContent = me.job_title || ROLES[me.role] || me.role;
    document.getElementById('who').hidden = false;
    SA.versionLabel().then(v => { if (v) document.getElementById('ver').textContent = 'SysAttack ' + v; });
    if (me.must_change) { main.innerHTML = ''; openFirstPassword(); return; }
    if (me.totp_required && !me.totp_enabled) {
      main.innerHTML = '<section class="panel"><h2 class="panel-title">Configuração de segurança pendente</h2><p class="hint">O administrador exige a verificação em duas etapas para a sua conta.</p></section>';
      try { await open2faSetup(true); } catch (ex) { main.innerHTML += `<p class="msg-err">${esc(ex.message)}</p>`; }
      return;
    }
    route();
  })();
})();
