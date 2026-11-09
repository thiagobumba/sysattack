/* SysAttack — Diagrama de Ishikawa (espinha de peixe): causas e subcausas de um problema do projeto, nas categorias 6M. */
const Ish = (() => {
  const { api, esc, ic } = SA;
  const A = () => window.App;

  // linha de cima e de baixo da espinha (a ordem é a leitura clássica do 6M)
  const CATS = {
    METODO: { label: 'Método', tip: ['Como o trabalho é feito.', '• Processos, procedimentos e regras de negócio mal definidos ou não seguidos.', '• Escopo, requisitos e critérios de aceite pouco claros.', '• Decisões e aprovações que demoram.'] },
    MATERIAL: { label: 'Material', tip: ['O que entra no trabalho.', '• Dados, documentos, especificações e insumos incompletos ou errados.', '• Licenças, contratos e acessos que não chegaram a tempo.', '• Entregas de terceiros fora do padrão.'] },
    MAO_DE_OBRA: { label: 'Mão de obra', tip: ['As pessoas envolvidas.', '• Falta de conhecimento, treinamento ou de gente disponível.', '• Sobrecarga, rotatividade e papéis mal definidos.', '• Comunicação e engajamento do time e do cliente.'] },
    MAQUINA: { label: 'Máquina', tip: ['Ferramentas e tecnologia.', '• Sistemas, equipamentos e integrações instáveis ou inadequados.', '• Ambiente de teste ou homologação indisponível.', '• Ferramenta que não atende a necessidade.'] },
    MEIO_AMBIENTE: { label: 'Meio ambiente', tip: ['O contexto externo e organizacional.', '• Mudança de prioridades, de legislação ou de mercado.', '• Cultura, política interna e resistência à mudança.', '• Condições físicas, clima ou fornecedores.'] },
    MEDICAO: { label: 'Medição', tip: ['Como acompanhamos e medimos.', '• Indicadores errados, ausentes ou lidos tarde.', '• Estimativas de prazo e custo imprecisas.', '• Falta de acompanhamento e de registro do que aconteceu.'] }
  };
  const TOP = ['METODO', 'MATERIAL', 'MAO_DE_OBRA'], BOTTOM = ['MAQUINA', 'MEIO_AMBIENTE', 'MEDICAO'];

  const ctx = { projectId: null, host: null, list: [], sel: null };

  async function tab(host, pid) {
    ctx.projectId = pid; ctx.host = host;
    ctx.list = await api('GET', 'ishikawa?project_id=' + pid);
    if (!ctx.list.some(d => d.id === ctx.sel)) ctx.sel = ctx.list.length ? ctx.list[0].id : null;
    draw();
  }
  const current = () => ctx.list.find(d => d.id === ctx.sel);

  function causeItem(c, subs) {
    return `<li><div class="ish-c"><span class="ish-t" data-action="ish-edit" data-id="${c.id}" role="button" tabindex="0" title="Clique para editar">${esc(c.text)}</span>
      <span class="ish-acts"><button class="icon-btn" data-action="ish-add" data-parent="${c.id}" data-cat="${c.category}" title="Adicionar subcausa (por quê?)" aria-label="Adicionar subcausa">${ic('plus')}</button>
      <button class="icon-btn del" data-action="ish-del-cause" data-id="${c.id}" title="Excluir causa" aria-label="Excluir causa">${ic('trash')}</button></span></div>
      ${subs.length ? `<ul class="ish-subs">${subs.map(s => `<li><div class="ish-c"><span class="ish-t" data-action="ish-edit" data-id="${s.id}" role="button" tabindex="0" title="Clique para editar">${esc(s.text)}</span>
        <span class="ish-acts"><button class="icon-btn del" data-action="ish-del-cause" data-id="${s.id}" title="Excluir subcausa" aria-label="Excluir subcausa">${ic('trash')}</button></span></div></li>`).join('')}</ul>` : ''}</li>`;
  }

  function catBlock(k, d, pos) {
    const info = CATS[k], all = d.causes.filter(c => c.category === k);
    const top = all.filter(c => !c.parent_id);
    return `<section class="ish-cat ${pos}"><header><span class="ish-cat-name">${Tip.wrap(esc(info.label), info.label, info.tip)}</span>
        <button class="icon-btn" data-action="ish-add" data-cat="${k}" title="Adicionar causa em ${esc(info.label)}" aria-label="Adicionar causa em ${esc(info.label)}">${ic('plus')}</button></header>
      <ul class="ish-causes">${top.length ? top.map(c => causeItem(c, all.filter(s => s.parent_id === c.id))).join('') : '<li class="ish-none">Sem causas</li>'}</ul><i class="ish-bone"></i></section>`;
  }

  function draw() {
    const host = ctx.host, d = current();
    if (!d) {
      host.innerHTML = `<div class="empty"><strong>Nenhum diagrama de Ishikawa</strong>Use o Ishikawa para investigar a causa de um problema do projeto (atraso, estouro de custo, retrabalho). Crie um diagrama, descreva o problema e registre as causas em cada categoria, perguntando "por quê?" até chegar à raiz.</div>`;
      return;
    }
    const total = d.causes.length;
    host.innerHTML = `<div class="ish-bar"><label for="ish-sel">Diagrama</label>
        <select id="ish-sel">${ctx.list.map(x => `<option value="${x.id}"${x.id === d.id ? ' selected' : ''}>${esc(x.problem.length > 70 ? x.problem.slice(0, 70) + '…' : x.problem)}</option>`).join('')}</select>
        <button class="btn btn-sm" data-action="ish-problem">Editar problema</button>
        <button class="icon-btn del" data-action="ish-del" title="Excluir diagrama" aria-label="Excluir diagrama">${ic('trash')}</button>
        <span class="small" style="margin-left:auto">${total} causa(s) · criado por ${esc(d.created_by || '—')}</span></div>
      <div class="ish"><div class="ish-grid">
        <div class="ish-row t">${TOP.map(k => catBlock(k, d, 'top')).join('')}</div>
        <div class="ish-spine"><i></i></div>
        <div class="ish-row b">${BOTTOM.map(k => catBlock(k, d, 'bottom')).join('')}</div>
        <div class="ish-head">${Tip.wrap(`<small>Problema (efeito)</small><b>${esc(d.problem)}</b>`, 'Problema (efeito)', ['É o resultado indesejado que você quer entender, escrito de forma objetiva e mensurável (ex.: "Entrega do módulo Compras atrasou 20 dias").', 'As espinhas mostram as causas possíveis, agrupadas em 6 categorias (6M). Para cada causa, pergunte "por quê?" e registre a subcausa até chegar à raiz.'])}</div>
      </div></div>
      <p class="hint" style="margin-top:12px">Clique em uma causa para editar. Use o + de cada categoria para adicionar causas e o + de uma causa para detalhar o "por quê?". Passe o mouse sobre o nome da categoria para ver exemplos.</p>`;
    document.getElementById('ish-sel').addEventListener('change', e => { ctx.sel = Number(e.target.value); draw(); });
  }

  function find(id) { for (const d of ctx.list) { const c = d.causes.find(x => x.id === id); if (c) return { d, c }; } return null; }

  function newDiagram() {
    A().openDialog('Novo diagrama de Ishikawa', `
      <div class="field full"><label for="ip">Qual é o problema?</label>
        <textarea id="ip" name="problem" maxlength="300" required style="min-height:90px" placeholder="Ex.: Entrega do módulo Compras atrasou 20 dias"></textarea></div>
      <p class="hint full" style="margin:0">Descreva o efeito de forma objetiva. Depois você registra as causas em cada categoria.</p>`,
      async d => {
        const r = await api('POST', 'ishikawa', { project_id: ctx.projectId, problem: (d.problem || '').trim() });
        ctx.sel = r.id; A().toast('Diagrama criado.');
      }, 'Criar diagrama');
  }

  function editProblem() {
    const d = current();
    A().openDialog('Editar problema', `<div class="field full"><label for="ip">Problema</label><textarea id="ip" name="problem" maxlength="300" required style="min-height:90px">${esc(d.problem)}</textarea></div>`,
      async f => { await api('PUT', 'ishikawa/' + d.id, { problem: (f.problem || '').trim() }); A().toast('Problema atualizado.'); }, 'Salvar');
  }

  function causeForm(cat, parentId, existing) {
    const d = current(), parent = parentId ? d.causes.find(c => c.id === parentId) : null;
    const title = existing ? 'Editar causa' : parent ? 'Nova subcausa' : 'Nova causa — ' + CATS[cat].label;
    A().openDialog(title, `${parent ? `<p class="hint full" style="margin:0">Por que acontece: <b>${esc(parent.text)}</b></p>` : ''}
      <div class="field full"><label for="ic">${parent ? 'Subcausa' : 'Causa'}</label><input id="ic" name="text" maxlength="200" required value="${existing ? esc(existing.text) : ''}" placeholder="${parent ? 'Por quê?' : 'O que pode ter contribuído para o problema?'}"></div>`,
      async f => {
        const text = (f.text || '').trim();
        if (existing) await api('PUT', 'ishikawa/causes/' + existing.id, { text });
        else await api('POST', `ishikawa/${d.id}/causes`, { category: cat, text, parent_id: parentId || null });
        A().toast('Causa salva.');
      }, 'Salvar');
  }

  async function action(el) {
    const a = el.dataset.action, id = Number(el.dataset.id);
    if (a === 'ish-new') return newDiagram();
    if (a === 'ish-problem') return editProblem();
    if (a === 'ish-add') return causeForm(el.dataset.cat, el.dataset.parent ? Number(el.dataset.parent) : null, null);
    if (a === 'ish-edit') { const f = find(id); if (f) causeForm(f.c.category, f.c.parent_id, f.c); return; }
    if (a === 'ish-del') {
      const d = current();
      if (confirm(`Tem certeza que deseja excluir este diagrama e suas ${d.causes.length} causa(s)? Esta ação não pode ser desfeita.`)) {
        await api('DELETE', 'ishikawa/' + d.id); ctx.sel = null; A().toast('Diagrama excluído.'); await A().route();
      }
    }
    if (a === 'ish-del-cause') {
      const f = find(id);
      const subs = f ? f.d.causes.filter(c => c.parent_id === id).length : 0;
      if (subs > 0) { alert(`Esta causa tem ${subs} subcausa(s). Exclua as subcausas antes de excluir a causa.`); return; }
      if (confirm(`Tem certeza que deseja excluir a causa "${f ? f.c.text : ''}"?`)) { await api('DELETE', 'ishikawa/causes/' + id); A().toast('Causa excluída.'); await A().route(); }
    }
  }
  // teclado: Enter/Espaço em uma causa abre a edição
  document.addEventListener('keydown', e => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.matches && e.target.matches('.ish-t[data-action="ish-edit"]')) { e.preventDefault(); action(e.target); }
  });

  return { tab, action };
})();
