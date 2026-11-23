package com.khalief.karaoke.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * v1.4.9: foreground service penjaga koneksi remote ke TV.
 *
 * Masalah yang diperbaiki: remote mati sendiri setelah kira-kira 2 lagu
 * saat diminimize atau layar HP mati. Android (apalagi MIUI di POCO,
 * yang agresif membunuh aplikasi latar belakang) mematikan proses
 * remote, lalu WebSocket mic dan sesi WebView ke TV ikut putus.
 *
 * Service ini jalan selama remote tersambung ke TV (dinyalakan dari
 * MainActivity.showWebView, dimatikan saat user keluar atau kembali
 * ke layar scan). Selama jalan:
 * - status foreground: sistem menganggap aplikasi sedang dipakai,
 *   jauh lebih sulit dimatikan daripada aplikasi background biasa;
 * - partial wake lock: CPU tetap menyala saat layar mati, jadi
 *   stream mic + WebSocket tidak dibekukan Doze;
 * - notifikasi menetap: user bisa ketuk untuk kembali ke remote.
 *
 * Service ini TIDAK membuat koneksi sendiri dan TIDAK memutar apa
 * pun; yang dijaga hidup adalah proses aplikasi (WebView + MicStreamer
 * yang sudah jalan di dalamnya). Berhenti sendiri saat MainActivity
 * memanggil stop(): keluar aplikasi, atau kembali ke layar scan.
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "karaoke-keepalive";
    private static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wakeLock;

    /** Nyalakan dari activity (hanya saat tersambung ke TV). */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Matikan (user keluar / kembali ke layar scan). */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "KaraokeRemote:KeepAlive");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
        startForeground(NOTIF_ID, buildNotification());
        // Kalau sistem sempat mematikan service, nyalakan lagi sendiri.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Karaoke Remote aktif", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Menjaga remote tetap tersambung ke TV saat "
                + "aplikasi diminimize atau layar mati.");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Karaoke Remote aktif")
                .setContentText("Tetap tersambung ke TV. "
                        + "Ketuk untuk kembali ke remote.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
