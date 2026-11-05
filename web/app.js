/* Karaoke for TV + Remote, satu file JS untuk dua mode.
   mode=tv    : jalan di WebView TV, pegang antrean + player YouTube.
   mode=phone : jalan di WebView HP, kirim perintah + cari lagu. */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var MODE = params.get('mode') === 'phone' ? 'phone' : 'tv';
  var CODE = params.get('code') || '';
  var WS_PORT = 8081;

  function $(id) { return document.getElementById(id); }
  function fmt(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var m = Math.floor(sec / 60), s = sec % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
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
      if (MODE === 'phone') { send({ type: 'get_state' }); }
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
        return;
      }
      setConn(false, 'Koneksi putus. Menghubungkan ulang…');
      setTimeout(connect, 2000);
    };
    ws.onerror = function () { /* onclose yang urus reconnect */ };
  }

  /* Putus manual dari TV: tutup socket, matikan auto-reconnect. TV tetap
     jalan (lagu/antrean dipegang halaman TV), cuma HP-nya yang putus.
     Sambung lagi lewat tombol "Scan Ulang QR TV" (halaman di-load ulang). */
  function disconnectTv() {
    manualDisconnect = true;
    wsOk = false;
    try { if (ws) ws.close(); } catch (e) {}
    setConn(false, 'Terputus dari TV. Scan ulang QR untuk sambung lagi.');
    var d = $('btnDisconnect'); if (d) d.classList.add('hidden');
    var b = $('btnRescan'); if (b) b.classList.remove('hidden');
    var card = $('npCard'); if (card) card.classList.add('hidden');
    $('queueCount').textContent = '0';
    $('queueList').innerHTML = '';
    setSearchStatus('Putus dari TV. TV tetap jalan, cuma HP-nya yang putus.');
  }

  function send(obj) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  }

  /* ================= MODE TV ================= */
  var queue = [], current = null, history = [];
  var playing = false, position = 0, duration = 0, volume = 80;
  var player = null, playerReady = false, ytLoaded = false, pendingVideo = null;

  function showTv(id) {
    $('waitScreen').classList.toggle('hidden', id !== 'wait');
    $('playerScreen').classList.toggle('hidden', id !== 'player');
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
    fetch('/api/info').then(function (r) { return r.json(); }).then(function (info) {
      if (info.code) $('pairCode').textContent = String(info.code).split('').join(' ');
      if (info.ip) $('waitIp').textContent = 'TV: ' + info.ip + ' · port 8080';
    }).catch(function () {});
    // refresh QR tiap 60 detik (kode bisa ganti kalau server restart)
    setInterval(function () {
      var img = $('qrImg');
      img.src = '/qr.png?t=' + Date.now();
    }, 60000);
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
  }

  /* ================= MODE PHONE ================= */
  var lastState = null;
  var volDragging = false;

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
        send({ type: 'queue_add', videoId: it.videoId, title: it.title, thumb: it.thumb });
        setSearchStatus('Ditambahkan ke antrean: ' + it.title);
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
    $('queueCount').textContent = q.length;
    var list = $('queueList');
    list.innerHTML = '';
    if (!q.length) {
      var empty = document.createElement('div');
      empty.className = 'queue-empty';
      empty.textContent = 'Antrean kosong. Cari lagu lalu ketuk +.';
      list.appendChild(empty);
    }
    q.forEach(function (it, idx) {
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
        send({ type: 'queue_remove', index: idx });
      };
      row.appendChild(img); row.appendChild(t); row.appendChild(rm);
      /* ketuk baris antrean = putar lagu itu sekarang (keluar dari antrean) */
      row.onclick = function () {
        send({ type: 'queue_remove', index: idx });
        send({ type: 'play', videoId: it.videoId, title: it.title, thumb: it.thumb });
        setSearchStatus('Memutar dari antrean: ' + it.title);
      };
      list.appendChild(row);
    });
  }

  function initPhone() {
    $('phoneView').classList.remove('hidden');
    setConn(false, 'Menghubungkan ke TV…');
    $('btnDisconnect').onclick = disconnectTv;
    $('searchBtn').onclick = doSearch;
    $('searchInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') doSearch(); });
    $('btnPlayPause').onclick = function () {
      if (!lastState || !lastState.current) return;
      send({ type: lastState.playing ? 'pause' : 'resume' });
    };
    $('btnNext').onclick = function () { send({ type: 'next' }); };
    $('btnPrev').onclick = function () { send({ type: 'prev' }); };
    $('btnReplay').onclick = function () { send({ type: 'replay' }); };
    $('btnClearQueue').onclick = function () { send({ type: 'queue_clear' }); };
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

  /* ---------- dispatch ---------- */
  function onMsg(msg) {
    if (MODE === 'tv') {
      if (msg.type === 'state') return; // state datang dari TV sendiri
      tvCommand(msg);
    } else {
      if (msg.type === 'state') renderState(msg);
      else if (msg.type === 'hello') send({ type: 'get_state' });
    }
  }

  if (MODE === 'tv') initTv(); else initPhone();
  connect();
})();
