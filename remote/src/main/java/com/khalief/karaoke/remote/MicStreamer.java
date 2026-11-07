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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Mic HP -> TV (v1.3.0). Delay dijaga seminimal mungkin:
 * - Suara dikirim MENTAH sebagai PCM 16-bit mono 16kHz, TANPA codec/encode.
 * - Frame kecil 10ms (160 sampel = 320 byte) per pesan biner lewat
 *   WebSocket yang sudah ada (port 8081). Tanpa channel UDP baru.
 * - AudioRecord buffer kecil; AcousticEchoCanceler diaktifkan kalau
 *   tersedia (kurangi feedback dari speaker TV).
 *
 * Alur: connect WS -> auth (role phone + "mic":true) -> kirim
 * {"type":"mic","on":true} -> mulai rekam & kirim frame biner.
 * Berhenti: kirim {"type":"mic","on":false} -> tutup WS -> lepas recorder.
 * Semua callback status ("on"/"off"/"denied"/"error") diteruskan ke
 * Listener; pemanggil yang menampilkan pesan ke user.
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

    private volatile boolean streaming;
    private volatile boolean wantStop;
    private volatile boolean authed;
    private volatile boolean wsClosed;
    private volatile boolean live; // sudah kirim "on" ke UI

    private WebSocketClient ws;
    private AudioRecord recorder;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread recordThread;

    public MicStreamer(String tvIp, String code, Listener listener) {
        this.wsUrl = "ws://" + tvIp + ":8081";
        this.code = code;
        this.listener = listener;
    }

    public synchronized void start() {
        if (streaming) return;
        streaming = true;
        wantStop = false;
        authed = false;
        wsClosed = false;
        live = false;
        new Thread(new Runnable() {
            @Override public void run() { controlLoop(); }
        }, "mic-control").start();
    }

    /** Berhenti: kirim mic-off ke TV, tutup WS, lepas recorder. */
    public synchronized void stop() {
        wantStop = true;
        try {
            if (ws != null && ws.isOpen()) ws.send("{\"type\":\"mic\",\"on\":false}");
        } catch (Exception ignored) {}
        try { if (ws != null) ws.close(); } catch (Exception ignored) {}
        stopRecorder();
    }

    private void notifyState(String state) {
        try { listener.onMicState(state); } catch (Exception ignored) {}
    }

    private void controlLoop() {
        try {
            ws = new MicWsClient(new URI(wsUrl));
            if (!ws.connectBlocking(5, TimeUnit.SECONDS)) {
                if (!live) notifyState("error");
                return;
            }
            long deadline = System.currentTimeMillis() + 3000;
            while (!authed && !wantStop && !wsClosed
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            if (wantStop || wsClosed || !authed) {
                if (!live) notifyState(wantStop ? "off" : "error");
                return;
            }
            ws.send("{\"type\":\"mic\",\"on\":true}");
            if (!startRecorder()) {
                if (!live) notifyState("error");
                return;
            }
            live = true;
            notifyState("on");
            while (!wantStop && !wsClosed) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) {
            if (!live) notifyState("error");
        } finally {
            stopRecorder();
            try { if (ws != null) ws.close(); } catch (Exception ignored) {}
            streaming = false;
            /* Stream sempat jalan lalu putus sendiri (bukan dimatikan user):
               beri tahu UI supaya tombol balik OFF. */
            if (live && !wantStop) notifyState("off");
        }
    }

    private boolean startRecorder() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return false;
            int bufSize = Math.max(minBuf, FRAME_BYTES * 2);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                return false;
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
            } catch (Exception ignored) {}
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(recorder.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
            } catch (Exception ignored) {}
            recorder.startRecording();
            recordThread = new Thread(new Runnable() {
                @Override public void run() { recordLoop(); }
            }, "mic-record");
            recordThread.start();
            return true;
        } catch (SecurityException e) {
            // izin mic dicabut di tengah jalan
            releaseRecorder();
            return false;
        } catch (Exception e) {
            releaseRecorder();
            return false;
        }
    }

    private void recordLoop() {
        short[] pcm = new short[FRAME_SAMPLES];
        byte[] bytes = new byte[FRAME_BYTES];
        while (!wantStop) {
            int n;
            try {
                n = recorder.read(pcm, 0, FRAME_SAMPLES);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) {
                if (n < 0) break; // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE
                continue;
            }
            if (ws == null || !ws.isOpen()) break;
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(pcm, 0, n);
            try {
                if (n == FRAME_SAMPLES) {
                    ws.send(bytes);
                } else {
                    ws.send(Arrays.copyOf(bytes, n * 2));
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    private synchronized void stopRecorder() {
        AudioRecord r = recorder;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        if (recordThread != null) {
            try { recordThread.join(500); } catch (InterruptedException ignored) {}
            recordThread = null;
        }
        releaseRecorder();
    }

    private void releaseRecorder() {
        if (aec != null) {
            try { aec.release(); } catch (Exception ignored) {}
            aec = null;
        }
        if (ns != null) {
            try { ns.release(); } catch (Exception ignored) {}
            ns = null;
        }
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private class MicWsClient extends WebSocketClient {
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
            wsClosed = true;
        }

        @Override
        public void onError(Exception ex) {
            // onClose yang urus penanda putus
        }
    }
}
