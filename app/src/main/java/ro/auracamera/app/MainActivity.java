package ro.auracamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.*;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends Activity {
    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader reader;
    private Handler bg;
    private String cameraId;
    private int exposure = 0;
    private int effect = CaptureRequest.CONTROL_EFFECT_MODE_OFF;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("AURA Camera v3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0,18,0,18);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        preview = new TextureView(this);
        root.addView(preview, new LinearLayout.LayoutParams(-1,0,1f));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Mașină","Selfie","Portret","Noapte","TikTok"};
        for (String n : names) {
            Button btn = new Button(this);
            btn.setText(n);
            btn.setOnClickListener(v -> applyPreset(n));
            presets.addView(btn);
        }
        scroll.addView(presets);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,-2));

        Button shutter = new Button(this);
        shutter.setText("FOTOGRAFIAZĂ");
        shutter.setTextSize(18);
        shutter.setOnClickListener(v -> takePhoto());
        root.addView(shutter, new LinearLayout.LayoutParams(-1,-2));

        setContentView(root);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){ startCamera(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){ return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s){}
        });
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
            return;
        }
        try {
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);
            for(String id:cm.getCameraIdList()) {
                CameraCharacteristics c=cm.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK){ cameraId=id; break; }
            }
            HandlerThread t=new HandlerThread("aura-camera"); t.start(); bg=new Handler(t.getLooper());
            reader=ImageReader.newInstance(1920,1080,android.graphics.ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(r -> saveImage(r.acquireNextImage()), bg);
            cm.openCamera(cameraId,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){ camera=c; createPreview(); }
                public void onDisconnected(CameraDevice c){ c.close(); }
                public void onError(CameraDevice c,int e){ c.close(); }
            },bg);
        } catch(Exception e){ toast(e.getMessage()); }
    }

    private void createPreview(){
        try {
            SurfaceTexture st=preview.getSurfaceTexture(); st.setDefaultBufferSize(1920,1080);
            Surface surface=new Surface(st);
            previewBuilder=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){ session=s; updatePreview(); }
                public void onConfigureFailed(CameraCaptureSession s){ toast("Camera config failed"); }
            },bg);
        } catch(Exception e){ toast(e.getMessage()); }
    }

    private void applyPreset(String p){
        effect=CaptureRequest.CONTROL_EFFECT_MODE_OFF; exposure=0;
        if(p.equals("Mașină")) exposure=1;
        else if(p.equals("Selfie")) exposure=2;
        else if(p.equals("Portret")) exposure=1;
        else if(p.equals("Noapte")) exposure=3;
        else if(p.equals("TikTok")) { exposure=1; effect=CaptureRequest.CONTROL_EFFECT_MODE_OFF; }
        updatePreview(); toast("Preset: "+p);
    }

    private void updatePreview(){
        try {
            previewBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,exposure);
            previewBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE,effect);
            session.setRepeatingRequest(previewBuilder.build(),null,bg);
        } catch(Exception ignored){}
    }

    private void takePhoto(){
        try {
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,exposure);
            b.set(CaptureRequest.JPEG_ORIENTATION,90);
            session.capture(b.build(),null,bg);
        } catch(Exception e){ toast(e.getMessage()); }
    }

    private void saveImage(Image image){
        if(image==null) return;
        try {
            ByteBuffer buf=image.getPlanes()[0].getBuffer(); byte[] data=new byte[buf.remaining()]; buf.get(data);
            ContentValues v=new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,"AURA_"+System.currentTimeMillis()+".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AURA Camera");
            Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            if(uri!=null){ try(OutputStream os=getContentResolver().openOutputStream(uri)){ os.write(data); } }
            runOnUiThread(() -> toast("Fotografie salvată"));
        } catch(Exception e){ runOnUiThread(() -> toast("Eroare salvare")); }
        finally { image.close(); }
    }

    private void toast(String s){ runOnUiThread(() -> Toast.makeText(this,s==null?"Eroare":s,Toast.LENGTH_SHORT).show()); }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){ super.onRequestPermissionsResult(r,p,g); if(r==7&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED) startCamera(); }
    @Override protected void onDestroy(){ super.onDestroy(); if(session!=null) session.close(); if(camera!=null) camera.close(); }
}
