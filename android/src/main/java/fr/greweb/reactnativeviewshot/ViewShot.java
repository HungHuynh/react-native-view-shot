package fr.greweb.reactnativeviewshot;

import javax.annotation.Nullable;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.opengl.GLSurfaceView;
import android.opengl.GLException;
import android.widget.ScrollView;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.UIBlock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import 	java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL10;

/**
 * Snapshot utility class allow to screenshot a view.
 */

public class ViewShot implements UIBlock {
    static final String ERROR_UNABLE_TO_SNAPSHOT = "E_UNABLE_TO_SNAPSHOT";

    private int tag;
    private String extension;
    private Bitmap.CompressFormat format;
    private double quality;
    private Integer width;
    private Integer height;
    private File output;
    private String result;
    private Promise promise;
    private Boolean snapshotContentContainer;

    public ViewShot(
      int tag,
      String extension,
      Bitmap.CompressFormat format,
      double quality,
      @Nullable Integer width,
      @Nullable Integer height,
      File output,
      String result,
      Boolean snapshotContentContainer,
      Promise promise) {
        this.tag = tag;
        this.extension = extension;
        this.format = format;
        this.quality = quality;
        this.width = width;
        this.height = height;
        this.output = output;
        this.result = result;
        this.snapshotContentContainer = snapshotContentContainer;
        this.promise = promise;
    }

    private interface BitmapReadyCallbacks{
        void onBitmapReady(Bitmap bitmap);
    }

    @Override
    public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
        OutputStream os = null;
        View view = nativeViewHierarchyManager.resolveView(tag);
        if (view == null) {
            promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "No view found with reactTag: "+tag);
            return;
        }
        try {
            // Hard code solution for webrtc RTCView
            if (view.getClass().getName().equals("com.oney.WebRTCModule.WebRTCView")) {
                Log.i("RNViewShot", "-----------------------WebRTCView shot");
                final SurfaceView sw = (SurfaceView) ((ViewGroup) view).getChildAt(0);
                Log.i("RNViewShot", "-----------------------"+sw.toString());
                final BitmapReadyCallbacks bitmapReadyCallbacks = new BitmapReadyCallbacks() {
                    @Override
                    public void onBitmapReady(Bitmap bitmap) {
                        OutputStream os = new ByteArrayOutputStream();
                        bitmap.compress(format, (int)(100.0 * quality), os);
                        byte[] bytes = ((ByteArrayOutputStream) os).toByteArray();
                        final String data = "data:image/"+extension+";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                        promise.resolve(data);
                    }
                };

                sw.post(new Runnable() { // TODO Add cast for view variable to GLSurfaceView
                    @Override
                    public void run() {
                        runOnRenderThread(new Runable () {
                            Log.i("RNViewShot","-----------------------in SurfView queue");
                                EGL10 egl = (EGL10) EGLContext.getEGL();
                                GL10 gl = (GL10) egl.eglGetCurrentContext().getGL();
                                final Bitmap snapshotBitmap = createBitmapFromGLSurface(0, 0, sw.getWidth(), sw.getHeight(), gl);
                            Log.i("RNViewShot","-----------------------Bitmap created");

                                //runOnUiThread(new Runnable() {
                                //    @Override
                                //    public void run() {
                            bitmapReadyCallbacks.onBitmapReady(snapshotBitmap);
                                //    }
                                //});
                        });
                    }
                });
            }
            else {
                if ("file".equals(result)) {
                    os = new FileOutputStream(output);
                    captureView(view, os);
                    String uri = Uri.fromFile(output).toString();
                    promise.resolve(uri);
                } else if ("base64".equals(result)) {
                    os = new ByteArrayOutputStream();
                    captureView(view, os);
                    byte[] bytes = ((ByteArrayOutputStream) os).toByteArray();
                    String data = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    promise.resolve(data);
                } else if ("data-uri".equals(result)) {
                    os = new ByteArrayOutputStream();
                    captureView(view, os);
                    byte[] bytes = ((ByteArrayOutputStream) os).toByteArray();
                    String data = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    data = "data:image/" + extension + ";base64," + data;
                    promise.resolve(data);
                } else {
                    promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Unsupported result: " + result + ". Try one of: file | base64 | data-uri");
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Failed to capture view snapshot");
        }
        finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Screenshot a view and return the captured bitmap.
     * @param view the view to capture
     * @return the screenshot or null if it failed.
     */
    private void captureView (View view, OutputStream os) {
        int w = view.getWidth();
        int h = view.getHeight();

        if (w <= 0 || h <= 0) {
            throw new RuntimeException("Impossible to snapshot the view: view is invalid");
        }

        //evaluate real height
        if (snapshotContentContainer) {
            h=0;
            ScrollView scrollView = (ScrollView)view;
            for (int i = 0; i < scrollView.getChildCount(); i++) {
                h += scrollView.getChildAt(i).getHeight();
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        view.draw(c);

        if (width != null && height != null && (width != w || height != h)) {
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }
        if (bitmap == null) {
            throw new RuntimeException("Impossible to snapshot the view");
        }
        bitmap.compress(format, (int)(100.0 * quality), os);
    }


    /* Antonio Edit Start */

    // from other answer in this question
    private Bitmap createBitmapFromGLSurface(int x, int y, int w, int h, GL10 gl) {

        int bitmapBuffer[] = new int[w * h];
        int bitmapSource[] = new int[w * h];
        IntBuffer intBuffer = IntBuffer.wrap(bitmapBuffer);
        intBuffer.position(0);

        Log.d("RNViewShot_GLSurface", "____________________" + x + "," + y + "," + w + "," + h + "," + pendingFrame)

        try {
            gl.glReadPixels(x, y, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, intBuffer);
            int offset1, offset2;
            for (int i = 0; i < h; i++) {
                offset1 = i * w;
                offset2 = (h - i - 1) * w;
                for (int j = 0; j < w; j++) {
                    int texturePixel = bitmapBuffer[offset1 + j];
                    int blue = (texturePixel >> 16) & 0xff;
                    int red = (texturePixel << 16) & 0x00ff0000;
                    int pixel = 0xFF00FF; //(texturePixel & 0xff00ff00) | red | blue;
                    bitmapSource[offset2 + j] = pixel;
                }
            }
        } catch (GLException e) {
            Log.e("RNViewShot", "createBitmapFromGLSurface: " + e.getMessage(), e);
            return null;
        }

        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
    }

    /* Antonio Edit End */
}
