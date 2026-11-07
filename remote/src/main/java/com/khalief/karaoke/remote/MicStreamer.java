package com.khalief.karaoke.remote;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Mic HP -> TV (v1.3.1). Delay dijaga seminimal mungkin:
 * - Suara dikirim MENTAH sebagai PCM 16-bit mono 16kHz, TANPA codec/encode.
 * - Frame kecil 10ms (160 sampel = 320 byte) per pesan biner lewat
 *   WebSocket yang sudah ada (port 8081). Tanpa channel UDP baru.
 * - AudioRecord buffer kecil; AcousticEchoCanceler diaktifkan kalau
 *   tersedia (kurangi feedback dari speaker TV).
 *
 * Alur: connect WS -> auth (role phone + "mic":true) -> kirim
 * {"type":"mic","on":true} -> mulai rekam & kirim frame biner.
 * Berhenti: kirim mic-off ke TV, tutup WS, lepas recorder.
 * Semua callback status ("on"/"off"/"denied"/"error") diteruskan ke
 * Listener; pemanggil yang menampilkan pesan ke user.
 *
 * v1.3.1: start() idempotent dan anti-macet (perbaiki bug v1.3.0).
 * Tiap start() menaikkan generasi; loop lama yang masih jalan jadi
 * basi dan keluar sendiri tanpa boleh menyentuh state generasi baru.
 * Soket dan recorder dipegang per generasi (salinan lokal), jadi
 * cleanup generasi lama tidak bisa merusak generasi baru. Ini
 * memperbaiki bug: setelah layanan putus (kode pairing ikut
 * berotasi), mic tidak bisa di-ON-kan lagi sampai aplikasi
 * di-restart. MainActivity juga membuat MicStreamer baru di tiap
 * start, supaya kode pairing yang dipakai selalu yang terbaru.
 */
public class MicStreamer {

    public interface Listener {
        /** "on" | "off" | "denied" | "error" */
        void onMicState(String state);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 160; // 10ms
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private final String wsUrl;
    private final String code;
    private final Listener listener;

    private volatile int generation;
    private volatile boolean streaming; // ada loop kontrol generasi aktif
    private volatile boolean wantStop;

    /* Handle ke koneksi/recorder generasi berjalan, supaya stop() bisa
       mematikannya dari thread lain. Loop kontrol memakai salinan lokal
       dan membersihkannya sendiri. */
    private volatile WebSocketClient currentWs;
    private volatile AudioRecord currentRecorder;

    public MicStreamer(String tvIp, String code, Listener listener) {
        this.wsUrl = "ws://" + tvIp + ":8081";
        this.code = code;
        this.listener = listener;
    }

    /** Idempotent: boleh dipanggil kapan saja, termasuk saat generasi
        lama masih winding down. Generasi lama dimatikan dulu (soket
        ditutup, recorder di-stop); loop lamanya keluar sendiri karena
        generasinya sudah basi, tanpa menyentuh state generasi baru. */
    public synchronized void start() {
        generation++;
        final int gen = generation;
        wantStop = true;
        try { if (currentWs != null) currentWs.close(); } catch (Exception ignored) {}
        stopCurrentRecorder();
        wantStop = false;
        streaming = true;
        new Thread(new Runnable() {
            @Override public void run() { controlLoop(gen); }
        }, "mic-control").start();
    }

    /** Berhenti: kirim mic-off ke TV, tutup WS, lepas recorder. */
    public synchronized void stop() {
        generation++;
        wantStop = true;
        try {
            if (currentWs != null && currentWs.isOpen()) {
                currentWs.send("{\"type\":\"mic\",\"on\":false}");
            }
        } catch (Exception ignored) {}
        try { if (currentWs != null) currentWs.close(); } catch (Exception ignored) {}
        stopCurrentRecorder();
    }

    private void notifyState(String state) {
        try { listener.onMicState(state); } catch (Exception ignored) {}
    }

    private void stopCurrentRecorder() {
        AudioRecord r = currentRecorder;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
    }

    /** Satu sesi rekam: recorder + AEC/NS yang menempel di sesinya.
        Dilepas oleh thread rekam yang memakainya (finally), jadi tidak
        ada tabrakan antar generasi. */
    private static class MicSession {
        AudioRecord recorder;
        AcousticEchoCanceler aec;
        NoiseSuppressor ns;
    }

    private void releaseSession(MicSession s) {
        if (s == null) return;
        if (s.aec != null) {
            try { s.aec.release(); } catch (Exception ignored) {}
            s.aec = null;
        }
        if (s.ns != null) {
            try { s.ns.release(); } catch (Exception ignored) {}
            s.ns = null;
        }
        if (s.recorder != null) {
            try { s.recorder.stop(); } catch (Exception ignored) {}
            try { s.recorder.release(); } catch (Exception ignored) {}
            if (currentRecorder == s.recorder) currentRecorder = null;
            s.recorder = null;
        }
    }

    private void controlLoop(final int gen) {
        MicWsClient myWs = null;
        MicSession session = null;
        boolean wasLive = false;
        try {
            myWs = new MicWsClient(new URI(wsUrl));
            currentWs = myWs;
            if (!myWs.connectBlocking(5, TimeUnit.SECONDS)) {
                if (gen == generation && !wasLive) notifyState("error");
                return;
            }
            long deadline = System.currentTimeMillis() + 3000;
            while (gen == generation && !wantStop && !myWs.closed && !myWs.authed
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            if (gen != generation || wantStop || myWs.closed || !myWs.authed) {
                if (gen == generation && !wasLive) notifyState(wantStop ? "off" : "error");
                return;
            }
            myWs.send("{\"type\":\"mic\",\"on\":true}");
            session = startRecorder(gen, myWs);
            if (session == null) {
                if (gen == generation && !wasLive) notifyState("error");
                return;
            }
            wasLive = true;
            notifyState("on");
            /* Jaga juga kondisi soket (isOpen), bukan cuma flag close:
               soket yang mati diam-diam tidak boleh menggantung loop. */
            while (gen == generation && !wantStop && !myWs.closed && myWs.isOpen()) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) {
            if (gen == generation && !wasLive) notifyState("error");
        } finally {
            if (session != null) releaseSession(session);
            try { if (myWs != null) myWs.close(); } catch (Exception ignored) {}
            /* Hanya generasi aktif yang boleh mengubah state bersama.
               Generasi basi diam saja: generasi baru yang punya kendali. */
            if (gen == generation) {
                streaming = false;
                /* Stream sempat jalan lalu putus sendiri (bukan dimatikan
                   user): beri tahu UI supaya tombol balik OFF. */
                if (wasLive && !wantStop) notifyState("off");
            }
        }
    }

    private MicSession startRecorder(final int gen, final MicWsClient ws) {
        MicSession s = new MicSession();
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return null;
            int bufSize = Math.max(minBuf, FRAME_BYTES * 2);
            s.recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (s.recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseSession(s);
                return null;
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    s.aec = AcousticEchoCanceler.create(s.recorder.getAudioSessionId());
                    if (s.aec != null) s.aec.setEnabled(true);
                }
            } catch (Exception ignored) {}
            try {
                if (NoiseSuppressor.isAvailable()) {
                    s.ns = NoiseSuppressor.create(s.recorder.getAudioSessionId());
                    if (s.ns != null) s.ns.setEnabled(true);
                }
            } catch (Exception ignored) {}
            s.recorder.startRecording();
            currentRecorder = s.recorder;
            final MicSession session = s;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { recordLoop(session, ws, gen); }
            }, "mic-record");
            t.start();
            return s;
        } catch (SecurityException e) {
            // izin mic dicabut di tengah jalan
            releaseSession(s);
            return null;
        } catch (Exception e) {
            releaseSession(s);
            return null;
        }
    }

    private void recordLoop(MicSession s, MicWsClient ws, final int gen) {
        short[] pcm = new short[FRAME_SAMPLES];
        try {
            while (gen == generation && !wantStop) {
                int n;
                try {
                    n = s.recorder.read(pcm, 0, FRAME_SAMPLES);
                } catch (Exception e) {
                    break;
                }
                if (n <= 0) {
                    if (n < 0) break; // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE
                    continue;
                }
                if (ws == null || !ws.isOpen()) break;
                /* Array baru tiap frame: jangan pakai buffer bersama yang
                   bisa ditimpa sebelum soket sempat mengirimnya. */
                byte[] bytes = new byte[n * 2];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().put(pcm, 0, n);
                try {
                    ws.send(bytes);
                } catch (Exception e) {
                    break;
                }
            }
        } finally {
            releaseSession(s);
        }
    }

    private class MicWsClient extends WebSocketClient {
        volatile boolean authed;
        volatile boolean closed;

        MicWsClient(URI uri) { super(uri); }

        @Override
        public void onOpen(ServerHandshake handshake) {
            try {
                send("{\"type\":\"auth\",\"role\":\"phone\",\"code\":\""
                        + code + "\",\"mic\":true}");
            } catch (Exception ignored) {}
        }

        @Override
        public void onMessage(String message) {
            if (message != null && message.contains("\"hello\"")) authed = true;
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            // TV tidak pernah kirim biner ke HP
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed = true;
        }

        @Override
        public void onError(Exception ex) {
            // onClose yang urus penanda putus
        }
    }
}
