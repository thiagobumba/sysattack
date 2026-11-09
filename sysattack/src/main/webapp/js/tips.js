/* SysAttack — balões de explicação ao passar o mouse (ou focar com o teclado).
   Uso no HTML gerado: ${Tip.attr('Título', ['linha', '• item', '# subtítulo', '= fórmula'])} no elemento.
   Prefixos das linhas: "# " subtítulo · "• " item de lista · "= " fórmula/conta · sem prefixo: parágrafo. */
const Tip = (() => {
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  function attr(title, lines) {
    const body = (Array.isArray(lines) ? lines : [lines]).filter(x => x != null && x !== '' && x !== false).join('\n');
    return ` data-tip-title="${esc(title)}" data-tip="${esc(body)}" tabindex="0"`;
  }
  const wrap = (html, title, lines, cls) => `<span class="has-tip${cls ? ' ' + cls : ''}"${attr(title, lines)}>${html}</span>`;

  let box = null, timer = null, cur = null;
  function build(el) {
    box.textContent = '';
    const t = el.dataset.tipTitle;
    if (t) { const h = document.createElement('strong'); h.className = 'tip-t'; h.textContent = t; box.appendChild(h); }
    let ul = null;
    (el.dataset.tip || '').split('\n').forEach(line => {
      if (line.startsWith('• ')) {
        if (!ul) { ul = document.createElement('ul'); box.appendChild(ul); }
        const li = document.createElement('li'); li.textContent = line.slice(2); ul.appendChild(li); return;
      }
      ul = null;
      const p = document.createElement(line.startsWith('= ') ? 'code' : 'p');
      if (line.startsWith('# ')) { p.className = 'tip-h'; p.textContent = line.slice(2); }
      else p.textContent = line.startsWith('= ') ? line.slice(2) : line;
      box.appendChild(p);
    });
  }
  function place(el) {
    const r = el.getBoundingClientRect(), vw = window.innerWidth, vh = window.innerHeight;
    box.style.left = '0px'; box.style.top = '0px';
    const bw = box.offsetWidth, bh = box.offsetHeight;
    let x = Math.min(Math.max(8, r.left), vw - bw - 8);
    let y = r.bottom + 8;
    if (y + bh > vh - 8) y = Math.max(8, r.top - bh - 8);
    box.style.left = x + 'px'; box.style.top = y + 'px';
  }
  function show(el) {
    if (!box) { box = document.createElement('div'); box.className = 'tip'; box.setAttribute('role', 'tooltip'); document.body.appendChild(box); }
    cur = el; build(el); box.classList.add('on'); place(el);
  }
  function hide() { clearTimeout(timer); timer = null; cur = null; if (box) box.classList.remove('on'); }
  const target = e => e.target && e.target.closest ? e.target.closest('[data-tip]') : null;
  document.addEventListener('mouseover', e => {
    const el = target(e);
    if (!el || el === cur) return;
    clearTimeout(timer); timer = setTimeout(() => show(el), 180);
  });
  document.addEventListener('mouseout', e => {
    const el = target(e);
    if (!el) return;
    if (e.relatedTarget && el.contains(e.relatedTarget)) return;
    hide();
  });
  document.addEventListener('focusin', e => { const el = target(e); if (el) show(el); });
  document.addEventListener('focusout', hide);
  document.addEventListener('keydown', e => { if (e.key === 'Escape') hide(); });
  document.addEventListener('scroll', hide, true);
  document.addEventListener('click', hide, true);
  return { attr, wrap, esc };
})();
