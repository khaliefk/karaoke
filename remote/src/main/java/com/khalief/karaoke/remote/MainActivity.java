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

    private WebView webView;
    private LinearLayout scanView;
    private TextView scanText;
    private long updateDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

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

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "Android");

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
        Intent i = new Intent(this, ScannerActivity.class);
        startActivityForResult(i, REQ_SCAN);
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
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String url = data.getStringExtra("url");
            if (url != null && url.startsWith("http") && url.contains("mode=phone")) {
                showWebView();
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "QR ini bukan QR Karaoke for TV",
                        Toast.LENGTH_LONG).show();
            }
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
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception e) {}
            downloadReceiver = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
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
}
