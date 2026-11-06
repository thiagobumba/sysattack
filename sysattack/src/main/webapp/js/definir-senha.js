(() => {
  const $ = id => document.getElementById(id);
  const token = new URLSearchParams(location.search).get('token') || '';
  history.replaceState(null, '', location.pathname); // tira o token da barra de endereço
  const call = async (method, body) => {
    const r = await fetch('api/invite/' + encodeURIComponent(token), {
      method, credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'SysAttack' },
      body: body ? JSON.stringify(body) : undefined
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) { const e = new Error(data.error || 'Não foi possível concluir. Tente novamente.'); e.status = r.status; throw e; }
    return data;
  };
  fetch('version.json', { cache: 'no-store' }).then(r => r.json()).then(v => { $('ver') && ($('ver').textContent = 'SysAttack ' + v.version); }).catch(() => {});
  const bad = msg => { $('loading').hidden = true; $('s').hidden = true; $('bad-msg').textContent = msg; $('bad').hidden = false; };
  (async () => {
    if (!token) return bad('Link incompleto. Abra o link exatamente como veio no e-mail.');
    try {
      const u = await call('GET');
      $('nm').textContent = u.name; $('em').textContent = u.email;
      $('loading').hidden = true; $('s').hidden = false; $('sp').focus();
    } catch (ex) { bad(ex.message); }
  })();
  try { SA.bindRules($('sp'), $('s')); } catch (e) { console.error(e); }
  $('s').addEventListener('submit', async e => {
    e.preventDefault();
    $('serr').hidden = true;
    try {
      if ($('sp').value !== $('sc').value) throw new Error('As senhas não conferem.');
      $('sgo').disabled = true;
      await call('POST', { password: $('sp').value });
      $('s').hidden = true; $('done').hidden = false;
    } catch (ex) {
      if (ex.status === 410) return bad(ex.message);
      $('serr').textContent = ex.message; $('serr').hidden = false; $('sgo').disabled = false;
    }
  });
})();
