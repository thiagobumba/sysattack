(() => {
  const $ = id => document.getElementById(id);
  const post = async (url, body) => {
    const r = await fetch('api/' + url, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'SysAttack' },
      body: JSON.stringify(body)
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data.error || 'Não foi possível concluir. Tente novamente.');
    return data;
  };

  fetch('version.json', { cache: 'no-store' }).then(r => r.json()).then(v => { $('ver').textContent = 'SysAttack ' + v.version + (v.stage ? ' - ' + v.stage : ''); }).catch(() => {});

  async function boot() {
    try {
      // já está logado? vai direto ao sistema
      const me = await fetch('api/me', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(8000) });
      if (me.ok) { location.replace('./'); return; }
      const st = await fetch('api/setup-status', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(8000) });
      if (!st.ok) throw new Error('HTTP ' + st.status);
      const { needed } = await st.json();
      $('loading').hidden = true;
      (needed ? $('s') : $('f')).hidden = false;
      (needed ? $('sn') : $('e')).focus();
    } catch (ex) {
      $('loading').innerHTML = '<div class="form-error">Não foi possível falar com o servidor do SysAttack (' + ex.message +
        '). Verifique se o Tomcat está ativo e se a versão do Tomcat corresponde ao arquivo .war gerado (veja o README).</div>';
    }
  }

  $('f').addEventListener('submit', async e => {
    e.preventDefault();
    $('err').hidden = true; $('go').disabled = true;
    try {
      const r = await post('login', { email: $('e').value.trim(), password: $('p').value });
      if (r && r.need_2fa) { $('f').hidden = true; $('t').hidden = false; $('tc').focus(); return; }
      location.replace('./');
    } catch (ex) {
      $('err').textContent = ex.message; $('err').hidden = false;
      $('go').disabled = false; $('p').select();
    }
  });

  $('t').addEventListener('submit', async e => {
    e.preventDefault();
    $('terr').hidden = true; $('tgo').disabled = true;
    try {
      await post('login/2fa', { code: $('tc').value.trim() });
      location.replace('./');
    } catch (ex) {
      $('terr').textContent = ex.message; $('terr').hidden = false; $('tgo').disabled = false; $('tc').select();
    }
  });

  try { SA.bindRules($('sp'), $('s')); } catch (e) { console.error(e); }
  $('s').addEventListener('submit', async e => {
    e.preventDefault();
    $('serr').hidden = true;
    try {
      if ($('sp').value !== $('sc').value) throw new Error('As senhas não conferem.');
      $('sgo').disabled = true;
      await post('setup', { name: $('sn').value.trim(), email: $('se').value.trim(), password: $('sp').value });
      location.replace('./');
    } catch (ex) {
      $('serr').textContent = ex.message; $('serr').hidden = false; $('sgo').disabled = false;
    }
  });

  boot();
})();
