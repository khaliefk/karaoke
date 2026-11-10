/* Karaoke for TV + Remote, satu file JS untuk dua mode.
   mode=tv    : jalan di WebView TV, pegang antrean + player YouTube.
   mode=phone : jalan di WebView HP, kirim perintah + cari lagu. */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var MODE = params.get('mode') === 'phone' ? 'phone' : 'tv';
  var CODE = params.get('code') || '';
  var WS_PORT = 8081;
  /* Versi aplikasi — UBAH BARENG android:versionName di kedua
     AndroidManifest.xml (tv + remote) tiap kali rilis baru. */
  var APP_VERSION = '1.4.2';
  document.body.classList.add(MODE === 'tv' ? 'mode-tv' : 'mode-phone');

  function $(id) { return document.getElementById(id); }
  function fmt(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var m = Math.floor(sec / 60), s = sec % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  /* ---------- Tema tampilan Remote (v1.3.8, mode HP saja) ----------
     Pilihan: system (ikut HP) / dark / light. Disimpan di aplikasi
     native lewat bridge Android (SharedPreferences) supaya layar
     pilihan native ikut ganti; kalau bridge tidak ada (dibuka di
     browser biasa), pakai localStorage. */
  var THEME_KEY = 'karaoke-theme';
  var themeMode = 'system'; // pilihan user: system | dark | light
  var themeMedia = null;    // matchMedia gelap/terang sistem

  function themeBridgeGet() {
    try {
      if (window.Android && window.Android.getTheme) {
        var t = window.Android.getTheme();
        if (t === 'dark' || t === 'light' || t === 'system') return t;
      }
    } catch (e) {}
    return null;
  }
  function themeBridgeSet(mode) {
    try {
      if (window.Android && window.Android.setTheme) window.Android.setTheme(mode);
    } catch (e) {}
  }
  function themeStoredGet() {
    var b = themeBridgeGet();
    if (b) return b;
    try {
      var s = localStorage.getItem(THEME_KEY);
      if (s === 'dark' || s === 'light' || s === 'system') return s;
    } catch (e) {}
    return 'system';
  }
  function themeStoredSet(mode) {
    themeBridgeSet(mode);
    try { localStorage.setItem(THEME_KEY, mode); } catch (e) {}
  }
  function themeIsDark() {
    if (themeMode === 'dark') return true;
    if (themeMode === 'light') return false;
    try {
      return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    } catch (e) { return true; }
  }
  function applyTheme() {
    if (MODE !== 'phone') return;
    document.body.classList.toggle('theme-light', !themeIsDark());
    var label = themeMode === 'system' ? 'Sistem' : themeMode === 'dark' ? 'Gelap' : 'Terang';
    var b = $('btnTheme');
    if (b) b.textContent = '🎨 ' + label;
    var opts = document.querySelectorAll('.theme-opt');
    for (var i = 0; i < opts.length; i++) {
      var o = opts[i];
      var on = o.getAttribute('data-theme-opt') === themeMode;
      o.classList.toggle('active', on);
      var c = o.querySelector('.theme-check');
      if (c) c.textContent = on ? ' ✓' : '';
    }
  }
  function setTheme(mode) {
    if (mode !== 'dark' && mode !== 'light' && mode !== 'system') return;
    themeMode = mode;
    themeStoredSet(mode);
    applyTheme();
  }
  function initTheme() {
    if (MODE !== 'phone') return;
    themeMode = themeStoredGet();
    applyTheme();
    try {
      if (window.matchMedia) {
        themeMedia = window.matchMedia('(prefers-color-scheme: dark)');
        var onChange = function () { if (themeMode === 'system') applyTheme(); };
        if (themeMedia.addEventListener) themeMedia.addEventListener('change', onChange);
        else if (themeMedia.addListener) themeMedia.addListener(onChange);
      }
    } catch (e) {}
    var tb = $('btnTheme');
    if (tb) tb.onclick = function () { $('themeModal').classList.remove('hidden'); };
    var tc = $('btnThemeClose');
    if (tc) tc.onclick = function () { $('themeModal').classList.add('hidden'); };
    var opts = document.querySelectorAll('.theme-opt');
    for (var i = 0; i < opts.length; i++) {
      (function (el) {
        el.onclick = function () {
          setTheme(el.getAttribute('data-theme-opt'));
          $('themeModal').classList.add('hidden');
        };
      })(opts[i]);
    }
    var tm = $('themeModal');
    if (tm) tm.addEventListener('click', function (e) { if (e.target === tm) tm.classList.add('hidden'); });
  }

  /* ---------- WebSocket ---------- */
  var ws = null, wsOk = false, authFailed = false, manualDisconnect = false;

  function wsUrl() { return 'ws://' + location.hostname + ':' + WS_PORT; }

  function connect() {
    if (authFailed || manualDisconnect) return;
    try { ws = new WebSocket(wsUrl()); } catch (e) { setTimeout(connect, 2000); return; }
    ws.onopen = function () {
      wsOk = true;
      send({ type: 'auth', role: MODE, code: CODE });
      if (MODE === 'phone') { loadSavedPlaylist(); renderPlaylist(); send({ type: 'get_state' }); }
      var d = $('btnDisconnect'); if (d) d.classList.remove('hidden');
      setConn(true, 'Terhubung ke TV');
    };
    ws.onmessage = function (ev) {
      var msg = null;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      onMsg(msg);
    };
    ws.onclose = function (ev) {
      wsOk = false;
      /* v1.3.1: mic ikut mati saat koneksi putus, apapun penyebabnya.
         Matikan stream native + reset UI, supaya toggle bisa di-ON-kan
         lagi setelah sambung ulang (bug v1.3.0: mic nyangkut OFF
         dengan pesan gagal setelah layanan putus). */
      stopMicNative();
      setMicUi(false, '');
      if (ev && ev.code === 4001) {
        authFailed = true;
        setConn(false, 'Kode pairing salah. Scan ulang QR di TV.');
        var b = $('btnRescan'); if (b) b.classList.remove('hidden');
        var d = $('btnDisconnect'); if (d) d.classList.add('hidden');
        return;
      }
      if (manualDisconnect) {
        setConn(false, 'Terputus dari TV. Scan ulang QR untuk sambung lagi.');
        var b2 = $('btnRescan'); if (b2) b2.classList.remove('hidden');
        renderPlaylist(); // tampilkan playlist tersimpan
        return;
      }
      setConn(false, 'Koneksi putus. Menghubungkan ulang…');
      renderPlaylist(); // tampilkan playlist tersimpan selama reconnect
      setTimeout(connect, 2000);
    };
    ws.onerror = function () { /* onclose yang urus reconnect */ };
  }

  /* v1.4.2: halaman HP wajib tahu port WS asli sebelum connect.
     Server TV bisa pindah port WS kalau 8081 kepakai (jatuh ke
     8083/18081/8889); port itu cuma ada di /api/info. Dulu halaman HP
     langsung connect ke 8081 yang ditulis mati di atas, jadi kalau
     server pindah port, HP nyambung ke server lama (atau ke port
     kosong) dan auth ditolak 4001 "bad code" walau kode di QR benar.
     Tanya /api/info dulu; kalau fetch gagal, pakai port HTTP + 1. */
  function connectWithWsPort() {
    var fallback = 8081;
    try {
      var hp = parseInt(location.port, 10);
      if (hp > 0) fallback = hp + 1;
    } catch (e) {}
    fetch('/api/info?t=' + Date.now()).then(function (r) { return r.json(); }).then(function (info) {
      if (typeof info.wsPort === 'number' && info.wsPort > 0) WS_PORT = info.wsPort;
      else WS_PORT = fallback;
      connect();
    }).catch(function () { WS_PORT = fallback; connect(); });
  }

  /* Putus manual dari TV: tutup socket, matikan auto-reconnect. TV tetap
     jalan (lagu/antrean dipegang halaman TV), cuma HP-nya yang putus.
     Sambung lagi lewat tombol "Scan Ulang QR TV" (halaman di-load ulang). */
  function disconnectTv() {
    manualDisconnect = true;
    wsOk = false;
    try { if (ws) ws.close(); } catch (e) {}
    stopMicNative(); // mic ikut mati saat putus dari TV
    setMicUi(false, '');
    setConn(false, 'Terputus dari TV. Scan ulang QR untuk sambung lagi.');
    var d = $('btnDisconnect'); if (d) d.classList.add('hidden');
    var b = $('btnRescan'); if (b) b.classList.remove('hidden');
    var card = $('npCard'); if (card) card.classList.add('hidden');
    /* v1.4.1: jangan kosongkan daftar, tampilkan playlist tersimpan
       supaya user bisa kirim ulang begitu sambung lagi. */
    renderPlaylist();
    setSearchStatus('Putus dari TV. TV tetap jalan, cuma HP-nya yang putus.');
  }

  function send(obj) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  }

  /* ================= MODE TV ================= */
  var queue = [], current = null, history = [];
  var playing = false, position = 0, duration = 0, volume = 80;
  var player = null, playerReady = false, ytLoaded = false, pendingVideo = null;
  var hadPhone = false;   // pernah ada HP terhubung sejak halaman dimuat
  var lastCode = '';      // kode pairing terakhir yang ditampilkan di layar tunggu

  function showTv(id) {
    $('waitScreen').classList.toggle('hidden', id !== 'wait');
    $('playerScreen').classList.toggle('hidden', id !== 'player');
  }

  /* HP terakhir putus: sesi selesai — matikan lagu, bersihkan antrean,
     balik ke menu awal (QR). Dipanggil saat server kirim peer_left,
     dan sebagai cadangan oleh polling /api/info kalau pesan itu kelewat. */
  function resetSession() {
    if (player && playerReady) {
      try { player.stopVideo(); }
      catch (e) { try { player.pauseVideo(); } catch (e2) {} }
    }
    pendingVideo = null;
    current = null; playing = false; position = 0; duration = 0;
    queue = []; history = [];
    setMicBadge(false); // mic ikut mati saat sesi selesai
    showTv('wait');
    broadcast();
  }

  /* Indikator mic HP (v1.3.0): badge kecil di pojok layar TV. */
  function setMicBadge(on) {
    var b = $('micBadge');
    if (b) b.classList.toggle('hidden', !on);
  }

  function loadYTApi() {
    if (MODE !== 'tv') return; // HP tidak boleh memuat pemutar media apa pun
    if (ytLoaded || window.YT) return;
    ytLoaded = true;
    var s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    document.head.appendChild(s);
  }

  window.onYouTubeIframeAPIReady = function () {
    if (pendingVideo) createPlayer(pendingVideo);
  };

  function createPlayer(videoId) {
    pendingVideo = null;
    /* JANGAN PERNAH ada dua player di div yang sama: hancurkan dulu.
       Tanpa ini, player lama tetap jalan tapi tak terpegang (orphan),
       sementara `player` menunjuk ke player baru yang gagal init —
       semua perintah sesudahnya (ganti lagu, next, pause, seek) hilang. */
    if (player) { try { player.destroy(); } catch (e) {} player = null; playerReady = false; }
    player = new YT.Player('player', {
      videoId: videoId,
      playerVars: { autoplay: 1, controls: 0, fs: 0, rel: 0, modestbranding: 1, playsinline: 1 },
      events: {
        onReady: function (e) {
          playerReady = true;
          try { e.target.setVolume(volume); } catch (err) {}
          e.target.playVideo();
          /* lagu yang diminta saat player masih init: putar sekarang */
          if (pendingVideo) { var v = pendingVideo; pendingVideo = null; loadVideoByIdSafe(v); }
        },
        onStateChange: function (e) {
          if (e.data === YT.PlayerState.ENDED) { playNext(); }
          else if (e.data === YT.PlayerState.PLAYING) { playing = true; broadcast(); }
          else if (e.data === YT.PlayerState.PAUSED) { playing = false; broadcast(); }
        },
        onError: function () { playNext(); }
      }
    });
  }

  function loadVideoByIdSafe(videoId) {
    try {
      player.loadVideoById(videoId);
    } catch (e) {
      /* player rusak: bangun ulang dari nol (destroy dulu di createPlayer) */
      createPlayer(videoId);
    }
  }

  function loadVideo(v) {
    if (!player) { pendingVideo = v.videoId; loadYTApi(); if (window.YT && window.YT.Player) createPlayer(v.videoId); return; }
    /* player ada tapi belum ready (internet lambat = init bisa makan detik):
       JANGAN panggil loadVideoById dulu — simpan, onReady yang eksekusi.
       Dulu di sini langsung dipanggil dan throw-nya bikin player ganda. */
    if (!playerReady) { pendingVideo = v.videoId; return; }
    loadVideoByIdSafe(v.videoId);
  }

  function playNow(v) {
    if (current) history.push(current);
    current = v;
    playing = true;
    loadVideo(v);
    updateNowBar();
    showTv('player');
    broadcast();
  }

  function playNext() {
    if (queue.length > 0) { playNow(queue.shift()); return; }
    current = null; playing = false; position = 0; duration = 0;
    showTv('wait');
    broadcast();
  }

  function playPrev() {
    if (position > 5) { replay(); return; }
    if (history.length > 0) {
      if (current) queue.unshift(current);
      var prev = history.pop();
      current = prev;
      loadVideo(prev);
      updateNowBar();
      broadcast();
      return;
    }
    replay();
  }

  function replay() {
    if (player && playerReady) { try { player.seekTo(0, true); player.playVideo(); } catch (e) {} }
    position = 0; playing = true; broadcast();
  }

  function updateNowBar() {
    if (!current) return;
    $('nowTitle').textContent = '▶ ' + current.title;
    $('nextTitle').textContent = queue.length > 0 ? 'Berikutnya: ' + queue[0].title : 'Antrean kosong, tambah lagu dari HP';
  }

  function broadcast() {
    updateNowBar();
    send({ type: 'state', current: current, queue: queue, playing: playing,
           position: position, duration: duration, volume: volume });
  }

  function tvCommand(msg) {
    switch (msg.type) {
      case 'play':
        if (msg.videoId) playNow({ videoId: msg.videoId, title: msg.title || '', thumb: msg.thumb || '' });
        break;
      case 'queue_add':
        if (msg.videoId) { queue.push({ videoId: msg.videoId, title: msg.title || '', thumb: msg.thumb || '' }); broadcast(); }
        break;
      case 'pause':
        if (player && playerReady) { try { player.pauseVideo(); } catch (e) {} }
        playing = false; broadcast();
        break;
      case 'resume':
        if (player && playerReady) { try { player.playVideo(); } catch (e) {} }
        playing = true; broadcast();
        break;
      case 'next': playNext(); break;
      case 'prev': playPrev(); break;
      case 'replay': replay(); break;
      case 'seek':
        var s = parseFloat(msg.value);
        if (!isNaN(s) && player && playerReady) {
          try { player.seekTo(Math.max(0, s), true); } catch (e) {}
          position = Math.max(0, s); broadcast();
        }
        break;
      case 'volume':
        volume = Math.max(0, Math.min(100, parseInt(msg.value, 10) || 0));
        if (player && playerReady) {
          try { if (volume > 0) player.unMute(); } catch (e) {}
          try { player.setVolume(volume); } catch (e) {}
        }
        broadcast();
        break;
      case 'queue_remove':
        var i = parseInt(msg.index, 10);
        if (!isNaN(i) && i >= 0 && i < queue.length) { queue.splice(i, 1); broadcast(); }
        break;
      case 'queue_clear':
        queue = []; broadcast();
        break;
      case 'get_state':
        broadcast();
        break;
    }
  }

  function initTv() {
    $('tvView').classList.remove('hidden');
    showTv('wait');
    /* tombol Keluar: layar QR + bar bawah player, dua-duanya panggil
       bridge Android yang mematikan service lalu menutup aplikasi */
    var ex1 = $('btnExitWait'); if (ex1) ex1.onclick = exitApp;
    var ex2 = $('btnExitPlayer'); if (ex2) ex2.onclick = exitApp;
    var ab = $('btnAboutWait'); if (ab) ab.onclick = openAbout;
    var tv = $('tvVersion'); if (tv) tv.textContent = 'v' + installedName();
    checkForUpdate(false); // cek update tiap aplikasi dibuka
    /* polling /api/info tiap detik: tampilkan kode pairing terbaru +
       hitung mundur rotasi QR, dan jadi cadangan kalau pesan peer_left
       kelewat saat WS TV sedang reconnect */
    function refreshQrInfo() {
      fetch('/api/info').then(function (r) { return r.json(); }).then(function (info) {
        if (info.code) {
          $('pairCode').textContent = String(info.code).split('').join(' ');
          if (info.code !== lastCode) {
            lastCode = info.code;
            $('qrImg').src = '/qr.png?t=' + Date.now();
          }
        }
        if (info.ip) $('waitIp').textContent = 'TV: ' + info.ip + ' · port ' + location.port;
        /* v1.3.9: server TV bisa pindah port WS kalau 8081 kepakai.
           /api/info selalu membawa wsPort aktual; pakai itu supaya
           halaman HP tetap nyambung walau port pindah. */
        if (typeof info.wsPort === 'number' && info.wsPort > 0 && info.wsPort !== WS_PORT) {
          WS_PORT = info.wsPort;
        }
        var cd = $('qrCountdown');
        if (cd) {
          if (info.phoneConnected) cd.textContent = 'HP terhubung — QR dijeda';
          else if (typeof info.codeExpiresIn === 'number') cd.textContent = 'QR baru dalam ' + info.codeExpiresIn + ' dtk';
          else cd.textContent = '';
        }
        if (!info.phoneConnected && hadPhone && current) {
          hadPhone = false;
          resetSession();
        }
      }).catch(function () {});
    }
    refreshQrInfo();
    setInterval(refreshQrInfo, 1000);
    // posisi player tiap 1 detik saat jalan
    setInterval(function () {
      if (player && playerReady && playing) {
        try {
          position = player.getCurrentTime() || 0;
          duration = player.getDuration() || 0;
        } catch (e) {}
        broadcast();
      }
    }, 1000);
    /* v1.3.4: kabari native kalau halaman TV sudah jalan, supaya layar
       tunggu native diganti WebView. Kalau WebView TV rusak/blank,
       panggilan ini tidak pernah sampai dan layar native tetap tampil. */
    if (window.Android && window.Android.onPageReady) {
      try { window.Android.onPageReady(); } catch (e) {}
    }
  }

  /* ================= MODE PHONE ================= */
  var lastState = null;
  var volDragging = false;

  /* ---------- Playlist tersimpan (v1.4.1) ----------
     Playlist = kumpulan lagu PILIHAN user yang tersimpan di HP
     (localStorage), BUKAN cerminan antrean TV. Aturannya:
     - Lagu masuk playlist saat user menekan "+" di tab Cari:
       langsung disimpan saat itu juga, tidak menunggu gema
       state dari TV — jadi tetap ada walau koneksi putus.
     - Antrean TV yang mengecil saat lagu diputar TIDAK PERNAH
       menimpa playlist (bug v1.4.0: simpanan ketimpa antrean
       live yang menyusut, playlist terasa hilang).
     - Playlist TIDAK dikirim otomatis ke TV: cuma dikirim kalau
       user menekan tombol "Putar playlist" di tab Playlist.
     - "Putar playlist" = memainkan playlist: antrean TV
       dibersihkan dulu lalu diisi playlist, lagu pertama
       langsung diputar (anti ganda, tekan dua kali hasilnya
       sama). Tambah manual satu-satu dari tab Cari tetap bisa. */
  var PLAYLIST_KEY = 'karaokePlaylist.v1';
  var savedPlaylist = [];      // [{videoId, title, thumb}]
  var liveQueue = [];          // antrean TV terakhir dari state

  function loadSavedPlaylist() {
    savedPlaylist = [];
    try {
      var raw = localStorage.getItem(PLAYLIST_KEY);
      if (raw) {
        var arr = JSON.parse(raw);
        if (Array.isArray(arr)) {
          savedPlaylist = arr.filter(function (it) { return it && it.videoId; })
            .map(function (it) { return { videoId: it.videoId, title: it.title || '', thumb: it.thumb || '' }; });
        }
      }
    } catch (e) {}
  }

  function storeSavedPlaylist() {
    try { localStorage.setItem(PLAYLIST_KEY, JSON.stringify(savedPlaylist)); } catch (e) {}
  }

  /* Tambah satu lagu ke playlist tersimpan (dedupe by videoId).
     Dipanggil saat user menekan "+" — langsung simpan, jangan
     tunggu gema state dari TV. */
  function addToSavedPlaylist(it) {
    if (!it || !it.videoId) return false;
    for (var i = 0; i < savedPlaylist.length; i++) {
      if (savedPlaylist[i].videoId === it.videoId) return false;
    }
    savedPlaylist.push({ videoId: it.videoId, title: it.title || '', thumb: it.thumb || '' });
    storeSavedPlaylist();
    return true;
  }

  function removeFromSavedPlaylist(videoId) {
    for (var i = 0; i < savedPlaylist.length; i++) {
      if (savedPlaylist[i].videoId === videoId) { savedPlaylist.splice(i, 1); storeSavedPlaylist(); return true; }
    }
    return false;
  }

  /* Cari indeks lagu di antrean TV (buat hapus/putar yang tepat —
     indeks baris playlist TIDAK SAMA dengan indeks antrean TV). */
  function findLiveIndex(videoId) {
    for (var i = 0; i < liveQueue.length; i++) {
      if (liveQueue[i].videoId === videoId) return i;
    }
    return -1;
  }

  function clearSavedPlaylist() {
    savedPlaylist = [];
    try { localStorage.removeItem(PLAYLIST_KEY); } catch (e) {}
  }

  function setPlaylistStatus(t) { var el = $('playlistStatus'); if (el) el.textContent = t || ''; }

  /* Daftar yang tampil di tab Playlist = playlist tersimpan,
     terhubung atau tidak. Antrean TV yang sedang jalan hanya
     tampil sebagai baris status di bawah (v1.4.0 dulu menampilkan
     antrean TV saat terhubung — begitu antrean kosong karena lagu
     habis / sesi reset, playlist terasa hilang padahal simpanannya
     ada). */
  function renderPlaylist() {
    var list = $('queueList');
    $('queueCount').textContent = savedPlaylist.length;
    var tabCount = $('tabPlaylistCount');
    if (tabCount) tabCount.textContent = savedPlaylist.length;
    list.innerHTML = '';
    if (!savedPlaylist.length) {
      var empty = document.createElement('div');
      empty.className = 'queue-empty';
      empty.textContent = 'Playlist kosong. Cari lagu di tab Cari lalu ketuk +.';
      list.appendChild(empty);
    } else {
      savedPlaylist.forEach(function (it, idx) {
        var row = document.createElement('div');
        row.className = 'queue-item';
        var img = document.createElement('img');
        img.src = it.thumb || '';
        img.alt = '';
        img.referrerPolicy = 'no-referrer';
        var t = document.createElement('div');
        t.className = 'result-title';
        t.textContent = (idx + 1) + '. ' + it.title;
        t.style.flex = '1';
        var rm = document.createElement('button');
        rm.className = 'queue-remove';
        rm.textContent = '✕';
        rm.onclick = function (ev) {
          ev.stopPropagation();
          /* hapus dari simpanan; kalau lagu itu juga ada di antrean
             TV, hapus juga dari sana supaya tidak ikut keputar */
          removeFromSavedPlaylist(it.videoId);
          if (wsOk) {
            var li = findLiveIndex(it.videoId);
            if (li >= 0) send({ type: 'queue_remove', index: li });
          }
          renderPlaylist();
        };
        row.appendChild(img); row.appendChild(t); row.appendChild(rm);
        /* ketuk baris = putar lagu itu sekarang (tetap di playlist) */
        row.onclick = function () {
          if (!wsOk) { setPlaylistStatus('Belum terhubung ke TV. Sambungkan dulu (scan QR).'); return; }
          var li2 = findLiveIndex(it.videoId);
          if (li2 >= 0) send({ type: 'queue_remove', index: li2 });
          send({ type: 'play', videoId: it.videoId, title: it.title, thumb: it.thumb });
          setSearchStatus('Memutar dari playlist: ' + it.title);
        };
        list.appendChild(row);
      });
    }
    /* baris status: apa yang sedang terjadi di TV (kalau terhubung) */
    if (wsOk) {
      var live = liveQueue.length ? 'Antrean TV: ' + liveQueue.length + ' lagu menunggu.' : 'Antrean TV kosong.';
      var now = (lastState && lastState.current && lastState.current.title)
        ? ' Sedang memutar: ' + lastState.current.title : '';
      setPlaylistStatus(savedPlaylist.length
        ? 'Tersimpan di HP (' + savedPlaylist.length + ' lagu). ' + live + now
        : live + now);
    } else {
      setPlaylistStatus(savedPlaylist.length
        ? 'Tersimpan di HP, belum dikirim ke TV. Sambungkan ke TV lalu ketuk "Putar playlist".'
        : '');
    }
  }

  /* Putar playlist: antrean TV dibersihkan dulu lalu diisi ulang
     dari playlist, lagu pertama langsung diputar. Tekan dua kali
     hasilnya sama — tidak pernah menggandakan (bug v1.4.0: cuma
     queue_add tanpa clear dan tanpa play, jadi antrean menumpuk
     dan TV yang lagi idle tidak mulai memutar). */
  function playSavedPlaylist() {
    if (!savedPlaylist.length) { setPlaylistStatus('Playlist kosong, belum ada lagu tersimpan.'); return; }
    if (!wsOk) { setPlaylistStatus('Belum terhubung ke TV. Sambungkan dulu (scan QR), baru ketuk Putar playlist.'); return; }
    var items = savedPlaylist.slice();
    var rest = items.slice(1);
    setPlaylistStatus('Memutar playlist (' + items.length + ' lagu)…');
    send({ type: 'queue_clear' });
    setTimeout(function () {
      send({ type: 'play', videoId: items[0].videoId, title: items[0].title, thumb: items[0].thumb });
      if (!rest.length) { setPlaylistStatus('1 lagu diputar dari playlist.'); return; }
      rest.forEach(function (it, i) {
        setTimeout(function () {
          send({ type: 'queue_add', videoId: it.videoId, title: it.title, thumb: it.thumb });
          if (i === rest.length - 1) {
            setPlaylistStatus(items.length + ' lagu diputar dari playlist.');
          }
        }, i * 150);
      });
    }, 300);
  }

  /* ---------- Mic HP (v1.3.0) ---------- */
  /* Satu tombol ON/OFF, tanpa setting lain. ON = mic HP aktif, suara
     dikirim ke TV (keluar lewat speaker TV). OFF = pakai mic wireless
     beneran di sisi TV. Status asli datang dari native lewat
     window.__micState (izin ditolak / gagal jalan ikut dilaporkan). */
  var micOn = false, micBusy = false, micTimer = null;
  var micVol = 50; // volume mic 0-100%, default 50% (v1.3.1, dulu 100%)

  /* Kirim volume mic ke TV (server teruskan ke AudioTrack mic).
     Berlaku langsung saat mic ON; kalau mic OFF, nilainya disimpan
     di TV dan dipakai saat mic dinyalakan lagi. */
  function sendMicVolume() {
    send({ type: 'mic_vol', value: micVol });
  }

  function setMicUi(on, msg) {
    var b = $('btnMic');
    if (b) {
      b.textContent = on ? '🎤 Mic HP: ON' : '🎤 Mic HP: OFF';
      b.classList.toggle('on', !!on);
    }
    var s = $('micStatus');
    if (s) s.textContent = msg || '';
  }

  function stopMicNative() {
    try {
      if (window.Android && window.Android.setMicOn) window.Android.setMicOn(false);
    } catch (e) {}
  }

  /* Dipanggil native (MainActivity) saat status mic berubah. */
  window.__micState = function (s) {
    micBusy = false;
    if (micTimer) { clearTimeout(micTimer); micTimer = null; }
    if (s === 'on') {
      micOn = true;
      setMicUi(true, 'Mic HP nyala — suaramu keluar dari speaker TV.');
    } else if (s === 'denied') {
      micOn = false;
      setMicUi(false, 'Izin mic ditolak. Nyalakan izin Mic di Pengaturan HP kalau mau pakai mic.');
    } else if (s === 'error') {
      micOn = false;
      setMicUi(false, 'Mic gagal jalan, coba lagi.');
    } else {
      micOn = false;
      setMicUi(false, '');
    }
  };

  function toggleMic() {
    if (micBusy) return;
    if (micOn) {
      /* OFF: langsung tampilkan OFF, native mematikan stream. */
      micOn = false;
      setMicUi(false, '');
      stopMicNative();
      return;
    }
    /* ON: tunggu konfirmasi native (izin / koneksi), jangan gantung. */
    if (!window.Android || !window.Android.setMicOn) {
      setMicUi(false, 'Mic belum didukung di versi ini.');
      return;
    }
    micBusy = true;
    setMicUi(false, 'Menyalakan mic…');
    if (micTimer) clearTimeout(micTimer);
    micTimer = setTimeout(function () {
      if (micBusy) { micBusy = false; setMicUi(false, 'Mic gagal jalan, coba lagi.'); }
    }, 5000);
    try { window.Android.setMicOn(true); sendMicVolume(); }
    catch (e) { micBusy = false; setMicUi(false, 'Mic gagal jalan, coba lagi.'); }
  }

  function setConn(ok, text) {
    var dot = $('connDot');
    if (dot) dot.className = 'conn-dot ' + (ok ? 'on' : 'off');
    var m = $('connMsg');
    if (m) m.textContent = text || (ok ? 'Terhubung ke TV' : 'Tidak terhubung');
  }

  function setSearchStatus(t) { var el = $('searchStatus'); if (el) el.textContent = t || ''; }

  function fetchTimeout(url, ms) {
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, ms);
    return fetch(url, { signal: ctrl.signal }).finally(function () { clearTimeout(timer); });
  }

  /* Instance Invidious yang masih hidup (dicek 6 Nov 2026):
     - yt.chocolatemoo53.com (api=true, cors=true di registry resmi)
     - invidious.materialio.us (hasil JSON ok, tapi CORS-nya cuma
       mengizinkan origin sendiri -> dari HP langsung bakal gagal CORS,
       makanya pencarian utama lewat proxy server TV /api/search).
     Yang sudah MATI semua: vid.puffyan.us (timeout), inv.nadeko.net
     (API dimatikan, 403), yewtu.be (balikin HTML), piped.video +
     piped.adminforge.de (API mati). Piped sudah tidak dipakai. */
  var INVIDIOUS = ['https://yt.chocolatemoo53.com', 'https://invidious.materialio.us'];

  function pickThumb(thumbs) {
    if (!thumbs || !thumbs.length) return '';
    var best = thumbs[0];
    for (var i = 0; i < thumbs.length; i++) {
      var t = thumbs[i];
      var w = t.width || 0;
      if (w >= 200 && w <= 480) return t.url;
      if (w > (best.width || 0)) best = t;
    }
    return best.url || '';
  }

  function normInv(x) {
    return { videoId: x.videoId, title: x.title || '', channel: x.author || '',
             duration: x.lengthSeconds || 0, thumb: pickThumb(x.videoThumbnails) };
  }

  function showSearchResult(items) {
    if (items && items.length) {
      setSearchStatus(items.length + ' lagu ketemu. Ketuk untuk langsung putar, + untuk masuk antrean.');
      renderResults(items);
      return true;
    }
    return false;
  }

  function doSearch() {
    var q = ($('searchInput').value || '').trim();
    if (!q) { setSearchStatus('Ketik judul lagu dulu.'); return; }
    if (!/karaoke/i.test(q)) q += ' karaoke';
    setSearchStatus('Mencari…');
    $('results').innerHTML = '';
    /* Jalur utama: proxy server TV (/api/search) — same-origin, bebas CORS,
       server yang fetch ke instance Invidious. Kalau proxy kosong/gagal,
       fallback ke fetch langsung dari HP (instance cadangan). */
    fetchTimeout('/api/search?q=' + encodeURIComponent(q), 20000)
      .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
      .then(function (d) {
        var arr = Array.isArray(d) ? d : [];
        var items = arr.filter(function (x) { return x.type === 'video' && x.videoId; }).map(normInv);
        if (showSearchResult(items)) return true;
        return searchChain(q).then(showSearchResult);
      })
      .catch(function () { return searchChain(q).then(showSearchResult); })
      .then(function (ok) {
        if (!ok) setSearchStatus('Pencarian gagal, cek internet / coba lagi.');
      });
  }

  function searchChain(q) {
    var chain = [];
    INVIDIOUS.forEach(function (base) {
      chain.push(function () {
        return fetchTimeout(base + '/api/v1/search?q=' + encodeURIComponent(q) + '&type=video', 8000)
          .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
          .then(function (d) {
            var arr = Array.isArray(d) ? d : [];
            return arr.filter(function (x) { return x.type === 'video' && x.videoId; }).map(normInv);
          });
      });
    });
    var p = Promise.resolve(null);
    chain.forEach(function (fn) {
      p = p.then(function (found) {
        if (found && found.length) return found;
        return fn().catch(function () { return null; }).then(function (items) {
          return (items && items.length) ? items : null;
        });
      });
    });
    return p.then(function (items) { return items || []; });
  }

  function renderResults(items) {
    var box = $('results');
    box.innerHTML = '';
    items.slice(0, 20).forEach(function (it) {
      var row = document.createElement('div');
      row.className = 'result-item';
      var img = document.createElement('img');
      img.src = it.thumb || '';
      img.alt = '';
      img.referrerPolicy = 'no-referrer';
      var info = document.createElement('div');
      info.className = 'result-info';
      var t = document.createElement('div');
      t.className = 'result-title';
      t.textContent = it.title;
      var meta = document.createElement('div');
      meta.className = 'result-meta';
      meta.textContent = it.channel + (it.duration ? ' · ' + fmt(it.duration) : '');
      info.appendChild(t); info.appendChild(meta);
      var add = document.createElement('button');
      add.className = 'result-add';
      add.textContent = '+';
      add.title = 'Tambah ke antrean';
      add.onclick = function (ev) {
        ev.stopPropagation();
        var added = addToSavedPlaylist(it);
        if (wsOk) send({ type: 'queue_add', videoId: it.videoId, title: it.title, thumb: it.thumb });
        setSearchStatus(added
          ? (wsOk ? 'Ditambahkan ke antrean + playlist: ' : 'Disimpan ke playlist (TV belum terhubung): ') + it.title
          : 'Sudah ada di playlist: ' + it.title);
        renderPlaylist();
      };
      row.appendChild(img); row.appendChild(info); row.appendChild(add);
      row.onclick = function () {
        send({ type: 'play', videoId: it.videoId, title: it.title, thumb: it.thumb });
        setSearchStatus('Memutar: ' + it.title);
      };
      box.appendChild(row);
    });
  }

  function renderState(s) {
    lastState = s;
    var card = $('npCard');
    if (s.current) {
      card.classList.remove('hidden');
      $('npTitle').textContent = s.current.title || '';
      $('npBar').style.width = (s.duration > 0 ? Math.min(100, (s.position / s.duration) * 100) : 0) + '%';
      $('npTime').textContent = fmt(s.position) + ' / ' + fmt(s.duration);
      $('btnPlayPause').textContent = s.playing ? '⏸' : '▶';
      /* jangan rebut slider saat user lagi geser: broadcast tiap detik
         sempat bikin jempol balik ke nilai lama, volume jadi susah diatur */
      if (typeof s.volume === 'number' && !volDragging) {
        $('volSlider').value = s.volume;
        $('volVal').textContent = s.volume;
      }
    } else {
      card.classList.add('hidden');
      $('btnPlayPause').textContent = '▶';
    }
    var q = s.queue || [];
    liveQueue = q;
    renderPlaylist();
  }

  function initPhone() {
    $('phoneView').classList.remove('hidden');
    initTheme(); // tema Sistem/Gelap/Terang (v1.3.8)
    setConn(false, 'Menghubungkan ke TV…');
    /* v1.4.1: dua tab kiri-kanan. Kiri = Cari, kanan = Playlist. */
    var tabCari = $('tabCari'), tabPlaylist = $('tabPlaylist');
    var tabBtnCari = $('tabBtnCari'), tabBtnPlaylist = $('tabBtnPlaylist');
    function showTab(name) {
      var cari = name !== 'playlist';
      if (tabCari) tabCari.classList.toggle('hidden', !cari);
      if (tabPlaylist) tabPlaylist.classList.toggle('hidden', cari);
      if (tabBtnCari) tabBtnCari.classList.toggle('active', cari);
      if (tabBtnPlaylist) tabBtnPlaylist.classList.toggle('active', !cari);
    }
    if (tabBtnCari) tabBtnCari.onclick = function () { showTab('cari'); };
    if (tabBtnPlaylist) tabBtnPlaylist.onclick = function () { showTab('playlist'); };
    loadSavedPlaylist();
    renderPlaylist();
    $('btnDisconnect').onclick = disconnectTv;
    $('btnMic').onclick = toggleMic; // tombol mic HP (v1.3.0)
    /* slider volume mic: kirim langsung tiap geseran (tanpa debounce),
       sama seperti slider volume musik di bawah */
    var mv = $('micVolSlider');
    mv.addEventListener('input', function () {
      micVol = parseInt(this.value, 10);
      if (isNaN(micVol)) micVol = 50;
      micVol = Math.max(0, Math.min(100, micVol));
      $('micVolVal').textContent = micVol;
      sendMicVolume();
    });
    var abp = $('btnAboutPhone'); if (abp) abp.onclick = openAbout;
    var exp = $('btnExitPhone'); if (exp) exp.onclick = exitApp;
    var pv = $('phoneVersion'); if (pv) pv.textContent = 'v' + installedName();
    checkForUpdate(false); // cek update tiap aplikasi dibuka
    $('searchBtn').onclick = doSearch;
    $('searchInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') doSearch(); });
    $('btnPlayPause').onclick = function () {
      if (!lastState || !lastState.current) return;
      send({ type: lastState.playing ? 'pause' : 'resume' });
    };
    $('btnNext').onclick = function () { send({ type: 'next' }); };
    $('btnPrev').onclick = function () { send({ type: 'prev' }); };
    $('btnReplay').onclick = function () { send({ type: 'replay' }); };
    /* Bersihkan = hapus antrean TV SEKALIGUS simpanan di HP. Kalau cuma
       antrean TV yang dibersihkan, simpanan lama akan muncul lagi. */
    $('btnClearQueue').onclick = function () { send({ type: 'queue_clear' }); clearSavedPlaylist(); };
    /* v1.4.1: tombol Putar playlist + hapus simpanan. */
    var playBtn = $('btnPlayPlaylist');
    if (playBtn) playBtn.onclick = playSavedPlaylist;
    var delBtn = $('btnDeleteSaved');
    if (delBtn) delBtn.onclick = function () {
      clearSavedPlaylist();
      renderPlaylist();
      setPlaylistStatus('Simpanan playlist dihapus. Antrean TV tidak berubah.');
    };
    /* ketuk bilah progres = loncat (seek) ke bagian lagu itu */
    $('npProgress').addEventListener('click', function (ev) {
      if (!lastState || !lastState.current || !lastState.duration) return;
      var r = this.getBoundingClientRect();
      var frac = (ev.clientX - r.left) / r.width;
      frac = Math.max(0, Math.min(1, frac));
      var sec = Math.floor(frac * lastState.duration);
      send({ type: 'seek', value: sec });
      setSearchStatus('Loncat ke ' + fmt(sec) + '.');
    });
    /* volume: kirim langsung tiap geseran (tanpa debounce), dan tandai
       saat jempol nempel di slider supaya broadcast tidak merebutnya */
    var vol = $('volSlider');
    vol.addEventListener('pointerdown', function () { volDragging = true; });
    vol.addEventListener('pointerup', function () { volDragging = false; });
    vol.addEventListener('pointercancel', function () { volDragging = false; });
    vol.addEventListener('change', function () { volDragging = false; });
    vol.addEventListener('input', function () {
      var v = parseInt(this.value, 10);
      $('volVal').textContent = v;
      send({ type: 'volume', value: v });
    });
  }

  function rescanQr() {
    if (window.Android && window.Android.rescan) { window.Android.rescan(); }
  }
  window.rescanQr = rescanQr;

  /* Keluar dari aplikasi TV: panggil bridge Android di MainActivity,
     yang mematikan service server lalu menutup activity. */
  function exitApp() {
    if (window.Android && window.Android.exitApp) { window.Android.exitApp(); }
  }
  window.exitApp = exitApp;

  /* ---------- Update di dalam aplikasi (v1.2.0, progress + Pasang otomatis v1.3.2) ---------- */
  /* version.json di repo adalah sumber versi terbaru. Aplikasi cek tiap
     dibuka; kalau ada yang baru, muncul dialog Update. Tombol Update
     download APK lewat DownloadManager, halaman web memantau progress
     tiap 500 ms (bar + persen di dialog), dan begitu download selesai
     installer sistem langsung dibuka otomatis. Tombol Pasang tetap ada
     sebagai cadangan kalau installer gagal kebuka sendiri. */
  var UPDATE_URL = 'https://raw.githubusercontent.com/khaliefk/karaoke/main/version.json';
  var pendingUpdate = null; // {version, code, url, notes} kalau ada update baru
  var updatePoll = null;      // interval pantau progress download
  var updateFinished = false; // sudah terminal (done/failed) untuk download ini
  var updateAutoFired = false; // installUpdate() otomatis sudah dipanggil

  function installedCode() {
    try {
      if (window.Android && window.Android.getVersionCode) return window.Android.getVersionCode() | 0;
    } catch (e) {}
    return 0;
  }

  function installedName() {
    try {
      if (window.Android && window.Android.getVersionName) {
        var v = window.Android.getVersionName();
        if (v) return v;
      }
    } catch (e) {}
    return APP_VERSION;
  }

  /* manual=true = dari tombol "Cek update" di About (tampilkan status).
     Diam kalau offline / tidak ada bridge / sudah versi terbaru. */
  function checkForUpdate(manual) {
    if (!window.Android || !window.Android.getVersionCode) return;
    var installed = installedCode();
    if (!installed) return;
    var statusEl = $('updateCheckStatus');
    function setStatus(t) { if (statusEl) statusEl.textContent = t || ''; }
    if (manual) setStatus('Mengecek update…');
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, 8000);
    fetch(UPDATE_URL + '?t=' + Date.now(), { signal: ctrl.signal })
      .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
      .then(function (d) {
        var code = parseInt(d.code, 10) || 0;
        if (code > installed) {
          pendingUpdate = {
            version: d.version || ('v' + code),
            code: code,
            url: MODE === 'tv' ? (d.tv || '') : (d.remote || ''),
            notes: d.notes || ''
          };
          if (!pendingUpdate.url) { if (manual) setStatus('Link update tidak tersedia.'); return; }
          var dismissed = null;
          try { dismissed = window.localStorage.getItem('karaokeUpdateDismissed'); } catch (e) {}
          if (manual || String(code) !== dismissed) openUpdateModal();
        } else {
          if (manual) setStatus('Sudah versi terbaru (' + installedName() + ').');
        }
      })
      .catch(function () {
        if (manual) setStatus('Tidak bisa cek update, cek internet lalu coba lagi.');
      })
      .finally(function () { clearTimeout(timer); });
  }

  function openUpdateModal() {
    var m = $('updateModal'); if (!m || !pendingUpdate) return;
    var am = $('aboutModal'); if (am) am.classList.add('hidden');
    $('updateTitle').textContent = 'Update tersedia';
    $('updateVersion').textContent = 'Versi ' + pendingUpdate.version + ' (yang terinstall ' + installedName() + ')';
    var n = $('updateNotes');
    n.textContent = pendingUpdate.notes || '';
    n.classList.toggle('hidden', !pendingUpdate.notes);
    if (!updatePoll) { // jangan reset kalau download sedang jalan
      $('updateStatus').textContent = '';
      hideUpdateProgress();
      setUpdateButton('update');
    }
    m.classList.remove('hidden');
    var b = $('btnUpdateNow'); if (b) b.focus(); // fokus ke Update (penting buat D-pad TV)
  }

  function closeUpdateModal(dismiss) {
    var m = $('updateModal'); if (m) m.classList.add('hidden');
    if (dismiss && pendingUpdate) {
      try { window.localStorage.setItem('karaokeUpdateDismissed', String(pendingUpdate.code)); } catch (e) {}
    }
  }

  function setUpdateProgress(pct, label) {
    var wrap = $('updateProgress'); if (!wrap) return;
    wrap.classList.remove('hidden');
    var bar = $('updateProgressBar');
    if (bar) bar.style.width = Math.max(0, Math.min(100, pct)) + '%';
    var t = $('updateProgressText'); if (t) t.textContent = label || '';
  }

  function hideUpdateProgress() {
    var wrap = $('updateProgress'); if (wrap) wrap.classList.add('hidden');
  }

  /* mode: 'update' | 'downloading' | 'install' | 'retry' */
  function setUpdateButton(mode) {
    var b = $('btnUpdateNow'); if (!b) return;
    if (mode === 'downloading') { b.textContent = 'Mengunduh…'; b.disabled = true; }
    else if (mode === 'install') { b.textContent = 'Pasang sekarang'; b.disabled = false; b.onclick = installUpdateNow; }
    else if (mode === 'retry') { b.textContent = 'Coba lagi'; b.disabled = false; b.onclick = doUpdate; }
    else { b.textContent = 'Update sekarang'; b.disabled = false; b.onclick = doUpdate; }
  }

  /* Tombol Pasang cadangan: buka lagi installer untuk APK yang sudah selesai di-download. */
  function installUpdateNow() {
    if (!window.Android || !window.Android.installUpdate) return;
    var ok = false;
    try { ok = !!window.Android.installUpdate(true); } catch (e) {}
    var st = $('updateStatus');
    if (st) st.textContent = ok ? 'Installer dibuka, ketuk Pasang.'
      : 'Installer tidak bisa dibuka. Coba lagi, atau pasang dari notifikasi download.';
  }

  function stopUpdatePoll() {
    if (updatePoll) { clearInterval(updatePoll); updatePoll = null; }
  }

  /* Download selesai: buka installer otomatis sekali, tombol Pasang tetap
     ada sebagai cadangan. Dipanggil polling dan callback native. */
  function onUpdateDone() {
    if (updateFinished) return;
    updateFinished = true;
    stopUpdatePoll();
    setUpdateProgress(100, 'Download selesai');
    setUpdateButton('install');
    var st = $('updateStatus');
    if (!updateAutoFired) {
      updateAutoFired = true;
      var ok = false;
      try { ok = !!(window.Android && window.Android.installUpdate && window.Android.installUpdate(false)); } catch (e) {}
      if (st) st.textContent = ok ? 'Download selesai. Installer dibuka, ketuk Pasang.'
        : 'Download selesai, tapi installer tidak terbuka otomatis. Ketuk Pasang sekarang.';
    } else if (st) {
      st.textContent = 'Download selesai. Installer dibuka, ketuk Pasang.';
    }
  }

  function onUpdateFailed() {
    if (updateFinished) return;
    updateFinished = true;
    stopUpdatePoll();
    var st = $('updateStatus');
    if (st) st.textContent = 'Download gagal. Cek internet lalu coba lagi.';
    setUpdateButton('retry');
  }

  function pollUpdateProgress() {
    if (!window.Android || !window.Android.getUpdateProgress) { stopUpdatePoll(); return; }
    var p = null;
    try { p = JSON.parse(window.Android.getUpdateProgress()); } catch (e) { return; }
    if (!p) return;
    if (p.state === 'done') { onUpdateDone(); return; }
    if (p.state === 'failed') { onUpdateFailed(); return; }
    if (p.state === 'downloading') {
      var pct = (p.total > 0) ? Math.floor(p.downloaded * 100 / p.total) : 0;
      setUpdateProgress(pct, p.total > 0 ? ('Mengunduh… ' + pct + '%') : 'Mengunduh…');
      var st = $('updateStatus');
      if (st) st.textContent = 'Mengunduh update…';
    }
  }

  /* Dipanggil native (cadangan polling) saat download selesai / gagal /
     installer gagal dibuka. */
  window.__updateState = function (state) {
    if (state === 'done' || state === 'installed') onUpdateDone();
    else if (state === 'failed') onUpdateFailed();
    else if (state === 'installFailed') {
      if (updateFinished) return;
      updateFinished = true;
      stopUpdatePoll();
      setUpdateProgress(100, 'Download selesai');
      setUpdateButton('install');
      var st = $('updateStatus');
      if (st) st.textContent = 'Download selesai, tapi installer tidak terbuka otomatis. Ketuk Pasang sekarang.';
    }
  };

  function doUpdate() {
    if (!pendingUpdate || !pendingUpdate.url) return;
    var st = $('updateStatus');
    if (window.Android && window.Android.canInstallUnknown && !window.Android.canInstallUnknown()) {
      if (st) st.textContent = 'Izinkan dulu install dari sumber tak dikenal, lalu ketuk Update lagi.';
      if (window.Android.openInstallSettings) window.Android.openInstallSettings();
      return;
    }
    if (window.Android && window.Android.startUpdate) {
      updateFinished = false;
      updateAutoFired = false;
      window.Android.startUpdate(pendingUpdate.url);
      setUpdateButton('downloading');
      setUpdateProgress(0, 'Mengunduh…');
      if (st) st.textContent = 'Mengunduh update…';
      stopUpdatePoll();
      if (window.Android.getUpdateProgress) {
        updatePoll = setInterval(pollUpdateProgress, 500);
      }
    }
  }
  window.checkForUpdate = checkForUpdate;

  /* ---------- About (TV + HP) ---------- */
  function openAbout() {
    var m = $('aboutModal'); if (!m) return;
    $('aboutTitle').textContent = MODE === 'tv' ? '🎤 Karaoke for TV' : '🎤 Karaoke Remote';
    $('aboutVersion').textContent = 'Versi ' + installedName();
    var s = $('updateCheckStatus'); if (s) s.textContent = '';
    m.classList.remove('hidden');
    var c = $('btnAboutClose'); if (c) c.focus(); // fokus ke Tutup (penting buat D-pad TV)
  }
  function closeAbout() {
    var m = $('aboutModal'); if (m) m.classList.add('hidden');
  }
  window.openAbout = openAbout;
  window.closeAbout = closeAbout;

  /* ---------- dispatch ---------- */
  function onMsg(msg) {
    if (MODE === 'tv') {
      if (msg.type === 'state' || msg.type === 'hello') return; // dari TV sendiri / server
      if (msg.type === 'peer_left') { hadPhone = false; setMicBadge(false); resetSession(); return; }
      if (msg.type === 'mic') { setMicBadge(!!msg.on); return; } // indikator mic HP
      hadPhone = true; // perintah dari HP
      tvCommand(msg);
    } else {
      if (msg.type === 'state') renderState(msg);
      else if (msg.type === 'hello') send({ type: 'get_state' });
    }
  }

  if (MODE === 'tv') initTv(); else initPhone();
  /* tombol Tutup + ketuk area gelap di luar kartu = tutup dialog About */
  var bc = $('btnAboutClose'); if (bc) bc.onclick = closeAbout;
  var am = $('aboutModal');
  if (am) am.addEventListener('click', function (e) { if (e.target === am) closeAbout(); });
  var cu = $('btnCheckUpdate'); if (cu) cu.onclick = function () { checkForUpdate(true); };
  /* dialog Update: tombol Update / Nanti + ketuk area gelap = Nanti */
  var un = $('btnUpdateNow'); if (un) un.onclick = doUpdate;
  var ul = $('btnUpdateLater'); if (ul) ul.onclick = function () { closeUpdateModal(true); };
  var um = $('updateModal');
  if (um) um.addEventListener('click', function (e) { if (e.target === um) closeUpdateModal(true); });
  if (MODE === 'phone') connectWithWsPort(); else connect();
})();
