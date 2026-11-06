package com.khalief.karaoke.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
