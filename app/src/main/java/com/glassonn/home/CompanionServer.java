package com.glassonn.home;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;

/** Minimal paired LAN-only controller; active only while this app's process is running.
 *  HTTP on a trusted home Wi-Fi is NOT encrypted. Don't use on untrusted networks. */
final class CompanionServer {
    private static final int PORT=8765;
    private final MainActivity owner;
    private final SecureRandom random=new SecureRandom();
    private final String pairingCode;
    private volatile String sessionToken="";
    private volatile boolean active=false;
    private ServerSocket socket;
    private Thread loop;
    private int badPins=0;
    private long blockedUntil=0;

    CompanionServer(MainActivity activity){owner=activity;pairingCode=String.format(Locale.US,"%06d",random.nextInt(1_000_000));}
    String pin(){return pairingCode;}
    boolean running(){return active&&socket!=null&&!socket.isClosed();}
    String address(){String ip=localIp();return running()&&!ip.isEmpty()?"http://"+ip+":"+PORT:"";}
    private String localIp(){
        try{
            for(Enumeration<NetworkInterface> its=NetworkInterface.getNetworkInterfaces();its.hasMoreElements();){
                NetworkInterface iface=its.nextElement();
                if(!iface.isUp()||iface.isLoopback())continue;
                String interfaceName=iface.getName().toLowerCase(Locale.US);
                if(interfaceName.startsWith("tun")||interfaceName.startsWith("vpn")||interfaceName.startsWith("tap"))continue;
                for(Enumeration<InetAddress> ips=iface.getInetAddresses();ips.hasMoreElements();){
                    InetAddress a=ips.nextElement();
                    if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;
                    String ip=a.getHostAddress();
                    if(isPrivate(ip))return ip;
                }
            }
        }catch(Exception ignored){}
        return "";
    }
    private static boolean isPrivate(String s){
        return s.startsWith("192.168.")||s.startsWith("10.")||s.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..+");
    }
    void start(){
        try{
            socket=new ServerSocket(PORT,4);
            socket.setSoTimeout(1000);active=true;
            loop=new Thread(()->{
                while(active){
                    try(Socket client=socket.accept()){
                        client.setSoTimeout(3500);
                        InetAddress a=client.getInetAddress();
                        if(!a.isLoopbackAddress()&&!isPrivate(a.getHostAddress()))continue;
                        handle(client);
                    }catch(SocketTimeoutException ignored){}
                    catch(Exception ignored){}
                }
            },"GlassONN-Pairing");
            loop.setDaemon(true);loop.start();
        }catch(Exception e){active=false;}
    }
    void stop(){active=false;sessionToken="";try{if(socket!=null)socket.close();}catch(Exception ignored){}}
    private static String line(InputStream in,int max)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1&&c!='\n'){
            if(c!='\r'){if(out.size()>=max)throw new Exception("Header too large");out.write(c);}
        }
        return out.toString("ISO-8859-1");
    }
    private static String newToken(SecureRandom r){
        byte[] b=new byte[24];r.nextBytes(b);StringBuilder s=new StringBuilder();
        for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();
    }
    private void reply(Socket c,int status,String type,String contents)throws Exception{
        replyBytes(c,status,type+"; charset=utf-8",contents.getBytes(StandardCharsets.UTF_8));
    }
    private void replyBytes(Socket c,int status,String type,byte[] data)throws Exception{
        String msg=status==200?"OK":status==400?"Bad Request":status==401?"Unauthorized":status==403?"Forbidden":status==404?"Not Found":status==413?"Payload Too Large":"Unavailable";
        String headers="HTTP/1.1 "+status+" "+msg+"\r\nContent-Type: "+type+"\r\n"+
            "Content-Length: "+data.length+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+
            "X-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; frame-ancestors 'none'\r\nConnection: close\r\n\r\n";
        OutputStream os=c.getOutputStream();os.write(headers.getBytes(StandardCharsets.US_ASCII));os.write(data);os.flush();
    }
    private void json(Socket c,int status,JSONObject obj)throws Exception{reply(c,status,"application/json",obj.toString());}
    private JSONObject error(String text){JSONObject o=new JSONObject();try{o.put("error",text);}catch(Exception ignored){}return o;}
    private void handle(Socket c)throws Exception{
        BufferedInputStream in=new BufferedInputStream(c.getInputStream());
        String first=line(in,4096);String[] req=first.split(" ");if(req.length<2)return;
        String method=req[0],path=req[1];String host="",origin="",token="",contentType="";int contentLength=0;
        for(int i=0;i<70;i++){
            String h=line(in,8192);if(h.isEmpty())break;
            int colon=h.indexOf(':');if(colon<1)continue;
            String k=h.substring(0,colon).trim().toLowerCase(Locale.US),v=h.substring(colon+1).trim();
            if(k.equals("host"))host=v;
            else if(k.equals("origin"))origin=v;
            else if(k.equals("x-glass-token"))token=v;
            else if(k.equals("content-type"))contentType=v;
            else if(k.equals("content-length"))try{contentLength=Integer.parseInt(v);}catch(Exception ignored){}
        }
        String ip=localIp();
        if(!host.equals(ip+":"+PORT)&&!host.equals("127.0.0.1:"+PORT)&&!host.equals("localhost:"+PORT)){
            json(c,403,error("Alamat tidak dibenarkan"));return;
        }
        if(!origin.isEmpty()&&!origin.equals("http://"+host)){
            json(c,403,error("Origin tidak dibenarkan"));return;
        }
        boolean upload="POST".equals(method)&&"/wallpaper/upload".equals(path);
        int limit=upload?UserWallpaperStore.MAX_UPLOAD_BYTES:16_384;
        if(contentLength<0||contentLength>limit){json(c,413,error("Gambar atau mesej terlalu besar"));return;}
        // Authenticate before receiving multi-megabyte photo bodies.
        if(upload && (sessionToken.isEmpty()||!sessionToken.equals(token))){json(c,401,error("Pairing diperlukan"));return;}
        byte[] raw=new byte[contentLength];int bodyBytesRead=0;
        while(bodyBytesRead<contentLength){int k=in.read(raw,bodyBytesRead,contentLength-bodyBytesRead);if(k<0)break;bodyBytesRead+=k;}
        if(bodyBytesRead!=contentLength)throw new Exception("Body incomplete");
        if(upload){
            if(!contentType.toLowerCase(Locale.US).startsWith("image/jpeg")){json(c,400,error("Hanya JPEG dibenarkan"));return;}
            try{json(c,200,owner.uploadUserWallpaper(raw));}catch(Exception e){json(c,400,error(e.getMessage()==null?"Upload gagal":e.getMessage()));}
            return;
        }
        String body=new String(raw,StandardCharsets.UTF_8);
        if("GET".equals(method)&&"/".equals(path)){
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            try(InputStream asset=owner.getAssets().open("companion.html")){
                byte[] b=new byte[4096];int n;while((n=asset.read(b))>0)data.write(b,0,n);
            }
            reply(c,200,"text/html",data.toString("UTF-8"));return;
        }
        JSONObject input=new JSONObject();
        if("POST".equals(method)){try{input=new JSONObject(body);}catch(Exception ex){json(c,400,error("JSON tidak sah"));return;}}
        if("POST".equals(method)&&"/pair".equals(path)){
            if(System.currentTimeMillis()<blockedUntil){json(c,403,error("Cuba lagi kemudian"));return;}
            if(!pairingCode.equals(input.optString("pin"))){
                badPins++;if(badPins>=5){badPins=0;blockedUntil=System.currentTimeMillis()+120_000;}
                json(c,401,error("Kod pairing tidak tepat"));return;
            }
            badPins=0;sessionToken=newToken(random);
            JSONObject ok=new JSONObject();ok.put("token",sessionToken);json(c,200,ok);return;
        }
        if(sessionToken.isEmpty()||!sessionToken.equals(token)){
            json(c,401,error("Pairing diperlukan"));return;
        }
        if("GET".equals(method)&&"/state".equals(path)){
            json(c,200,owner.companionState());return;
        }
        if("GET".equals(method)&&path.startsWith("/wallpaper/thumb?id=")){
            String id=path.substring("/wallpaper/thumb?id=".length());
            if(!id.matches("[a-f0-9]{32}")){json(c,400,error("ID gambar tidak sah"));return;}
            byte[] thumb=owner.userWallpaperThumbnail(id);
            if(thumb==null){json(c,404,error("Gambar tiada dalam cache"));return;}
            replyBytes(c,200,"image/jpeg",thumb);return;
        }
        if("POST".equals(method)&&"/wallpaper/mode".equals(path)){
            boolean ok=owner.selectUserWallpaper(input.optString("mode"),input.optString("selected"));
            json(c,ok?200:400,ok?owner.userWallpaperState():error("Pilih mod dan gambar yang sah"));return;
        }
        if("POST".equals(method)&&"/wallpaper/delete".equals(path)){
            boolean ok=owner.removeUserWallpaper(input.optString("id"));
            json(c,ok?200:404,ok?owner.userWallpaperState():error("Gambar tidak dijumpai"));return;
        }
        if("POST".equals(method)&&"/wallpaper/clear".equals(path)){
            owner.clearUserWallpapers();json(c,200,owner.userWallpaperState());return;
        }
        if("POST".equals(method)&&"/set".equals(path)){
            String name=input.optString("key"),v=input.optString("value");boolean ok=true;
            switch(name){
                case "place":try{int x=Integer.parseInt(v);if(x>=0&&x<5)owner.setPlace(x);else ok=false;}catch(Exception ex){ok=false;}break;
                case "wallpaper":if(v.equals("auto")||v.matches("[0-3]"))owner.setWallpaperMode(v);else ok=false;break;
                case "source":if(v.equals("local")||v.equals("commons")||v.equals("picsum")||v.equals("cinematic")||v.equals("user"))owner.setWallpaperSource(v);else ok=false;break;
                case "layoutMode":if(v.matches("balanced|movies|apps|compact"))owner.setLayoutMode(v);else ok=false;break;
                case "size_info":case "size_trending":case "size_apps":
                    try{int n=Integer.parseInt(v);if(n>=-3&&n<=3)owner.setWidgetSize(name.substring(5),n);else ok=false;}catch(Exception ex){ok=false;}break;
                case "swapInfo":owner.setSwapInfo("true".equals(v));break;
                case "appsFirst":owner.setAppsFirst("true".equals(v));break;
                case "showAppTitles":owner.setShowAppTitles("true".equals(v));break;
                case "recoSource":if(v.matches("cinemeta|tmdb|charts|trakt"))owner.setRecommendationSource(v);else ok=false;break;
                case "theme":if(v.matches("pastel|grey|dark|colourful|glass|bright"))owner.setTheme(v);else ok=false;break;
                case "fx":owner.setWeatherFx("true".equals(v));break;
                case "soundFx":owner.setSoundFx("true".equals(v));break;
                case "glassOpacity":try{int o=Integer.parseInt(v);if(o>=0&&o<=100)owner.setGlassOpacity(o);else ok=false;}catch(Exception ex){ok=false;}break;
                case "glassBlur":try{int b=Integer.parseInt(v);if(b>=0&&b<=18)owner.setGlassBlur(b);else ok=false;}catch(Exception ex){ok=false;}break;
                case "favorite":ok=owner.setFavorite(input.optString("pkg"),"true".equals(v));break;
                case "card":ok=owner.setCardVisible(input.optString("pkg"),"true".equals(v));break;
                case "hidden":ok=owner.setAppHidden(input.optString("pkg"),"true".equals(v));break;
                case "tmdb":if(v.isEmpty()||v.matches("[A-Za-z0-9_.-]{32,700}")){owner.setTmdbToken(v);}else ok=false;break;
                default:ok=false;
            }
            json(c,ok?200:400,ok?new JSONObject().put("ok",true):error("Pilihan tidak sah"));return;
        }
        json(c,404,error("Tidak dijumpai"));
    }
}
