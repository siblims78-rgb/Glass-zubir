package com.glassonn.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Optional personal Trakt OAuth device-code integration. No Trakt account password,
 * OAuth tokens or private developer credentials are compiled into the APK or served over Companion HTTP.
 * Credentials and tokens are encrypted with a non-exportable Android Keystore key at rest.
 *
 * Trakt recommends Authorization Code + PKCE for public apps with verified HTTPS App Links.
 * This TV Device Code flow uses only the personal Trakt API Client ID.
 * Newly created Trakt apps may not expose a Client Secret; never embed one in an APK.
 */
final class TraktSync {
    interface Events { void event(String type, JSONObject payload); }
    private static final String PREF="trakt_v1_ciphertext";
    private static final String KEY_ALIAS="glassonn_local_trakt_v1";
    private static final String AUTH="https://auth.trakt.tv";
    private static final String API="https://api.trakt.tv";
    private final Context context;
    private final Events events;
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private String clientId="",accessToken="",refreshToken="";
    private long tokenExpires=0;
    private String deviceCode="";
    private long deviceExpiry=0, lastPoll=0;
    private int interval=5;
    private volatile int session=0;

    TraktSync(Context ctx, Events emitter) {
        context=ctx.getApplicationContext();events=emitter;
        try {
            String encrypted=prefs().getString(PREF,"");
            if(!encrypted.isEmpty()) {
                JSONObject j=new JSONObject(decrypt(encrypted));
                clientId=j.optString("id","");
                accessToken=j.optString("access","");refreshToken=j.optString("refresh","");
                tokenExpires=j.optLong("expires",0);
            }
        } catch(Exception ex) { disconnect(); }
        // Public OAuth Client ID may come from build; never embed an account token.
        if(!credential(clientId)&&credential(BuildConfig.DEFAULT_TRAKT_CLIENT_ID))
            clientId=BuildConfig.DEFAULT_TRAKT_CLIENT_ID;
    }
    private SharedPreferences prefs(){return context.getSharedPreferences("glassonn_trakt",Context.MODE_PRIVATE);}
    private SecretKey aesKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        KeyStore.Entry existing=store.getEntry(KEY_ALIAS,null);
        if(existing instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)existing).getSecretKey();
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }
    private String encrypt(String plain) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,aesKey());
        byte[] iv=cipher.getIV(),data=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all=new byte[iv.length+data.length];System.arraycopy(iv,0,all,0,iv.length);
        System.arraycopy(data,0,all,iv.length,data.length);
        return Base64.encodeToString(all,Base64.NO_WRAP);
    }
    private String decrypt(String encoded) throws Exception {
        byte[] all=Base64.decode(encoded,Base64.NO_WRAP);
        if(all.length<28)throw new IllegalStateException("Trakt cipher too short");
        byte[] iv=new byte[12];System.arraycopy(all,0,iv,0,12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,aesKey(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(all,12,all.length-12),StandardCharsets.UTF_8);
    }
    private synchronized void save() throws Exception {
        JSONObject j=new JSONObject();j.put("id",clientId);
        j.put("access",accessToken);j.put("refresh",refreshToken);j.put("expires",tokenExpires);
        if(!prefs().edit().putString(PREF,encrypt(j.toString())).commit())throw new IllegalStateException("Unable to save token");
    }
    synchronized String getPublicClientId(){
        if(credential(clientId))return clientId;
        return credential(BuildConfig.DEFAULT_TRAKT_CLIENT_ID)?BuildConfig.DEFAULT_TRAKT_CLIENT_ID:"";
    }
        synchronized JSONObject state() {
        JSONObject j=new JSONObject();
        try{j.put("configured",!getPublicClientId().isEmpty());j.put("connected",!accessToken.isEmpty());
            j.put("busy",busy.get());j.put("waiting",!deviceCode.isEmpty()&&System.currentTimeMillis()<deviceExpiry);
            j.put("expiresAt",tokenExpires);}catch(Exception ignored){}
        return j;
    }
    synchronized void disconnect(){
        session++;
        clientId="";accessToken="";refreshToken="";tokenExpires=0;
        deviceCode="";deviceExpiry=0;lastPoll=0;
        prefs().edit().remove(PREF).apply();
        // Local disconnect only. Existing Trakt grant may be revoked on trakt.tv settings.
    }
    private void emit(String type,JSONObject payload) {events.event(type,payload);}
    private void error(String msg){JSONObject j=new JSONObject();try{j.put("message",msg);}catch(Exception ignored){}emit("error",j);}
    private static boolean credential(String x){return x!=null&&x.length()>=20&&x.length()<=256&&x.matches("[A-Za-z0-9._-]+");}
    void begin(String id) {
        id=id==null?"":id.trim();
        if(id.isEmpty())id=getPublicClientId();
        if(!credential(id)){error("Client ID belum ada. Masukkan sekali sahaja atau tetapkan ID dalam build.");return;}
        synchronized(this){if(id.equals(clientId)&&!accessToken.isEmpty()){emit("connected",state());return;}}
        if(!busy.compareAndSet(false,true))return;
        final String selectedId=id;
        new Thread(()->{
            try{
                synchronized(this){session++;clientId=selectedId;accessToken="";refreshToken="";tokenExpires=0;deviceCode="";save();}
                JSONObject request=new JSONObject().put("client_id",selectedId);
                JSONObject response=http(AUTH+"/oauth/device/code",request,selectedId,null);
                synchronized(this){if(!clientId.equals(selectedId))return;deviceCode=response.getString("device_code");
                    interval=Math.max(5,Math.min(60,response.optInt("interval",5)));
                    deviceExpiry=System.currentTimeMillis()+Math.min(1800,response.optInt("expires_in",600))*1000L;
                    lastPoll=0;
                }
                JSONObject event=new JSONObject();event.put("user_code",response.optString("user_code",""));
                event.put("verification_url",response.optString("verification_url","https://trakt.tv/activate"));
                event.put("interval",interval);emit("device",event);
            }catch(ApiError e){error("Trakt gagal keluarkan kod TV (HTTP "+e.status+"). Semak Client ID dan akses API.");}
            catch(Exception e){error("Tidak dapat hubungi Trakt untuk meminta kod TV. Semak internet.");}
            finally{busy.set(false);}
        },"GlassONN-TraktStart").start();
    }
    void poll(){
        final String code,id;final int currentSession;
        synchronized(this){
            if(deviceCode.isEmpty())return;
            if(System.currentTimeMillis()>=deviceExpiry){deviceCode="";error("Kod Trakt tamat tempoh. Cuba sambung semula.");return;}
            if(System.currentTimeMillis()-lastPoll<interval*1000L)return;
            if(!busy.compareAndSet(false,true))return;
            lastPoll=System.currentTimeMillis();code=deviceCode;id=clientId;currentSession=session;
        }
        new Thread(()->{
            try{
                JSONObject request=new JSONObject().put("code",code).put("client_id",id);
                JSONObject response=http(AUTH+"/oauth/device/token",request,id,null);
                synchronized(this){if(session!=currentSession)return;deviceCode="";storeToken(response);}
                emit("connected",state());
            }catch(ApiError e){
                if(e.status==400){} // device authorization pending
                else if(e.status==429){synchronized(this){interval=Math.min(90,interval+5);}}
                else if(e.status==403){error("Trakt menolak aplikasi. Semak Client ID/API app.");}
                else if(e.status==404||e.status==410||e.status==409){synchronized(this){deviceCode="";}error("Kod Trakt tamat tempoh / dibatalkan.");}
                else error("Sambungan Trakt gagal (HTTP "+e.status+").");
            }catch(Exception e){error("Tiada sambungan ke Trakt. Cuba semula.");}
            finally{busy.set(false);}
        },"GlassONN-TraktPoll").start();
    }
    private synchronized void storeToken(JSONObject response) throws Exception {
        String access=response.optString("access_token","");
        if(access.isEmpty())throw new IllegalStateException("No token");
        String refresh=response.optString("refresh_token","");
        accessToken=access;refreshToken=refresh;
        tokenExpires=System.currentTimeMillis()+Math.max(60,response.optLong("expires_in",604800))*1000L;
        save();
    }
    void refresh(){
        if(!busy.compareAndSet(false,true))return;
        final int currentSession; synchronized(this){currentSession=session;}
        new Thread(()->{
            try{
                String token=getAccess();if(token.isEmpty()){error("Akaun Trakt belum disambungkan.");return;}
                JSONArray raw=httpArray(API+"/sync/playback?extended=full",clientId,token);
                JSONArray items=new JSONArray();
                for(int i=0;i<raw.length()&&items.length()<12;i++){
                    JSONObject row=raw.optJSONObject(i);if(row==null)continue;
                    double progress=row.optDouble("progress",-1);
                    if(progress<=0||progress>=100)continue;
                    String type=row.optString("type","");boolean movie="movie".equals(type);
                    JSONObject media=row.optJSONObject(movie?"movie":"show");
                    if(media==null)continue;
                    JSONObject ids=media.optJSONObject("ids");String imdb=ids==null?"":ids.optString("imdb","");
                    JSONObject episode=row.optJSONObject("episode");
                    String title=media.optString("title","");if(title.isEmpty())continue;
                    JSONObject item=new JSONObject();
                    item.put("title",title);item.put("year",media.optInt("year",0));
                    item.put("type",movie?"Filem":"Siri");item.put("imdbId",imdb);
                    item.put("progress",Math.round(progress*10)/10.0);
                    item.put("pausedAt",row.optString("paused_at",""));
                    int runtime=movie?media.optInt("runtime",0):(episode==null?0:episode.optInt("runtime",0));
                    if(runtime>0&&runtime<1000)item.put("minutesLeft",Math.max(0,Math.round(runtime*(100-progress)/100)));
                    if(!movie&&episode!=null){
                        int season=episode.optInt("season",0),number=episode.optInt("number",0);
                        item.put("episode","S"+String.format(java.util.Locale.US,"%02d",season)+"E"+String.format(java.util.Locale.US,"%02d",number));
                        item.put("episodeTitle",episode.optString("title",""));
                    }
                    items.put(item);
                }
                JSONObject payload=new JSONObject().put("items",items);
                if(session==currentSession)emit("progress",payload);
            }catch(ApiError e){
                if(e.status==401){error("Trakt perlu sambung semula (token tamat tempoh).");}
                else if(e.status==402||e.status==403){error("Akses playback Trakt tidak dibenarkan untuk aplikasi/akaun ini.");}
                else if(e.status==429){error("Had permintaan Trakt dicapai. Cuba kemudian.");}
                else error("Trakt API tidak tersedia (HTTP "+e.status+").");
            }catch(Exception e){error("Tak dapat baca progress Trakt. Semak internet dan akaun.");}
            finally{busy.set(false);}
        },"GlassONN-TraktProgress").start();
    }
    private synchronized String getAccess() throws Exception {
        if(accessToken.isEmpty())return "";
        if(System.currentTimeMillis()<tokenExpires-120000)return accessToken;
        if(refreshToken.isEmpty()){accessToken="";save();return "";}
        JSONObject request=new JSONObject().put("client_id",clientId)
                .put("refresh_token",refreshToken).put("grant_type","refresh_token");
        JSONObject response=http(AUTH+"/oauth/token",request,clientId,null);
        // Trakt refresh tokens are single-use: persist both replacements atomically.
        storeToken(response);return accessToken;
    }
    private static final class ApiError extends Exception {
        final int status; ApiError(int code){super("HTTP "+code);status=code;}
    }
    private JSONObject http(String address,JSONObject body,String id,String access) throws Exception {
        String content=request(address,body,id,access);return new JSONObject(content);
    }
    private JSONArray httpArray(String address,String id,String access) throws Exception {
        return new JSONArray(request(address,null,id,access));
    }
    private String request(String address,JSONObject body,String id,String access) throws Exception {
        if(!(address.startsWith(AUTH+"/")||address.startsWith(API+"/")))throw new IllegalArgumentException();
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(7500);c.setReadTimeout(9500);
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("trakt-api-version","2");
            c.setRequestProperty("trakt-api-key",id);
            c.setRequestProperty("User-Agent","GlassONN-Trakt/0.6.3 AndroidTV");
            if(access!=null)c.setRequestProperty("Authorization","Bearer "+access);
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);
                try(OutputStream out=c.getOutputStream()){out.write(b);}}
            int code=c.getResponseCode();
            if(code<200||code>299)throw new ApiError(code);
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))>0){if(data.size()+n>400_000)throw new IllegalStateException("Trakt response too large");data.write(buf,0,n);}
            }
            return data.toString("UTF-8");
        }finally{if(c!=null)c.disconnect();}
    }
}
