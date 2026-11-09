package com.khalief.karaoke.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.InputStream;
import java.security.SecureRandom;

/**
 * Foreground service: menjalankan server HTTP + WebSocket selama TV app hidup,
 * supaya HP tetap bisa terhubung walau layar TV idle.
 */
public class KaraokeService extends Service {

    private static final String CHANNEL_ID = "karaoke_tv";
    private static final int NOTIF_ID = 1;

    /** v1.3.9: port HTTP aktual yang dipakai server (bisa pindah kalau 8080
        kepakai) dan alasan terakhir kalau server gagal jalan. Dibaca layar
        TV (MainActivity) supaya URL polling dan pesan error selalu benar. */
    public static volatile int httpPort = -1;
    public static volatile String lastError = null;

    private volatile KaraokeServer server;
    private MicPlayer micPlayer;
    private String code;
    private String ip;

    @Override
    public void onCreate() {
        super.onCreate();
        code = String.format("%06d", 100000 + new SecureRandom().nextInt(900000));
        ip = NetworkUtil.getLocalIpAddress(this);
        if (ip == null) ip = "127.0.0.1";

        createChannel();
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Karaoke for TV berjalan")
                .setContentText("Buka HP, scan QR di TV untuk mulai karaoke")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif);

        KaraokeServer.AssetProvider assets = new KaraokeServer.AssetProvider() {
            @Override
            public InputStream open(String path) throws java.io.IOException {
                return getAssets().open(path);
            }
        };
        KaraokeServer.QrProvider qr = new KaraokeServer.QrProvider() {
            @Override
            public byte[] getQrPng() throws Exception {
                /* QR selalu digambar ulang dari URL terbaru: kode pairing
                   berotasi, jadi cache lama akan menampilkan kode kadaluarsa */
                return QrCode.makePng(server.controllerUrl(), 512);
            }
        };

        server = null;
        /* Mic HP (v1.3.0): suara mentah dari HP diputar via AudioTrack
           low-latency. Kalau mic tidak pernah dipakai, MicPlayer cuma
           menunggu dan tidak membuat AudioTrack. */
        micPlayer = new MicPlayer();
        startServerWithRetry(assets, qr);
    }

    /** v1.3.9: coba nyalakan server sampai 3x (jeda 2 detik). Dulu sekali
        gagal langsung stopSelf diam-diam, layar TV stuck di QR kosong.
        Kalau tetap gagal, simpan alasannya supaya layar TV bisa
        menampilkan pesan yang jelas, lalu matikan service. */
    private void startServerWithRetry(final KaraokeServer.AssetProvider assets,
                                      final KaraokeServer.QrProvider qr) {
        httpPort = -1;
        lastError = null;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    KaraokeServer s = new KaraokeServer(ip, code, assets, qr);
                    s.setMicListener(micPlayer);
                    server = s;
                    try {
                        httpPort = s.start();
                        lastError = null;
                        return;
                    } catch (Exception e) {
                        lastError = shortReason(e);
                        try { s.stop(); } catch (Exception ignored) {}
                        if (attempt < 3) {
                            try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                        }
                    }
                }
                stopSelf();
            }
        }).start();
    }

    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) m = e.toString();
        if (m.indexOf("EADDRINUSE") >= 0 || m.indexOf("Address already in use") >= 0) {
            return "port dipakai aplikasi lain";
        }
        if (m.length() > 80) m = m.substring(0, 80);
        return m;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (micPlayer != null) {
            micPlayer.destroy();
            micPlayer = null;
        }
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Karaoke for TV",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Server karaoke lokal di TV");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
