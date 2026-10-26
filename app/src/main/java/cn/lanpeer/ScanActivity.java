package cn.lanpeer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline scanner: no Play Services, network requests, or external scanner app required. */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private SurfaceView preview;
    private TextView hint;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private volatile boolean active;
    private boolean delivered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        hint = new TextView(this); hint.setText("将对方设备的配对二维码放入画面"); hint.setTextColor(Color.WHITE); hint.setTextSize(18); hint.setPadding(24, 32, 24, 32);
        root.addView(hint);
        preview = new SurfaceView(this); root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this); cancel.setText("返回 / 手动输入"); cancel.setOnClickListener(v -> finish()); root.addView(cancel);
        setContentView(root); preview.getHolder().addCallback(this);
    }

    @Override protected void onResume() { super.onResume(); active = true; if (preview.getHolder().getSurface().isValid()) open(preview.getHolder()); }
    @Override protected void onPause() { active = false; close(); super.onPause(); }
    @Override protected void onDestroy() { decoder.shutdownNow(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { if (active) open(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { close(); }

    private void open(SurfaceHolder holder) {
        if (camera != null) return;
        try {
            int cameraId = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { cameraId = i; break; }
            }
            Camera.getCameraInfo(cameraId, info);
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size chosen = parameters.getSupportedPreviewSizes().get(0);
            long best = Long.MAX_VALUE;
            for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                long score = Math.abs((long) size.width * size.height - 1280L * 720);
                if (score < best) { best = score; chosen = size; }
            }
            parameters.setPreviewSize(chosen.width, chosen.height);
            parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
            camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((data, source) -> {
                if (!active || delivered || !decoding.compareAndSet(false, true)) return;
                Camera.Size size = source.getParameters().getPreviewSize();
                decoder.execute(() -> {
                    try {
                        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
                        hints.put(DecodeHintType.TRY_HARDER, true);
                        Result result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                                new PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false))), hints);
                        Pairing pairing = Pairing.parse(result.getText());
                        runOnUiThread(() -> {
                            if (!active || delivered) return;
                            delivered = true;
                            setResult(RESULT_OK, new Intent().putExtra("invitation", pairing.invitation()));
                            finish();
                        });
                    } catch (IllegalArgumentException e) {
                        runOnUiThread(() -> { if (active) hint.setText("请扫描“局域互控”应用显示的配对二维码"); });
                    } catch (Exception ignored) { }
                    finally { decoding.set(false); }
                });
            });
            camera.startPreview();
        } catch (Exception e) {
            close(); hint.setText("无法打开相机，请返回并使用手动输入连接。");
        }
    }

    private void close() {
        if (camera != null) { camera.setPreviewCallback(null); camera.stopPreview(); camera.release(); camera = null; }
    }
}
