package com.khalief.karaoke.remote;

import android.Manifest;
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
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity HP: scan QR di TV, lalu tampilkan remote karaoke di WebView.
 * HP murni remote: tidak ada suara dan tidak ada video di sini.
 * Semua kontrol (cari, antrean, play/pause, volume) ada di halaman web,
 * activity ini hanya menyediakan kamera + WebView + jembatan scan ulang.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 100;
    private static final int REQ_SCAN = 101;
    private static final int REQ_MIC = 102;

    private WebView webView;
    private LinearLayout scanView;
    private TextView scanText;
    private long updateDownloadId = -1;
    private long installLaunchedId = -1;
    private BroadcastReceiver downloadReceiver;
    /* Mic HP (v1.3.0): alamat TV + kode pairing dari QR yang di-scan,
       dipakai MicStreamer untuk sambung WebSocket sendiri ke TV. */
    private String tvIp;
    private String pairCode;
    private boolean pendingMic;
    private MicStreamer micStreamer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        scanView = findViewById(R.id.scan_view);
        scanText = findViewById(R.id.scan_text);
        Button scanBtn = findViewById(R.id.scan_btn);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        // v1.3.5: entri kode manual (IP + kode 6 digit dari layar TV).
        final EditText manualIp = findViewById(R.id.manual_ip);
        final EditText manualCode = findViewById(R.id.manual_code);
        Button manualBtn = findViewById(R.id.manual_btn);
        manualBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectManual(manualIp.getText().toString().trim(),
                        manualCode.getText().toString().trim());
            }
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "Android");

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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showScanView("Tidak bisa terhubung ke TV. "
                            + "Pastikan HP dan TV di WiFi yang sama, lalu scan ulang.");
                }
            }
        });

        ensureCameraThenScan();
    }

    private void ensureCameraThenScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void startScan() {
        stopMicStream(); // scan ulang = sesi baru, mic lama harus mati
        micStreamer = null; // v1.3.1: buang streamer lama supaya start
        // berikutnya memakai kode pairing BARU dari QR yang di-scan
        Intent i = new Intent(this, ScannerActivity.class);
        startActivityForResult(i, REQ_SCAN);
    }

    /** v1.3.5: sambung tanpa kamera. IP + kode dibaca user dari layar TV
        (layar tunggu native v1.3.4 menampilkannya), URL yang dibangun
        sama persis dengan isi QR. */
    private void connectManual(String ip, String code) {
        if (!isValidIp(ip) || code == null || !code.matches("[0-9]{6}")) {
            Toast.makeText(this, "IP atau kode tidak valid. IP dan kode ada di layar TV.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        stopMicStream();
        micStreamer = null;
        tvIp = ip;
        pairCode = code;
        showWebView();
        webView.loadUrl("http://" + ip + ":8080/?mode=phone&code=" + code);
    }

    private static boolean isValidIp(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.length() == 0 || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void showScanView(String message) {
        if (message != null) scanText.setText(message);
        webView.setVisibility(View.GONE);
        scanView.setVisibility(View.VISIBLE);
    }

    private void showWebView() {
        scanView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan();
            } else {
                Toast.makeText(this, "Izin kamera dibutuhkan untuk scan QR",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingMic) startMicStream();
            } else {
                // ditolak: beri tahu halaman web, biar tombol balik OFF
                // dengan pesan sopan. JANGAN crash.
                jsMicState("denied");
            }
            pendingMic = false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String url = data.getStringExtra("url");
            if (url != null && url.startsWith("http") && url.contains("mode=phone")) {
                try {
                    Uri u = Uri.parse(url);
                    tvIp = u.getHost();
                    pairCode = u.getQueryParameter("code");
                } catch (Exception ignored) {}
                showWebView();
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "QR ini bukan QR Karaoke for TV",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQ_SCAN && resultCode == RESULT_CANCELED && data != null
                && "camera".equals(data.getStringExtra("error"))) {
            // v1.3.5: kamera gagal total setelah 3x coba. Arahkan user
            // ke entri kode manual di bawah, jangan dibiarkan bingung.
            showScanView("Kamera tidak bisa dibuka di HP ini. "
                    + "Masukkan IP TV dan kode 6 digit di bawah.");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopMicStream();
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception e) {}
            downloadReceiver = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    /* ---------- Mic HP (v1.3.0) ---------- */

    /** Dipanggil dari halaman web saat user menekan tombol mic ON/OFF. */
    private void setMic(boolean on) {
        if (!on) {
            stopMicStream();
            return;
        }
        if (tvIp == null || pairCode == null) {
            jsMicState("error");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startMicStream();
        } else {
            pendingMic = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    /* v1.3.1: SELALU buat MicStreamer baru dengan tvIp/pairCode saat
       ini. Streamer lama menyimpan kode pairing dari QR pertama;
       setelah scan ulang (kode berotasi) kode itu sudah mati dan
       mic tidak akan pernah bisa ON lagi sampai aplikasi di-restart
       (bug v1.3.0). MicStreamer.start() sendiri sudah idempotent. */
    private void startMicStream() {
        stopMicStream();
        micStreamer = new MicStreamer(tvIp, pairCode, new MicStreamer.Listener() {
            @Override
            public void onMicState(final String state) {
                jsMicState(state);
            }
        });
        micStreamer.start();
    }

    private void stopMicStream() {
        if (micStreamer != null) micStreamer.stop();
    }

    /** Teruskan status mic ke halaman web (tombol + pesan). */
    private void jsMicState(final String state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                            "window.__micState && window.__micState('" + state + "')", null);
                } catch (Exception ignored) {}
            }
        });
    }

    /** Dipanggil dari halaman web saat user menekan "Scan Ulang QR TV". */
    private class JsBridge {
        @JavascriptInterface
        public void rescan() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startScan();
                }
            });
        }

        /** Mic HP on/off (v1.3.0). Berjalan di thread binder, jadi
            dilempar ke UI thread dulu. */
        @JavascriptInterface
        public void setMicOn(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setMic(on);
                }
            });
        }

        /** Dipanggil dari halaman web saat user menekan "Keluar". */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
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
}
