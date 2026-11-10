// Harness logika playlist karaoke v1.4.1 (tanpa Android, tanpa TV).
// Menjalankan web/app.js di VM Node dengan stub DOM/WebSocket/localStorage,
// lalu menguji skenario persis laporan user:
//  1. "+" menyimpan ke playlist (dedupe), tetap ada walau putus + reload.
//  2. Antrean TV yang menyusut saat lagu diputar TIDAK menimpa playlist.
//  3. "Putar playlist" = clear + putar lagu pertama + antre sisanya,
//     tekan dua kali hasilnya sama (tidak menggandakan).
//  4. "+" tetap bisa saat putus (bangun playlist offline).
//  5. ✕ hapus dari simpanan (+ queue_remove TV kalau lagunya ada di antrean).
// Jalankan: node tools/test-playlist-v141.mjs
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
    _dump: () => Object.fromEntries(m),
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

const FAKE_RESULTS = [
  { type: 'video', videoId: 'AAA', title: 'Lagu A Karaoke', author: 'Ch A', lengthSeconds: 200, videoThumbnails: [{ url: 'http://t/a.jpg', width: 320 }] },
  { type: 'video', videoId: 'BBB', title: 'Lagu B Karaoke', author: 'Ch B', lengthSeconds: 210, videoThumbnails: [{ url: 'http://t/b.jpg', width: 320 }] },
  { type: 'video', videoId: 'CCC', title: 'Lagu C Karaoke', author: 'Ch C', lengthSeconds: 190, videoThumbnails: [{ url: 'http://t/c.jpg', width: 320 }] },
  { type: 'video', videoId: 'DDD', title: 'Lagu D Karaoke', author: 'Ch D', lengthSeconds: 220, videoThumbnails: [{ url: 'http://t/d.jpg', width: 320 }] },
];

// Satu "HP": jalankan app.js mode phone di sandbox baru.
// storage boleh dipakai ulang untuk simulasi reload halaman.
function bootPhone(storage) {
  const doc = makeDocument();
  const wsInstances = [];
  class FakeWS {
    constructor(url) { this.url = url; this.sent = []; this.readyState = 1; wsInstances.push(this); }
    send(s) { this.sent.push(JSON.parse(s)); }
    close() { if (this.onclose) this.onclose({}); }
  }
  const sandbox = {
    console, JSON, Math, Date,
    setTimeout, clearTimeout, setInterval, clearInterval,
    URLSearchParams, AbortController, Promise,
    document: doc,
    location: { search: '?mode=phone&code=1234', hostname: '192.168.1.5', port: '8080' },
    localStorage: storage,
    WebSocket: FakeWS,
    fetch: async (url) => {
      if (String(url).startsWith('/api/search')) {
        return { ok: true, json: async () => FAKE_RESULTS };
      }
      // v1.4.2: halaman HP tanya /api/info dulu sebelum connect.
      if (String(url).startsWith('/api/info')) {
        return { ok: true, json: async () => ({ code: '1234', ip: '192.168.1.5', wsPort: 8081, phoneConnected: false, codeExpiresIn: 25 }) };
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
  const saved = () => {
    try { return JSON.parse(storage.getItem('karaokePlaylist.v1') || '[]'); } catch { return null; }
  };
  const rows = () => el('queueList').children.filter((c) => c.className === 'queue-item');
  const ws = () => wsInstances[wsInstances.length - 1];
  const state = (queue, current) => {
    ws().onmessage({ data: JSON.stringify({ type: 'state', current: current || null, queue, playing: !!current, position: 0, duration: 0, volume: 80 }) });
  };
  return { doc, el, ws, wsInstances, saved, rows, state, storage };
}

const S = (q) => q.map((id) => ({ videoId: id, title: 'Lagu ' + id, thumb: '' }));

async function main() {
  console.log('== skenario 1: tambah 3 lagu via + ==');
  const storage = makeStorage();
  const p = bootPhone(storage);
  await sleep(50); // v1.4.2: connect HP async (tanya /api/info dulu)
  p.ws().onopen();
  p.el('searchInput').value = 'lagu';
  p.el('searchBtn').onclick();
  await sleep(80);
  const addBtns = p.el('results').children.map((row) => row.children[2]);
  check('hasil cari tampil 4 baris', p.el('results').children.length === 4, p.el('results').children.length);
  for (let i = 0; i < 3; i++) addBtns[i].onclick({ stopPropagation() {} });
  check('tersimpan 3 lagu', (p.saved() || []).length === 3, p.saved());
  check('tab playlist tampil 3 baris', p.rows().length === 3, p.rows().length);
  check('counter tab = 3', p.el('queueCount').textContent === '3' || p.el('queueCount').textContent === 3, p.el('queueCount').textContent);
  check('queue_add terkirim 3x', p.ws().sent.filter((m) => m.type === 'queue_add').length === 3, p.ws().sent.map((m) => m.type));

  console.log('== skenario 2: antrean TV menyusut saat diputar, playlist tetap 3 ==');
  p.state(S(['AAA', 'BBB', 'CCC']), null);
  check('state penuh: simpan tetap 3', p.saved().length === 3, p.saved());
  p.state(S(['BBB', 'CCC']), { videoId: 'AAA', title: 'Lagu AAA' });
  check('A diputar: simpan tetap 3', p.saved().length === 3, p.saved());
  check('tab tetap tampil 3 baris', p.rows().length === 3, p.rows().length);
  p.state(S(['CCC']), { videoId: 'BBB', title: 'Lagu BBB' });
  check('B diputar: simpan tetap 3', p.saved().length === 3, p.saved());
  p.state([], { videoId: 'CCC', title: 'Lagu CCC' });
  check('C diputar (antrean kosong): simpan tetap 3', p.saved().length === 3, p.saved());
  p.state([], null);
  check('semua selesai: simpan tetap 3', p.saved().length === 3, p.saved());

  console.log('== skenario 3: putus lalu reload halaman, playlist tetap ada ==');
  p.ws().close();
  check('saat putus: tab tetap tampil 3 baris', p.rows().length === 3, p.rows().length);
  const p2 = bootPhone(storage); // reload: storage sama, DOM baru
  await sleep(50); // v1.4.2: connect HP async (tanya /api/info dulu)
  check('setelah reload: tab tampil 3 baris', p2.rows().length === 3, p2.rows().length);
  check('setelah reload: isi = AAA,BBB,CCC', JSON.stringify(p2.saved().map((x) => x.videoId)) === '["AAA","BBB","CCC"]', p2.saved());

  console.log('== skenario 4: putar playlist 2x, tidak mengganda ==');
  p2.ws().onopen();
  const sent0 = p2.ws().sent.length;
  p2.el('btnPlayPlaylist').onclick();
  await sleep(1000);
  const batch1 = p2.ws().sent.slice(sent0);
  check('tekan 1: urutan clear,play,add,add', JSON.stringify(batch1.map((m) => m.type)) === '["queue_clear","play","queue_add","queue_add"]', batch1.map((m) => m.type));
  check('tekan 1: play = AAA', batch1[1].videoId === 'AAA', batch1[1]);
  check('tekan 1: add = BBB,CCC', batch1[2].videoId === 'BBB' && batch1[3].videoId === 'CCC', batch1.slice(2));
  // TV tiruan: terapkan pesan ke antrean
  const tvq = [];
  let current = null;
  const apply = (batch) => {
    for (const m of batch) {
      if (m.type === 'queue_clear') tvq.length = 0;
      if (m.type === 'play') current = m.videoId;
      if (m.type === 'queue_add') tvq.push(m.videoId);
    }
  };
  apply(batch1);
  check('TV setelah tekan 1: current AAA, antrean BBB,CCC', current === 'AAA' && JSON.stringify(tvq) === '["BBB","CCC"]', { current, tvq });
  const sent1 = p2.ws().sent.length;
  p2.el('btnPlayPlaylist').onclick();
  await sleep(1000);
  const batch2 = p2.ws().sent.slice(sent1);
  apply(batch2);
  check('tekan 2: pesan sama persis', JSON.stringify(batch2.map((m) => m.type)) === '["queue_clear","play","queue_add","queue_add"]', batch2.map((m) => m.type));
  check('TV setelah tekan 2: tetap AAA + BBB,CCC (tidak ganda)', current === 'AAA' && JSON.stringify(tvq) === '["BBB","CCC"]', { current, tvq });

  console.log('== skenario 5: + saat putus tetap simpan (offline), dedupe ==');
  p2.ws().close();
  p2.el('searchInput').value = 'lagu';
  p2.el('searchBtn').onclick();
  await sleep(80);
  const addBtns2 = p2.el('results').children.map((row) => row.children[2]);
  const sentBefore = p2.ws().sent.length;
  addBtns2[3].onclick({ stopPropagation() {} }); // DDD
  check('offline +: tersimpan jadi 4', p2.saved().length === 4, p2.saved());
  check('offline +: tidak ada queue_add terkirim', p2.ws().sent.length === sentBefore, p2.ws().sent.length);
  check('offline +: tab tampil 4 baris', p2.rows().length === 4, p2.rows().length);
  addBtns2[0].onclick({ stopPropagation() {} }); // AAA lagi
  check('dedupe: + AAA lagi tetap 4', p2.saved().length === 4, p2.saved());

  console.log('== skenario 6: hapus via ✕ ==');
  p2.ws().onopen();
  p2.state(S(['BBB', 'CCC', 'DDD']), { videoId: 'AAA', title: 'Lagu AAA' });
  const sentBeforeRm = p2.ws().sent.length;
  const rowBBB = p2.rows()[1];
  rowBBB.children[2].onclick({ stopPropagation() {} }); // ✕ BBB
  check('✕ BBB: tersimpan jadi 3 (AAA,CCC,DDD)', JSON.stringify(p2.saved().map((x) => x.videoId)) === '["AAA","CCC","DDD"]', p2.saved());
  check('✕ BBB: tab tampil 3 baris', p2.rows().length === 3, p2.rows().length);
  const rmMsgs = p2.ws().sent.slice(sentBeforeRm);
  check('✕ BBB: queue_remove index 0 terkirim ke TV', rmMsgs.length === 1 && rmMsgs[0].type === 'queue_remove' && rmMsgs[0].index === 0, rmMsgs);

  console.log('\n' + pass + ' lolos, ' + fail + ' gagal');
  process.exit(fail ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
