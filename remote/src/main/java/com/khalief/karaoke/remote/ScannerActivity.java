package com.khalief.karaoke.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Scanner QR di dalam aplikasi: pratinjau kamera (Camera2) + decode ZXing.
 * Hasilnya dikembalikan ke MainActivity lewat extra "url".
 * Tidak ada suara atau video yang diputar di sini, cuma pratinjau kamera.
 */
public class ScannerActivity extends Activity {

    private TextureView textureView;
    private TextView hintText;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Size previewSize;
    private boolean found = false;
    private int frameCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hintText = new TextView(this);
        hintText.setText("Arahkan kamera ke QR di TV");
        hintText.setTextColor(0xFFFFFFFF);
        hintText.setTextSize(18);
        hintText.setGravity(Gravity.CENTER);
        hintText.setBackgroundColor(0x88000000);
        hintText.setPadding(16, 24, 16, 24);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP;
        root.addView(hintText, hintLp);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Batal");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        btnLp.bottomMargin = 48;
        root.addView(cancelBtn, btnLp);

        setContentView(root);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBgThread();
        if (textureView.isAvailable()) openCamera();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBgThread();
        super.onPause();
    }

    private void startBgThread() {
        bgThread = new HandlerThread("qr-scan");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(); } catch (InterruptedException ignored) {}
            bgThread = null;
            bgHandler = null;
        }
    }

    private void openCamera() {
        if (camera != null || bgHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        try {
            CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
            String backId = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backId = id;
                    StreamConfigurationMap map =
                            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) previewSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
                    break;
                }
            }
            if (backId == null) {
                String[] ids = mgr.getCameraIdList();
                if (ids.length == 0) { finish(); return; }
                backId = ids[0];
                CameraCharacteristics ch = mgr.getCameraCharacteristics(backId);
                StreamConfigurationMap map =
                        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) previewSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            }
            if (previewSize == null) previewSize = new Size(1280, 720);
            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image img = reader.acquireLatestImage();
                    if (img == null) return;
                    try {
                        frameCount++;
                        if (!found && frameCount % 4 == 0) {
                            String text = decodeYuv(img);
                            if (text != null) {
                                found = true;
                                Intent data = new Intent();
                                data.putExtra("url", text);
                                setResult(RESULT_OK, data);
                                finish();
                            }
                        }
                    } finally {
                        img.close();
                    }
                }
            }, bgHandler);
            mgr.openCamera(backId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    camera = d;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice d) { d.close(); camera = null; }

                @Override
                public void onError(CameraDevice d, int error) { d.close(); camera = null; }
            }, bgHandler);
        } catch (CameraAccessException e) {
            finish();
        }
    }

    /** Pilih ukuran frame YUV paling kecil yang lebarnya >= 640, maks 1280. */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1280) continue;
            if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) {
                if (s.getWidth() >= 640) best = s;
            }
        }
        if (best == null) best = sizes[0];
        return best;
    }

    private void startPreview() {
        if (camera == null || !textureView.isAvailable()) return;
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(st);
            CaptureRequest.Builder b =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.addTarget(imageReader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            final CaptureRequest req = b.build();
            camera.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(req, null, bgHandler);
                            } catch (CameraAccessException ignored) {}
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {}
                    }, bgHandler);
        } catch (CameraAccessException ignored) {}
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    /**
     * Ambil bidang Y dari frame, coba decode QR di 4 rotasi.
     * Logika ini murni byte + ZXing, bisa diuji di JVM biasa.
     */
    static String decodeYuv(Image img) {
        Image.Plane yPlane = img.getPlanes()[0];
        ByteBuffer buf = yPlane.getBuffer();
        int w = img.getWidth();
        int h = img.getHeight();
        int stride = yPlane.getRowStride();
        byte[] y = new byte[w * h];
        if (stride == w) {
            buf.get(y);
        } else {
            byte[] row = new byte[stride];
            for (int r = 0; r < h; r++) {
                buf.get(row);
                System.arraycopy(row, 0, y, r * w, w);
            }
        }
        return decodeRotations(y, w, h);
    }

    static String decodeRotations(byte[] y, int w, int h) {
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        reader.setHints(hints);
        byte[] data = y;
        int dw = w, dh = h;
        for (int r = 0; r < 4; r++) {
            try {
                LuminanceSource src = new PlanarYUVLuminanceSource(
                        data, dw, dh, 0, 0, dw, dh, false);
                Result res = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                return res.getText();
            } catch (NotFoundException ignored) {
                // coba rotasi berikutnya
            } finally {
                reader.reset();
            }
            data = rotate90(data, dw, dh);
            int tmp = dw; dw = dh; dh = tmp;
        }
        return null;
    }

    /** Putar array luminance 90 derajat searah jarum jam. */
    static byte[] rotate90(byte[] in, int w, int h) {
        byte[] out = new byte[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[x * h + (h - 1 - y)] = in[y * w + x];
            }
        }
        return out;
    }
}
