/* SysAttack — gerador de QR Code (modo byte, correção M, versões 1 a 10), sem bibliotecas. Uso: QR.svg(texto, { scale, margin }) */
const QR = (() => {
  const ECC = [10, 16, 26, 18, 24, 16, 18, 22, 22, 26];  // bytes de correção por bloco (M), versões 1..10
  const BLK = [1, 1, 1, 2, 2, 4, 4, 4, 5, 5];             // número de blocos (M)
  const EXP = new Array(512), LOG = new Array(256);
  (() => { let x = 1; for (let i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; } for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; })();
  const mul = (a, b) => (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]];

  function rsGenerator(deg) {
    let p = [1];
    for (let i = 0; i < deg; i++) { const n = new Array(p.length + 1).fill(0); for (let j = 0; j < p.length; j++) { n[j] ^= p[j]; n[j + 1] ^= mul(p[j], EXP[i]); } p = n; }
    return p;
  }
  function rsRemainder(data, deg) {
    const gen = rsGenerator(deg), res = new Array(deg).fill(0);
    for (const d of data) {
      const f = d ^ res.shift(); res.push(0);
      for (let i = 0; i < deg; i++) res[i] ^= mul(gen[i + 1], f);
    }
    return res;
  }
  const rawModules = v => { let r = (16 * v + 128) * v + 64; if (v >= 2) { const n = Math.floor(v / 7) + 2; r -= (25 * n - 10) * n - 55; if (v >= 7) r -= 36; } return r; };
  const dataCodewords = v => Math.floor(rawModules(v) / 8) - ECC[v - 1] * BLK[v - 1];
  const alignPositions = v => {
    if (v === 1) return [];
    const n = Math.floor(v / 7) + 2, size = v * 4 + 17, step = Math.ceil((v * 4 + 4) / (n * 2 - 2)) * 2, res = [6];
    for (let pos = size - 7; res.length < n; pos -= step) res.splice(1, 0, pos);
    return res;
  };
  function bch(value, poly, bitsLen) { let r = value << bitsLen; const pl = 31 - Math.clz32(poly); for (let i = 31 - Math.clz32(r); i >= pl; i--) if ((r >>> i) & 1) r ^= poly << (i - pl); return r; }

  function build(bytes) {
    let ver = 1;
    for (; ver <= 10; ver++) { const cap = dataCodewords(ver) * 8; const lenBits = ver < 10 ? 8 : 16; if (4 + lenBits + bytes.length * 8 <= cap) break; }
    if (ver > 10) throw new Error('Texto longo demais para o QR Code.');
    const size = ver * 4 + 17, cap = dataCodewords(ver) * 8;
    // fluxo de bits
    const bits = [];
    const put = (val, n) => { for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    put(4, 4); put(bytes.length, ver < 10 ? 8 : 16); bytes.forEach(b => put(b, 8));
    put(0, Math.min(4, cap - bits.length));
    while (bits.length % 8) bits.push(0);
    for (let pad = 0xec; bits.length < cap; pad ^= 0xec ^ 0x11) put(pad, 8);
    const data = []; for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
    // blocos + correção de erros
    const nb = BLK[ver - 1], ecl = ECC[ver - 1], raw = Math.floor(rawModules(ver) / 8), shortCnt = nb - raw % nb, shortLen = Math.floor(raw / nb);
    const blocks = []; let k = 0;
    for (let i = 0; i < nb; i++) { const len = shortLen - ecl + (i < shortCnt ? 0 : 1); const d = data.slice(k, k + len); k += len; blocks.push({ d, e: rsRemainder(d, ecl) }); }
    const out = [];
    for (let i = 0; i < blocks[nb - 1].d.length; i++) blocks.forEach((b, j) => { if (i < b.d.length) out.push(b.d[i]); });
    for (let i = 0; i < ecl; i++) blocks.forEach(b => out.push(b.e[i]));
    // matriz
    const mod = Array.from({ length: size }, () => new Array(size).fill(false)), fn = Array.from({ length: size }, () => new Array(size).fill(false));
    const set = (x, y, dark) => { mod[y][x] = dark; fn[y][x] = true; };
    for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    const finder = (cx, cy) => { for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) { const x = cx + dx, y = cy + dy; if (x < 0 || y < 0 || x >= size || y >= size) continue; const d = Math.max(Math.abs(dx), Math.abs(dy)); set(x, y, d !== 2 && d !== 4); } };
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    const ap = alignPositions(ver);
    ap.forEach((ay, i) => ap.forEach((ax, j) => { if ((i === 0 && j === 0) || (i === 0 && j === ap.length - 1) || (i === ap.length - 1 && j === 0)) return; for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1); }));
    const formatBits = mask => { const d = (0 << 3) | mask; const v = ((d << 10) | bch(d, 0x537, 10)) ^ 0x5412; // nível M = 00
      const g = i => ((v >>> i) & 1) === 1;
      for (let i = 0; i <= 5; i++) set(8, i, g(i)); set(8, 7, g(6)); set(8, 8, g(7)); set(7, 8, g(8));
      for (let i = 9; i < 15; i++) set(14 - i, 8, g(i));
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, g(i)); for (let i = 8; i < 15; i++) set(8, size - 15 + i, g(i)); set(8, size - 8, true); };
    formatBits(0);
    if (ver >= 7) { const v = (ver << 12) | bch(ver, 0x1f25, 12); for (let i = 0; i < 18; i++) { const bit = ((v >>> i) & 1) === 1, a = size - 11 + i % 3, b = Math.floor(i / 3); set(a, b, bit); set(b, a, bit); } }
    // dados em zigue-zague
    const stream = []; out.forEach(b => { for (let i = 7; i >= 0; i--) stream.push((b >>> i) & 1); });
    let idx = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
        const x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
        if (!fn[y][x] && idx < stream.length) { mod[y][x] = stream[idx++] === 1; }
      }
    }
    // máscara com a menor penalidade
    const MASKS = [(x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x, y) => x % 3 === 0, (x, y) => (x + y) % 3 === 0, (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
      (x, y) => x * y % 2 + x * y % 3 === 0, (x, y) => (x * y % 2 + x * y % 3) % 2 === 0, (x, y) => ((x + y) % 2 + x * y % 3) % 2 === 0];
    const apply = m => { for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && MASKS[m](x, y)) mod[y][x] = !mod[y][x]; };
    const penalty = () => {
      let p = 0;
      for (let pass = 0; pass < 2; pass++) for (let a = 0; a < size; a++) { let run = 1; for (let b = 1; b < size; b++) { const c = pass ? mod[b][a] : mod[a][b], pr = pass ? mod[b - 1][a] : mod[a][b - 1]; if (c === pr) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1; } }
      for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) { const c = mod[y][x]; if (c === mod[y][x + 1] && c === mod[y + 1][x] && c === mod[y + 1][x + 1]) p += 3; }
      let dark = 0; mod.forEach(r => r.forEach(c => { if (c) dark++; }));
      p += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10;
      return p;
    };
    let best = 0, bestP = Infinity;
    for (let m = 0; m < 8; m++) { apply(m); formatBits(m); const p = penalty(); if (p < bestP) { bestP = p; best = m; } apply(m); }
    apply(best); formatBits(best);
    return mod;
  }

  function svg(text, opt) {
    const o = Object.assign({ scale: 4, margin: 4 }, opt || {});
    const m = build(Array.from(new TextEncoder().encode(text))), n = m.length, full = n + o.margin * 2;
    let d = '';
    m.forEach((row, y) => { let x = 0; while (x < n) { if (row[x]) { let w = 1; while (x + w < n && row[x + w]) w++; d += `M${x + o.margin} ${y + o.margin}h${w}v1h-${w}z`; x += w; } else x++; } });
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${full} ${full}" width="${full * o.scale}" height="${full * o.scale}" shape-rendering="crispEdges" role="img" aria-label="QR Code"><rect width="${full}" height="${full}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
  }
  return { svg };
})();
