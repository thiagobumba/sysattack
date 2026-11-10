/* Configurações IA Agent: uma guia por IA (Claude, ChatGPT, Copilot/Azure OpenAI), visão geral e segurança. */
const Ai = (() => {
  const { esc } = SA;
  const A = () => window.App;
  const P = {
    claude: { name: 'Claude', vendor: 'Anthropic', badge: 'C', color: '#c96442', tab: 'Claude' },
    openai: { name: 'ChatGPT', vendor: 'OpenAI', badge: 'G', color: '#10a37f', tab: 'ChatGPT (OpenAI)' },
    azure: { name: 'Copilot', vendor: 'Microsoft · Azure OpenAI', badge: 'M', color: '#0078d4', tab: 'Copilot (Microsoft)' }
  };
  const ORDER = ['claude', 'openai', 'azure'];
  let st = { tab: 'v', d: null };

  const when = s => { const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}:\d{2})/.exec(String(s || '')); return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}` : ''; };
  const prov = k => st.d.providers[k];

  function tabs() {
    const t = (k, label, dot) => `<button type="button" role="tab" class="tab${st.tab === k ? ' active' : ''}" aria-selected="${st.tab === k}" data-action="ai-tab" data-tab="${k}">${dot === undefined ? '' : `<i class="ia-dot${dot ? ' on' : ''}"></i>`}${label}</button>`;
    return `<div class="ia-tabs" role="tablist" aria-label="IAs">${t('v', 'Visão geral')}${ORDER.map(k => t(k, P[k].tab, prov(k).connected)).join('')}${t('seg', 'Segurança e custos')}</div>`;
  }

  function overview() {
    const inUse = st.d.in_use;
    const card = k => {
      const p = prov(k), m = P[k], on = p.connected, sel = inUse === k;
      const meta = on
        ? `<span>${k === 'azure' ? 'Implantação' : 'Modelo'}: <b>${esc(p.model)}</b></span><span>Chave: <b>${esc(p.key_hint)}</b></span>
           <span>Último teste: ${p.last_test_at ? `<b>${p.last_test_ok ? 'OK' : 'Falhou'}</b> · ${esc(when(p.last_test_at))}` : '<b>ainda não testado</b>'}</span>`
        : k === 'claude' ? '<span>Chave de API da Anthropic (Claude Console).</span>'
        : k === 'openai' ? '<span>Chave de API da OpenAI.</span><span>Os modelos aparecem depois de validar a chave.</span>'
        : '<span>Endereço do recurso + chave + nome da implantação.</span>';
      return `<div class="ia-card${on ? ' on' : ''}${sel ? ' use' : ''}"><h3><span class="ia-badge" style="background:${m.color}">${m.badge}</span><span>${m.name}<span class="small">${m.vendor}</span></span></h3>
        <span class="ia-chip${on ? ' on' : ''}"><i></i>${on ? 'Conectado' : 'Não conectado'}</span><div class="ia-meta">${meta}</div>
        <button type="button" class="btn btn-sm${on ? '' : ' btn-primary'}" data-action="ai-tab" data-tab="${k}">${on ? 'Gerenciar' : 'Conectar'}</button>
        <label class="ia-use${on ? '' : ' off'}"><input type="radio" name="ai-use" ${sel ? 'checked' : ''} ${on ? '' : 'disabled'} data-action="ai-use" data-p="${k}"> ${on ? (sel ? 'Em uso nos relatórios' : 'Usar esta IA nos relatórios') : 'Conecte para poder usar'}</label></div>`;
    };
    const using = inUse ? `Os relatórios com IA dos projetos Spot usam hoje o <b>${esc(P[inUse].name)} (${esc(prov(inUse).model)})</b>. Trocar a IA em uso não altera relatórios já gerados: cada relatório guarda o modelo que o escreveu.`
      : 'Nenhuma IA está conectada ainda. Conecte uma em sua guia para liberar os relatórios com IA dos projetos Spot.';
    return `<section class="panel"><div class="panel-head"><h2 class="panel-title">IAs conectadas</h2></div>
      <p class="ia-lead">O SysAttack usa uma IA para escrever os relatórios dos projetos Spot. Conecte uma ou mais e escolha qual delas está em uso. Cada IA tem a sua guia, com o passo a passo da conexão.</p>
      <div class="ia-cards">${ORDER.map(card).join('')}</div></section>
      <section class="panel"><div class="panel-head"><h2 class="panel-title">Em uso nos relatórios</h2></div><p class="ia-lead" style="margin:0">${using}</p></section>`;
  }

  const head = (k, extra) => `<div class="panel-head"><h2 class="panel-title">Conexão com ${k === 'azure' ? 'o Copilot (Microsoft)' : k === 'openai' ? 'o ChatGPT (OpenAI)' : 'o Claude'}</h2>
    <span class="ai-status${prov(k).connected ? ' on' : ''}"><i></i>${prov(k).connected ? 'Conectado' : 'Desconectado'}</span></div>${extra || ''}`;
  const lock = '<div class="ia-lock"><span aria-hidden="true">🔒</span><span>A chave é guardada criptografada no servidor e nunca é exibida de novo.</span></div>';

  function connectedView(k) {
    const p = prov(k), inUse = st.d.in_use === k;
    const test = p.last_test_at ? `<span class="badge ${p.last_test_ok ? 'b-green' : 'b-red'}">${p.last_test_ok ? 'OK' : 'Falhou'}</span> <span class="small" style="display:inline">${esc(when(p.last_test_at))} · ${esc(p.last_test_msg || '')}</span>` : '<span class="small" style="display:inline">Ainda não testado</span>';
    const modelField = k === 'azure'
      ? `<div class="field"><label for="ai-m">Nome da implantação</label><input id="ai-m" value="${esc(p.model)}" maxlength="64"></div>`
      : `<div class="field"><label for="ai-m">Modelo usado nos relatórios</label><select id="ai-m"><option value="${esc(p.model)}" selected>${esc(p.model)}</option></select></div>`;
    return `<section class="panel">${head(k)}<div class="ia-two"><div>
      <dl class="meta-grid ia-dl"><div><dt>Chave</dt><dd><code>${esc(p.key_hint)}</code></dd></div><div><dt>Conectado por</dt><dd>${esc(p.connected_by || '—')}</dd></div>
        <div><dt>Em</dt><dd>${esc(when(p.connected_at))}</dd></div>
        ${k === 'azure' ? `<div class="wide"><dt>Recurso</dt><dd>${esc(p.endpoint)}</dd></div><div><dt>Versão da API</dt><dd>${esc(p.api_version)}</dd></div>` : p.workspace_id ? `<div><dt>${k === 'openai' ? 'Organização' : 'Workspace'}</dt><dd>${esc(p.workspace_id)}</dd></div>` : ''}
        <div class="wide"><dt>Último teste</dt><dd>${test}</dd></div></dl></div>
      <div class="ia-form">${modelField}
        <div class="ia-actions"><button type="button" class="btn btn-sm" data-action="ai-save-model" data-p="${k}">Salvar ${k === 'azure' ? 'implantação' : 'modelo'}</button>
          <button type="button" class="btn btn-sm btn-primary" data-action="ai-test" data-p="${k}">Testar conexão</button>
          <button type="button" class="btn btn-sm btn-danger" data-action="ai-disconnect" data-p="${k}">Desconectar</button></div>
        ${inUse ? '<p class="ia-note ok">Esta IA está em uso nos relatórios.</p>' : `<div><button type="button" class="btn btn-sm" data-action="ai-use" data-p="${k}">Usar esta IA nos relatórios</button></div>`}</div></div>
      <p class="hint" style="margin-top:16px">“Desconectar” apaga a chave do SysAttack. Para encerrar o acesso por completo, desative ou exclua também a chave ${k === 'azure' ? 'no portal Azure' : k === 'openai' ? 'na plataforma da OpenAI' : 'no Claude Console'}.</p></section>`;
  }

  function claudeForm() {
    return `<section class="panel">${head('claude', `<div class="notice"><b>Por que chave de API e não “entrar com a conta”?</b> A Anthropic não permite usar o login do Claude.ai (Free, Pro ou Max) dentro de sistemas de terceiros. A conexão oficial é feita com uma chave de API criada na conta da empresa no <b>Claude Console</b>.</div>`)}
      <div class="ia-two"><ol class="ia-steps"><li><span>Entre em <b>platform.claude.com/settings/keys</b> com a conta da empresa (com faturamento ou créditos ativos).</span></li>
        <li><span>Clique em <b>Create key</b>, dê o nome <b>SysAttack</b> e, se possível, defina uma data de expiração.</span></li>
        <li><span>Copie a chave (começa com <code>sk-ant-</code>), ela só aparece uma vez, e cole ao lado.</span></li></ol>
      <form id="ai-form" data-p="claude" class="ia-form" novalidate autocomplete="off">
        <div class="field"><label for="ai-k">Chave de API</label><input id="ai-k" name="api_key" type="password" autocomplete="off" spellcheck="false" placeholder="sk-ant-…" required></div>
        <div class="field"><label for="ai-m">Modelo</label><select id="ai-m" name="model">${st.d.default_models.map((m, i) => `<option value="${esc(m)}"${i === 0 ? ' selected' : ''}>${esc(m)}${i === 0 ? ' (recomendado)' : ''}</option>`).join('')}</select></div>
        <details><summary>Opções avançadas</summary><div class="field" style="margin-top:10px"><label for="ai-w">ID do workspace (opcional)</label><input id="ai-w" name="workspace_id" placeholder="wrkspc_… (só se a chave não for de um workspace)" maxlength="80"></div></details>
        ${lock}<div class="form-error" hidden role="alert"></div><div><button type="submit" class="btn btn-primary">Conectar</button></div></form></div></section>`;
  }

  function openaiForm() {
    return `<section class="panel">${head('openai', `<div class="notice"><b>Por que chave de API e não “entrar com a conta”?</b> A assinatura do ChatGPT (Plus, Pro, Team) não pode ser usada dentro de sistemas de terceiros. A conexão oficial é feita com uma chave de API criada na conta da empresa na plataforma da OpenAI, cobrada à parte da assinatura.</div>`)}
      <div class="ia-two"><ol class="ia-steps"><li><span>Entre em <b>platform.openai.com/api-keys</b> com a conta da empresa (com créditos ou faturamento ativos).</span></li>
        <li><span>Clique em <b>Create new secret key</b>, dê o nome <b>SysAttack</b> e, se puder, limite as permissões.</span></li>
        <li><span>Copie a chave (começa com <code>sk-</code>), ela só aparece uma vez, e cole ao lado.</span></li>
        <li><span>Clique em <b>Validar chave</b> e escolha o modelo na lista que a OpenAI devolver.</span></li></ol>
      <form id="ai-form" data-p="openai" class="ia-form" novalidate autocomplete="off">
        <div class="field"><label for="ai-k">Chave de API</label><input id="ai-k" name="api_key" type="password" autocomplete="off" spellcheck="false" placeholder="sk-…" required></div>
        <div class="field"><label for="ai-m">Modelo</label><select id="ai-m" name="model" disabled><option value="">Valide a chave para listar os modelos</option></select></div>
        <details><summary>Opções avançadas</summary><div class="field" style="margin-top:10px"><label for="ai-w">ID da organização (opcional)</label><input id="ai-w" name="workspace_id" placeholder="org-…" maxlength="80"></div></details>
        ${lock}<div class="form-error" hidden role="alert"></div>
        <div class="ia-actions"><button type="button" class="btn" data-action="ai-probe">Validar chave</button><button type="submit" class="btn btn-primary" disabled>Conectar</button></div></form></div></section>`;
  }

  function azureForm() {
    return `<section class="panel">${head('azure', `<div class="notice"><b>Copilot e Azure OpenAI.</b> O Copilot do Microsoft 365 (o chat do Word, Teams e Edge) exige o login de cada usuário com licença Copilot e não aceita uma chave fixa de servidor. Para acoplar a IA da Microsoft ao SysAttack, o caminho oficial é o <b>Azure OpenAI (Microsoft Foundry)</b>: mesmos modelos, com chave e dentro do seu tenant Azure.</div>`)}
      <div class="ia-two"><ol class="ia-steps"><li><span>No portal Azure, abra o recurso <b>Azure OpenAI / Foundry</b> da empresa e vá em <b>Chaves e Ponto de extremidade</b>.</span></li>
        <li><span>Copie o <b>endereço</b> (<code>https://NOME.openai.azure.com</code>) e uma das <b>chaves</b>.</span></li>
        <li><span>Em <b>Implantações</b>, copie o <b>nome da implantação</b> do modelo que o SysAttack vai usar.</span></li>
        <li><span>Cole os três campos ao lado e clique em conectar.</span></li></ol>
      <form id="ai-form" data-p="azure" class="ia-form" novalidate autocomplete="off">
        <div class="field"><label for="ai-e">Endereço do recurso</label><input id="ai-e" name="endpoint" placeholder="https://nome-do-recurso.openai.azure.com" maxlength="200" required></div>
        <div class="field"><label for="ai-k">Chave</label><input id="ai-k" name="api_key" type="password" autocomplete="off" spellcheck="false" placeholder="Cole a chave do recurso" required></div>
        <div class="field"><label for="ai-m">Nome da implantação</label><input id="ai-m" name="model" placeholder="ex.: relatorios-spot" maxlength="64" required></div>
        <details><summary>Opções avançadas</summary><div class="field" style="margin-top:10px"><label for="ai-v">Versão da API</label><input id="ai-v" name="api_version" value="${esc(st.d.azure_version)}" maxlength="30"></div></details>
        ${lock}<div class="form-error" hidden role="alert"></div><div><button type="submit" class="btn btn-primary">Validar e conectar</button></div></form></div></section>`;
  }

  function security() {
    return `<section class="panel"><div class="panel-head"><h2 class="panel-title">Segurança e custos</h2></div><ul class="ai-list">
      <li>As chaves são guardadas <b>criptografadas</b> no servidor e nunca são exibidas de novo, só a versão mascarada.</li>
      <li>Somente <b>administradores</b> veem e alteram esta página.</li>
      <li>Nenhuma das IAs aceita o login de contas de chat (Claude.ai, ChatGPT Plus/Team, Copilot do Microsoft 365) em sistemas de terceiros: a conexão usa <b>chave de API</b>, cobrada na conta da empresa em cada fornecedor, <b>separada de qualquer assinatura</b>.</li>
      <li>Use uma <b>chave exclusiva</b> para o SysAttack, com validade quando o fornecedor permitir, e defina um <b>limite de gastos</b> na conta (Claude Console, plataforma da OpenAI ou orçamento no Azure).</li>
      <li>O relatório com IA envia à IA em uso os números e os nomes de demandas do projeto Spot. Só conecte fornecedores aprovados para esse tipo de dado.</li>
      <li>O servidor do SysAttack precisa de acesso à internet (porta 443) para <code>api.anthropic.com</code>, <code>api.openai.com</code> e o endereço do recurso Azure, conforme as IAs conectadas. Se a rede usa proxy, avise a TI.</li></ul></section>`;
  }

  function render() {
    const body = st.tab === 'v' ? overview() : st.tab === 'seg' ? security()
      : prov(st.tab).connected ? connectedView(st.tab) : st.tab === 'claude' ? claudeForm() : st.tab === 'openai' ? openaiForm() : azureForm();
    A().main.innerHTML = A().pageHead(['SysAttack', 'Configurações IA Agent'], 'Configurações IA Agent', 'Conecte as IAs que o SysAttack pode usar e escolha qual delas escreve os relatórios.') + tabs() + body;
    const f = document.getElementById('ai-form');
    if (f) f.addEventListener('submit', submit);
    const sel = document.querySelector('select#ai-m');
    if (sel && st.tab !== 'v' && prov(st.tab).connected && st.tab !== 'azure') {
      const k = st.tab, cur = prov(k).model;
      SA.api('GET', `ai/${k}/models`).then(r => {   // lista os modelos que a chave realmente enxerga
        if (st.tab !== k) return;
        const s2 = document.querySelector('select#ai-m'); if (!s2) return;
        const ids = r.models.includes(cur) ? r.models : [cur, ...r.models];
        s2.innerHTML = ids.map(m => `<option value="${esc(m)}"${m === cur ? ' selected' : ''}>${esc(m)}</option>`).join('');
      }).catch(() => {});
    }
  }

  async function submit(e) {
    e.preventDefault();
    const f = e.target, k = f.dataset.p, err = f.querySelector('.form-error'), b = f.querySelector('[type=submit]'), label = b.textContent;
    const body = {};
    new FormData(f).forEach((v, n) => { body[n] = String(v).trim(); });
    b.disabled = true; b.textContent = 'Validando…'; err.hidden = true;
    try {
      st.d = await SA.api('POST', `ai/${k}/connect`, body);
      A().toast(`${P[k].name} conectado.`);
      render();
    } catch (ex) { err.textContent = ex.message; err.hidden = false; b.disabled = false; b.textContent = label; }
  }

  async function view() {
    st.d = await SA.api('GET', 'ai');
    if (st.tab !== 'v' && st.tab !== 'seg' && !P[st.tab]) st.tab = 'v';
    render();
  }

  async function action(el) {
    const a = el.dataset.action, k = el.dataset.p;
    switch (a) {
      case 'ai-tab': st.tab = el.dataset.tab; render(); window.scrollTo(0, 0); break;
      case 'ai-use': st.d = await SA.api('PUT', 'ai', { in_use: k }); A().toast(`${P[k].name} em uso nos relatórios.`); render(); break;
      case 'ai-probe': {
        const f = el.closest('form'), err = f.querySelector('.form-error');
        el.disabled = true; el.textContent = 'Validando…'; err.hidden = true;
        try {
          const r = await SA.api('POST', `ai/${f.dataset.p}/probe`, { api_key: f.api_key.value.trim(), workspace_id: f.workspace_id.value.trim() });
          if (!r.models.length) throw new Error('A chave é válida, mas não enxerga nenhum modelo de texto.');
          f.model.innerHTML = r.models.map(m => `<option value="${esc(m)}">${esc(m)}</option>`).join(''); f.model.disabled = false;
          f.querySelector('[type=submit]').disabled = false; el.textContent = 'Chave válida ✓';
        } catch (ex) { err.textContent = ex.message; err.hidden = false; el.disabled = false; el.textContent = 'Validar chave'; }
        break;
      }
      case 'ai-test': {
        el.disabled = true; el.textContent = 'Testando…';
        try { const r = await SA.api('POST', `ai/${k}/test`, {}); A().toast(`Conexão OK com ${r.model}.`); } catch (ex) { A().toast(ex.message); }
        await view(); break;
      }
      case 'ai-save-model': st.d = await SA.api('PUT', `ai/${k}`, { model: document.getElementById('ai-m').value.trim() }); A().toast(k === 'azure' ? 'Implantação salva.' : 'Modelo salvo.'); render(); break;
      case 'ai-disconnect':
        if (confirm(`Desconectar ${P[k].name} do SysAttack? A chave salva será apagada daqui.\n\nPara encerrar o acesso por completo, desative ou exclua também a chave no fornecedor.`)) {
          st.d = await SA.api('DELETE', `ai/${k}`); A().toast(`${P[k].name} desconectado.`); render();
        }
        break;
    }
  }

  return { view, action };
})();
