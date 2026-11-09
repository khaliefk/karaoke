package com.khalief.karaoke.tv;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Activity TV: nyalakan service server, tampilkan layar tunggu NATIVE
 * (QR + kode pairing digambar Android langsung, tanpa WebView), lalu
 * muat UI TV di WebView di belakangnya dan beralih begitu halaman web
 * melaporkan dirinya sudah jalan.
 *
 * v1.3.4 (7 Nov 2026): layar tunggu native. Alasan: di beberapa TV
 * (mis. Changhong Android 14) WebView sistem tidak me-render halaman
 * sama sekali, layarnya putih polos dan QR tidak pernah muncul, padahal
 * browser TV-nya bisa buka YouTube dengan normal. Dengan layar native,
 * QR + kode selalu tampil walau WebView TV-nya rusak; pairing tetap
 * jalan karena HP terhubung langsung ke server, bukan ke WebView.
 * WebView cuma dipakai untuk player, dan cuma ditampilkan kalau halaman
 * web memanggil Android.onPageReady() dari initTv().
 *
 * v1.3.9 (10 Nov 2026): port HTTP/WS pindah otomatis kalau 8080/8081
 * kepakai aplikasi lain di TV; URL polling memakai port aktual dari
 * service; kalau server tetap gagal, layar menampilkan alasannya.
 *
 * Perbaikan 6 Nov 2026 (dipertahankan): dulu WebView langsung load URL
 * begitu activity dibuat, padahal service server baru mulai belakangan
 * (startService itu async). Hasilnya halaman gagal dimuat dengan
 * ERR_CONNECTION_REFUSED. Sekarang activity menunggu server menjawab
 * dulu, dan kalau load gagal, dicoba ulang otomatis.
 */
public class MainActivity extends Activity {

    /** v1.3.9: port HTTP bisa pindah kalau 8080 kepakai (lihat KaraokeServer).
        URL selalu memakai port aktual dari service, default 8080. */
    private static int httpPort() {
        int p = KaraokeService.httpPort;
        return p > 0 ? p : 8080;
    }

    private static String tvUrl() {
        return "http://localhost:" + httpPort() + "/?mode=tv";
    }

    private static String infoUrl() {
        return "http://127.0.0.1:" + httpPort() + "/api/info";
    }

    private static String qrUrl() {
        return "http://127.0.0.1:" + httpPort() + "/qr.png";
    }
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int FAIL_AFTER = 10;   // ~10 detik tanpa jawaban server
    private static final int MAX_RETRIES = 10;

    private WebView webView;
    private View nativeView;
    private ImageView qrImage;
    private TextView codeText;
    private TextView countdownText;
    private TextView ipText;
    private TextView statusText;
    private Handler mainHandler;
    private int retries;
    private volatile boolean destroyed;
    private volatile boolean webLoaded;
    private volatile boolean webReady;
    private String lastCode = "";
    private Bitmap lastQr;
    private long updateDownloadId = -1;
    private long installLaunchedId = -1;
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

        FrameLayout root = new FrameLayout(this);

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
                                webView.loadUrl(tvUrl());
                            }
                        }
                    }, 1000);
                }
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Layar tunggu native menutupi WebView sampai halaman web siap.
        nativeView = buildNativeWaitView();
        root.addView(nativeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Download update selesai -> buka installer sistem.
        // v1.3.2: receiver ini cuma cadangan. Jalur utama adalah polling
        // dari halaman web (getUpdateProgress) yang memanggil installUpdate()
        // begitu status done. Semua jalan keluar yang sama:
        // launchInstallForDownload(), sekali per download, dan halaman web
        // selalu diberi tahu hasilnya supaya dialog bisa menampilkan
        // tombol Pasang kalau installer gagal kebuka sendiri.
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != updateDownloadId) return;
                int status = queryDownloadStatus(id);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (launchInstallForDownload(id, false)) {
                        notifyJsUpdateState("installed");
                    } else {
                        notifyJsUpdateState("installFailed");
                    }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    notifyJsUpdateState("failed");
                }
            }
        };
        // Android 13+ (targetSdk 34): registerReceiver wajib bawa flag
        // RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED, kalau tidak aplikasi
        // crash di onCreate (SecurityException). Broadcast download ini
        // datang dari sistem, jadi NOT_EXPORTED sudah cukup.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        // Poll /api/info tiap detik: isi layar tunggu native, dan muat
        // halaman web begitu server menjawab.
        startInfoPoller();
    }

    /** Layar tunggu native: judul, QR, kode pairing, hitung mundur, IP. */
    private View buildNativeWaitView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#0D0D0D"));
        int pad = dp(24);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Karaoke for TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("Scan QR dari HP untuk mulai");
        sub.setTextColor(Color.parseColor("#AAAAAA"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        layout.addView(sub, subLp);

        int qrSize = (int) (Math.min(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.42);
        qrImage = new ImageView(this);
        qrImage.setBackgroundColor(Color.WHITE);
        int qrPad = dp(12);
        qrImage.setPadding(qrPad, qrPad, qrPad, qrPad);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrLp.topMargin = dp(24);
        layout.addView(qrImage, qrLp);

        codeText = new TextView(this);
        codeText.setText(". . . . . .");
        codeText.setTextColor(Color.WHITE);
        codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(16);
        layout.addView(codeText, codeLp);

        countdownText = new TextView(this);
        countdownText.setTextColor(Color.parseColor("#E8B64C"));
        countdownText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        countdownText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cdLp.topMargin = dp(8);
        layout.addView(countdownText, cdLp);

        ipText = new TextView(this);
        ipText.setTextColor(Color.parseColor("#AAAAAA"));
        ipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ipText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipLp.topMargin = dp(8);
        layout.addView(ipText, ipLp);

        TextView version = new TextView(this);
        version.setText("v" + getAppVersionName());
        version.setTextColor(Color.parseColor("#888888"));
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(16);
        layout.addView(version, vLp);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#FF6B6B"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusText.setGravity(Gravity.CENTER);
        statusText.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(16);
        layout.addView(statusText, stLp);

        return layout;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** Poll /api/info tiap detik sampai activity mati. */
    private void startInfoPoller() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int fails = 0;
                while (!destroyed) {
                    String body = httpGet(infoUrl());
                    if (body != null) {
                        fails = 0;
                        try {
                            JSONObject info = new JSONObject(body);
                            final String code = info.optString("code", "");
                            final String ip = info.optString("ip", "");
                            final boolean connected = info.optBoolean("phoneConnected", false);
                            final int expires = info.optInt("codeExpiresIn", -1);
                            final boolean codeChanged = !code.equals(lastCode);
                            if (codeChanged) lastCode = code;
                            final Bitmap qr = codeChanged ? httpGetBitmap(qrUrl()) : null;
                            mainHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (!destroyed) applyInfo(code, ip, connected, expires, qr);
                                }
                            });
                            if (!webLoaded) {
                                webLoaded = true;
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        if (!destroyed) {
                                            retries = 0;
                                            webView.loadUrl(tvUrl());
                                        }
                                    }
                                });
                            }
                        } catch (Exception e) {
                            // JSON rusak: lewati putaran ini
                        }
                    } else {
                        fails++;
                        if (fails >= FAIL_AFTER) {
                            mainHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (!destroyed && statusText != null) {
                                        statusText.setVisibility(View.VISIBLE);
                                        String err = KaraokeService.lastError;
                                        if (err != null && err.length() > 0) {
                                            statusText.setText("Server tidak bisa jalan: " + err + ". Coba restart TV.");
                                        } else {
                                            statusText.setText("Server tidak bisa jalan. Tutup aplikasi ini lalu buka lagi.");
                                        }
                                    }
                                }
                            });
                        }
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }).start();
    }

    /** Terapkan hasil /api/info ke layar tunggu native (jalan di UI thread). */
    private void applyInfo(String code, String ip, boolean connected, int expires, Bitmap qr) {
        if (statusText != null) statusText.setVisibility(View.GONE);
        if (codeText != null && code.length() > 0) {
            // Spasi antar digit, sama seperti halaman web.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < code.length(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(code.charAt(i));
            }
            codeText.setText(sb.toString());
        }
        if (qr != null && qrImage != null) {
            qrImage.setImageBitmap(qr);
            if (lastQr != null && lastQr != qr) {
                try { lastQr.recycle(); } catch (Exception e) {}
            }
            lastQr = qr;
        }
        if (ipText != null && ip.length() > 0) {
            ipText.setText("TV: " + ip + " - port " + httpPort());
        }
        if (countdownText != null) {
            if (connected) countdownText.setText("HP terhubung - QR dijeda");
            else if (expires >= 0) countdownText.setText("QR baru dalam " + expires + " dtk");
            else countdownText.setText("");
        }
    }

    /** Sembunyikan layar native, tampilkan WebView (sekali saja). */
    private void showWebView() {
        if (webReady || destroyed) return;
        webReady = true;
        if (nativeView != null) nativeView.setVisibility(View.GONE);
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Bitmap httpGetBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            return BitmapFactory.decodeStream(c.getInputStream());
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

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

        /** Halaman TV memanggil ini begitu initTv() selesai: halaman jalan,
            layar tunggu native boleh disembunyikan. Kalau WebView TV rusak
            dan halaman tidak pernah jalan, panggilan ini tidak pernah
            sampai dan layar native tetap tampil. */
        @JavascriptInterface
        public void onPageReady() {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (!destroyed) showWebView();
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

        /** Progress download update untuk halaman web, format JSON:
            {"state":"idle|downloading|done|failed","downloaded":0,"total":0} */
        @JavascriptInterface
        public String getUpdateProgress() {
            String s = "idle";
            long downloaded = 0;
            long total = 0;
            if (updateDownloadId >= 0) {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById(updateDownloadId);
                    android.database.Cursor c = null;
                    try {
                        c = dm.query(q);
                        if (c != null && c.moveToFirst()) {
                            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                            downloaded = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            if (status == DownloadManager.STATUS_SUCCESSFUL) s = "done";
                            else if (status == DownloadManager.STATUS_FAILED) s = "failed";
                            else s = "downloading";
                        }
                    } catch (Exception e) {
                    } finally {
                        if (c != null) c.close();
                    }
                }
            }
            return "{\"state\":\"" + s + "\",\"downloaded\":" + downloaded + ",\"total\":" + total + "}";
        }

        /** Buka installer untuk APK update yang sudah selesai di-download.
            force=false dipakai jalur otomatis (sekali per download);
            force=true dipakai tombol Pasang (boleh coba lagi). */
        @JavascriptInterface
        public boolean installUpdate(boolean force) {
            return launchInstallForDownload(updateDownloadId, force);
        }
    }

    /** Status download update, atau -1 kalau tidak ada catatannya. */
    private int queryDownloadStatus(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null || id < 0) return -1;
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(id);
        android.database.Cursor c = null;
        try {
            c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                return c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception e) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** Buka installer sistem untuk APK update yang sudah selesai di-download.
        Mengembalikan true kalau intent installer berhasil dilempar. Kalau
        gagal (misal URI tidak ketemu atau tidak ada activity yang menangani),
        mengembalikan false supaya halaman web bisa menampilkan tombol Pasang
        dan user tidak dibiarkan menunggu tanpa kabar. */
    private boolean launchInstallForDownload(long id, boolean force) {
        if (id < 0) return false;
        if (!force && id == installLaunchedId) return true;
        if (queryDownloadStatus(id) != DownloadManager.STATUS_SUCCESSFUL) return false;
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) return false;
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            installLaunchedId = id;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Teruskan status update ke halaman web (dialog progress). */
    private void notifyJsUpdateState(final String state) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (destroyed || webView == null) return;
                try {
                    webView.evaluateJavascript(
                            "window.__updateState && window.__updateState('" + state + "')", null);
                } catch (Exception ignored) {}
            }
        });
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
            installLaunchedId = -1;
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
        if (lastQr != null) {
            try { lastQr.recycle(); } catch (Exception e) {}
            lastQr = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
