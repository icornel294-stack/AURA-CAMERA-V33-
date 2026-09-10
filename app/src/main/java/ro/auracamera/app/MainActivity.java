package ro.auracamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.hardware.camera2.params.ScalerAvailableStreamConfigurations;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.view.*;
import android.widget.*;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(38, 255, 142);
    private static final int DARK = Color.rgb(8, 12, 11);
    private static final int PANEL = Color.rgb(18, 24, 22);

    private FrameLayout previewFrame;
    private TextureView preview;
    private GridOverlay gridOverlay;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader reader;
    private Handler bg;
    private HandlerThread bgThread;
    private String cameraId;
    private boolean front = false;
    private boolean flash = false;
    private boolean grid = true;
    private int timerSeconds = 0;
    private int exposure = 0;
    private float zoom = 1f;
    private Rect activeArray;
    private int sensorOrientation = 90;
    private String activePreset = "Mașină";
    private ImageView lastThumb;
    private TextView presetTitle;
    private TextView presetSubtitle;
    private TextView settingsText;
    private final Map<String, LinearLayout> modeViews = new HashMap<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView txt(String s, float sp, int color){
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int stroke){
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius));
        if(stroke > 0) g.setStroke(dp(stroke), strokeColor); return g;
    }

    private void buildUi(){
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView brand = txt("AURA CAMERA", 20, Color.WHITE); brand.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView flashBtn = iconButton("⚡");
        flashBtn.setOnClickListener(v -> { flash = !flash; updatePreview(); flashBtn.setTextColor(flash ? GREEN : Color.WHITE); });
        top.addView(flashBtn);
        TextView switchBtn = iconButton("↻");
        switchBtn.setOnClickListener(v -> switchCamera());
        top.addView(switchBtn);
        TextView settingsBtn = iconButton("⚙");
        settingsBtn.setOnClickListener(v -> showSettings(settingsBtn));
        top.addView(settingsBtn);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(58)));

        previewFrame = new FrameLayout(this);
        preview = new TextureView(this);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1,-1));
        gridOverlay = new GridOverlay(this); gridOverlay.setVisibility(grid ? View.VISIBLE : View.GONE);
        previewFrame.addView(gridOverlay, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(dp(14), dp(12), dp(14), dp(12));
        hud.setBackground(rounded(0xB0111715, 14, 0x334FFFFFF, 1));
        presetTitle = txt("MAȘINĂ", 23, Color.WHITE); presetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        presetSubtitle = txt("Exterior · AURA Auto", 13, 0xFFB7C3BE);
        settingsText = txt("AUTO  •  EV +0.3  •  1×  •  JPEG MAX", 12, GREEN);
        hud.addView(txt("PRESET ACTIV", 10, GREEN));
        hud.addView(presetTitle); hud.addView(presetSubtitle); hud.addView(settingsText);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        hp.setMargins(dp(12), dp(12), dp(12), 0); previewFrame.addView(hud, hp);

        SeekBar zoomBar = new SeekBar(this); zoomBar.setMax(40); zoomBar.setProgress(0);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean from){ zoom = 1f + p/10f; applyZoom(); updatePreview(); updateHud(); }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        });
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(dp(180), dp(44), Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        zp.setMargins(0,0,0,dp(10)); previewFrame.addView(zoomBar,zp);

        root.addView(previewFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(dp(4), dp(6), dp(4), dp(4));
        String[] names = {"Mașină","Selfie","Portret","Noapte","TikTok"};
        int[] types = {1,2,3,4,5};
        for(int i=0;i<names.length;i++){
            LinearLayout item = makeMode(names[i], types[i]);
            modes.addView(item, new LinearLayout.LayoutParams(0, dp(70), 1f));
            modeViews.put(names[i], item);
        }
        root.addView(modes, new LinearLayout.LayoutParams(-1, dp(76)));

        FrameLayout controls = new FrameLayout(this); controls.setPadding(dp(18), dp(6), dp(18), dp(10));
        lastThumb = new ImageView(this); lastThumb.setScaleType(ImageView.ScaleType.CENTER_CROP); lastThumb.setBackground(rounded(PANEL, 12, 0x556FFFFFF, 1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER_VERTICAL|Gravity.START); controls.addView(lastThumb,lp);

        ShutterView shutter = new ShutterView(this); shutter.setOnClickListener(v -> triggerPhoto(shutter));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER); controls.addView(shutter,sp);

        TextView pro = txt("PRO", 12, Color.WHITE); pro.setGravity(Gravity.CENTER); pro.setTypeface(Typeface.DEFAULT_BOLD); pro.setBackground(rounded(PANEL, 12, 0x556FFFFFF, 1));
        pro.setOnClickListener(v -> showSettings(pro));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER_VERTICAL|Gravity.END); controls.addView(pro,pp);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(96)));

        setContentView(root);
        setPresetSelected("Mașină");

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){ startCamera(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){ closeCamera(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s){}
        });
    }

    private TextView iconButton(String s){
        TextView v = txt(s, 22, Color.WHITE); v.setGravity(Gravity.CENTER); v.setBackground(rounded(PANEL, 12, 0x334FFFFFF, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(42),dp(42)); p.setMargins(dp(5),0,0,0); v.setLayoutParams(p); return v;
    }

    private LinearLayout makeMode(String name, int iconType){
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER); l.setPadding(dp(2),dp(3),dp(2),dp(3));
        ModeIconView icon = new ModeIconView(this, iconType); l.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView label = txt(name.toUpperCase(new Locale("ro")), 9, 0xFFB8C1BD); label.setGravity(Gravity.CENTER); label.setTag("label");
        l.addView(label,new LinearLayout.LayoutParams(-1,dp(24)));
        l.setOnClickListener(v -> { applyPreset(name); if(name.equals("Selfie") && !front) switchCamera(); else if(!name.equals("Selfie") && front) switchCamera(); });
        return l;
    }

    private void setPresetSelected(String p){
        activePreset = p;
        for(Map.Entry<String,LinearLayout> e: modeViews.entrySet()){
            boolean sel = e.getKey().equals(p); LinearLayout l=e.getValue(); l.setBackground(sel ? rounded(0x1826FF8E,12,GREEN,1) : null);
            if(l.getChildCount()>1){ ((TextView)l.getChildAt(1)).setTextColor(sel?GREEN:0xFFB8C1BD); }
            if(l.getChildAt(0) instanceof ModeIconView){ ((ModeIconView)l.getChildAt(0)).selected=sel; l.getChildAt(0).invalidate(); }
        }
        updateHud();
    }

    private void applyPreset(String p){
        setPresetSelected(p);
        exposure = 0;
        if(p.equals("Mașină")) exposure=1;
        else if(p.equals("Selfie")) exposure=1;
        else if(p.equals("Portret")) exposure=1;
        else if(p.equals("Noapte")) exposure=2;
        else if(p.equals("TikTok")) exposure=1;
        updatePreview();
    }

    private void updateHud(){
        if(presetTitle==null)return;
        presetTitle.setText(activePreset.toUpperCase(new Locale("ro")));
        String sub="Exterior · AURA Auto";
        if(activePreset.equals("Selfie")) sub="Natural · Skin Tone";
        else if(activePreset.equals("Portret")) sub="Subject · Depth Look";
        else if(activePreset.equals("Noapte")) sub="Low Light · Noise Control";
        else if(activePreset.equals("TikTok")) sub="Social · Vertical Ready";
        presetSubtitle.setText(sub);
        String ev = exposure==0?"0.0":(exposure>0?"+"+String.format(Locale.US,"%.1f",exposure*0.3):String.format(Locale.US,"%.1f",exposure*0.3));
        settingsText.setText("AUTO  •  EV "+ev+"  •  "+String.format(Locale.US,"%.1f×",zoom)+"  •  JPEG MAX");
    }

    private void showSettings(View anchor){
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16),dp(14),dp(16),dp(14)); panel.setBackground(rounded(0xFA101614,18,GREEN,1));
        TextView h=txt("SETĂRI AURA PRO",16,Color.WHITE); h.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(h,new LinearLayout.LayoutParams(-1,dp(38)));
        Switch gridSw = new Switch(this); gridSw.setText("Grilă 3×3"); gridSw.setTextColor(Color.WHITE); gridSw.setChecked(grid); gridSw.setOnCheckedChangeListener((b,c)->{grid=c;gridOverlay.setVisibility(c?View.VISIBLE:View.GONE);}); panel.addView(gridSw);
        TextView timer = txt("Timer: "+timerSeconds+"s  (apasă pentru 0/3/10)",14,Color.WHITE); timer.setPadding(0,dp(12),0,dp(12));
        timer.setOnClickListener(v->{ timerSeconds=timerSeconds==0?3:timerSeconds==3?10:0; timer.setText("Timer: "+timerSeconds+"s  (apasă pentru 0/3/10)"); }); panel.addView(timer);
        TextView ev = txt("Expunere",14,Color.WHITE); panel.addView(ev);
        SeekBar evBar=new SeekBar(this); evBar.setMax(6); evBar.setProgress(exposure+3); evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean f){ exposure=p-3; updatePreview(); updateHud(); }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        }); panel.addView(evBar,new LinearLayout.LayoutParams(-1,dp(46)));
        TextView note=txt("ISO • Shutter • Focus • White Balance: AUTO inteligent\nZoom: glisor peste imagine • Flash: ⚡ • Cameră: ↻",12,0xFFBAC4C0); panel.addView(note);
        PopupWindow w=new PopupWindow(panel,dp(310),-2,true); w.setOutsideTouchable(true); w.setElevation(dp(12)); w.showAtLocation(anchor,Gravity.TOP|Gravity.END,dp(12),dp(70));
    }

    private void startBg(){ if(bgThread==null){ bgThread=new HandlerThread("aura-camera"); bgThread.start(); bg=new Handler(bgThread.getLooper()); } }
    private void stopBg(){ if(bgThread!=null){ bgThread.quitSafely(); bgThread=null; bg=null; } }

    private void startCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.CAMERA},7); return; }
        startBg();
        try{
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);
            cameraId=null;
            for(String id:cm.getCameraIdList()){
                CameraCharacteristics c=cm.getCameraCharacteristics(id); Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null && ((front&&facing==CameraCharacteristics.LENS_FACING_FRONT)||(!front&&facing==CameraCharacteristics.LENS_FACING_BACK))){
                    cameraId=id; activeArray=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE); Integer so=c.get(CameraCharacteristics.SENSOR_ORIENTATION); sensorOrientation=so==null?90:so;
                    Size size=chooseJpegSize(c); reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),android.graphics.ImageFormat.JPEG,2);
                    reader.setOnImageAvailableListener(r->{ Image im=r.acquireLatestImage(); if(im!=null) processAndSave(im); },bg); break;
                }
            }
            if(cameraId==null){ toast("Camera nu este disponibilă"); return; }
            cm.openCamera(cameraId,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){ camera=c; createPreview(); }
                public void onDisconnected(CameraDevice c){ c.close(); camera=null; }
                public void onError(CameraDevice c,int e){ c.close(); camera=null; toast("Eroare cameră: "+e); }
            },bg);
        }catch(Exception e){ toast("Pornire cameră: "+e.getMessage()); }
    }

    private Size chooseJpegSize(CameraCharacteristics c){
        try{
            android.hardware.camera2.params.StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes=map==null?null:map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            if(sizes!=null&&sizes.length>0){
                Arrays.sort(sizes,(a,b)->Long.compare((long)b.getWidth()*b.getHeight(),(long)a.getWidth()*a.getHeight()));
                for(Size s:sizes) if((long)s.getWidth()*s.getHeight()<=16000000L) return s;
                return sizes[sizes.length-1];
            }
        }catch(Exception ignored){}
        return new Size(1920,1080);
    }

    private void createPreview(){
        try{
            SurfaceTexture st=preview.getSurfaceTexture(); if(st==null)return; st.setDefaultBufferSize(1920,1080); Surface surface=new Surface(st);
            previewBuilder=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); previewBuilder.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){ session=s; updatePreview(); }
                public void onConfigureFailed(CameraCaptureSession s){ toast("Configurarea camerei a eșuat"); }
            },bg);
        }catch(Exception e){ toast(e.getMessage()); }
    }

    private int clampExposure(int value){
        try{
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE); CameraCharacteristics c=cm.getCameraCharacteristics(cameraId);
            android.util.Range<Integer> r=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE); if(r!=null) return Math.max(r.getLower(),Math.min(r.getUpper(),value));
        }catch(Exception ignored){} return 0;
    }

    private void applyZoom(){
        if(previewBuilder==null||activeArray==null)return;
        float z=Math.max(1f,Math.min(5f,zoom)); int cw=(int)(activeArray.width()/z), ch=(int)(activeArray.height()/z);
        int l=activeArray.centerX()-cw/2,t=activeArray.centerY()-ch/2; previewBuilder.set(CaptureRequest.SCALER_CROP_REGION,new Rect(l,t,l+cw,t+ch));
    }

    private void updatePreview(){
        if(previewBuilder==null||session==null)return;
        try{
            previewBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,clampExposure(exposure));
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,flash&&!front?CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH:CaptureRequest.CONTROL_AE_MODE_ON);
            applyZoom(); session.setRepeatingRequest(previewBuilder.build(),null,bg);
        }catch(Exception ignored){}
    }

    private void switchCamera(){ front=!front; closeCamera(); startCamera(); }

    private void closeCamera(){
        try{ if(session!=null)session.close(); }catch(Exception ignored){} session=null;
        try{ if(camera!=null)camera.close(); }catch(Exception ignored){} camera=null;
        try{ if(reader!=null)reader.close(); }catch(Exception ignored){} reader=null;
    }

    private void triggerPhoto(ShutterView shutter){
        if(timerSeconds<=0){ takePhoto(); return; }
        final int[] left={timerSeconds}; TextView countdown=txt(String.valueOf(left[0]),54,Color.WHITE); countdown.setGravity(Gravity.CENTER); countdown.setTypeface(Typeface.DEFAULT_BOLD); countdown.setBackgroundColor(0x66000000);
        previewFrame.addView(countdown,new FrameLayout.LayoutParams(-1,-1)); Handler h=new Handler(getMainLooper());
        Runnable r=new Runnable(){ public void run(){ left[0]--; if(left[0]<=0){ previewFrame.removeView(countdown); takePhoto(); } else { countdown.setText(String.valueOf(left[0])); h.postDelayed(this,1000); } }}; h.postDelayed(r,1000);
    }

    private int jpegOrientation(){
        int rotation=getWindowManager().getDefaultDisplay().getRotation(); int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
        return front ? (sensorOrientation + degrees)%360 : (sensorOrientation - degrees + 360)%360;
    }

    private void takePhoto(){
        if(camera==null||session==null||reader==null)return;
        try{
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO); b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,clampExposure(exposure));
            b.set(CaptureRequest.CONTROL_AE_MODE,flash&&!front?CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH:CaptureRequest.CONTROL_AE_MODE_ON);
            if(activeArray!=null){ float z=Math.max(1f,Math.min(5f,zoom)); int cw=(int)(activeArray.width()/z),ch=(int)(activeArray.height()/z);int l=activeArray.centerX()-cw/2,t=activeArray.centerY()-ch/2;b.set(CaptureRequest.SCALER_CROP_REGION,new Rect(l,t,l+cw,t+ch)); }
            b.set(CaptureRequest.JPEG_ORIENTATION,jpegOrientation()); b.set(CaptureRequest.JPEG_QUALITY,(byte)96);
            session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){ @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){ updatePreview(); }},bg);
        }catch(Exception e){ toast("Fotografie: "+e.getMessage()); }
    }

    private void processAndSave(Image image){
        try{
            ByteBuffer buf=image.getPlanes()[0].getBuffer(); byte[] raw=new byte[buf.remaining()]; buf.get(raw);
            Bitmap src=BitmapFactory.decodeByteArray(raw,0,raw.length); if(src==null) throw new Exception("Imagine invalidă");
            Bitmap out=applyLook(src,activePreset);
            ContentValues v=new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME,"AURA_"+activePreset.toUpperCase(new Locale("ro")).replace('Ș','S').replace('Ă','A')+"_"+System.currentTimeMillis()+".jpg"); v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            if(android.os.Build.VERSION.SDK_INT>=29) v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AURA Camera");
            Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v); if(uri==null)throw new Exception("MediaStore");
            try(OutputStream os=getContentResolver().openOutputStream(uri)){ out.compress(Bitmap.CompressFormat.JPEG,96,os); }
            Bitmap thumb=Bitmap.createScaledBitmap(out,160,120,true); runOnUiThread(()->{ lastThumb.setImageBitmap(thumb); toast("Salvat · "+activePreset); });
            if(out!=src)out.recycle(); src.recycle();
        }catch(Exception e){ runOnUiThread(()->toast("Eroare salvare: "+e.getMessage())); }
        finally{ image.close(); }
    }

    private Bitmap applyLook(Bitmap src,String mode){
        float sat=1f, contrast=1f, bright=0f;
        if(mode.equals("Mașină")){ sat=1.10f; contrast=1.16f; bright=2f; }
        else if(mode.equals("Selfie")){ sat=1.03f; contrast=1.04f; bright=8f; }
        else if(mode.equals("Portret")){ sat=0.98f; contrast=1.10f; bright=5f; }
        else if(mode.equals("Noapte")){ sat=1.08f; contrast=1.07f; bright=18f; }
        else if(mode.equals("TikTok")){ sat=1.20f; contrast=1.14f; bright=5f; }
        Bitmap out=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888); Canvas c=new Canvas(out); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        ColorMatrix m=new ColorMatrix(); m.setSaturation(sat); ColorMatrix cb=new ColorMatrix(new float[]{contrast,0,0,0,bright,0,contrast,0,0,bright,0,0,contrast,0,bright,0,0,0,1,0}); m.postConcat(cb); p.setColorFilter(new ColorMatrixColorFilter(m)); c.drawBitmap(src,0,0,p);
        if(mode.equals("Mașină")||mode.equals("TikTok")){ Paint overlay=new Paint(); overlay.setColor(0x1000FF88); c.drawRect(0,0,out.getWidth(),out.getHeight(),overlay); }
        return out;
    }

    private void toast(String s){ runOnUiThread(()->Toast.makeText(this,s==null?"Eroare":s,Toast.LENGTH_SHORT).show()); }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){ super.onRequestPermissionsResult(r,p,g); if(r==7&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera(); }
    @Override protected void onPause(){ super.onPause(); closeCamera(); stopBg(); }
    @Override protected void onResume(){ super.onResume(); if(preview!=null&&preview.isAvailable())startCamera(); }

    static class GridOverlay extends View{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); GridOverlay(android.content.Context c){super(c);p.setColor(0x88FFFFFF);p.setStrokeWidth(1f);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();c.drawLine(w/3,0,w/3,h,p);c.drawLine(2*w/3,0,2*w/3,h,p);c.drawLine(0,h/3,w,h/3,p);c.drawLine(0,2*h/3,w,2*h/3,p);}
    }

    static class ShutterView extends View{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); ShutterView(android.content.Context c){super(c);setClickable(true);}
        @Override protected void onDraw(Canvas c){float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-3;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);p.setColor(Color.WHITE);c.drawCircle(cx,cy,r,p);p.setStyle(Paint.Style.FILL);p.setColor(0xFFF5F7F6);c.drawCircle(cx,cy,r-9,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(GREEN);c.drawCircle(cx,cy,r-15,p);}
    }

    static class ModeIconView extends View{
        final int type; boolean selected=false; Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); ModeIconView(android.content.Context c,int t){super(c);type=t;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);p.setColor(selected?GREEN:0xFFC2CBC7);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.6f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);float w=getWidth(),h=getHeight();
            if(type==1){ RectF body=new RectF(w*.14f,h*.42f,w*.86f,h*.72f);c.drawRoundRect(body,6,6,p);c.drawLine(w*.25f,h*.42f,w*.36f,h*.27f,p);c.drawLine(w*.36f,h*.27f,w*.66f,h*.27f,p);c.drawLine(w*.66f,h*.27f,w*.76f,h*.42f,p);c.drawCircle(w*.3f,h*.72f,w*.09f,p);c.drawCircle(w*.7f,h*.72f,w*.09f,p);}
            else if(type==2){c.drawCircle(w*.5f,h*.35f,w*.16f,p);c.drawArc(new RectF(w*.24f,h*.48f,w*.76f,h*.90f),205,130,false,p);c.drawLine(w*.72f,h*.15f,w*.86f,h*.15f,p);c.drawLine(w*.86f,h*.15f,w*.86f,h*.29f,p);}
            else if(type==3){c.drawCircle(w*.5f,h*.31f,w*.14f,p);c.drawArc(new RectF(w*.2f,h*.44f,w*.8f,h*.92f),205,130,false,p);c.drawRect(w*.08f,h*.08f,w*.92f,h*.92f,p);}
            else if(type==4){Path moon=new Path();moon.moveTo(w*.62f,h*.12f);moon.cubicTo(w*.30f,h*.18f,w*.24f,h*.70f,w*.66f,h*.82f);moon.cubicTo(w*.42f,h*.88f,w*.16f,h*.70f,w*.16f,h*.45f);moon.cubicTo(w*.16f,h*.22f,w*.36f,h*.06f,w*.62f,h*.12f);c.drawPath(moon,p);}
            else {c.drawLine(w*.55f,h*.17f,w*.55f,h*.66f,p);c.drawLine(w*.55f,h*.17f,w*.78f,h*.24f,p);c.drawCircle(w*.41f,h*.70f,w*.13f,p);c.drawCircle(w*.67f,h*.73f,w*.13f,p);}
        }
    }
}
