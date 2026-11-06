package com.khalief.karaoke.tv;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Activity TV: nyalakan service server, tunggu server siap, baru tampilkan
 * UI TV di WebView fullscreen.
 *
 * Perbaikan 6 Nov 2026: dulu WebView langsung load URL begitu activity
 * dibuat, padahal service server baru mulai belakangan (startService itu
 * async). Hasilnya halaman gagal dimuat dengan ERR_CONNECTION_REFUSED.
 * Sekarang activity menunggu server menjawab dulu, dan kalau load gagal,
 * dicoba ulang otomatis.
 */
public class MainActivity extends Activity {

    private static final String TV_URL = "http://localhost:8080/?mode=tv";
    private static final String HEALTH_URL = "http://127.0.0.1:8080/api/info";
    private static final int MAX_WAIT_TRIES = 40;   // ~20 detik
    private static final int WAIT_INTERVAL_MS = 500;
    private static final int MAX_RETRIES = 10;

    private WebView webView;
    private Handler mainHandler;
    private int retries;
    private volatile boolean destroyed;
    private long updateDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mainHandler = new Handler(Looper.getMainLooper());

        Intent svc = new Intent(this, KaraokeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new TvBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame() && retries < MAX_RETRIES && !destroyed) {
                    retries++;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                webView.loadUrl(TV_URL);
                            }
                        }
                    }, 1000);
                }
            }
        });
        setContentView(webView);

        // Download update selesai -> buka installer sistem.
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != updateDownloadId) return;
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return;
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(id);
                android.database.Cursor c = null;
                try {
                    c = dm.query(q);
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri uri = dm.getUriForDownloadedFile(id);
                            if (uri != null) {
                                Intent install = new Intent(Intent.ACTION_VIEW);
                                install.setDataAndType(uri, "application/vnd.android.package-archive");
                                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(install);
                            }
                        }
                    }
                } catch (Exception e) {
                } finally {
                    if (c != null) c.close();
                }
            }
        };
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        // Tampilkan layar tunggu, lalu muat halaman begitu server menjawab.
        webView.loadData(WAIT_HTML, "text/html", "UTF-8");
        waitForServerThenLoad();
    }

    private void waitForServerThenLoad() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < MAX_WAIT_TRIES; i++) {
                    if (destroyed) {
                        return;
                    }
                    if (serverUp()) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!destroyed) {
                                    retries = 0;
                                    webView.loadUrl(TV_URL);
                                }
                            }
                        });
                        return;
                    }
                    try {
                        Thread.sleep(WAIT_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!destroyed) {
                            webView.loadData(FAIL_HTML, "text/html", "UTF-8");
                        }
                    }
                });
            }
        }).start();
    }

    private static boolean serverUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(400);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static final String WAIT_HTML =
            "<html><body style='background:#0d0d0d;color:#eee;font-family:sans-serif;"
            + "text-align:center;padding-top:20%'>"
            + "<h1>Karaoke for TV</h1><p>Menyalakan server...</p></body></html>";

    private static final String FAIL_HTML =
            "<html><body style='background:#0d0d0d;color:#eee;font-family:sans-serif;"
            + "text-align:center;padding-top:20%'>"
            + "<h1>Karaoke for TV</h1>"
            + "<p>Server tidak bisa jalan. Tutup aplikasi ini lalu buka lagi.</p>"
            + "</body></html>";

    /** Keluar bersih: matikan service server lalu tutup activity. */
    private void exitApp() {
        stopService(new Intent(this, KaraokeService.class));
        finish();
    }

    /** Bridge JS untuk halaman TV: tombol "Keluar" di WebView memanggil ini. */
    private class TvBridge {
        @JavascriptInterface
        public void exitApp() {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (!destroyed) MainActivity.this.exitApp();
                }
            });
        }

        /** Versi terinstall, dipakai halaman web untuk bandingkan dengan version.json. */
        @JavascriptInterface
        public int getVersionCode() {
            return getAppVersionCode();
        }

        @JavascriptInterface
        public String getVersionName() {
            return getAppVersionName();
        }

        /** Boleh install dari sumber tak dikenal? (Android 8+: toggle di Pengaturan) */
        @JavascriptInterface
        public boolean canInstallUnknown() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return getPackageManager().canRequestPackageInstalls();
            }
            return true;
        }

        /** Buka halaman Pengaturan "install dari sumber tak dikenal" untuk aplikasi ini. */
        @JavascriptInterface
        public void openInstallSettings() {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                }
            });
        }

        /** Download APK update lalu buka installer sistem ("Pasang"). */
        @JavascriptInterface
        public void startUpdate(final String url) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && !getPackageManager().canRequestPackageInstalls()) {
                        openInstallSettings();
                        return;
                    }
                    startUpdateDownload(url);
                }
            });
        }
    }

    private int getAppVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return (int) getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).getLongVersionCode();
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String getAppVersionName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void startUpdateDownload(String url) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Karaoke update");
            req.setDescription("Mengunduh update...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "karaoke-update.apk");
            req.setMimeType("application/vnd.android.package-archive");
            req.setAllowedOverRoaming(false);
            updateDownloadId = dm.enqueue(req);
        } catch (Exception e) {
            // gagal mulai download: user bisa coba lagi dari tombol Update
        }
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception e) {}
            downloadReceiver = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
