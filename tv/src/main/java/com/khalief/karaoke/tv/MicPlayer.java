package com.khalief.karaoke.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pemutar suara mic HP di TV (v1.3.1). Delay dijaga seminimal mungkin:
 * - AudioTrack PERFORMANCE_MODE_LOW_LATENCY (API 26+) + buffer kecil.
 * - Prebuffer cuma 1 frame (10ms) sebelum playback dimulai (v1.3.0:
 *   2 frame / 20ms) supaya suara keluar lebih cepat, dengan buffer
 *   AudioTrack 20ms sebagai penyangga supaya tidak putus-putus.
 * - Kalau tidak ada frame selama ~200ms, track dimatikan (stream selesai).
 * Kalau mic tidak pernah dipakai, thread ini cuma menunggu antrean kosong
 * dan tidak pernah membuat AudioTrack - TV jalan normal seperti biasa.
 */
public class MicPlayer implements KaraokeServer.MicListener {

    private static final int SAMPLE_RATE = 16000;   // harus sama dengan MicStreamer HP
    private static final int FRAME_BYTES = 320;     // 10ms, PCM 16-bit mono 16kHz

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(64);
    private volatile boolean destroyed;
    private final Thread playThread;
    private AudioTrack track; // hanya disentuh thread playback + stopTrack
    private volatile float volume = 0.5f; // volume mic 0.0-1.0, default 50% (v1.3.1)

    public MicPlayer() {
        playThread = new Thread(new Runnable() {
            @Override public void run() { playLoop(); }
        }, "mic-player");
        playThread.setDaemon(true);
        playThread.start();
    }

    @Override
    public void onMicAudio(byte[] data, int length) {
        if (destroyed) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        /* Antrean penuh: buang frame terbaru. Lebih baik sedetik putus
           daripada delay menumpuk makin jauh dari suara asli. */
        queue.offer(copy);
    }

    @Override
    public void onMicState(boolean on) {
        if (!on) {
            queue.clear();
            stopTrack();
        }
    }

    @Override
    public void onMicVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        AudioTrack t = getTrack();
        if (t != null) {
            try { t.setVolume(volume); } catch (Exception ignored) {}
        }
    }

    public void destroy() {
        destroyed = true;
        playThread.interrupt();
        stopTrack();
    }

    private synchronized AudioTrack getTrack() { return track; }

    private void playLoop() {
        while (!destroyed) {
            try {
                byte[] frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    stopTrack();
                    continue;
                }
                if (getTrack() == null) {
                    /* v1.3.1: langsung putar di frame pertama (prebuffer
                       10ms, dulu 20ms). Buffer AudioTrack 20ms yang
                       menahan jitter, bukan prebuffer yang menumpuk. */
                    startTrack();
                }
                writeFrame(frame);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private synchronized void startTrack() {
        if (track != null) return;
        try {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return;
            int bufSize = Math.max(minBuf, FRAME_BYTES * 2); // 20ms (v1.3.0: 40ms)
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufSize);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = b.build();
            track.setVolume(volume);
            track.play();
        } catch (Exception e) {
            track = null;
        }
    }

    private void stopTrack() {
        AudioTrack t;
        synchronized (this) {
            t = track;
            track = null;
        }
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void writeFrame(byte[] frame) {
        AudioTrack t = getTrack();
        if (t == null) return;
        int off = 0;
        while (off < frame.length) {
            int n = t.write(frame, off, frame.length - off);
            if (n <= 0) break; // track mati / dilepas di tengah jalan
            off += n;
        }
    }
}
