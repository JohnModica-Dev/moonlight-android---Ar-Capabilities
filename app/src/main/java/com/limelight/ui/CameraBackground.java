package com.limelight.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.Collections;

public class CameraBackground {
    private final Context context;
    private CameraDevice camera;
    private HandlerThread thread;
    private Handler handler;

    public CameraBackground(Context context) {
        this.context = context;
    }

    public void start(final Surface surface) {
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        close();

        thread = new HandlerThread("cameraBackground");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            CameraManager mgr = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = cid;
                    break;
                }
            }
            if (id == null) return;

            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    camera = cam;
                    try {
                        final CaptureRequest.Builder b =
                                cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        cam.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try { s.setRepeatingRequest(b.build(), null, handler); }
                                        catch (Exception e) { e.printStackTrace(); }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {}
                                }, handler);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); }
                @Override public void onError(CameraDevice cam, int error) { cam.close(); }
            }, handler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void close() {
        if (camera != null) { camera.close(); camera = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }
}