/* Utilitários compartilhados: API, datas, escape de HTML, ícones. */
const SA = (() => {
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  async function api(method, url, body) {
    const r = await fetch('api/' + url, {
      method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'SysAttack' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const data = await r.json().catch(() => null);
    if (r.status === 401) { location.replace('login.html'); throw new Error('Sessão expirada.'); }
    if (!r.ok) throw new Error((data && data.error) || 'Erro inesperado (' + r.status + ').');
    return data;
  }

  const pad = n => String(n).padStart(2, '0');
  const todayIso = () => { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
  const fmt = iso => iso ? iso.split('-').reverse().join('/') : '';
  const DAY = 86400000;
  const dayNum = iso => Math.round(Date.parse(iso + 'T00:00:00Z') / DAY);
  const isoOf = n => new Date(n * DAY).toISOString().slice(0, 10);
  const addDays = (iso, n) => isoOf(dayNum(iso) + n);
  const duration = (a, b) => dayNum(b) - dayNum(a) + 1;

  const icons = {
    box: '<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/>',
    list: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
    bars: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    people: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/><path d="M16 4.5a3.5 3.5 0 010 7M18 14.5c2.2.6 3.5 2.6 3.5 5.5"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M16 7l3 3M14 9l2 2"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 018 0v3"/>',
    unlock: '<rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 017.5-2"/>'
  };
  const ic = n => `<svg class="ic" viewBox="0 0 24 24" aria-hidden="true">${icons[n]}</svg>`;

  const PW_RULES = [
    ['Pelo menos 10 caracteres', p => p.length >= 10],
    ['Uma letra minúscula', p => /[a-z]/.test(p)],
    ['Uma letra maiúscula', p => /[A-Z]/.test(p)],
    ['Um número', p => /\d/.test(p)],
    ['Um símbolo (! @ # $ %)', p => /[^A-Za-z0-9]/.test(p)]
  ];
  const rulesHtml = () => '<ul class="rules" aria-live="polite">' + PW_RULES.map(r => `<li>${r[0]}</li>`).join('') + '</ul>';
  /** Atualiza a lista de regras (dentro de `root`) conforme o campo de senha é digitado. */
  const bindRules = (input, root) => {
    const lis = root.querySelectorAll('.rules li');
    const upd = () => PW_RULES.forEach((r, i) => lis[i].classList.toggle('ok', r[1](input.value)));
    input.addEventListener('input', upd); upd();
  };

  /** Versão do sistema (arquivo version.json): { version, stage, released }. Texto: "1.0.0 - Alpha". */
  const versionLabel = async () => {
    try {
      const r = await fetch('version.json', { cache: 'no-store' });
      const v = await r.json();
      return v.version + (v.stage ? ' - ' + v.stage : '');
    } catch (e) { return ''; }
  };

  return { versionLabel, rulesHtml, bindRules, esc, api, todayIso, fmt, dayNum, isoOf, addDays, duration, ic };
})();
