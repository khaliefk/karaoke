package com.khalief.karaoke.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
    }
}
