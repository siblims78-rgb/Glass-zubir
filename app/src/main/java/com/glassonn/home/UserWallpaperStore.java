package com.glassonn.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Private, disposable cache for personal wallpapers received over the paired LAN Companion.
 *  No external storage, network upload, backup, or media-scanning permission. */
final class UserWallpaperStore {
    static final int MAX_UPLOAD_BYTES = 2_500_000;
    static final int MAX_PHOTOS = 24;
    private static final long MAX_TOTAL_BYTES = 32_000_000;
    private final File directory;
    private final SharedPreferences prefs;
    UserWallpaperStore(Context app, SharedPreferences settings){
        prefs=settings;
        directory=new File(app.getCacheDir(),"glassonn_user_wallpapers");
    }
    private static boolean valid(String id){return id!=null && id.matches("[a-f0-9]{32}");}
    private File path(String id){return valid(id)?new File(directory,id+".jpg"):null;}
    synchronized File file(String id){
        File file=path(id);
        return file!=null && file.isFile()?file:null;
    }
    private File[] files(){
        File[] all=directory.listFiles((dir,name)->name.matches("[a-f0-9]{32}\\.jpg"));
        if(all==null)return new File[0];
        Arrays.sort(all,Comparator.comparingLong(File::lastModified).reversed());
        return all;
    }
    synchronized JSONObject state(){
        JSONObject out=new JSONObject();
        try{
            File[] all=files();JSONArray items=new JSONArray();
            for(File f:all){JSONObject item=new JSONObject();
                item.put("id",f.getName().substring(0,32));item.put("size",f.length());items.put(item);
            }
            String selected=prefs.getString("user_wallpaper_selected","");
            if(file(selected)==null && all.length>0)selected=all[0].getName().substring(0,32);
            out.put("items",items);
            out.put("selected",selected);
            out.put("mode",prefs.getString("user_wallpaper_mode","shuffle"));
            out.put("maxCount",MAX_PHOTOS);
        }catch(Exception ignored){}
        return out;
    }
    synchronized String save(byte[] data) throws Exception {
        if(data==null||data.length<512||data.length>MAX_UPLOAD_BYTES)throw new Exception("Gambar terlalu besar atau kosong (maks 2.5 MB)");
        if((data[0]&255)!=0xff||(data[1]&255)!=0xd8||(data[2]&255)!=0xff)throw new Exception("Hanya gambar JPEG yang diterima");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if(bounds.outWidth<240||bounds.outHeight<240||bounds.outWidth>4096||bounds.outHeight>4096 ||
           ((long)bounds.outWidth*bounds.outHeight)>12_000_000L)throw new Exception("Resolusi gambar tidak disokong");
        BitmapFactory.Options checkOptions=new BitmapFactory.Options();checkOptions.inSampleSize=8;
        Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length,checkOptions);
        if(decoded==null)throw new Exception("Fail JPEG tidak dapat dibaca");
        decoded.recycle();
        File[] current=files();
        long total=0;for(File f:current)total+=f.length();
        if(current.length>=MAX_PHOTOS)throw new Exception("Galeri penuh. Padam gambar sebelum tambah lagi (maks 24)");
        if(total+data.length>MAX_TOTAL_BYTES)throw new Exception("Cache hampir penuh. Padam beberapa gambar dahulu");
        if(!directory.exists()&&!directory.mkdirs())throw new Exception("Tidak dapat menyediakan cache TV");
        String id=UUID.randomUUID().toString().replace("-","");
        File tmp=new File(directory,id+".part"),dest=path(id);
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);out.getFD().sync();}
        if(!tmp.renameTo(dest)){tmp.delete();throw new Exception("Gagal simpan gambar dalam cache");}
        if(current.length==0 || file(prefs.getString("user_wallpaper_selected",""))==null){
            prefs.edit().putString("user_wallpaper_selected",id).apply();
        }
        return id;
    }
    synchronized boolean select(String mode,String selected){
        if(!"fixed".equals(mode)&&!"shuffle".equals(mode))return false;
        if("fixed".equals(mode)&&file(selected)==null)return false;
        if("shuffle".equals(mode)&&files().length==0)return false;
        SharedPreferences.Editor e=prefs.edit().putString("user_wallpaper_mode",mode);
        if(file(selected)!=null)e.putString("user_wallpaper_selected",selected);
        e.apply();return true;
    }
    synchronized boolean remove(String id){
        File f=file(id);if(f==null)return false;
        if(!f.delete())return false;
        File[] remain=files();
        if(id.equals(prefs.getString("user_wallpaper_selected","")))
            prefs.edit().putString("user_wallpaper_selected",remain.length>0?remain[0].getName().substring(0,32):"").apply();
        return true;
    }
    synchronized void clear(){
        for(File f:files())f.delete();
        prefs.edit().remove("user_wallpaper_selected").putString("user_wallpaper_mode","shuffle").apply();
    }
    synchronized byte[] thumbnail(String id)throws Exception{
        File f=file(id);if(f==null)return null;
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        int sample=1;while(o.outWidth/sample>480||o.outHeight/sample>300)sample*=2;
        o.inJustDecodeBounds=false;o.inSampleSize=sample;
        Bitmap image=BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        if(image==null)return null;
        Bitmap result=Bitmap.createBitmap(280,158,Bitmap.Config.RGB_565);
        Canvas c=new Canvas(result);int w=image.getWidth(),h=image.getHeight();
        float scale=Math.max(280f/w,158f/h),cropW=280/scale,cropH=158/scale;
        Rect src=new Rect(Math.max(0,Math.round((w-cropW)/2)),Math.max(0,Math.round((h-cropH)/2)),
          Math.min(w,Math.round((w+cropW)/2)),Math.min(h,Math.round((h+cropH)/2)));
        c.drawBitmap(image,src,new RectF(0,0,280,158),new Paint(Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream out=new ByteArrayOutputStream();result.compress(Bitmap.CompressFormat.JPEG,68,out);
        image.recycle();result.recycle();return out.toByteArray();
    }
}
