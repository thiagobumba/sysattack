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
          <small class="fhint">PNG, JPG, WEBP ou GIF. A imagem é reduzida para até ${MAX_PX} px e ajustada ao avatar redondo: a moldura vazia é recortada e o fundo de cor única preenche o círculo.</small>
          <small class="fhint logo-msg" data-logo-msg hidden></small></div></div></div>`;
  }

  function toBlob(cv, type, q) { return new Promise(res => cv.toBlob(res, type, q)); }

  /**
   * Recorta a moldura vazia da imagem (margem transparente ou de cor única) e descobre a cor de fundo.
   * É isso que faz uma logo com respiro embutido deixar de aparecer pequena dentro do avatar redondo.
   */
  function analyze(bmp) {
    const w = bmp.width, h = bmp.height;
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const g = cv.getContext('2d', { willReadFrequently: true });
    g.drawImage(bmp, 0, 0);
    let d;
    try { d = g.getImageData(0, 0, w, h).data; } catch (e) { return { x: 0, y: 0, w, h, bg: null }; }
    const at = (x, y) => { const i = (y * w + x) * 4; return [d[i], d[i + 1], d[i + 2], d[i + 3]]; };
    const corners = [at(0, 0), at(w - 1, 0), at(0, h - 1), at(w - 1, h - 1)];
    const near = (a, b) => Math.abs(a[3] - b[3]) < 12 && (a[3] < 16 || (Math.abs(a[0] - b[0]) < 12 && Math.abs(a[1] - b[1]) < 12 && Math.abs(a[2] - b[2]) < 12));
    const uniform = corners.every(c => near(c, corners[0]));
    if (!uniform) return { x: 0, y: 0, w, h, bg: null };
    const ref = corners[0];
    const bg = ref[3] < 16 ? null : `rgb(${ref[0]},${ref[1]},${ref[2]})`;
    const isBg = (x, y) => near(at(x, y), ref);
    let x0 = 0, y0 = 0, x1 = w - 1, y1 = h - 1;
    const rowBg = y => { for (let x = x0; x <= x1; x++) if (!isBg(x, y)) return false; return true; };
    const colBg = x => { for (let y = y0; y <= y1; y++) if (!isBg(x, y)) return false; return true; };
    while (y0 < y1 && rowBg(y0)) y0++;
    while (y1 > y0 && rowBg(y1)) y1--;
    while (x0 < x1 && colBg(x0)) x0++;
    while (x1 > x0 && colBg(x1)) x1--;
    const cw = x1 - x0 + 1, ch = y1 - y0 + 1;
    if (cw < 8 || ch < 8) return { x: 0, y: 0, w, h, bg }; // imagem quase toda vazia: não recorta
    return { x: x0, y: y0, w: cw, h: ch, bg };
  }

  /** Reduz a imagem para caber em MAX_PX e em menos de ~900 KB, já ajustada ao avatar redondo. */
  async function shrink(file) {
    if (!TYPES.includes(file.type)) throw new Error('Formato não aceito. Use PNG, JPG, WEBP ou GIF.');
    let bmp;
    try { bmp = await createImageBitmap(file); } catch (e) { throw new Error('Não foi possível ler essa imagem.'); }
    // sempre quadrada, porque o avatar é redondo: a moldura vazia sai fora, o fundo de cor única preenche
    // o círculo inteiro e a logo ocupa o máximo que cabe sem a borda cortar o desenho.
    const a = analyze(bmp);
    const ratio = a.w / a.h, squareish = ratio > 0.9 && ratio < 1.1;
    let side = Math.min(MAX_PX, Math.max(a.w, a.h));
    for (let i = 0; i < 4; i++, side = Math.round(side * 0.75)) {
      const cv = document.createElement('canvas'); cv.width = cv.height = Math.max(8, side);
      const g = cv.getContext('2d'); g.imageSmoothingQuality = 'high';
      if (a.bg) { g.fillStyle = a.bg; g.fillRect(0, 0, cv.width, cv.height); }
      // com fundo de cor única o círculo já fica preenchido, então a logo respira; sem fundo, ela cresce
      const box = cv.width * (a.bg ? 0.84 : squareish ? 0.98 : 0.92);
      const sc = Math.min(box / a.w, box / a.h), w = a.w * sc, h = a.h * sc;
      g.drawImage(bmp, a.x, a.y, a.w, a.h, (cv.width - w) / 2, (cv.height - h) / 2, w, h);
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
