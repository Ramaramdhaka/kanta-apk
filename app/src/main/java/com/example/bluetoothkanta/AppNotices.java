package com.example.bluetoothkanta;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Public, optional announcements only. No changes to licensing, Auth, Bluetooth or SPP data. */
final class AppNotices {
    private AppNotices() {}
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(4,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
        .callTimeout(9,TimeUnit.SECONDS).build();
    static final class Notice {
        final boolean bannerEnabled, updateEnabled;
        final String campaign, bannerText, bannerUrl, updateTitle, updateText, updateUrl;
        final int minVersion;
        Notice(JSONObject data) {
            JSONObject banner=data.optJSONObject("banner");
            JSONObject update=data.optJSONObject("update");
            if(banner==null)banner=new JSONObject();
            if(update==null)update=new JSONObject();
            bannerEnabled=banner.optBoolean("enabled",false);
            campaign=safe(banner.optString("id",""),60);
            bannerText=safe(banner.optString("text",""),180);
            bannerUrl=https(banner.optString("url",""));
            updateEnabled=update.optBoolean("enabled",false);
            minVersion=update.optInt("versionCode",0);
            updateTitle=safe(update.optString("title","New JG Kanta Update"),80);
            updateText=safe(update.optString("message","A new update is available."),260);
            updateUrl=https(update.optString("url",""));
        }
        private static String safe(String s,int limit){
            if(s==null)return "";
            s=s.replaceAll("[\\p{Cntrl}&&[^\\n]]"," ").trim();
            return s.length()>limit?s.substring(0,limit):s;
        }
        private static String https(String url){
            if(url==null||url.length()>500)return "";
            try {
                java.net.URI parsed=new java.net.URI(url.trim());
                return "https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost()!=null
                    && parsed.getUserInfo()==null ? parsed.toString():"";
            }catch(Exception ignored){return "";}
        }
    }
    interface Callback { void onData(Notice notice); }
    static void fetch(Callback callback){
        WORKER.execute(()->{
            Notice result=null;
            String address=Obfuscated.baseUrl()+"appPublic/jgKanta.json";
            try {
                Request req=new Request.Builder().url(address)
                    .header("Cache-Control","no-store").get().build();
                try(Response response=HTTP.newCall(req).execute()){
                    if(response.isSuccessful()&&response.body()!=null){
                        String text=response.body().string();
                        if(text.length()<8192 && text.trim().startsWith("{")){
                            result=new Notice(new JSONObject(text));
                        }
                    }
                }
            }catch(Exception ignored){/* Advisory notices fail silently. */ }
            final Notice value=result;
            MAIN.post(()->{if(value!=null)callback.onData(value);});
        });
    }
}
