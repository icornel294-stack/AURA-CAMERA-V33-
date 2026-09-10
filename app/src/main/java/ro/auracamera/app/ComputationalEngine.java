package ro.auracamera.app;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU-safe computational photography primitives for AURA Camera MAX.
 * Designed for Android devices such as Note 10+ without external native dependencies.
 */
public final class ComputationalEngine {
    private ComputationalEngine() {}

    public static final class Metrics {
        public final float mean, contrast, highlights, shadows, sharpness;
        public Metrics(float mean, float contrast, float highlights, float shadows, float sharpness) {
            this.mean = mean; this.contrast = contrast; this.highlights = highlights;
            this.shadows = shadows; this.sharpness = sharpness;
        }
    }

    public static Metrics metrics(Bitmap b) {
        int target = Math.min(320, b.getWidth());
        int h = Math.max(1, Math.round(b.getHeight() * target / (float)b.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(b, target, h, true);
        int[] px = new int[target * h]; s.getPixels(px, 0, target, 0, 0, target, h);
        double sum = 0, sum2 = 0, edge = 0; int hi = 0, sh = 0;
        for (int y=0;y<h;y++) {
            float prev = -1;
            for (int x=0;x<target;x++) {
                int c = px[y*target+x];
                float l = lum(c); sum += l; sum2 += l*l;
                if (l > .96f) hi++; if (l < .06f) sh++;
                if (prev >= 0) edge += Math.abs(l-prev); prev = l;
            }
        }
        int n = px.length;
        float mean=(float)(sum/n);
        float contrast=(float)Math.sqrt(Math.max(0,sum2/n-mean*mean));
        float sharp=(float)(edge/Math.max(1,n-h));
        s.recycle();
        return new Metrics(mean,contrast,hi/(float)n,sh/(float)n,sharp);
    }

    public static int bestFrameIndex(List<Bitmap> frames) {
        if (frames == null || frames.isEmpty()) return -1;
        int best=0; double bestScore=-1e9;
        for (int i=0;i<frames.size();i++) {
            Metrics m=metrics(frames.get(i));
            double exposurePenalty=Math.abs(m.mean-.48f)*.9 + m.highlights*1.8 + m.shadows*.6;
            double score=m.sharpness*8 + m.contrast*.8 - exposurePenalty;
            if (score>bestScore){bestScore=score;best=i;}
        }
        return best;
    }

    /** Translation-only alignment. Good for small handheld motion and intentionally conservative. */
    public static Bitmap alignTo(Bitmap ref, Bitmap src) {
        int w=Math.min(240, Math.min(ref.getWidth(),src.getWidth()));
        int h=Math.min(180, Math.min(ref.getHeight(),src.getHeight()));
        Bitmap r=Bitmap.createScaledBitmap(ref,w,h,true);
        Bitmap s=Bitmap.createScaledBitmap(src,w,h,true);
        int[] rp=new int[w*h],sp=new int[w*h]; r.getPixels(rp,0,w,0,0,w,h); s.getPixels(sp,0,w,0,0,w,h);
        int bestDx=0,bestDy=0; double best=Double.MAX_VALUE;
        for(int dy=-8;dy<=8;dy+=2) for(int dx=-8;dx<=8;dx+=2){
            double err=0; int count=0;
            for(int y=18;y<h-18;y+=4){int sy=y+dy;if(sy<0||sy>=h)continue;
                for(int x=18;x<w-18;x+=4){int sx=x+dx;if(sx<0||sx>=w)continue;
                    float a=lum(rp[y*w+x]),b=lum(sp[sy*w+sx]); float d=a-b;err+=d*d;count++;}}
            if(count>0){err/=count;if(err<best){best=err;bestDx=dx;bestDy=dy;}}
        }
        r.recycle();s.recycle();
        float sx=src.getWidth()/(float)w, sy=src.getHeight()/(float)h;
        int dx=Math.round(bestDx*sx),dy=Math.round(bestDy*sy);
        Bitmap out=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888);
        int[] in=new int[src.getWidth()*src.getHeight()],op=new int[in.length];src.getPixels(in,0,src.getWidth(),0,0,src.getWidth(),src.getHeight());
        int W=src.getWidth(),H=src.getHeight();
        for(int y=0;y<H;y++){int yy=y+dy;for(int x=0;x<W;x++){int xx=x+dx;op[y*W+x]=(xx>=0&&xx<W&&yy>=0&&yy<H)?in[yy*W+xx]:in[y*W+x];}}
        out.setPixels(op,0,W,0,0,W,H);return out;
    }

    /**
     * Exposure fusion with simple deghost protection. Middle/reference frame remains authoritative
     * where aligned frames disagree strongly.
     */
    public static Bitmap fuse(List<Bitmap> input, boolean night) {
        if(input==null||input.isEmpty())return null;
        int refIndex=bestFrameIndex(input); if(refIndex<0)refIndex=input.size()/2;
        Bitmap ref=input.get(refIndex); int W=ref.getWidth(),H=ref.getHeight();
        List<Bitmap> aligned=new ArrayList<>();
        for(int i=0;i<input.size();i++){
            Bitmap b=input.get(i);
            if(b.getWidth()!=W||b.getHeight()!=H)b=Bitmap.createScaledBitmap(b,W,H,true);
            aligned.add(i==refIndex?b:alignTo(ref,b));
        }
        int[] refPx=new int[W*H];ref.getPixels(refPx,0,W,0,0,W,H);
        int[][] all=new int[aligned.size()][];
        for(int i=0;i<aligned.size();i++){all[i]=new int[W*H];aligned.get(i).getPixels(all[i],0,W,0,0,W,H);}
        int[] out=new int[W*H];
        for(int p=0;p<out.length;p++){
            int rc=refPx[p];float rl=lum(rc);double wr=0,wg=0,wb=0,ws=0;
            for(int i=0;i<all.length;i++){
                int c=all[i][p];float l=lum(c);float diff=Math.abs(l-rl);
                float exposureWeight=1f-Math.min(1f,Math.abs(l-.50f)*1.7f);
                float ghostWeight=diff>.20f?(i==refIndex?1f:.08f):1f;
                float weight=Math.max(.05f,exposureWeight)*ghostWeight;
                if(night){weight*=i==refIndex?1.3f:1f;}
                wr+=Color.red(c)*weight;wg+=Color.green(c)*weight;wb+=Color.blue(c)*weight;ws+=weight;
            }
            out[p]=Color.rgb(clamp((int)(wr/ws)),clamp((int)(wg/ws)),clamp((int)(wb/ws)));
        }
        Bitmap merged=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);merged.setPixels(out,0,W,0,0,W,H);
        for(int i=0;i<aligned.size();i++)if(i!=refIndex&&!input.contains(aligned.get(i)))aligned.get(i).recycle();
        return localToneMap(merged,night);
    }

    /** Tile-based local tone mapping to protect highlights while opening useful shadows. */
    public static Bitmap localToneMap(Bitmap src, boolean night) {
        int W=src.getWidth(),H=src.getHeight(); int[] p=new int[W*H];src.getPixels(p,0,W,0,0,W,H);
        final int grid=32; int gw=(W+grid-1)/grid,gh=(H+grid-1)/grid;float[] avg=new float[gw*gh];int[] count=new int[avg.length];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int g=(y/grid)*gw+x/grid;avg[g]+=lum(p[y*W+x]);count[g]++;}
        for(int i=0;i<avg.length;i++)avg[i]/=Math.max(1,count[i]);
        int[] out=new int[p.length];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int c=p[y*W+x],g=(y/grid)*gw+x/grid;float local=avg[g];
            float lift=local<.35f?(night?.13f:.08f)*(1f-local/.35f):0f;
            float protect=local>.62f?.16f*((local-.62f)/.38f):0f;
            float r=Color.red(c)/255f,gg=Color.green(c)/255f,b=Color.blue(c)/255f;
            r=tone(r,lift,protect);gg=tone(gg,lift,protect);b=tone(b,lift,protect);
            out[y*W+x]=Color.rgb(clamp(Math.round(r*255)),clamp(Math.round(gg*255)),clamp(Math.round(b*255)));
        }
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);b.setPixels(out,0,W,0,0,W,H);return b;
    }

    private static float tone(float v,float lift,float protect){
        float x=v + lift*(1f-v); if(x>.65f)x-=protect*(x-.65f)/.35f; return Math.max(0,Math.min(1,x));
    }
    private static float lum(int c){return (.2126f*Color.red(c)+.7152f*Color.green(c)+.0722f*Color.blue(c))/255f;}
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
