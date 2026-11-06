/* Utilitários compartilhados: API, datas, escape de HTML, ícones. */
const SA = (() => {
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  async function api(method, url, body, extra) {
    const r = await fetch('api/' + url, {
      method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'SysAttack', ...(extra || {}) },
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
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M16 7l3 3M14 9l2 2"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 018 0v3"/>',
    unlock: '<rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11V8a4 4 0 017.5-2"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="1"/><path d="M3 7l9 6 9-6"/>',
    shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>'
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

  document.querySelectorAll('[data-year]').forEach(e => { e.textContent = new Date().getFullYear(); });

  // ---- valores em reais: máscara brasileira (1.234,56) em campo de texto; o envio à API usa 1234.56
  const moneyFmt = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  /** Número (ou texto numérico) -> "1.234,56"; vazio se não houver valor. */
  function moneyText(v) {
    if (v === null || v === undefined || v === '' || !isFinite(Number(v))) return '';
    return moneyFmt.format(Number(v));
  }
  /** "1.234,56" -> "1234.56" (texto aceito pela API); "" se vazio. */
  function moneyValue(t) {
    const d = String(t ?? '').replace(/\D/g, '').replace(/^0+(?=\d)/, '');
    if (!d) return '';
    const p = d.padStart(3, '0');
    return p.slice(0, -2) + '.' + p.slice(-2);
  }
  /** Campo de valor em reais. */
  function moneyInput(id, name, value, { required = false, placeholder = '' } = {}) {
    return `<input id="${id}" name="${name}" type="text" inputmode="numeric" autocomplete="off" data-money value="${esc(moneyText(value))}"${required ? ' required' : ''}${placeholder ? ` placeholder="${esc(placeholder)}"` : ''}>`;
  }
  // máscara ao digitar: os dígitos entram como centavos (1 -> 0,01; 12345 -> 123,45)
  document.addEventListener('input', e => {
    const el = e.target;
    if (!el || !el.matches || !el.matches('input[data-money]')) return;
    const v = moneyValue(el.value);
    el.value = v ? moneyText(v) : '';
  });
  /** Troca, em um objeto de formulário, os campos de valor pelo formato da API. */
  function moneyFields(form, d) {
    form.querySelectorAll('input[data-money]').forEach(i => { if (i.name in d) d[i.name] = moneyValue(i.value); });
    return d;
  }

  return { moneyText, moneyValue, moneyInput, moneyFields, versionLabel, rulesHtml, bindRules, esc, api, todayIso, fmt, dayNum, isoOf, addDays, duration, ic };
})();
