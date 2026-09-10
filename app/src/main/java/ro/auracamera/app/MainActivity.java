package ro.auracamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.*;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int GREEN = Color.rgb(55, 238, 125);
    private static final int BLUE = Color.rgb(59, 130, 246);
    private static final int PURPLE = Color.rgb(168, 85, 247);
    private static final int DARK = Color.rgb(4, 10, 16);
    private static final int PANEL = Color.rgb(15, 24, 35);

    private FrameLayout previewFrame;
    private TextureView preview;
    private GuideOverlay guideOverlay;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader reader;
    private Handler bg;
    private HandlerThread bgThread;
    private String cameraId;
    private CameraCharacteristics cameraChars;
    private boolean front = false, flash = false, grid = true;
    private int timerSeconds = 0, exposure = 0;
    private float zoom = 1f;
    private Rect activeArray;
    private int sensorOrientation = 90;
    private String activePreset = "Mașină";
    private ImageView lastThumb;
    private TextView presetTitle, presetSubtitle, settingsText, guideText, angleText;
    private SeekBar exposureBar;
    private final Map<String, LinearLayout> modeViews = new HashMap<>();

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float azimuth, pitch, roll, lastPitch, lastRoll, motionScore;
    private boolean haveOrientation = false, carCalibrated = false;
    private float carReferenceAzimuth = 0f;
    private Face[] lastFaces = new Face[0];
    private Integer lastIso = null;
    private Long lastExposureNs = null;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        buildUi();
    }

    private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView txt(String s, float sp, int color){ TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t; }
    private GradientDrawable rounded(int color,int radius,int strokeColor,int stroke){ GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke>0)g.setStroke(dp(stroke),strokeColor);return g; }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(DARK);

        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(14),dp(8),dp(14),dp(8));
        LogoView logo=new LogoView(this); top.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brandBox=new LinearLayout(this); brandBox.setOrientation(LinearLayout.VERTICAL); brandBox.setPadding(dp(8),0,0,0);
        TextView brand=txt("AURA Camera",20,Color.WHITE); brand.setTypeface(Typeface.DEFAULT_BOLD);
        TextView tagline=txt("Fotografii mai bune. În orice scenă.",10,0xFF8FA6C0);
        brandBox.addView(brand); brandBox.addView(tagline); top.addView(brandBox,new LinearLayout.LayoutParams(0,dp(46),1f));
        TextView flashBtn=iconButton("⚡"); flashBtn.setOnClickListener(v->{flash=!flash;updatePreview();flashBtn.setTextColor(flash?GREEN:Color.WHITE);}); top.addView(flashBtn);
        TextView switchBtn=iconButton("↻"); switchBtn.setOnClickListener(v->switchCamera()); top.addView(switchBtn);
        TextView settingsBtn=iconButton("⚙"); settingsBtn.setOnClickListener(v->showSettings(settingsBtn)); top.addView(settingsBtn);
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(62)));

        previewFrame=new FrameLayout(this); preview=new TextureView(this); previewFrame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        guideOverlay=new GuideOverlay(this); previewFrame.addView(guideOverlay,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout guideCard=new LinearLayout(this); guideCard.setOrientation(LinearLayout.VERTICAL); guideCard.setPadding(dp(12),dp(10),dp(12),dp(10)); guideCard.setBackground(rounded(0xCC111A25,14,0x665B6B7A,1));
        presetTitle=txt("MAȘINĂ",16,Color.WHITE); presetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        presetSubtitle=txt("Față 3/4 recomandat · 30–45° · ideal 35°",11,0xFFC4CDD7);
        guideText=txt("Țintește drept fața mașinii și atinge aici pentru calibrare.",12,Color.WHITE); guideText.setPadding(0,dp(4),0,0);
        settingsText=txt("AUTO • HDR • DETALII MAX",10,GREEN); settingsText.setPadding(0,dp(4),0,0);
        guideCard.addView(presetTitle); guideCard.addView(presetSubtitle); guideCard.addView(guideText); guideCard.addView(settingsText);
        guideCard.setOnClickListener(v->{ if(activePreset.equals("Mașină")&&haveOrientation){carReferenceAzimuth=azimuth;carCalibrated=true;guideOverlay.setCarAngle(0,true);updateSmartGuide();toast("Calibrare față mașină realizată");} });
        FrameLayout.LayoutParams gc=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP); gc.setMargins(dp(12),dp(12),dp(12),0); previewFrame.addView(guideCard,gc);

        angleText=txt("📐 35°",14,GREEN); angleText.setGravity(Gravity.CENTER); angleText.setTypeface(Typeface.DEFAULT_BOLD); angleText.setBackground(rounded(0xCC0B151E,12,GREEN,1));
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(92),dp(42),Gravity.END|Gravity.CENTER_VERTICAL); ap.setMargins(0,0,dp(12),0);previewFrame.addView(angleText,ap);

        root.addView(previewFrame,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout modes=new LinearLayout(this); modes.setGravity(Gravity.CENTER); modes.setPadding(dp(6),dp(5),dp(6),dp(3));
        String[] names={"Mașină","Selfie","Portret","Noapte","TikTok"}; int[] types={1,2,3,4,5};
        for(int i=0;i<names.length;i++){ LinearLayout item=makeMode(names[i],types[i]); modes.addView(item,new LinearLayout.LayoutParams(0,dp(70),1f)); modeViews.put(names[i],item); }
        root.addView(modes,new LinearLayout.LayoutParams(-1,dp(76)));

        LinearLayout expRow=new LinearLayout(this); expRow.setOrientation(LinearLayout.VERTICAL); expRow.setPadding(dp(18),0,dp(18),0);
        LinearLayout expTitle=new LinearLayout(this); TextView e1=txt("Expunere",12,Color.WHITE); TextView e2=txt("+0.0",12,Color.WHITE); e2.setGravity(Gravity.END); expTitle.addView(e1,new LinearLayout.LayoutParams(0,dp(24),1f));expTitle.addView(e2,new LinearLayout.LayoutParams(dp(60),dp(24)));
        exposureBar=new SeekBar(this); exposureBar.setMax(12); exposureBar.setProgress(6); exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean from){if(from){exposure=p-6;updatePreview();e2.setText(String.format(Locale.US,"%+.1f",exposure*0.3));}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        expRow.addView(expTitle); expRow.addView(exposureBar,new LinearLayout.LayoutParams(-1,dp(32))); root.addView(expRow,new LinearLayout.LayoutParams(-1,dp(56)));

        FrameLayout controls=new FrameLayout(this); controls.setPadding(dp(18),dp(4),dp(18),dp(8));
        lastThumb=new ImageView(this);lastThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);lastThumb.setBackground(rounded(PANEL,12,0x667A8795,1));controls.addView(lastThumb,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER_VERTICAL|Gravity.START));
        ShutterView shutter=new ShutterView(this);shutter.setOnClickListener(v->triggerPhoto());controls.addView(shutter,new FrameLayout.LayoutParams(dp(82),dp(82),Gravity.CENTER));
        TextView pro=txt("PRO",12,Color.WHITE);pro.setGravity(Gravity.CENTER);pro.setTypeface(Typeface.DEFAULT_BOLD);pro.setBackground(rounded(PANEL,14,0x667A8795,1));pro.setOnClickListener(v->showSettings(pro));controls.addView(pro,new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.CENTER_VERTICAL|Gravity.END));
        root.addView(controls,new LinearLayout.LayoutParams(-1,dp(94)));

        setContentView(root); setPresetSelected("Mașină");
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){startCamera();}public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}public boolean onSurfaceTextureDestroyed(SurfaceTexture s){closeCamera();return true;}public void onSurfaceTextureUpdated(SurfaceTexture s){}});
    }

    private TextView iconButton(String s){TextView v=txt(s,21,Color.WHITE);v.setGravity(Gravity.CENTER);v.setBackground(rounded(PANEL,12,0x667A8795,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(42),dp(42));p.setMargins(dp(5),0,0,0);v.setLayoutParams(p);return v;}

    private LinearLayout makeMode(String name,int iconType){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setGravity(Gravity.CENTER);l.setPadding(dp(2),dp(3),dp(2),dp(3));
        ModeIconView icon=new ModeIconView(this,iconType);l.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView label=txt(name,10,0xFFD0D6DE);label.setGravity(Gravity.CENTER);l.addView(label,new LinearLayout.LayoutParams(-1,dp(24)));
        l.setOnClickListener(v->{applyPreset(name);if(name.equals("Selfie")&&!front)switchCamera();else if(!name.equals("Selfie")&&front)switchCamera();});return l;
    }

    private void setPresetSelected(String p){
        activePreset=p; carCalibrated=false;
        for(Map.Entry<String,LinearLayout> e:modeViews.entrySet()){
            boolean sel=e.getKey().equals(p);LinearLayout l=e.getValue();l.setBackground(sel?rounded(0x331E6EFF,14,sel&&p.equals("Selfie")?PURPLE:BLUE,2):null);
            ((TextView)l.getChildAt(1)).setTextColor(sel?Color.WHITE:0xFFD0D6DE);((ModeIconView)l.getChildAt(0)).selected=sel;l.getChildAt(0).invalidate();
        }
        guideOverlay.setMode(p);updateHud();updateSmartGuide();
    }

    private void applyPreset(String p){
        setPresetSelected(p);
        if(p.equals("Mașină"))exposure=0; else if(p.equals("Selfie"))exposure=1; else if(p.equals("Portret"))exposure=0; else if(p.equals("Noapte"))exposure=1; else exposure=0;
        if(exposureBar!=null)exposureBar.setProgress(exposure+6);updatePreview();
    }

    private void updateHud(){
        if(presetTitle==null)return;presetTitle.setText(activePreset.toUpperCase(new Locale("ro")));
        if(activePreset.equals("Mașină"))presetSubtitle.setText("Față 3/4 recomandat · 30–45° · ideal 35°");
        else if(activePreset.equals("Selfie"))presetSubtitle.setText("Tonuri naturale · lumină echilibrată · poziție ghidată");
        else if(activePreset.equals("Portret"))presetSubtitle.setText("Subiect evidențiat · distanță și cadru ghidate");
        else if(activePreset.equals("Noapte"))presetSubtitle.setText("Mai multă lumină · zgomot redus · stabilitate");
        else presetSubtitle.setText("Vertical · culori puternice · spațiu pentru text");
        settingsText.setText("AUTO • AWB • NR HQ • DETALII MAX • JPEG 100");
    }

    private void showSettings(View anchor){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),dp(14),dp(16),dp(14));panel.setBackground(rounded(0xFA0C1420,18,BLUE,1));
        TextView h=txt("SETĂRI AURA PRO",16,Color.WHITE);h.setTypeface(Typeface.DEFAULT_BOLD);panel.addView(h,new LinearLayout.LayoutParams(-1,dp(38)));
        Switch gridSw=new Switch(this);gridSw.setText("Grilă 3×3");gridSw.setTextColor(Color.WHITE);gridSw.setChecked(grid);gridSw.setOnCheckedChangeListener((b,c)->{grid=c;guideOverlay.grid=c;guideOverlay.invalidate();});panel.addView(gridSw);
        TextView timer=txt("Timer: "+timerSeconds+"s  (0 / 3 / 10)",14,Color.WHITE);timer.setPadding(0,dp(10),0,dp(10));timer.setOnClickListener(v->{timerSeconds=timerSeconds==0?3:timerSeconds==3?10:0;timer.setText("Timer: "+timerSeconds+"s  (0 / 3 / 10)");});panel.addView(timer);
        TextView quality=txt("Calitate: rezoluție JPEG maximă disponibilă\nFocus: continuu • White balance: auto\nNoise/edge/tonemap: high quality când telefonul suportă",12,0xFFC4CDD7);panel.addView(quality);
        PopupWindow w=new PopupWindow(panel,dp(320),-2,true);w.setOutsideTouchable(true);w.setElevation(dp(12));w.showAtLocation(anchor,Gravity.TOP|Gravity.END,dp(10),dp(68));
    }

    private void startBg(){if(bgThread==null){bgThread=new HandlerThread("aura-camera");bgThread.start();bg=new Handler(bgThread.getLooper());}}
    private void stopBg(){if(bgThread!=null){bgThread.quitSafely();bgThread=null;bg=null;}}

    private void startCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},7);return;}startBg();
        try{
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);cameraId=null;
            for(String id:cm.getCameraIdList()){
                CameraCharacteristics c=cm.getCameraCharacteristics(id);Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null&&((front&&facing==CameraCharacteristics.LENS_FACING_FRONT)||(!front&&facing==CameraCharacteristics.LENS_FACING_BACK))){cameraId=id;cameraChars=c;activeArray=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);Integer so=c.get(CameraCharacteristics.SENSOR_ORIENTATION);sensorOrientation=so==null?90:so;break;}
            }
            if(cameraId==null){toast("Camera nu este disponibilă");return;}
            Size size=chooseJpegSize(cameraChars);reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),android.graphics.ImageFormat.JPEG,2);reader.setOnImageAvailableListener(r->{Image im=r.acquireLatestImage();if(im!=null)processAndSave(im);},bg);
            cm.openCamera(cameraId,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){camera=c;createPreview();}public void onDisconnected(CameraDevice c){c.close();camera=null;}public void onError(CameraDevice c,int e){c.close();camera=null;toast("Eroare cameră: "+e);}},bg);
        }catch(Exception e){toast("Pornire cameră: "+e.getMessage());}
    }

    private Size chooseJpegSize(CameraCharacteristics c){
        try{StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);Size[] sizes=map==null?null:map.getOutputSizes(android.graphics.ImageFormat.JPEG);if(sizes!=null&&sizes.length>0){Arrays.sort(sizes,(a,b)->Long.compare((long)b.getWidth()*b.getHeight(),(long)a.getWidth()*a.getHeight()));return sizes[0];}}catch(Exception ignored){}return new Size(4032,3024);
    }

    private void createPreview(){
        try{SurfaceTexture st=preview.getSurfaceTexture();if(st==null)return;st.setDefaultBufferSize(1920,1080);Surface surface=new Surface(st);previewBuilder=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);previewBuilder.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){session=s;updatePreview();}public void onConfigureFailed(CameraCaptureSession s){toast("Configurarea camerei a eșuat");}},bg);
        }catch(Exception e){toast("Preview: "+e.getMessage());}
    }

    private boolean hasInt(int[] a,int v){if(a==null)return false;for(int x:a)if(x==v)return true;return false;}
    private int clampExposure(int value){try{Range<Integer> r=cameraChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);if(r!=null)return Math.max(r.getLower(),Math.min(r.getUpper(),value));}catch(Exception ignored){}return 0;}
    private Rect zoomRect(){if(activeArray==null)return null;float z=Math.max(1f,Math.min(getMaxZoom(),zoom));int cw=(int)(activeArray.width()/z),ch=(int)(activeArray.height()/z),l=activeArray.centerX()-cw/2,t=activeArray.centerY()-ch/2;return new Rect(l,t,l+cw,t+ch);}
    private float getMaxZoom(){try{Float m=cameraChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);return Math.max(1f,Math.min(8f,m==null?4f:m));}catch(Exception e){return 4f;}}

    private void applyQuality(CaptureRequest.Builder b,boolean still){
        try{b.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);}catch(Exception ignored){}
        try{b.set(CaptureRequest.CONTROL_AF_MODE,still?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE:CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);}catch(Exception ignored){}
        try{b.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_AUTO);}catch(Exception ignored){}
        try{b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,clampExposure(exposure));}catch(Exception ignored){}
        try{b.set(CaptureRequest.CONTROL_AE_MODE,flash&&!front?CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH:CaptureRequest.CONTROL_AE_MODE_ON);}catch(Exception ignored){}
        try{int[] n=cameraChars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);if(hasInt(n,CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))b.set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);}catch(Exception ignored){}
        try{int[] e=cameraChars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);if(hasInt(e,CaptureRequest.EDGE_MODE_HIGH_QUALITY))b.set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_HIGH_QUALITY);}catch(Exception ignored){}
        try{int[] t=cameraChars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);if(hasInt(t,CaptureRequest.TONEMAP_MODE_HIGH_QUALITY))b.set(CaptureRequest.TONEMAP_MODE,CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);}catch(Exception ignored){}
        try{int maxFaces=cameraChars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);int[] fm=cameraChars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);if(maxFaces>0&&hasInt(fm,CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE))b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE);}catch(Exception ignored){}
        Rect zr=zoomRect();if(zr!=null)try{b.set(CaptureRequest.SCALER_CROP_REGION,zr);}catch(Exception ignored){}
        if(still)try{b.set(CaptureRequest.JPEG_QUALITY,(byte)100);}catch(Exception ignored){}
    }

    private final CameraCaptureSession.CaptureCallback liveCallback=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult result){
            Face[] f=result.get(CaptureResult.STATISTICS_FACES);lastFaces=f==null?new Face[0]:f;lastIso=result.get(CaptureResult.SENSOR_SENSITIVITY);lastExposureNs=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);runOnUiThread(()->{guideOverlay.setFaces(lastFaces,activeArray,front);updateSmartGuide();});
        }
    };

    private void updatePreview(){if(previewBuilder==null||session==null)return;try{applyQuality(previewBuilder,false);session.setRepeatingRequest(previewBuilder.build(),liveCallback,bg);}catch(Exception ignored){}}
    private void switchCamera(){front=!front;closeCamera();startCamera();}
    private void closeCamera(){try{if(session!=null)session.close();}catch(Exception ignored){}session=null;try{if(camera!=null)camera.close();}catch(Exception ignored){}camera=null;try{if(reader!=null)reader.close();}catch(Exception ignored){}reader=null;}

    private void triggerPhoto(){if(timerSeconds<=0){takePhoto();return;}final int[] left={timerSeconds};TextView countdown=txt(String.valueOf(left[0]),56,Color.WHITE);countdown.setGravity(Gravity.CENTER);countdown.setTypeface(Typeface.DEFAULT_BOLD);countdown.setBackgroundColor(0x55000000);previewFrame.addView(countdown,new FrameLayout.LayoutParams(-1,-1));Handler h=new Handler(getMainLooper());Runnable r=new Runnable(){public void run(){left[0]--;if(left[0]<=0){previewFrame.removeView(countdown);takePhoto();}else{countdown.setText(String.valueOf(left[0]));h.postDelayed(this,1000);}}};h.postDelayed(r,1000);}

    private int jpegOrientation(){int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;return front?(sensorOrientation+degrees)%360:(sensorOrientation-degrees+360)%360;}
    private void takePhoto(){if(camera==null||session==null||reader==null)return;try{CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());applyQuality(b,true);b.set(CaptureRequest.JPEG_ORIENTATION,jpegOrientation());session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){updatePreview();}},bg);}catch(Exception e){toast("Fotografie: "+e.getMessage());}}

    private Bitmap rotateForSave(Bitmap src){int o=jpegOrientation();if((o==90||o==270)&&src.getWidth()>src.getHeight()){Matrix m=new Matrix();m.postRotate(o);return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);}if(o==180){Matrix m=new Matrix();m.postRotate(180);return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);}return src;}

    private void processAndSave(Image image){
        try{ByteBuffer buf=image.getPlanes()[0].getBuffer();byte[] raw=new byte[buf.remaining()];buf.get(raw);Bitmap decoded=BitmapFactory.decodeByteArray(raw,0,raw.length);if(decoded==null)throw new Exception("Imagine invalidă");Bitmap oriented=rotateForSave(decoded);Bitmap out=applyLook(oriented,activePreset);
            ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"AURA_"+activePreset.toUpperCase(new Locale("ro")).replace('Ș','S').replace('Ă','A')+"_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(android.os.Build.VERSION.SDK_INT>=29)v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AURA Camera");Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new Exception("MediaStore");try(OutputStream os=getContentResolver().openOutputStream(uri)){out.compress(Bitmap.CompressFormat.JPEG,100,os);}Bitmap thumb=Bitmap.createScaledBitmap(out,180,135,true);runOnUiThread(()->{lastThumb.setImageBitmap(thumb);toast("Salvat · "+activePreset+" · MAX");});if(out!=oriented)out.recycle();if(oriented!=decoded)oriented.recycle();decoded.recycle();
        }catch(Exception e){runOnUiThread(()->toast("Eroare salvare: "+e.getMessage()));}finally{image.close();}
    }

    private Bitmap applyLook(Bitmap src,String mode){
        float sat=1f,contrast=1f,bright=0f,r=1f,g=1f,b=1f;
        if(mode.equals("Mașină")){sat=1.05f;contrast=1.06f;bright=-1f;r=1.01f;g=0.995f;b=0.99f;}
        else if(mode.equals("Selfie")){sat=1.02f;contrast=1.02f;bright=3f;r=1.025f;g=1.005f;b=0.98f;}
        else if(mode.equals("Portret")){sat=0.99f;contrast=1.05f;bright=2f;r=1.012f;g=1.0f;b=0.99f;}
        else if(mode.equals("Noapte")){sat=1.03f;contrast=1.03f;bright=7f;r=1.01f;g=1.0f;b=0.985f;}
        else if(mode.equals("TikTok")){sat=1.10f;contrast=1.07f;bright=2f;}
        Bitmap out=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);ColorMatrix m=new ColorMatrix();m.setSaturation(sat);ColorMatrix cb=new ColorMatrix(new float[]{contrast*r,0,0,0,bright,0,contrast*g,0,0,bright,0,0,contrast*b,0,bright,0,0,0,1,0});m.postConcat(cb);p.setColorFilter(new ColorMatrixColorFilter(m));c.drawBitmap(src,0,0,p);return out;
    }

    private float wrap180(float d){while(d>180)d-=360;while(d<-180)d+=360;return d;}
    private RectF primaryFaceNorm(){if(lastFaces==null||lastFaces.length==0||activeArray==null)return null;Face best=lastFaces[0];for(Face f:lastFaces)if(f.getBounds().width()*f.getBounds().height()>best.getBounds().width()*best.getBounds().height())best=f;Rect r=best.getBounds();float l=(r.left-activeArray.left)/(float)activeArray.width(),t=(r.top-activeArray.top)/(float)activeArray.height(),rr=(r.right-activeArray.left)/(float)activeArray.width(),bb=(r.bottom-activeArray.top)/(float)activeArray.height();if(front){float nl=1f-rr,nr=1f-l;l=nl;rr=nr;}return new RectF(l,t,rr,bb);}

    private void updateSmartGuide(){
        if(guideText==null)return;String msg;boolean good=false;
        if(activePreset.equals("Mașină")){
            if(!carCalibrated){msg="Țintește drept fața mașinii și atinge panoul pentru calibrare.";angleText.setText("📐 CAL");guideOverlay.setCarAngle(0,false);}
            else{float d=wrap180(azimuth-carReferenceAzimuth),a=Math.abs(d);guideOverlay.setCarAngle(a,true);angleText.setText(String.format(Locale.US,"📐 %.0f°",a));
                if(Math.abs(roll)>4){msg=roll>0?"Îndreaptă telefonul spre stânga":"Îndreaptă telefonul spre dreapta";}
                else if(pitch>18){msg="Mai jos · ține telefonul la nivelul farurilor";}
                else if(pitch<-18){msg="Mai sus · spre nivelul farurilor";}
                else if(a<30){msg=d>=0?"Mai la dreapta · caută 30–45°":"Mai la stânga · caută 30–45°";}
                else if(a>45){msg=d>=0?"Puțin la stânga · revino spre 35°":"Puțin la dreapta · revino spre 35°";}
                else if(Math.abs(a-35)<=2){msg="UNGHI IDEAL · 35° · nivelul farurilor";good=true;}
                else{msg="UNGHI RECOMANDAT · 30–45° · apropie de 35°";good=true;}
            }
        } else if(activePreset.equals("Noapte")){
            long ms=lastExposureNs==null?0:lastExposureNs/1000000L;int iso=lastIso==null?0:lastIso;
            if(motionScore>2.2f)msg="Ține telefonul mai stabil";else if(ms>33||iso>1200)msg="Apropie-te de lumină sau sprijină telefonul";else{msg="CADRU DE NOAPTE OPTIM";good=true;}
            angleText.setText("☾ AUTO");
        } else {
            RectF f=primaryFaceNorm();
            if(f==null){msg=activePreset.equals("TikTok")?"Încadrează subiectul · format vertical":"Încadrează fața în ghid";}
            else{float cx=f.centerX(),cy=f.centerY(),fw=f.width();
                if(cx<0.42f)msg="Mai la stânga";else if(cx>0.58f)msg="Mai la dreapta";else if(cy<0.30f)msg="Mai sus";else if(cy>0.58f)msg="Mai jos";else if(fw<0.20f)msg="Apropie-te";else if(fw>0.55f)msg="Depărtează-te";else{msg=activePreset.equals("Selfie")?"POZIȚIE IDEALĂ":activePreset.equals("Portret")?"PORTRET IDEAL":"CADRU TIKTOK IDEAL";good=true;}}
            angleText.setText(good?"✓ IDEAL":"◎ GHID");
        }
        guideText.setText(msg);guideText.setTextColor(good?GREEN:Color.WHITE);angleText.setTextColor(good?GREEN:0xFFE5E7EB);guideOverlay.good=good;guideOverlay.invalidate();
    }

    @Override public void onSensorChanged(SensorEvent event){if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;float[] R=new float[9],ori=new float[3];SensorManager.getRotationMatrixFromVector(R,event.values);SensorManager.getOrientation(R,ori);float na=(float)Math.toDegrees(ori[0]),np=(float)Math.toDegrees(ori[1]),nr=(float)Math.toDegrees(ori[2]);if(haveOrientation){float delta=Math.abs(np-lastPitch)+Math.abs(nr-lastRoll);motionScore=motionScore*0.85f+delta*0.15f;}azimuth=na;pitch=np;roll=nr;lastPitch=np;lastRoll=nr;haveOrientation=true;runOnUiThread(this::updateSmartGuide);}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,s==null?"Eroare":s,Toast.LENGTH_SHORT).show());}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();}
    @Override protected void onResume(){super.onResume();if(rotationSensor!=null)sensorManager.registerListener(this,rotationSensor,SensorManager.SENSOR_DELAY_UI);if(preview!=null&&preview.isAvailable())startCamera();}
    @Override protected void onPause(){super.onPause();sensorManager.unregisterListener(this);closeCamera();stopBg();}

    static class LogoView extends View{Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);LogoView(android.content.Context c){super(c);}protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),r=Math.min(w,h)*.42f;p.setStyle(Paint.Style.FILL);p.setColor(0xFF0C1724);c.drawRoundRect(new RectF(1,1,w-1,h-1),w*.22f,w*.22f,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(2,w*.055f));p.setColor(0xFF7D4CFF);c.drawCircle(w/2,h/2,r,p);p.setColor(0xFF27C7FF);c.drawArc(new RectF(w/2-r,h/2-r,w/2+r,h/2+r),-70,150,false,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(w*.52f);c.drawText("A",w/2,h*.69f,p);}}

    static class ShutterView extends View{Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);ShutterView(android.content.Context c){super(c);setClickable(true);}protected void onDraw(Canvas c){float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-3;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.WHITE);c.drawCircle(cx,cy,r,p);p.setStrokeWidth(2);p.setColor(0xFF94A3B8);c.drawCircle(cx,cy,r-7,p);p.setStyle(Paint.Style.FILL);p.setColor(0xFFF8FAFC);c.drawCircle(cx,cy,r-12,p);}}

    static class ModeIconView extends View{final int type;boolean selected=false;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);ModeIconView(android.content.Context c,int t){super(c);type=t;}protected void onDraw(Canvas c){p.setColor(selected?Color.WHITE:0xFFD0D6DE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);float w=getWidth(),h=getHeight();if(type==1){RectF body=new RectF(w*.12f,h*.44f,w*.88f,h*.72f);c.drawRoundRect(body,6,6,p);c.drawLine(w*.25f,h*.44f,w*.36f,h*.28f,p);c.drawLine(w*.36f,h*.28f,w*.66f,h*.28f,p);c.drawLine(w*.66f,h*.28f,w*.76f,h*.44f,p);c.drawCircle(w*.3f,h*.72f,w*.09f,p);c.drawCircle(w*.7f,h*.72f,w*.09f,p);}else if(type==2){c.drawCircle(w*.5f,h*.34f,w*.15f,p);c.drawArc(new RectF(w*.23f,h*.48f,w*.77f,h*.92f),205,130,false,p);}else if(type==3){c.drawRect(w*.10f,h*.10f,w*.90f,h*.90f,p);c.drawCircle(w*.5f,h*.34f,w*.14f,p);c.drawArc(new RectF(w*.25f,h*.48f,w*.75f,h*.88f),205,130,false,p);}else if(type==4){Path moon=new Path();moon.moveTo(w*.65f,h*.12f);moon.cubicTo(w*.30f,h*.18f,w*.27f,h*.70f,w*.69f,h*.82f);moon.cubicTo(w*.42f,h*.89f,w*.17f,h*.70f,w*.17f,h*.45f);moon.cubicTo(w*.17f,h*.22f,w*.38f,h*.06f,w*.65f,h*.12f);c.drawPath(moon,p);}else{c.drawLine(w*.55f,h*.15f,w*.55f,h*.66f,p);c.drawLine(w*.55f,h*.15f,w*.78f,h*.23f,p);c.drawCircle(w*.40f,h*.70f,w*.13f,p);c.drawCircle(w*.67f,h*.73f,w*.13f,p);}}}

    static class GuideOverlay extends View{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);boolean grid=true,good=false,carCal=false;String mode="Mașină";float carAngle=0;RectF faceNorm=null;
        GuideOverlay(android.content.Context c){super(c);setWillNotDraw(false);}void setMode(String m){mode=m;faceNorm=null;invalidate();}void setCarAngle(float a,boolean cal){carAngle=a;carCal=cal;invalidate();}
        void setFaces(Face[] faces,Rect active,boolean front){if(faces==null||faces.length==0||active==null){faceNorm=null;invalidate();return;}Face best=faces[0];for(Face f:faces)if(f.getBounds().width()*f.getBounds().height()>best.getBounds().width()*best.getBounds().height())best=f;Rect r=best.getBounds();float l=(r.left-active.left)/(float)active.width(),t=(r.top-active.top)/(float)active.height(),rr=(r.right-active.left)/(float)active.width(),bb=(r.bottom-active.top)/(float)active.height();if(front){float nl=1-rr,nr=1-l;l=nl;rr=nr;}faceNorm=new RectF(l,t,rr,bb);invalidate();}
        protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();if(grid){p.setColor(0x42FFFFFF);p.setStrokeWidth(1);for(int i=1;i<3;i++){c.drawLine(w*i/3,0,w*i/3,h,p);c.drawLine(0,h*i/3,w,h*i/3,p);}}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(good?GREEN:0xAAFFFFFF);float m=24,len=34;c.drawLine(m,m,m+len,m,p);c.drawLine(m,m,m,m+len,p);c.drawLine(w-m,m,w-m-len,m,p);c.drawLine(w-m,m,w-m,m+len,p);c.drawLine(m,h-m,m+len,h-m,p);c.drawLine(m,h-m,m,h-m-len,p);c.drawLine(w-m,h-m,w-m-len,h-m,p);c.drawLine(w-m,h-m,w-m,h-m-len,p);
            if(mode.equals("Mașină")){float cx=w*.82f,cy=h*.48f,r=Math.min(w,h)*.16f;p.setStrokeWidth(5);p.setColor(0x99FFFFFF);c.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-55,30,false,p);p.setColor(GREEN);c.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-45,15,false,p);if(carCal){double rad=Math.toRadians(-45+Math.min(60,carAngle));float x=(float)(cx+r*Math.cos(rad)),y=(float)(cy+r*Math.sin(rad));p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,8,p);p.setStyle(Paint.Style.STROKE);}}
            else if(faceNorm!=null){p.setStrokeWidth(3);p.setColor(good?GREEN:0xFFB6C2CF);RectF rr=new RectF(faceNorm.left*w,faceNorm.top*h,faceNorm.right*w,faceNorm.bottom*h);c.drawRoundRect(rr,18,18,p);}p.setStyle(Paint.Style.FILL);
        }
    }
}
