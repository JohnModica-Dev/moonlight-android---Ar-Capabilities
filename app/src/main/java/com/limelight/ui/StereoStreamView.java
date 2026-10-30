package com.limelight.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class StereoStreamView extends GLSurfaceView
        implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface Listener {
        void onVideoSurfaceReady(Surface surface);
        void onCameraSurfaceReady(Surface surface);
    }

    public static final int CAMERA_WIDTH = 1280;
    public static final int CAMERA_HEIGHT = 720;

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
                    "uniform vec2 uScale;\n" +
                    "uniform float uFlip;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);\n" +
                    "  vec2 tc = (uTexMatrix * aTexCoord).xy;\n" +
                    "  vTexCoord = mix(tc, vec2(1.0) - tc, uFlip);\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}\n";

    private static final float[] POSITIONS = { -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f };
    private static final float[] TEXCOORDS = {  0f,  0f,  1f,  0f,   0f, 1f,  1f, 1f };

    private final FloatBuffer positionBuffer = toBuffer(POSITIONS);
    private final FloatBuffer texCoordBuffer = toBuffer(TEXCOORDS);
    private final float[] videoMatrix = new float[16];
    private final float[] cameraMatrix = new float[16];
    private final Object frameLock = new Object();

    private SurfaceTexture videoTexture, cameraTexture;
    private Surface videoSurface, cameraSurface;
    private Listener listener;
    private boolean videoFrame, cameraFrame;      // new frame waiting
    private boolean videoReady, cameraReady;      // at least one frame received
    private volatile boolean cameraFlip;
    private int program, aPos, aTex, uTexMatrix, uScale, uFlip;
    private int videoTexId, cameraTexId;
    private int surfaceWidth, surfaceHeight;
    private float videoAspect = 16f / 9f;
    private float zoom = 1.0f; // how much of each half the stream screen fills

    public StereoStreamView(Context context) { super(context); init(); }
    public StereoStreamView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        Matrix.setIdentityM(videoMatrix, 0);
        Matrix.setIdentityM(cameraMatrix, 0);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setListener(Listener l) { listener = l; }
    public void setVideoSize(int width, int height) { videoAspect = (float) width / height; }
    public void setZoom(float z) { zoom = z; }
    public void setCameraFlip(boolean flip) { cameraFlip = flip; }

    private static FloatBuffer toBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static int createExternalTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPosition");
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uScale = GLES20.glGetUniformLocation(program, "uScale");
        uFlip = GLES20.glGetUniformLocation(program, "uFlip");

        videoTexId = createExternalTexture();
        videoTexture = new SurfaceTexture(videoTexId);
        videoTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoTexture);

        cameraTexId = createExternalTexture();
        cameraTexture = new SurfaceTexture(cameraTexId);
        cameraTexture.setDefaultBufferSize(CAMERA_WIDTH, CAMERA_HEIGHT);
        cameraTexture.setOnFrameAvailableListener(this);
        cameraSurface = new Surface(cameraTexture);

        videoReady = false;
        cameraReady = false;

        final Surface v = videoSurface;
        final Surface c = cameraSurface;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (listener != null) {
                    listener.onCameraSurfaceReady(c);
                    listener.onVideoSurfaceReady(v);
                }
            }
        });
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) {
            if (st == cameraTexture) cameraFrame = true;
            else videoFrame = true;
        }
        requestRender();
    }

    private void drawQuad(int texId, float[] matrix, float sx, float sy, float flip) {
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, matrix, 0);
        GLES20.glUniform2f(uScale, sx, sy);
        GLES20.glUniform1f(uFlip, flip);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        boolean vNew, cNew;
        synchronized (frameLock) {
            vNew = videoFrame;
            cNew = cameraFrame;
            videoFrame = false;
            cameraFrame = false;
        }
        if (vNew) {
            videoTexture.updateTexImage();
            videoTexture.getTransformMatrix(videoMatrix);
            videoReady = true;
        }

        if (cNew) {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(cameraMatrix);

            // 1.0f means normal size. 1.2f stretches the texture space out by 20% (zooming out)
            float zoomOutFactor = 1.0f;

            // 1. Move coordinate origin to the center of the texture (0.5, 0.5)
            Matrix.translateM(cameraMatrix, 0, 0.5f, 0.5f, 0.0f);

            // 2. Rotate 270 degrees counter-clockwise (90 degrees clockwise visually)
            Matrix.rotateM(cameraMatrix, 0, 270.0f, 0.0f, 0.0f, 1.0f);

            // 3. Apply scaling to zoom out (X and Y coordinates)
            Matrix.scaleM(cameraMatrix, 0, zoomOutFactor, zoomOutFactor, 1.0f);

            // 4. Move the origin back to the corner
            Matrix.translateM(cameraMatrix, 0, -0.5f, -0.5f, 0.0f);

            cameraReady = true;
        }



        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        int eyeW = surfaceWidth / 2;
        int eyeH = surfaceHeight;
        float eyeAspect = (float) eyeW / eyeH;

        // Stream screen: fit inside each half, then shrink by zoom
        float vsx, vsy;
        if (videoAspect > eyeAspect) { vsx = 1f; vsy = eyeAspect / videoAspect; }
        else { vsx = videoAspect / eyeAspect; vsy = 1f; }
        vsx *= zoom;
        vsy *= zoom;

        // Camera: cover each half completely
        float camAspect = (float) CAMERA_WIDTH / CAMERA_HEIGHT;
        float csx, csy;
        if (camAspect > eyeAspect) { csx = camAspect / eyeAspect; csy = 1f; }
        else { csx = 1f; csy = eyeAspect / camAspect; }

        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positionBuffer);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        for (int eye = 0; eye < 2; eye++) {
            GLES20.glViewport(eye * eyeW, 0, eyeW, eyeH);
            if (cameraReady) {
                drawQuad(cameraTexId, cameraMatrix, csx, csy, cameraFlip ? 1f : 0f);
            }
            if (videoReady) {
                drawQuad(videoTexId, videoMatrix, vsx, vsy, 0f);
            }
        }

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }
}