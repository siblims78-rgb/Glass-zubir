package com.glassonn.home;

import android.app.Activity;
import android.view.KeyEvent;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
import android.content.Intent;
import android.net.Uri;
import android.app.SearchManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.graphics.BitmapFactory;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Glass ONN personal TV dashboard. No HOME takeover; no accessibility/overlay/GPS permissions. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView page;
    private MediaPlayer weatherSound;
    private AudioFocusRequest soundFocus;
    private AudioManager audioManager;
    private CompanionServer companion;
    private UserWallpaperStore userWallpapers;
    private TraktSync trakt;
    private volatile boolean textInputFocused = false;
    private volatile String wallpaperDisplayedId = "";
    private volatile String wallpaperDisplayError = "Not yet shown";

    private final String[][] places = {
        {"Setapak / Kuala Lumpur", "WLY01", "3.205", "101.718"},
        {"Gombak / Selangor", "SGR01", "3.257", "101.702"},
        {"Kuala Lumpur", "WLY01", "3.139", "101.687"},
        {"Johor Bahru", "JHR02", "1.493", "103.742"},
        {"Pulau Pinang", "PNG01", "5.414", "100.329"}
    };
    SharedPreferences prefs() { return getPreferences(MODE_PRIVATE); }
    void sendJs(String js) { ui.post(() -> {if (page != null) page.evaluateJavascript(js, null);}); }
    void remoteUpdated() { sendJs("if(window.onRemoteChanged)window.onRemoteChanged()"); }

    // DPAD_CENTER is forwarded as explicit Down/Up. TV WebViews may deliver
    // key repeats/long presses differently, causing a normal click too soon.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                key == KeyEvent.KEYCODE_BUTTON_SELECT || key == KeyEvent.KEYCODE_BUTTON_A;
        // Text fields need WebView/IME handling; otherwise OK never opens the keyboard.
        if(select && page != null && textInputFocused) {
            if(event.getAction()==KeyEvent.ACTION_UP) {
                page.requestFocus();
                android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(im!=null)im.showSoftInput(page,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
            return super.dispatchKeyEvent(event);
        }
        if(select && page != null) {
            if(event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                sendJs("if(window.tvSelectDown)window.tvSelectDown()");
            else if(event.getAction() == KeyEvent.ACTION_UP)
                sendJs("if(window.tvSelectUp)window.tvSelectUp()");
            return true;
        }
        if(key == KeyEvent.KEYCODE_MENU && event.getAction()==KeyEvent.ACTION_UP) {
            sendJs("if(window.tvShowFocusedMenu)window.tvShowFocusedMenu()");
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
        page = new WebView(this);
        page.setBackgroundColor(0xff0b1429);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        page.getSettings().setJavaScriptEnabled(true);
        page.getSettings().setDomStorageEnabled(true);
        page.getSettings().setAllowFileAccess(true);
        page.getSettings().setAllowContentAccess(false);
        page.getSettings().setAllowFileAccessFromFileURLs(false);
        page.getSettings().setAllowUniversalAccessFromFileURLs(false);
        userWallpapers = new UserWallpaperStore(this,prefs());
        page.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri=request.getUrl();
                if("https".equals(uri.getScheme()) && "glassonn.local".equals(uri.getHost()) &&
                        uri.getPath()!=null && uri.getPath().startsWith("/user-wallpaper/")) {
                    String name=uri.getLastPathSegment();
                    if(name!=null && name.matches("[a-f0-9]{32}\\.jpg")) {
                        File image=userWallpapers.file(name.substring(0,32));
                        if(image!=null) {
                            try {
                                Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");
                                return new WebResourceResponse("image/jpeg",null,200,"OK",headers,new FileInputStream(image));
                            } catch(Exception ignored){}
                        }
                    }
                    return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",new HashMap<>(),new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view,request);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Only the bundled HTML is permitted in the TV WebView.
                return !"file".equalsIgnoreCase(r.getUrl().getScheme());
            }
        });
        trakt=new TraktSync(this,(type,payload)->sendJs("if(window.onTraktEvent)window.onTraktEvent("+JSONObject.quote(type)+","+JSONObject.quote(payload.toString())+")"));
        page.addJavascriptInterface(new LocalBridge(), "Native");
        setContentView(page);
        companion = new CompanionServer(this);
        page.loadUrl("file:///android_asset/index.html");
        // Offline nature samples are packaged during GitHub build; no first-launch download needed.
    }

    private static final String[] PRIORITIES = {
        "com.netflix.ninja", "com.stremio.one", "com.google.android.youtube.tv", "ar.tvplayer.tv",
        "com.syncler", "org.courville.nova"
    };
    private int priority(String pkg) {
        for (int i=0;i<PRIORITIES.length;i++) if (PRIORITIES[i].equals(pkg)) return i;
        return 100;
    }
    private List<ResolveInfo> discoveredApps() {
        PackageManager m=getPackageManager();
        List<ResolveInfo> out=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            Intent i=new Intent(Intent.ACTION_MAIN);i.addCategory(category);
            for(ResolveInfo info:m.queryIntentActivities(i,0)) {
                if(info.activityInfo==null)continue;
                String pkg=info.activityInfo.packageName;
                if(getPackageName().equals(pkg)||!seen.add(pkg))continue;
                out.add(info);
            }
        }
        Collections.sort(out,Comparator.comparingInt((ResolveInfo x)->priority(x.activityInfo.packageName))
                .thenComparing(x->String.valueOf(x.loadLabel(m)).toLowerCase()));
        return out;
    }
    JSONArray apps(boolean withIcons) {
        JSONArray out=new JSONArray();PackageManager m=getPackageManager();
        for(ResolveInfo info:discoveredApps()){
            try{
                JSONObject j=new JSONObject();
                j.put("name",String.valueOf(info.loadLabel(m)));
                j.put("pkg",info.activityInfo.packageName);
                if(withIcons){
                    // Prefer native Android TV banner 16:9. Not every app provides one.
                    Drawable banner=info.activityInfo.loadBanner(m);
                    if(banner==null)banner=m.getApplicationBanner(info.activityInfo.packageName);
                    if(banner!=null){
                        Bitmap wide=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(wide);
                        int iw=banner.getIntrinsicWidth(),ih=banner.getIntrinsicHeight();
                        if(iw<=0||ih<=0){iw=320;ih=180;}
                        float scale=Math.min(320f/iw,180f/ih);
                        int width=Math.max(1,Math.round(iw*scale)),height=Math.max(1,Math.round(ih*scale));
                        banner.setBounds((320-width)/2,(180-height)/2,(320+width)/2,(180+height)/2);
                        banner.draw(canvas);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        wide.compress(Bitmap.CompressFormat.PNG,95,bytes);wide.recycle();
                        j.put("banner","data:image/png;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP));
                    }
                    Drawable icon=info.loadIcon(m);
                    if(icon!=null){
                        Bitmap bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);
                        Canvas c=new Canvas(bitmap);icon.setBounds(0,0,72,72);icon.draw(c);
                        ByteArrayOutputStream data=new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG,85,data);bitmap.recycle();
                        j.put("icon","data:image/png;base64,"+Base64.encodeToString(data.toByteArray(),Base64.NO_WRAP));
                    }
                }
                out.put(j);
            }catch(Exception ignored){}
        }
        return out;
    }
    boolean appExists(String pkg){
        if(pkg==null||pkg.length()>180||!pkg.matches("[A-Za-z0-9_.]+"))return false;
        for(ResolveInfo i:discoveredApps()) if(pkg.equals(i.activityInfo.packageName))return true;
        return false;
    }
    JSONArray favorites(){
        JSONArray stored;
        try{stored=new JSONArray(prefs().getString("favorites",""));}
        catch(Exception ex){
            stored=new JSONArray();
            for(ResolveInfo info:discoveredApps()){if(stored.length()>=8)break;stored.put(info.activityInfo.packageName);}
        }
        return stored;
    }
    boolean setFavorite(String pkg, boolean on){
        if(!appExists(pkg)||(on&&isHiddenApp(pkg)))return false;
        JSONArray old=favorites(),next=new JSONArray();boolean found=false;
        for(int i=0;i<old.length();i++){
            String v=old.optString(i);
            if(v.equals(pkg)){found=true;if(!on)continue;}
            next.put(v);
        }
        if(on&&!found){next.put(pkg);}
        prefs().edit().putString("favorites",next.toString()).apply();
        remoteUpdated();return true;
    }
    private static final String[] CARD_NAMES={"weather","prayer","trending","favorites","continue","nextPlay","movies","shows","netflix"};
    boolean validCard(String key){for(String name:CARD_NAMES)if(name.equals(key))return true;return false;}
    JSONObject cardState(){JSONObject out=new JSONObject();try{for(String name:CARD_NAMES)out.put(name,prefs().getBoolean("card_"+name,true));}catch(Exception ignored){}return out;}
    boolean setCardVisible(String key,boolean visible){
        if(!validCard(key))return false;
        prefs().edit().putBoolean("card_"+key,visible).apply();remoteUpdated();return true;
    }
    JSONArray hiddenApps(){try{return new JSONArray(prefs().getString("hidden_apps","[]"));}catch(Exception ignored){return new JSONArray();}}
    boolean isHiddenApp(String pkg){JSONArray hidden=hiddenApps();for(int i=0;i<hidden.length();i++)if(pkg.equals(hidden.optString(i)))return true;return false;}
    boolean setAppHidden(String pkg,boolean hidden){
        if(!appExists(pkg))return false;
        JSONArray prev=hiddenApps(),next=new JSONArray();
        for(int i=0;i<prev.length();i++){String p=prev.optString(i);if(!p.equals(pkg))next.put(p);}
        if(hidden)next.put(pkg);
        prefs().edit().putString("hidden_apps",next.toString()).apply();
        if(hidden)setFavorite(pkg,false);
        remoteUpdated();return true;
    }
    void setPlace(int n){if(n<0||n>=places.length)return;prefs().edit().putInt("place",n).apply();remoteUpdated();}
    int getPlace(){return prefs().getInt("place",0);}
    String getWallpaperMode(){return prefs().getString("wallpaper_mode","auto");}
    void setWallpaperMode(String mode){
        if(mode==null||!(mode.equals("auto")||mode.matches("[0-3]")))return;
        prefs().edit().putString("wallpaper_mode",mode).apply();remoteUpdated();
    }
    String getWallpaperSource(){return prefs().getString("wallpaper_source","cinematic");}
    void setWallpaperSource(String source){
        if(!"local".equals(source)&&!"commons".equals(source)&&!"picsum".equals(source)&&!"cinematic".equals(source)&&!"user".equals(source))return;
        if("user".equals(source)&&userWallpapers.state().optJSONArray("items").length()==0)return;
        prefs().edit().putString("wallpaper_source",source).apply();remoteUpdated();
    }
    JSONObject userWallpaperState(){return userWallpapers.state();}
    void reportWallpaperDisplay(String id, boolean ok){
        if(id==null||!id.matches("[a-f0-9]{32}"))return;
        wallpaperDisplayedId=ok?id:"";
        wallpaperDisplayError=ok?"Shown on TV":"TV could not load the image";
    }
    String getGlassTheme(){return prefs().getString("theme","glass");}
    void setTheme(String value){
        if(!"glass".equals(value)&&!"bright".equals(value)&&!"dark".equals(value))return;
        prefs().edit().putString("theme",value).apply();remoteUpdated();
    }

    JSONObject uploadUserWallpaper(byte[] jpg) throws Exception {
        String id=userWallpapers.save(jpg);
        wallpaperDisplayedId="";wallpaperDisplayError="Queued for TV display";
        setWallpaperSource("user");
        JSONObject response=userWallpapers.state();response.put("ok",true);response.put("added",id);
        return response;
    }
    boolean selectUserWallpaper(String mode,String id){
        if(!userWallpapers.select(mode,id))return false;
        wallpaperDisplayedId="";
        wallpaperDisplayError="Waiting for the TV to show the selected photo";
        setWallpaperSource("user");return true;
    }
    boolean removeUserWallpaper(String id){
        boolean ok=userWallpapers.remove(id);
        if(ok){
            if(id!=null && id.equals(wallpaperDisplayedId)){wallpaperDisplayedId="";wallpaperDisplayError="The displayed photo was deleted";}
            if(userWallpapers.state().optJSONArray("items").length()==0)setWallpaperSource("local");else remoteUpdated();
        }
        return ok;
    }
    void clearUserWallpapers(){userWallpapers.clear();wallpaperDisplayedId="";wallpaperDisplayError="Cache cleared";setWallpaperSource("local");}
    byte[] userWallpaperThumbnail(String id)throws Exception{return userWallpapers.thumbnail(id);}
    int getGlassOpacity(){return prefs().getInt("glass_opacity",50);}
    int getGlassBlur(){return prefs().getInt("glass_blur",0);}
    void setGlassOpacity(int value){if(value<8||value>60)return;prefs().edit().putInt("glass_opacity",value).apply();remoteUpdated();}
    void setGlassBlur(int value){if(value<0||value>18)return;prefs().edit().putInt("glass_blur",value).apply();remoteUpdated();}
    String getLayoutMode(){return prefs().getString("layout_mode","balanced");}
    void setLayoutMode(String mode){
        if(!"balanced".equals(mode)&&!"movies".equals(mode)&&!"apps".equals(mode)&&!"compact".equals(mode))return;
        prefs().edit().putString("layout_mode",mode).apply();remoteUpdated();
    }
    int getWidgetSize(String key){
        if(!"info".equals(key)&&!"trending".equals(key)&&!"apps".equals(key))return 0;
        return Math.max(-3,Math.min(3,prefs().getInt("widget_size_"+key,0)));
    }
    void setWidgetSize(String key,int value){
        if(!"info".equals(key)&&!"trending".equals(key)&&!"apps".equals(key)||value < -3||value >3)return;
        prefs().edit().putInt("widget_size_"+key,value).apply();remoteUpdated();
    }
    boolean getSwapInfo(){return prefs().getBoolean("swap_info",false);}
    void setSwapInfo(boolean on){prefs().edit().putBoolean("swap_info",on).apply();remoteUpdated();}
    boolean getAppsFirst(){return prefs().getBoolean("apps_first",true);}
    void setAppsFirst(boolean on){prefs().edit().putBoolean("apps_first",on).apply();remoteUpdated();}
    String getRecommendationSource(){return prefs().getString("reco_source",trakt!=null&&trakt.state().optBoolean("configured")?"trakt":"cinemeta");}
    void setRecommendationSource(String mode){
        if(!"cinemeta".equals(mode)&&!"tmdb".equals(mode)&&!"charts".equals(mode)&&!"trakt".equals(mode))return;
        prefs().edit().putString("reco_source",mode).apply();remoteUpdated();
    }
    boolean getShowAppTitles(){return prefs().getBoolean("show_app_titles",false);}
    void setShowAppTitles(boolean show){prefs().edit().putBoolean("show_app_titles",show).apply();remoteUpdated();}
    boolean getWeatherFx(){return prefs().getBoolean("weather_fx",true);}
    boolean getSoundFx(){return prefs().getBoolean("sound_fx",true);}
    void setSoundFx(boolean on){prefs().edit().putBoolean("sound_fx",on).apply();if(!on)stopWeatherAudio();remoteUpdated();}
    void soundStatus(String status){sendJs("if(window.onSoundStatus)window.onSoundStatus("+JSONObject.quote(status)+")");}
    void abandonSoundFocus(){
        if(audioManager!=null&&soundFocus!=null){try{audioManager.abandonAudioFocusRequest(soundFocus);}catch(Exception ignored){}}
        soundFocus=null;
    }
    private void stopWeatherAudioOnUi(){
        if(weatherSound!=null){try{weatherSound.stop();}catch(Exception ignored){}try{weatherSound.release();}catch(Exception ignored){}weatherSound=null;}
        abandonSoundFocus();
    }
    void stopWeatherAudio(){
        // IMPORTANT: execute immediately on UI thread so a delayed stop never kills the newly started recording.
        if(Looper.myLooper()==Looper.getMainLooper())stopWeatherAudioOnUi();
        else ui.post(this::stopWeatherAudioOnUi);
    }
    // Public-domain field recording from Wikimedia Commons: File:Rain.ogg by ジダネ (2007).
    // Download asynchronously once. The offline original ambience stays available if offline.
    private static final String REAL_RAIN_URL="https://upload.wikimedia.org/wikipedia/commons/3/3d/Rain.ogg";
    private volatile boolean rainDownloadAttempted=false;
    private File rainRecording(){return new File(getCacheDir(),"real_rain_pd_v056.ogg");}
    private void prefetchRealRain(){
        if(rainDownloadAttempted||rainRecording().length()>10000)return;
        rainDownloadAttempted=true;
        new Thread(()->{
            HttpURLConnection conn=null;File temp=null;
            try{
                conn=(HttpURLConnection)new URL(REAL_RAIN_URL).openConnection();
                conn.setConnectTimeout(6000);conn.setReadTimeout(8000);
                if(conn.getResponseCode()!=200)return;
                int announced=conn.getContentLength();if(announced>700000)return;
                temp=new File(getCacheDir(),"real_rain_partial.ogg");
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(temp)){
                    byte[] buffer=new byte[8192];int n,used=0;
                    while((n=in.read(buffer))!=-1){used+=n;if(used>700000)return;out.write(buffer,0,n);}
                }
                if(temp.length()>10000)temp.renameTo(rainRecording());
            }catch(Exception ignored){}finally{
                if(conn!=null)conn.disconnect();if(temp!=null&&temp.exists())temp.delete();
            }
        },"GlassRainCache").start();
    }
    // Public-domain nature recordings from Wikimedia Commons, licensed individually in AUDIO_LICENSES_065.md.
    // 4 distinct conditions. Bundled by Gradle with verified source filenames; cached locally at playback.
    private static final String[] NATURE_KEYS={"sun","cloud","rain","storm"};
    private static final String[] NATURE_FILENAMES={"zen_clear.ogg","zen_cloud.ogg","zen_rain.ogg","zen_storm.ogg"};
    private volatile long latestAudioRequest=0;
    void playWeatherAudio(String kind){
        if(!getSoundFx()){soundStatus("Nature audio OFF");return;}
        int position=-1;
        for(int i=0;i<NATURE_KEYS.length;i++)if(NATURE_KEYS[i].equals(kind))position=i;
        if(position<0)return;
        final int index=position;
        final long request=++latestAudioRequest;
        new Thread(()->{
            final File local=new File(getCacheDir(),NATURE_FILENAMES[index]);
            if(!local.exists()||local.length()<5000){
                try(InputStream input=getAssets().open("nature/"+NATURE_FILENAMES[index]);FileOutputStream output=new FileOutputStream(local)){
                    byte[] data=new byte[8192];int n;
                    while((n=input.read(data))!=-1)output.write(data,0,n);
                }catch(Exception ex){local.delete();soundStatus("Missing natural sound: "+NATURE_KEYS[index]);return;}
            }
            ui.post(()->{
                if(request!=latestAudioRequest||isFinishing()||isDestroyed()||!getSoundFx())return;
                stopWeatherAudioOnUi();
                try{
                    audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
                    AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                         .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                    soundFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                         .setAudioAttributes(attr).build();
                    if(audioManager!=null&&audioManager.requestAudioFocus(soundFocus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
                        soundStatus("TV audio focus unavailable");return;
                    }
                    MediaPlayer m=new MediaPlayer();m.setAudioAttributes(attr);m.setDataSource(local.getAbsolutePath());
                    m.setLooping(false);m.prepare();weatherSound=m;
                    // Low levels and no periodic play. Storm has natural distant thunder, no jump scare.
                    float volume=index==3?.085f:index==2?.12f:index==1?.075f:.105f;
                    m.setVolume(volume,volume);
                    m.setOnCompletionListener(mp->{if(weatherSound==mp){weatherSound=null;abandonSoundFocus();}mp.release();});
                    m.setOnErrorListener((mp,what,extra)->{if(weatherSound==mp){weatherSound=null;abandonSoundFocus();}mp.release();soundStatus("Nature recording failed to play");return true;});
                    m.start();soundStatus("Now playing: "+NATURE_KEYS[index]+" (natural recording)");
                    ui.postDelayed(()->{if(weatherSound==m){stopWeatherAudioOnUi();}},index==3?15000:10500);
                }catch(Exception ex){soundStatus("Unable to play this nature recording");abandonSoundFocus();}
            });
        },"GlassNature-"+kind).start();
    }
    void setWeatherFx(boolean state){prefs().edit().putBoolean("weather_fx",state).apply();remoteUpdated();}
    void setTmdbToken(String token){
        // This is an optional TMDB read-only API token, NOT a GitHub key or account password.
        if(token==null)return;
        token=token.trim();
        if(token.isEmpty()||token.matches("[a-zA-Z0-9_.\\-]{32,700}")) {
            prefs().edit().putString("tmdb_token",token).apply();
            remoteUpdated();
        }
    }
    boolean hasTmdbToken(){return !prefs().getString("tmdb_token","").isEmpty();}
    JSONObject companionState(){
        JSONObject j=new JSONObject();
        try{
            j.put("place",getPlace());j.put("wallpaper",getWallpaperMode());
            j.put("source",getWallpaperSource());j.put("layoutMode",getLayoutMode());j.put("appsFirst",getAppsFirst());j.put("showAppTitles",getShowAppTitles());j.put("swapInfo",getSwapInfo());JSONObject sizes=new JSONObject();for(String key:new String[]{"info","trending","apps"})sizes.put(key,getWidgetSize(key));j.put("widgetSizes",sizes);j.put("recoSource",getRecommendationSource());j.put("fx",getWeatherFx());j.put("soundFx",getSoundFx());j.put("glassOpacity",getGlassOpacity());j.put("glassBlur",getGlassBlur());
            j.put("userWallpapers",userWallpapers.state());
            j.put("wallpaperDisplayedId",wallpaperDisplayedId);j.put("wallpaperDisplayStatus",wallpaperDisplayError);j.put("theme",getGlassTheme());
            j.put("tmdbConnected",hasTmdbToken());j.put("cards",cardState());j.put("hidden",hiddenApps());j.put("favorites",favorites());
            j.put("apps",apps(false));
        }catch(Exception ignored){}
        return j;
    }

    // TMDB region MY provider data uses TMDB IDs, NOT Netflix catalogue IDs.
    // When verified Netflix is listed, open Netflix's in-app search page if supported.
    // For Apple/TVMaze data or unresolved providers use Stremio's documented search route.
    private boolean netflixMalaysiaAvailable(String kind,int tmdbId){
        if(tmdbId<1||tmdbId>50_000_000||!hasTmdbToken())return false;
        HttpURLConnection conn=null;
        try{
            String token=prefs().getString("tmdb_token","");
            String url="https://api.themoviedb.org/3/"+("tv".equals(kind)?"tv":"movie")+"/"+tmdbId+"/watch/providers";
            if(token.length()==32)url+="?api_key="+token;
            conn=(HttpURLConnection)new URL(url).openConnection();
            conn.setConnectTimeout(6000);conn.setReadTimeout(6000);
            if(token.length()!=32)conn.setRequestProperty("Authorization","Bearer "+token);
            if(conn.getResponseCode()!=200)return false;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=new BufferedInputStream(conn.getInputStream())){
                byte[] b=new byte[4096];int k;
                while((k=in.read(b))>0){if(bytes.size()+k>90_000)return false;bytes.write(b,0,k);}
            }
            JSONObject malaysia=new JSONObject(bytes.toString("UTF-8")).optJSONObject("results");
            if(malaysia==null)return false;
            JSONObject region=malaysia.optJSONObject("MY");if(region==null)return false;
            JSONArray flatrate=region.optJSONArray("flatrate");if(flatrate==null)return false;
            for(int i=0;i<flatrate.length();i++){
                JSONObject v=flatrate.optJSONObject(i);
                if(v!=null&&(v.optInt("provider_id")==8||v.optString("provider_name","").toLowerCase(java.util.Locale.ROOT).startsWith("netflix")))return true;
            }
        }catch(Exception ignored){}
        finally{if(conn!=null)conn.disconnect();}
        return false;
    }
    private void openNetflixTitleSearch(String title){
        Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.netflix.com/search?q="+Uri.encode(title)));
        link.setPackage("com.netflix.ninja");link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(link);Toast.makeText(this,"Netflix MY • Cari: "+title,Toast.LENGTH_LONG).show();return;}catch(Exception ignored){}
        Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage("com.netflix.ninja");
        if(launch==null)launch=getPackageManager().getLaunchIntentForPackage("com.netflix.ninja");
        if(launch!=null){try{startActivity(launch);Toast.makeText(this,"Cari dalam Netflix: "+title,Toast.LENGTH_LONG).show();return;}catch(Exception ignored){}}
        openStremioTitleSearch(title);
    }
    private void openStremioTitleSearch(String title){
        Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("stremio:///search?search="+Uri.encode(title)));
        link.setPackage("com.stremio.one");link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(link);return;}catch(Exception ignored){}
        Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage("com.stremio.one");
        if(launch==null)launch=getPackageManager().getLaunchIntentForPackage("com.stremio.one");
        if(launch!=null){try{startActivity(launch);Toast.makeText(this,"Cari dalam Stremio: "+title,Toast.LENGTH_LONG).show();return;}catch(Exception ignored){}}
        Toast.makeText(this,"Stremio tidak tersedia pada TV ini",Toast.LENGTH_LONG).show();
    }
    void openTrending(String title,String kind,String rawId){
        if(title==null||title.trim().length()<1||title.length()>160||!("movie".equals(kind)||"tv".equals(kind)))return;
        final String clean=title.trim();final int id;
        try{id=Integer.parseInt(rawId);}catch(Exception ignored){ui.post(()->openStremioTitleSearch(clean));return;}
        new Thread(()->{
            boolean netflix=netflixMalaysiaAvailable(kind,id);
            ui.post(()->{if(netflix)openNetflixTitleSearch(clean);else openStremioTitleSearch(clean);});
        },"GlassONN-ProviderMY").start();
    }

    void showAppInfo(String pkg){
        if(!appExists(pkg))return;
        ui.post(()->{
            try{
                Intent intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+pkg));
                startActivity(intent);
            }catch(Exception ex){Toast.makeText(this,"Tetapan aplikasi tidak tersedia",Toast.LENGTH_SHORT).show();}
        });
    }
    void requestAppUninstall(String pkg){
        if(!appExists(pkg))return;
        ui.post(()->{
            try{
                // ACTION_DELETE routes through Android's system confirmation UI.
                Intent intent=new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+pkg));
                startActivity(intent);
            }catch(Exception ex){
                Toast.makeText(this,"Android tidak menyediakan Uninstall untuk aplikasi ini",Toast.LENGTH_LONG).show();
                showAppInfo(pkg);
            }
        });
    }
    private class LocalBridge {
        @JavascriptInterface public String traktState(){return trakt.state().toString();}
        @JavascriptInterface public void traktConnect(String id){trakt.begin(id);}
        @JavascriptInterface public void traktPoll(){trakt.poll();}
        @JavascriptInterface public void traktRefresh(){trakt.refresh();}
        @JavascriptInterface public void traktDisconnect(){trakt.disconnect();remoteUpdated();}
        @JavascriptInterface public String getPlace(){return ""+MainActivity.this.getPlace();}
        @JavascriptInterface public void setPlace(String s){try{MainActivity.this.setPlace(Integer.parseInt(s));}catch(Exception ignored){}}
        @JavascriptInterface public String getTheme(){return MainActivity.this.getGlassTheme();}
        @JavascriptInterface public void setTheme(String value){MainActivity.this.setTheme(value);}
        @JavascriptInterface public void reportWallpaperDisplay(String id, boolean ok){MainActivity.this.reportWallpaperDisplay(id,ok);}
        @JavascriptInterface public String getWallpaperMode(){return MainActivity.this.getWallpaperMode();}
        @JavascriptInterface public void setWallpaperMode(String m){MainActivity.this.setWallpaperMode(m);}
        @JavascriptInterface public String getWallpaperSource(){return MainActivity.this.getWallpaperSource();}
        @JavascriptInterface public String getUserWallpaperState(){return userWallpaperState().toString();}
        @JavascriptInterface public void setWallpaperSource(String s){MainActivity.this.setWallpaperSource(s);}
        @JavascriptInterface public int getGlassOpacity(){return MainActivity.this.getGlassOpacity();}
        @JavascriptInterface public int getGlassBlur(){return MainActivity.this.getGlassBlur();}
        @JavascriptInterface public void setGlassOpacity(int value){MainActivity.this.setGlassOpacity(value);}
        @JavascriptInterface public void setGlassBlur(int value){MainActivity.this.setGlassBlur(value);}
        @JavascriptInterface public String getLayoutMode(){return MainActivity.this.getLayoutMode();}
        @JavascriptInterface public void setLayoutMode(String mode){MainActivity.this.setLayoutMode(mode);}
        @JavascriptInterface public int getWidgetSize(String key){return MainActivity.this.getWidgetSize(key);}
        @JavascriptInterface public void setWidgetSize(String key,int value){MainActivity.this.setWidgetSize(key,value);}
        @JavascriptInterface public boolean getSwapInfo(){return MainActivity.this.getSwapInfo();}
        @JavascriptInterface public void setSwapInfo(boolean value){MainActivity.this.setSwapInfo(value);}
        @JavascriptInterface public boolean getAppsFirst(){return MainActivity.this.getAppsFirst();}
        @JavascriptInterface public boolean getShowAppTitles(){return MainActivity.this.getShowAppTitles();}
        @JavascriptInterface public void setShowAppTitles(boolean on){MainActivity.this.setShowAppTitles(on);}
        @JavascriptInterface public void setAppsFirst(boolean first){MainActivity.this.setAppsFirst(first);}
        @JavascriptInterface public String getRecommendationSource(){return MainActivity.this.getRecommendationSource();}
        @JavascriptInterface public void setRecommendationSource(String mode){MainActivity.this.setRecommendationSource(mode);}
        @JavascriptInterface public boolean getWeatherFx(){return MainActivity.this.getWeatherFx();}
        @JavascriptInterface public boolean getSoundFx(){return MainActivity.this.getSoundFx();}
        @JavascriptInterface public void setSoundFx(boolean on){MainActivity.this.setSoundFx(on);}
        @JavascriptInterface public void playWeatherAudio(String kind){MainActivity.this.playWeatherAudio(kind);}
        @JavascriptInterface public void setWeatherFx(boolean on){MainActivity.this.setWeatherFx(on);}
        @JavascriptInterface public String getDisplayCards(){return cardState().toString();}
        @JavascriptInterface public void setCardVisible(String key, boolean visible){MainActivity.this.setCardVisible(key,visible);}
        @JavascriptInterface public String getHiddenApps(){return hiddenApps().toString();}
        @JavascriptInterface public void setAppHidden(String pkg,boolean hidden){MainActivity.this.setAppHidden(pkg,hidden);}
        @JavascriptInterface public void setTextEditing(boolean active){textInputFocused=active;}
        @JavascriptInterface public String getFavorites(){return favorites().toString();}
        @JavascriptInterface public void setFavorite(String pkg, boolean on){MainActivity.this.setFavorite(pkg,on);}
        @JavascriptInterface public String getApps(){return apps(true).toString();}
        @JavascriptInterface public String getCompanionInfo(){
            JSONObject j=new JSONObject();
            try{
                j.put("url",companion==null?"":companion.address());
                j.put("pin",companion==null?"":companion.pin());
                j.put("online",companion!=null&&companion.running());
            }catch(Exception ignored){}
            return j.toString();
        }
        @JavascriptInterface public boolean hasTmdb(){return hasTmdbToken();}
        @JavascriptInterface public void setTmdbToken(String token){MainActivity.this.setTmdbToken(token);}
        @JavascriptInterface public void openApp(String pkg){
            if(!appExists(pkg))return;
            ui.post(()->{
                Intent i=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if(i==null)i=getPackageManager().getLaunchIntentForPackage(pkg);
                if(i!=null){try{startActivity(i);}catch(Exception ignored){}}
                else Toast.makeText(MainActivity.this,"App tidak boleh dibuka",Toast.LENGTH_SHORT).show();
            });
        }
        @JavascriptInterface public void openTrending(String title,String kind,String tmdbId){MainActivity.this.openTrending(title,kind,tmdbId);}
        @JavascriptInterface public void openCinemeta(String kind,String imdbId){
            if(imdbId==null||!imdbId.matches("tt[0-9]{7,10}"))return;
            if(!"movie".equals(kind)&&!"series".equals(kind))return;
            ui.post(()->{
                try{
                    Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("stremio:///detail/"+kind+"/"+imdbId+("movie".equals(kind)?"/"+imdbId:"")));
                    intent.setPackage("com.stremio.one");startActivity(intent);
                }catch(Exception ex){Toast.makeText(MainActivity.this,"Tidak dapat buka Stremio; cuba buka aplikasi secara manual",Toast.LENGTH_SHORT).show();}
            });
        }
        @JavascriptInterface public void openAppInfo(String pkg){MainActivity.this.showAppInfo(pkg);}
        @JavascriptInterface public void requestUninstall(String pkg){MainActivity.this.requestAppUninstall(pkg);}
        @JavascriptInterface public void fetchDetail(String type,String imdbId){
            if(imdbId==null||!imdbId.matches("tt[0-9]{7,10}"))return;
            if(!"movie".equals(type)&&!"series".equals(type))return;
            new Thread(()->{
                HttpURLConnection conn=null;
                try{
                    conn=(HttpURLConnection)new URL("https://v3-cinemeta.strem.io/meta/"+type+"/"+imdbId+".json").openConnection();
                    conn.setConnectTimeout(6500);conn.setReadTimeout(7000);
                    if(conn.getResponseCode()!=200)return;
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    try(InputStream in=conn.getInputStream()){
                        byte[] b=new byte[4096];int n;
                        while((n=in.read(b))!=-1){if(out.size()+n>220000)return;out.write(b,0,n);}
                    }
                    JSONObject meta=new JSONObject(out.toString("UTF-8")).optJSONObject("meta");
                    if(meta==null)return;
                    JSONObject result=new JSONObject();result.put("id",imdbId);
                    result.put("year",meta.optString("releaseInfo",""));
                    result.put("description",meta.optString("description",""));
                    JSONArray cast=meta.optJSONArray("cast");JSONArray safe=new JSONArray();
                    if(cast!=null)for(int k=0;k<Math.min(cast.length(),10);k++){
                        String name=cast.optString(k,"");if(name.length()>0&&name.length()<120)safe.put(name);
                    }
                    result.put("cast",safe);
                    sendJs("if(window.onDetailMetadata)window.onDetailMetadata("+JSONObject.quote(result.toString())+")");
                }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
            },"GlassONN-CastDetail").start();
        }
        @JavascriptInterface public void fetchCinematic(String imdbId){
            if(imdbId==null||!imdbId.matches("tt[0-9]{7,10}"))return;
            new Thread(()->{
                HttpURLConnection conn=null;
                try{
                    conn=(HttpURLConnection)new URL("https://v3-cinemeta.strem.io/meta/movie/"+imdbId+".json").openConnection();
                    conn.setConnectTimeout(7000);conn.setReadTimeout(8000);
                    if(conn.getResponseCode()!=200)throw new Exception("HTTP");
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    try(InputStream in=conn.getInputStream()){
                        byte[] b=new byte[4096];int count;
                        while((count=in.read(b))!=-1){if(out.size()+count>150000)throw new Exception("Too big");out.write(b,0,count);}
                    }
                    JSONObject doc=new JSONObject(out.toString("UTF-8"));
                    JSONObject meta=doc.optJSONObject("meta");
                    String backdrop=meta==null?"":meta.optString("background","");
                    if(backdrop.startsWith("https://")&&!backdrop.contains("\"")){
                        sendJs("window.onNativeData('cinematicMeta',"+JSONObject.quote(new JSONObject().put("id",imdbId).put("url",backdrop).toString())+")");
                    }
                }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
            },"GlassONN-CinemaMeta").start();
        }
        /** Search public Cinemeta metadata only. No Stremio/Netflix account credentials are used. */
        @JavascriptInterface public void searchMedia(String query){
            if(query==null)return;
            final String term=query.trim();
            if(term.length()<2||term.length()>70)return;
            new Thread(()->{
                JSONArray items=new JSONArray();
                for(String type:new String[]{"movie","series"}){
                    HttpURLConnection conn=null;
                    try{
                        String encoded=java.net.URLEncoder.encode(term,"UTF-8").replace("+","%20");
                        URL url=new URL("https://v3-cinemeta.strem.io/catalog/"+type+"/top/search="+encoded+".json");
                        conn=(HttpURLConnection)url.openConnection();
                        conn.setConnectTimeout(7500);conn.setReadTimeout(9000);
                        conn.setRequestProperty("User-Agent","GlassONN/0.6.0 AndroidTV");
                        if(conn.getResponseCode()!=200)continue;
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        try(InputStream in=new BufferedInputStream(conn.getInputStream())){
                            byte[] buffer=new byte[8192];int n;
                            while((n=in.read(buffer))!=-1){if(out.size()+n>1_200_000)break;out.write(buffer,0,n);}
                        }
                        JSONObject response=new JSONObject(out.toString("UTF-8"));
                        JSONArray metas=response.optJSONArray("metas");
                        if(metas!=null)for(int i=0;i<metas.length()&&i<35;i++){
                            JSONObject m=metas.optJSONObject(i);if(m==null)continue;
                            m.put("type",type);items.put(m);
                        }
                    }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
                }
                JSONObject result=new JSONObject();
                try{result.put("items",items);}catch(Exception ignored){}
                sendJs("window.onSearchResults("+JSONObject.quote(term)+","+JSONObject.quote(result.toString())+")");
            },"GlassONN-CinemetaSearch").start();
        }
        @JavascriptInterface public void fetch(String kind){
            if(!"traktMovies".equals(kind)&&!"traktShows".equals(kind)&&!"weather".equals(kind)&&!"prayer".equals(kind)&&!"gallery".equals(kind)&&!"trending".equals(kind)&&!"popularMovies".equals(kind)&&!"popularShows".equals(kind)&&!"cinemetaMovies".equals(kind)&& !"cinemetaSeries".equals(kind)&&!"prayerYear".equals(kind)&&!"netflixMovies".equals(kind)&&!"netflixShows".equals(kind))return;
            new Thread(()->{
                HttpURLConnection conn=null;
                try{
                    String url;
                    if("traktMovies".equals(kind)||"traktShows".equals(kind)){
                        String publicId=trakt.getPublicClientId();
                        if(publicId.isEmpty()){sendJs("window.onNativeData("+JSONObject.quote(kind)+",'')");return;}
                        url="https://api.trakt.tv/"+("traktMovies".equals(kind)?"movies":"shows")+"/trending?limit=30&extended=full";
                    }else if("weather".equals(kind)){
                        String[] p=places[MainActivity.this.getPlace()];
                        url="https://api.open-meteo.com/v1/forecast?latitude="+p[2]+"&longitude="+p[3]+
                            "&current=temperature_2m,relative_humidity_2m,weather_code,is_day&timezone=Asia%2FKuala_Lumpur";
                    }else if("prayer".equals(kind)){
                        url="https://solat.my/api/daily/"+places[MainActivity.this.getPlace()][1];
                    }else if("prayerYear".equals(kind)){
                        java.util.Calendar myDay=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
                        myDay.add(java.util.Calendar.DAY_OF_MONTH,1);
                        url="https://solat.my/api/yearly/"+places[MainActivity.this.getPlace()][1]+"/"+myDay.get(java.util.Calendar.YEAR);
                    }else if("netflixMovies".equals(kind)||"netflixShows".equals(kind)){
                        if(!hasTmdbToken()){sendJs("window.onNativeData("+JSONObject.quote(kind)+",'')");return;}
                        String media="netflixShows".equals(kind)?"tv":"movie";
                        url="https://api.themoviedb.org/3/discover/"+media+"?watch_region=MY&with_watch_providers=8&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&language=en-US&page=1";
                    }else if("popularMovies".equals(kind)){
                        url="https://itunes.apple.com/us/rss/topmovies/limit=50/json";
                    }else if("popularShows".equals(kind)){
                        url="https://api.tvmaze.com/schedule?country=US";
                    }else if("cinemetaMovies".equals(kind)){
                        url="https://v3-cinemeta.strem.io/catalog/movie/top.json";
                    }else if("cinemetaSeries".equals(kind)){
                        url="https://v3-cinemeta.strem.io/catalog/series/top.json";
                    }else if("gallery".equals(kind)){
                        url="https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers"+
                          "&gcmtitle=Category%3AFeatured_pictures_of_landscapes&gcmtype=file&gcmlimit=25"+
                          "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1600&format=json";
                    }else{
                        if(!hasTmdbToken()){sendJs("window.onNativeData('trending','')");return;}
                        url="https://api.themoviedb.org/3/trending/all/week?language=en-US&page=1";
                    }
                    conn=(HttpURLConnection)new URL(url).openConnection();
                    conn.setConnectTimeout(9000);conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent","GlassONN/0.6.0 (https://github.com/siblims78-rgb/Glass-zubir) AndroidTV");
                    if("traktMovies".equals(kind)||"traktShows".equals(kind)){
                        conn.setRequestProperty("trakt-api-version","2");
                        conn.setRequestProperty("trakt-api-key",trakt.getPublicClientId());
                        conn.setRequestProperty("Accept","application/json");
                    }
                    if("trending".equals(kind)||"netflixMovies".equals(kind)||"netflixShows".equals(kind)){
                        String token=prefs().getString("tmdb_token","");
                        if(token.length()==32){ // legacy TMDB v3 API key, not a Bearer token
                            conn.disconnect();conn=(HttpURLConnection)new URL(url+"&api_key="+token).openConnection();
                            conn.setConnectTimeout(9000);conn.setReadTimeout(10000);
                        }else conn.setRequestProperty("Authorization","Bearer "+token);
                    }
                    if(conn.getResponseCode()!=200)throw new Exception("API HTTP "+conn.getResponseCode());
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    int max="prayerYear".equals(kind)?2_500_000:("gallery".equals(kind)?1_200_000:("popularShows".equals(kind)||kind.startsWith("cinemeta")?2_000_000:900_000));
                    try(InputStream in=new BufferedInputStream(conn.getInputStream())){
                        byte[] buffer=new byte[8192];int count;
                        while((count=in.read(buffer))!=-1){
                            if(out.size()+count>max)throw new Exception("Too much data");
                            out.write(buffer,0,count);
                        }
                    }
                    sendJs("window.onNativeData("+JSONObject.quote(kind)+","+JSONObject.quote(out.toString("UTF-8"))+")");
                }catch(Exception ex){sendJs("window.onNativeData("+JSONObject.quote(kind)+",'')");}
                finally{if(conn!=null)conn.disconnect();}
            },"GlassONN-API-"+kind).start();
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(companion==null||!companion.running()){
            companion=new CompanionServer(this);
            companion.start();
        }
        remoteUpdated();
    }
    @Override protected void onPause(){
        // Paired LAN control is only reachable while Glass ONN is the foreground app.
        if(companion!=null)companion.stop();
        stopWeatherAudio();
        super.onPause();
    }
    @Override public void onBackPressed(){sendJs("if(window.handleBack)window.handleBack()");}
    @Override protected void onDestroy(){
        if(companion!=null)companion.stop();
        stopWeatherAudio();
        if(page!=null){page.removeJavascriptInterface("Native");page.destroy();page=null;}
        super.onDestroy();
    }
}
