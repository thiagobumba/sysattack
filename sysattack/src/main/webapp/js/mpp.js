/* SysAttack — Importação de cronograma do Microsoft Project (.mpp ou XML) para as tarefas de um projeto. */
const Mpp = (() => {
  const { api, esc, fmt, ic } = SA;
  const A = () => window.App;
  const MAX_BYTES = 25 * 1024 * 1024;
  let st = null; // estado da importação em andamento

  const KIND = {
    principal: ['Tarefa principal', 'b-dark'], sub: ['Subtarefa', 'b-grey'], marco: ['Marco', 'b-orange'],
    grupo: ['Agrupador (vira prefixo)', 'b-light'], ignorada: ['Título (não importa)', 'b-light'], semdata: ['Sem data', 'b-red']
  };

  function dlgShell(title, body) {
    const dlg = A().dlg;
    dlg.className = 'wide';
    dlg.innerHTML = `<div class="dlg-head"><h2>${esc(title)}</h2><button class="icon-btn" data-action="close" aria-label="Fechar">${ic('x')}</button></div><div class="dlg-body">${body}</div>`;
    if (!dlg.open) dlg.showModal();
    return dlg;
  }

  // ---- passo 1: escolher o arquivo
  function open(projectId) {
    st = { projectId };
    const dlg = dlgShell('Importar tarefas', `
      <p class="hint" style="margin-bottom:14px">Envie o arquivo <b>.mpp</b> do Microsoft Project (2010 ou mais novo), o <b>XML do Project</b> (Arquivo &gt; Salvar como &gt; XML), um <b>JSON</b> (inclusive o exportado do Teamwork) ou um <b>CSV</b> de tarefas. Você vai ver tudo o que seria criado e escolher o que importar antes de qualquer coisa ser gravada.</p>
      <div class="field full"><label for="mpp-f">Arquivo do Project</label><input id="mpp-f" type="file" accept=".mpp,.xml,.json,.csv"></div>
      <div class="form-error" id="mpp-err" role="alert" hidden></div>
      <div class="form-actions"><button type="button" class="btn" data-action="close">Cancelar</button><button type="button" class="btn btn-primary" id="mpp-read">Ler arquivo</button></div>`);
    const err = dlg.querySelector('#mpp-err'), go = dlg.querySelector('#mpp-read');
    go.addEventListener('click', async () => {
      const file = dlg.querySelector('#mpp-f').files[0];
      err.hidden = true;
      if (!file) { err.textContent = 'Selecione o arquivo do Project.'; err.hidden = false; return; }
      if (file.size > MAX_BYTES) { err.textContent = 'O arquivo é maior que 25 MB.'; err.hidden = false; return; }
      go.disabled = true; go.textContent = 'Lendo…';
      try {
        const r = await fetch(`api/projects/${projectId}/import-preview`, {
          method: 'POST', credentials: 'same-origin', body: file,
          headers: { 'X-Requested-With': 'SysAttack', 'Content-Type': 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name) }
        });
        const data = await r.json().catch(() => null);
        if (r.status === 401) { location.replace('login.html'); return; }
        if (!r.ok) throw new Error((data && data.error) || 'Não foi possível ler o arquivo.');
        const [proj, tasks] = await Promise.all([api('GET', 'projects/' + projectId), api('GET', `projects/${projectId}/tasks`)]);
        start(file.name, data, proj, tasks.length);
      } catch (ex) { err.textContent = ex.message; err.hidden = false; go.disabled = false; go.textContent = 'Ler arquivo'; }
    });
  }

  // ---- regras de conversão: as linhas do Project viram tarefas principais e subtarefas (o SysAttack tem 2 níveis)
  function start(fileName, data, proj, existing) {
    const rows = data.rows.filter(r => r.name);
    if (!rows.length) { dlgShell('Importar tarefas', '<p>O arquivo não tem tarefas.</p>'); return; }
    const levels = [...new Set(rows.map(r => r.level))].sort((a, b) => a - b);
    const minL = levels[0];
    const top = rows.filter(r => r.level === minL);
    // um único título no topo (o nome do projeto) não vira tarefa: as tarefas principais começam no nível seguinte
    const mainLevel = top.length === 1 && top[0].summary && levels.length > 1 ? levels[1] : minL;
    st = { ...st, fileName, rows, levels, mainLevel, proj, existing, format: data.format, projectName: data.project_name,
           sel: new Map(), deps: rows.some(r => r.preds.length), adjust: true };
    plan();
    render();
  }

  /** Classifica cada linha conforme o nível escolhido para as tarefas principais. */
  function plan() {
    const stack = {}; let main = null;
    st.plan = st.rows.map(r => {
      const p = { row: r, kind: 'ignorada', parent: null, name: r.name };
      if (!r.start || !r.end) { p.kind = 'semdata'; return p; }
      if (r.level < st.mainLevel) { p.kind = 'ignorada'; return p; }
      for (const k of Object.keys(stack)) if (Number(k) >= r.level) delete stack[k];
      if (r.level === st.mainLevel) {
        main = r; p.kind = r.milestone ? 'marco' : 'principal';
        return p;
      }
      if (r.summary) { stack[r.level] = r.name; p.kind = 'grupo'; return p; }
      const path = Object.keys(stack).map(Number).sort((a, b) => a - b).map(k => stack[k]);
      p.kind = r.milestone ? 'marco' : 'sub';
      if (main) { p.parent = main.uid; p.name = [...path, r.name].join(' › '); }
      else p.kind = r.milestone ? 'marco' : 'principal';
      return p;
    });
    for (const p of st.plan) if (isImportable(p) && !st.sel.has(p.row.uid)) st.sel.set(p.row.uid, true);
  }
  const isImportable = p => ['principal', 'sub', 'marco'].includes(p.kind);
  // incluída = marcada e, se tiver tarefa principal, a principal também marcada
  const included = p => isImportable(p) && st.sel.get(p.row.uid) && (!p.parent || st.sel.get(p.parent));

  function counts() {
    const inc = st.plan.filter(included);
    return { main: inc.filter(p => !p.parent).length, sub: inc.filter(p => p.parent).length, ms: inc.filter(p => p.row.milestone).length, total: inc.length };
  }

  function render() {
    const lvOpt = st.levels.map(l => {
      const ex = st.rows.find(r => r.level === l);
      return `<option value="${l}"${l === st.mainLevel ? ' selected' : ''}>Nível ${l}: ${esc((ex.name || '').slice(0, 46))}…</option>`;
    }).join('');
    const rows = st.plan;
    const minD = rows.filter(p => isImportable(p)).map(p => p.row.start).sort()[0], maxD = rows.filter(p => isImportable(p)).map(p => p.row.end).sort().pop();
    const outside = minD && (minD < st.proj.start_date || maxD > st.proj.end_date);
    const dlg = dlgShell('Importar tarefas', `
      <p class="hint" style="margin-bottom:12px"><b>${esc(st.fileName)}</b> · ${st.rows.length} linhas${st.projectName ? ` · ${esc(st.projectName)}` : ''}. Marque o que deve ser importado. Nada foi gravado ainda.</p>
      <div class="fgrid" style="margin-bottom:10px">
        <div class="field full"><label for="mpp-lv">Qual nível do Project vira tarefa principal?</label>
          <select id="mpp-lv">${lvOpt}</select>
          <p class="hint">O SysAttack tem dois níveis (tarefa e subtarefa). Linhas abaixo do nível escolhido viram subtarefas; grupos intermediários entram como prefixo do nome.</p></div>
        <div class="field full"><label style="display:flex;align-items:center;gap:10px;cursor:pointer"><input type="checkbox" id="mpp-dep" style="width:auto;margin:0"${st.deps ? ' checked' : ''}${rows.some(p => p.row.preds.length) ? '' : ' disabled'}> Importar a predecessora (a primeira de cada tarefa)</label></div>
        <div class="field full"><label style="display:flex;align-items:center;gap:10px;cursor:pointer"><input type="checkbox" id="mpp-adj" style="width:auto;margin:0"${st.adjust ? ' checked' : ''}> Ajustar as datas do projeto para caber no cronograma${outside ? ` <span class="badge b-orange">hoje ${fmt(st.proj.start_date)} – ${fmt(st.proj.end_date)}; cronograma ${fmt(minD)} – ${fmt(maxD)}</span>` : ''}</label></div>
        ${st.existing ? `<div class="full"><p class="hint msg-err">Este projeto já tem ${st.existing} tarefa(s). As importadas serão adicionadas e não substituem as atuais.</p></div>` : ''}
      </div>
      <div class="bar-actions" style="margin:0 0 8px;justify-content:flex-start;display:flex;gap:8px;align-items:center;flex-wrap:wrap"><button type="button" class="btn btn-sm" id="mpp-all">Marcar todas</button><button type="button" class="btn btn-sm" id="mpp-none">Desmarcar todas</button><span class="small" id="mpp-count" style="display:inline"></span></div>
      <div class="table-wrap" style="max-height:36vh;overflow:auto"><table class="tbl"><thead><tr><th></th><th>ID</th><th>Linha do Project</th><th>Tipo</th><th>Início</th><th>Fim</th><th>%</th></tr></thead><tbody>
        ${rows.map(p => {
          const r = p.row, can = isImportable(p), off = can && p.parent && !st.sel.get(p.parent);
          const dim = !can || off ? ' style="opacity:.5"' : '';
          return `<tr${dim}><td>${can ? `<input type="checkbox" data-uid="${r.uid}" ${st.sel.get(r.uid) && !off ? 'checked' : ''}${off ? ' disabled' : ''} aria-label="Importar ${esc(r.name)}">` : ''}</td>
            <td>${r.id}</td><td style="padding-left:${12 + Math.max(0, r.level - st.levels[0]) * 16}px"${p.kind === 'principal' ? ' class="strong"' : ''}>${esc(p.name)}</td>
            <td><span class="badge ${KIND[p.kind][1]}">${KIND[p.kind][0]}</span></td>
            <td class="nowrap">${r.start ? fmt(r.start) : '—'}</td><td class="nowrap">${r.end ? fmt(r.end) : '—'}</td><td>${r.summary ? '' : r.percent + '%'}</td></tr>`;
        }).join('')}</tbody></table></div>
      <div class="form-error" id="mpp-err" role="alert" hidden style="margin-top:12px"></div>
      <div class="form-actions"><button type="button" class="btn" data-action="close">Cancelar</button><button type="button" class="btn btn-primary" id="mpp-go">Importar</button></div>`);
    const upd = () => {
      const c = counts();
      dlg.querySelector('#mpp-count').textContent = `${c.total} para importar: ${c.main} principal(is), ${c.sub} subtarefa(s)${c.ms ? `, ${c.ms} marco(s)` : ''}.`;
      const go = dlg.querySelector('#mpp-go'); go.textContent = c.total ? `Importar ${c.total} tarefa(s)` : 'Nada selecionado'; go.disabled = !c.total;
    };
    upd();
    dlg.querySelector('#mpp-lv').addEventListener('change', e => { st.mainLevel = Number(e.target.value); st.sel = new Map(); plan(); render(); });
    dlg.querySelector('#mpp-dep').addEventListener('change', e => { st.deps = e.target.checked; });
    dlg.querySelector('#mpp-adj').addEventListener('change', e => { st.adjust = e.target.checked; });
    dlg.querySelector('#mpp-all').addEventListener('click', () => { st.plan.filter(isImportable).forEach(p => st.sel.set(p.row.uid, true)); render(); });
    dlg.querySelector('#mpp-none').addEventListener('click', () => { st.plan.filter(isImportable).forEach(p => st.sel.set(p.row.uid, false)); render(); });
    dlg.querySelectorAll('input[data-uid]').forEach(cb => cb.addEventListener('change', () => { st.sel.set(Number(cb.dataset.uid), cb.checked); render(); }));
    dlg.querySelector('#mpp-go').addEventListener('click', run);
  }

  async function run() {
    const dlg = A().dlg, err = dlg.querySelector('#mpp-err'), go = dlg.querySelector('#mpp-go');
    err.hidden = true; go.disabled = true; go.textContent = 'Importando…';
    const inc = st.plan.filter(included);
    const incUids = new Set(inc.map(p => p.row.uid));
    const hasKids = new Set(inc.filter(p => p.parent).map(p => p.parent));
    const tasks = inc.map(p => {
      const r = p.row, t = { ref: r.uid, parent: p.parent, name: p.name.slice(0, 160), start_date: r.start, end_date: r.end, progress: r.percent, milestone: r.milestone, responsible: (r.responsible || '').slice(0, 120),
        description: `Importado de arquivo (linha ${r.id})` };
      if (st.deps && !hasKids.has(r.uid)) {
        const pr = r.preds.find(l => incUids.has(l.uid) && !hasKids.has(l.uid) && l.uid !== r.uid);
        if (pr) t.depends = pr.uid;
      }
      return t;
    });
    try {
      const res = await api('POST', `projects/${st.projectId}/import-tasks`, { tasks, source: st.fileName, adjust_project: st.adjust });
      dlg.close();
      A().toast(`${res.created} tarefa(s) importada(s)${res.dependencies ? `, ${res.dependencies} predecessora(s)` : ''}${res.project_adjusted ? '; datas do projeto ajustadas' : ''}.`);
      await A().route();
    } catch (ex) { err.textContent = ex.message; err.hidden = false; go.disabled = false; go.textContent = 'Tentar de novo'; }
  }

  async function action(el) {
    if (el.dataset.action === 'mpp-open') open(Number(el.dataset.project));
  }

  return { open, action };
})();
