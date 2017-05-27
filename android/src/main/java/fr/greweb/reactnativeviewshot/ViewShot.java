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
import android.opengl.GLES20;
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
import java.lang.reflect.InvocationTargetException;
import  java.nio.IntBuffer;

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
                final SurfaceView sw = (SurfaceView) ((ViewGroup) view).getChildAt(0);
                
                try {
                    final Object capturedSignal = Class.forName("com.oney.WebRTCModule.SurfaceViewRenderer")
                    .getMethod("getCapturedSignal")
                    .invoke(sw);
                    
                    Class.forName("com.oney.WebRTCModule.SurfaceViewRenderer")
                    .getMethod("createBitmapFromFrame")
                    .invoke(sw);
                    
                    Log.d("RNViewShotHack", "Waiting for captured done");
                    synchronized (capturedSignal) {
                        capturedSignal.wait();
                        Log.d("RNViewShotHack", "Captured done waiting");
                    }
                    
                    Bitmap capturedBitmap = (Bitmap) Class.forName("com.oney.WebRTCModule.SurfaceViewRenderer")
                    .getMethod("getCapturedBitmap")
                    .invoke(sw);
                    
                    os = new ByteArrayOutputStream();
                    capturedBitmap.compress(format, (int)(100.0 * quality), os);
                    byte[] bytes = ((ByteArrayOutputStream) os).toByteArray();
                    final String data = "data:image/"+extension+";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                    promise.resolve(data);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e){
                    promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Class not found runtime error");
                }
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
}
