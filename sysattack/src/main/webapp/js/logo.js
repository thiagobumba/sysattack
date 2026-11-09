/* SysAttack — imagem (logo) do projeto e do projeto Spot: campo de upload com prévia, ajuste de tamanho e avatar. */
const Logo = (() => {
  const { esc } = SA;
  const MAX_PX = 400;
  const TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];
  const pending = new WeakMap(); // formulário -> { blob, remove }
  const base = (kind, id) => `${kind === 'S' ? 'spot' : 'projects'}/${id}/logo`;
  const src = (kind, p) => `api/${base(kind, p.id)}?v=${encodeURIComponent(p.logo_at || '')}`;

  const initials = name => {
    const w = String(name || '').trim().split(/\s+/).filter(Boolean);
    return ((w[0] || '?')[0] + (w.length > 1 ? w[w.length - 1][0] : '')).toUpperCase();
  };

  /** Avatar redondo (tamanhos sm, md, lg): a imagem do projeto ou as iniciais quando não há imagem. */
  function avatar(kind, p, size = 'sm') {
    return p.logo_at
      ? `<span class="avatar av-${size}"><img src="${src(kind, p)}" alt="" loading="lazy"></span>`
      : `<span class="avatar av-${size} ph" aria-hidden="true">${esc(initials(p.name))}</span>`;
  }

  /** Campo do formulário: prévia, escolher e remover. */
  function field(kind, p) {
    const has = !!(p && p.id && p.logo_at);
    return `<div class="field full logo-field"><label>Imagem do projeto <span class="opt">(logo, opcional)</span></label>
      <div class="logo-row"><span class="logo-prev" data-logo-prev>${has ? `<img src="${src(kind, p)}" alt="Imagem atual do projeto">` : '<i>Sem imagem</i>'}</span>
        <div class="logo-ctl"><span class="logo-btns"><label class="btn btn-sm logo-pick">Escolher imagem<input type="file" accept="image/png,image/jpeg,image/webp,image/gif" data-logo-file hidden></label>
          <button type="button" class="btn btn-sm" data-logo-remove${has ? '' : ' hidden'}>Remover imagem</button></span>
          <small class="fhint">PNG, JPG, WEBP ou GIF. A imagem é reduzida automaticamente para até ${MAX_PX} px. Funciona melhor com logo quadrada ou fundo transparente.</small>
          <small class="fhint logo-msg" data-logo-msg hidden></small></div></div></div>`;
  }

  function toBlob(cv, type, q) { return new Promise(res => cv.toBlob(res, type, q)); }

  /** Reduz a imagem para caber em MAX_PX e em menos de ~900 KB. */
  async function shrink(file) {
    if (!TYPES.includes(file.type)) throw new Error('Formato não aceito. Use PNG, JPG, WEBP ou GIF.');
    let bmp;
    try { bmp = await createImageBitmap(file); } catch (e) { throw new Error('Não foi possível ler essa imagem.'); }
    let k = Math.min(1, MAX_PX / Math.max(bmp.width, bmp.height));
    for (let i = 0; i < 4; i++, k *= 0.75) {
      const w = Math.max(1, Math.round(bmp.width * k)), h = Math.max(1, Math.round(bmp.height * k));
      const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
      cv.getContext('2d').drawImage(bmp, 0, 0, w, h);
      const blob = await toBlob(cv, 'image/png'); // PNG mantém a transparência
      if (blob && blob.size <= 900 * 1024) return blob;
    }
    throw new Error('A imagem é muito pesada. Escolha uma menor.');
  }

  /** Liga os botões do campo ao formulário (chamar logo depois de abrir o diálogo). */
  function bind(form) {
    const prev = form.querySelector('[data-logo-prev]'), file = form.querySelector('[data-logo-file]');
    const rm = form.querySelector('[data-logo-remove]'), msg = form.querySelector('[data-logo-msg]');
    if (!file) return;
    const original = prev.innerHTML, hadImage = !rm.hidden;
    const say = t => { msg.textContent = t || ''; msg.hidden = !t; };
    file.addEventListener('change', async () => {
      const f = file.files[0]; say('');
      if (!f) return;
      try {
        const blob = await shrink(f);
        pending.set(form, { blob, remove: false });
        const u = URL.createObjectURL(blob);
        prev.innerHTML = `<img src="${u}" alt="Prévia da nova imagem">`;
        rm.hidden = false; rm.textContent = 'Descartar escolha';
        say(`Nova imagem pronta (${Math.round(blob.size / 1024)} KB). Ela é gravada ao salvar.`);
      } catch (e) { say(e.message); }
      file.value = '';
    });
    rm.addEventListener('click', () => {
      const cur = pending.get(form);
      if (cur && cur.blob) { // descartar a escolha e voltar ao que existia
        pending.delete(form); prev.innerHTML = original; rm.textContent = 'Remover imagem'; rm.hidden = !hadImage; say('');
        return;
      }
      pending.set(form, { blob: null, remove: true });
      prev.innerHTML = '<i>Sem imagem</i>'; rm.hidden = true; say('A imagem será removida ao salvar.');
    });
  }

  /** Envio de bytes para a API (o cabeçalho extra serve à justificativa dos projetos Spot encerrados). */
  async function send(method, path, blob, extra) {
    const r = await fetch('api/' + path, { method, credentials: 'same-origin', body: blob || undefined,
      headers: { 'X-Requested-With': 'SysAttack', ...(blob ? { 'Content-Type': blob.type } : {}), ...(extra || {}) } });
    if (r.status === 401) { location.replace('login.html'); throw new Error('Sessão expirada.'); }
    if (!r.ok) { const d = await r.json().catch(() => null); throw new Error((d && d.error) || 'Falha ao enviar a imagem (' + r.status + ').'); }
  }

  /** Depois de salvar o projeto: grava ou remove a imagem escolhida. Falha aqui não desfaz o projeto: só avisa. */
  async function apply(kind, id, form, sender) {
    const s = pending.get(form); if (!s) return;
    pending.delete(form);
    const go = sender || ((m, b) => send(m, base(kind, id), b));
    try {
      if (s.blob) await go('PUT', s.blob); else if (s.remove) await go('DELETE', null);
    } catch (e) { window.App.toast('Projeto salvo, mas a imagem não foi atualizada: ' + e.message); }
  }

  return { avatar, field, bind, apply, send, has: form => pending.has(form) };
})();
