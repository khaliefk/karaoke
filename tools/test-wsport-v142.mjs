// Harness logika port WS karaoke v1.4.2 (tanpa Android, tanpa TV).
// Menjalankan web/app.js di VM Node dengan stub DOM/WebSocket/fetch,
// lalu menguji skenario persis laporan user (11 Nov 2026):
//  server TV pindah ke port cadangan (HTTP 8082, WS 8083) karena
//  8080/8081 masih kepakai saat service restart sesudah update.
//  Dulu halaman HP langsung connect ke 8081 yang ditulis mati,
//  jadi nyambung ke server lama dan auth ditolak 4001 "bad code"
//  walau kode di QR benar. Sekarang halaman HP wajib tanya /api/info
//  dulu sebelum connect.
//  1. Info bilang WS 8083 -> WebSocket dibuka ke 8083, bukan 8081.
//  2. Fetch /api/info gagal -> fallback pakai port HTTP + 1.
//  3. Port normal (HTTP 8080, WS 8081) -> tetap connect ke 8081.
//  4. Mode TV tidak berubah: WS pertama ke 8081, lalu polling
//     /api/info membetulkan port untuk reconnect berikutnya.
// Jalankan: node tools/test-wsport-v142.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const code = fs.readFileSync(path.join(root, 'web', 'app.js'), 'utf8');

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('  ok  ' + name); }
  else { fail++; console.log('  GAGAL  ' + name + (extra !== undefined ? ' — ' + JSON.stringify(extra) : '')); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => { m.set(k, String(v)); },
    removeItem: (k) => { m.delete(k); },
  };
}

function makeEl(id) {
  const el = {
    id, children: [], style: {}, textContent: '', value: '',
    src: '', alt: '', referrerPolicy: '', className: '',
    onclick: null, _listeners: {},
    _cls: new Set(),
    classList: {
      add: (c) => el._cls.add(c),
      remove: (c) => el._cls.delete(c),
      toggle: (c, f) => { if (f === undefined) f = !el._cls.has(c); f ? el._cls.add(c) : el._cls.delete(c); },
      contains: (c) => el._cls.has(c),
    },
    addEventListener: (t, fn) => { (el._listeners[t] = el._listeners[t] || []).push(fn); },
    appendChild: (c) => { el.children.push(c); return c; },
    focus: () => {},
    getBoundingClientRect: () => ({ left: 0, width: 100 }),
  };
  let html = '';
  Object.defineProperty(el, 'innerHTML', {
    get: () => html,
    set: (v) => { html = String(v); el.children = []; },
  });
  return el;
}

function makeDocument() {
  const byId = new Map();
  const body = makeEl('body');
  return {
    body,
    getElementById: (id) => {
      if (!byId.has(id)) byId.set(id, makeEl(id));
      return byId.get(id);
    },
    createElement: (tag) => makeEl(tag),
    querySelectorAll: () => [],
    addEventListener: () => {},
  };
}

// Satu "HP" atau "TV": jalankan app.js di sandbox baru.
// opts: { mode, httpPort, wsPort (jawaban /api/info, null = fetch gagal) }
function boot(opts) {
  const doc = makeDocument();
  const storage = makeStorage();
  const wsInstances = [];
  class FakeWS {
    constructor(url) { this.url = url; this.sent = []; this.readyState = 1; wsInstances.push(this); }
    send(s) { this.sent.push(JSON.parse(s)); }
    close() { if (this.onclose) this.onclose({}); }
  }
  const search = opts.mode === 'tv' ? '?mode=tv' : '?mode=phone&code=123456';
  const sandbox = {
    console, JSON, Math, Date,
    setTimeout, clearTimeout, setInterval, clearInterval,
    URLSearchParams, AbortController, Promise,
    document: doc,
    location: { search, hostname: '192.168.1.5', port: String(opts.httpPort) },
    localStorage: storage,
    WebSocket: FakeWS,
    fetch: async (url) => {
      if (String(url).startsWith('/api/info')) {
        if (opts.wsPort === null) throw new Error('fetch gagal');
        return { ok: true, json: async () => ({ code: '123456', ip: '192.168.1.5', wsPort: opts.wsPort, phoneConnected: false, codeExpiresIn: 25 }) };
      }
      return { ok: false, status: 404, json: async () => ({}) };
    },
    navigator: {},
  };
  sandbox.window = sandbox;
  sandbox.window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: 'app.js' });
  const el = (id) => doc.getElementById(id);
  const ws = () => wsInstances[wsInstances.length - 1];
  return { doc, el, ws, wsInstances, storage };
}

async function main() {
  console.log('== skenario 1: server di port cadangan, HP wajib ke WS 8083 ==');
  const p1 = boot({ mode: 'phone', httpPort: 8082, wsPort: 8083 });
  await sleep(80);
  check('WebSocket dibuka (bukan diam)', p1.wsInstances.length === 1, p1.wsInstances.length);
  check('WS ke port 8083 dari /api/info', p1.ws() && p1.ws().url === 'ws://192.168.1.5:8083', p1.ws() && p1.ws().url);
  p1.ws().onopen();
  check('auth terkirim role phone + kode QR', p1.ws().sent.length >= 1 && p1.ws().sent[0].type === 'auth' && p1.ws().sent[0].role === 'phone' && p1.ws().sent[0].code === '123456', p1.ws().sent[0]);

  console.log('== skenario 2: /api/info gagal, fallback port HTTP + 1 ==');
  const p2 = boot({ mode: 'phone', httpPort: 8082, wsPort: null });
  await sleep(80);
  check('WS ke port 8083 (8082 + 1), bukan 8081', p2.ws() && p2.ws().url === 'ws://192.168.1.5:8083', p2.ws() && p2.ws().url);

  console.log('== skenario 3: port normal, tetap ke 8081 ==');
  const p3 = boot({ mode: 'phone', httpPort: 8080, wsPort: 8081 });
  await sleep(80);
  check('WS ke port 8081', p3.ws() && p3.ws().url === 'ws://192.168.1.5:8081', p3.ws() && p3.ws().url);

  console.log('== skenario 4: mode TV tidak berubah ==');
  const p4 = boot({ mode: 'tv', httpPort: 8082, wsPort: 8083 });
  check('WS pertama TV ke 8081 (default lama)', p4.ws() && p4.ws().url === 'ws://192.168.1.5:8081', p4.ws() && p4.ws().url);
  p4.ws().onopen();
  await sleep(80); // polling /api/info pertama selesai, WS_PORT jadi 8083
  p4.ws().close(); // putus biasa -> reconnect 2 detik lagi pakai port baru
  await sleep(2200);
  check('reconnect TV pakai WS 8083 dari polling', p4.wsInstances.length === 2 && p4.ws().url === 'ws://192.168.1.5:8083', p4.wsInstances.map((w) => w.url));

  console.log('\n' + pass + ' lolos, ' + fail + ' gagal');
  process.exit(fail ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
