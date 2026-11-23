package com.jgkanta.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal standalone Android owner console. Firebase UID + database Rules are authority. */
public final class MainActivity extends Activity {
    private static final int BG=0xFF0A1224, CARD=0xFF17243B, SURFACE=0xFF22324D;
    private static final int TEXT=0xFFF6F9FF, SUB=0xFFABBBD1, ACCENT=0xFF39DDAD, GOLD=0xFFFFD37C;
    private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private final OkHttpClient http=new OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build();
    private final SecureRandom random=new SecureRandom();
    private android.content.SharedPreferences vault;
    private String idToken="",refreshToken="";
    private long expiresAt=0;
    private LinearLayout content,records;
    private TextView feedback,stats;
    private EditText customer,search;
    private Spinner plan,type;
    private boolean busy=false;
    private final ArrayList<Entry> entries=new ArrayList<>();

    private static class Entry {
        String hash,customer,status,type,expiry,uid,deviceId,plan;
        boolean claimed;
        Entry(String key,JSONObject data) {
            hash=key;customer=data.optString("customer","-");
            status=data.optString("status","?");type=data.optString("type","");
            expiry=data.optString("expiry","");uid=data.optString("uid","");
            deviceId=data.optString("deviceId","");plan=data.optString("plan","");
            claimed=data.optBoolean("claimed",false);
        }
    }
    interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            MasterKey key=new MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            vault=EncryptedSharedPreferences.create(this,"jg_admin_key_vault",key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch(Exception e) { vault=null; } // No insecure plaintext fallback.
        loginScreen();
    }
    @Override protected void onDestroy() {pool.shutdownNow();super.onDestroy();}

    private void execute(Work job,Runnable success) {
        if(busy)return;
        busy=true;
        if(feedback!=null){feedback.setText("Working securely…");feedback.setTextColor(GOLD);}
        pool.execute(()->{
            String failure=null;
            try { job.run(); } catch(Exception e) {
                failure=e.getMessage();
                if(failure==null)failure=e.getClass().getSimpleName();
            }
            final String finalFailure=failure;
            runOnUiThread(()->{
                busy=false;
                if(finalFailure==null) {
                    if(feedback!=null){feedback.setText("Ready");feedback.setTextColor(ACCENT);}
                    if(success!=null)success.run();
                } else {
                    if(feedback!=null){feedback.setText("Error: "+finalFailure);feedback.setTextColor(0xFFFF9999);}
                    Toast.makeText(this,finalFailure,Toast.LENGTH_LONG).show();
                }
            });
        });
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout row(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    private TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void space(LinearLayout c,int height){c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout card(LinearLayout parent){
        LinearLayout c=column();c.setBackground(bg(CARD,18));c.setPadding(dp(17),dp(17),dp(17),dp(17));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(c,lp);
        return c;
    }
    private EditText field(String hint,boolean password){
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(15);e.setTextColor(TEXT);
        e.setHintTextColor(SUB);e.setHint(hint);e.setPadding(dp(14),0,dp(14),0);
        e.setBackground(bg(SURFACE,12));e.setMinHeight(dp(52));
        if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private Button button(String title,int color,Runnable action){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);
        b.setTextSize(14);b.setTextColor(color==ACCENT?BG:TEXT);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(color));b.setOnClickListener(v->action.run());return b;
    }
    private void root(String title,String subtitle){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        content=column();content.setPadding(dp(18),dp(30),dp(18),dp(35));
        scroll.addView(content);setContentView(scroll);
        content.addView(label("JG  /  PRIVATE CONSOLE",12,ACCENT,true));
        space(content,9);content.addView(label(title,29,TEXT,true));space(content,3);
        content.addView(label(subtitle,13,SUB,false));space(content,18);
        feedback=label("",12,ACCENT,false);content.addView(feedback);space(content,12);
    }
    private void loginScreen(){
        idToken="";refreshToken="";expiresAt=0;
        root("Admin Login","Protected by Firebase Authentication, not admin / 123");
        LinearLayout c=card(content);
        c.addView(label("Owner-only license management",18,TEXT,true));space(c,13);
        EditText email=field("Admin email",false);
        email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(BuildConfig.ADMIN_EMAIL);c.addView(email);space(c,11);
        EditText pass=field("Firebase admin password",true);c.addView(pass);space(c,14);
        c.addView(button("SIGN IN",ACCENT,()->{
            String e=email.getText().toString().trim(),p=pass.getText().toString();
            if(!BuildConfig.ADMIN_EMAIL.equalsIgnoreCase(e)||p.isEmpty()){
                feedback.setText("Enter registered admin email and Firebase password.");return;
            }
            pass.setText("");
            execute(()->{
                JSONObject body=new JSONObject();body.put("email",e);body.put("password",p);
                body.put("returnSecureToken",true);
                JSONObject result=request("POST",
                    "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key="+enc(BuildConfig.API_KEY),
                    body,null);
                if(!BuildConfig.ADMIN_UID.equals(result.optString("localId")))throw new IOException("Account is not authorized owner");
                idToken=result.getString("idToken");refreshToken=result.getString("refreshToken");
                expiresAt=System.currentTimeMillis()+result.optLong("expiresIn",3600)*1000L;
            },()->{dashboard();refresh();});
        }));
        space(content,14);content.addView(label("No fixed admin/123 credential is saved. The owner UID is checked again by Firebase Rules.",12,SUB,false));
    }
    private String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private JSONObject request(String method,String url,JSONObject body,String condition)throws Exception{
        Request.Builder b=new Request.Builder().url(url).header("Accept","application/json").header("Cache-Control","no-store");
        if(condition!=null)b.header("If-Match",condition);
        if("GET".equals(method))b.get();
        else if("DELETE".equals(method))b.delete();
        else b.method(method,RequestBody.create(body==null?"{}":body.toString(),JSON));
        try(Response response=http.newCall(b.build()).execute()){
            String raw=response.body()==null?"":response.body().string();
            if(!response.isSuccessful()){
                if(response.code()==412)throw new IOException("KEY_COLLISION");
                if(response.code()==401||response.code()==403)throw new IOException("Firebase 401/403: check database Rules and admin account");
                throw new IOException("Firebase HTTP "+response.code());
            }
            return raw.isEmpty()||"null".equals(raw)?new JSONObject():new JSONObject(raw);
        }
    }
    private String sessionToken()throws Exception {
        if(idToken.isEmpty())throw new IOException("Sign in again");
        if(System.currentTimeMillis()+60000<expiresAt)return idToken;
        Request req=new Request.Builder().url("https://securetoken.googleapis.com/v1/token?key="+enc(BuildConfig.API_KEY))
            .post(RequestBody.create("grant_type=refresh_token&refresh_token="+enc(refreshToken),
                MediaType.parse("application/x-www-form-urlencoded"))).build();
        try(Response resp=http.newCall(req).execute()){
            if(!resp.isSuccessful())throw new IOException("Session expired, please sign in again");
            JSONObject data=new JSONObject(resp.body().string());
            idToken=data.getString("id_token");refreshToken=data.optString("refresh_token",refreshToken);
            expiresAt=System.currentTimeMillis()+data.optLong("expires_in",3600)*1000L;
            return idToken;
        }
    }
    private JSONObject database(String method,String path,JSONObject value,String condition)throws Exception{
        return request(method,BuildConfig.DB_URL+"/"+path+".json?auth="+enc(sessionToken()),value,condition);
    }
    private String hashKey(String key)throws Exception {
        if(!key.matches("[0-9]{8}")||!BuildConfig.HASH_SALT.matches("[A-Za-z0-9_-]{24,128}"))
            throw new IOException("Invalid key or build license salt");
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
            (key+BuildConfig.HASH_SALT).getBytes(StandardCharsets.UTF_8));
        StringBuilder builder=new StringBuilder(64);
        for(byte v:bytes)builder.append(String.format(Locale.US,"%02x",v&255));
        return builder.toString();
    }
    private String newKey(){return Integer.toString(random.nextInt(90000000)+10000000);}
    private Spinner dropdown(String[] options){
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
        s.setAdapter(adapter);return s;
    }
    private void dashboard(){
        root("License Manager","Generate and control customer keys from your phone");
        LinearLayout nav=row();content.addView(nav);
        nav.addView(button("↻ Refresh",SURFACE,this::refresh),new LinearLayout.LayoutParams(0,dp(50),1f));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1f);lp.leftMargin=dp(8);
        nav.addView(button("Logout",SURFACE,this::loginScreen),lp);
        space(content,15);
        LinearLayout statCard=card(content);
        stats=label("Loading license records…",16,TEXT,true);statCard.addView(stats);
        LinearLayout generator=card(content);
        generator.addView(label("GENERATE 8-DIGIT KEY",12,ACCENT,true));space(generator,12);
        customer=field("Customer name",false);generator.addView(customer);space(generator,11);
        generator.addView(label("Validity plan",12,SUB,false));space(generator,5);
        plan=dropdown(new String[]{"30 Days • ₹300","1 Year • ₹1200","Lifetime • ₹2000"});
        generator.addView(plan);space(generator,11);
        generator.addView(label("License type",12,SUB,false));space(generator,5);
        type=dropdown(new String[]{"Device Lock (1 phone)","One Time (single claim)"});
        generator.addView(type);space(generator,14);
        generator.addView(button("+ GENERATE KEY",ACCENT,this::generate));
        LinearLayout history=card(content);
        history.addView(label("LICENSE RECORDS",12,ACCENT,true));space(history,10);
        search=field("Search by customer, key or hash",false);history.addView(search);space(history,9);
        history.addView(button("Search / Show All",SURFACE,this::render));space(history,12);
        records=column();history.addView(records);
        content.addView(label("Original keys are encrypted on this phone. Keep an external backup; hashes cannot be reversed.",11,SUB,false));
    }
    private void refresh() {
        if(idToken.isEmpty())return;
        final ArrayList<Entry> loaded=new ArrayList<>();
        execute(()->{
            JSONObject data=database("GET","licenses",null,null);
            Iterator<String> iterator=data.keys();
            while(iterator.hasNext()){
                String key=iterator.next();
                if(!key.matches("[a-f0-9]{64}"))continue;
                JSONObject j=data.optJSONObject(key);
                if(j!=null)loaded.add(new Entry(key,j));
            }
            Collections.sort(loaded,Comparator.comparing(e->e.customer.toLowerCase(Locale.ROOT)));
        },()->{
            entries.clear();entries.addAll(loaded);
            int active=0,hold=0;
            for(Entry e:entries){if("ACTIVE".equals(e.status))active++;else hold++;}
            stats.setText("Total "+entries.size()+"     Active "+active+"     Hold/Used "+hold);
            render();
        });
    }
    private void generate(){
        final String name=customer.getText().toString().trim();
        if(name.isEmpty()||name.length()>120){feedback.setText("Enter customer name (max 120 chars)");return;}
        final int period=plan.getSelectedItemPosition(),kind=type.getSelectedItemPosition();
        final String[] output=new String[1];
        execute(()->{
            for(int i=0;i<10;i++){
                String plain=newKey(),hashed=hashKey(plain);
                JSONObject node=new JSONObject();
                node.put("customer",name);node.put("status","ACTIVE");
                node.put("type",kind==0?"DEVICE_LOCK":"ONE_TIME");
                node.put("expiry",period==2?"2099-12-31":LocalDate.now().plusDays(period==0?30:365).toString());
                node.put("plan",period==0?"30 Days":period==1?"1 Year":"Lifetime");
                node.put("claimed",false);node.put("uid","");node.put("deviceId","");
                node.put("createdAt",System.currentTimeMillis());
                try {
                    database("PUT","licenses/"+hashed,node,"null_etag");
                    if(vault!=null)vault.edit().putString(hashed,plain).apply();
                    output[0]=plain;return;
                } catch(IOException collision) {
                    if("KEY_COLLISION".equals(collision.getMessage()))continue;
                    throw collision;
                }
            }
            throw new IOException("Could not generate unique key");
        },()->{
            customer.setText("");
            new AlertDialog.Builder(this).setTitle("New license created")
                .setMessage("8-DIGIT KEY: "+output[0]+"\n\nCopy and securely record this key. The database hash cannot reveal it.")
                .setPositiveButton("COPY",(dlg,which)->copy(output[0]))
                .setNegativeButton("Done",null).show();
            refresh();
        });
    }
    private void copy(String value){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("JG license",value));
        Toast.makeText(this,"Key copied",Toast.LENGTH_SHORT).show();
    }
    private void render(){
        if(records==null)return;
        records.removeAllViews();
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String hashed="";
        if(query.matches("[0-9]{8}"))try{hashed=hashKey(query);}catch(Exception ignore){}
        int shown=0;
        for(Entry e:entries){
            String original=vault==null?null:vault.getString(e.hash,null);
            if(!query.isEmpty()&&!e.customer.toLowerCase(Locale.ROOT).contains(query)&&
                !e.hash.contains(query)&&!(original!=null&&original.contains(query))&&!e.hash.equals(hashed))continue;
            shown++;
            LinearLayout c=column();c.setPadding(dp(13),dp(13),dp(13),dp(13));c.setBackground(bg(SURFACE,13));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(9);records.addView(c,lp);
            c.addView(label(e.customer,17,TEXT,true));space(c,4);
            c.addView(label((original==null?"HASH "+e.hash.substring(0,12)+"…":original)+" · "+e.plan,13,ACCENT,true));
            space(c,5);c.addView(label(e.status+" • "+e.type+" • Expires "+e.expiry,12,SUB,false));
            c.addView(label(e.claimed?"Claimed · "+e.deviceId:"Unclaimed",11,SUB,false));space(c,9);
            LinearLayout actions=row();c.addView(actions);
            if(original!=null)actions.addView(button("Copy",0xFF247861,()->copy(original)),
                new LinearLayout.LayoutParams(0,dp(46),1f));
            LinearLayout.LayoutParams actionLp=new LinearLayout.LayoutParams(0,dp(46),1f);actionLp.leftMargin=dp(5);
            actions.addView(button("Manage",CARD,()->manage(e)),actionLp);
        }
        if(shown==0)records.addView(label("No matching keys. Generate one above.",13,SUB,false));
    }
    private void manage(Entry entry){
        String[] actions={entry.status.equals("ACTIVE")?"Put on HOLD":"Activate","Reset Device + Owner","Delete License"};
        new AlertDialog.Builder(this).setTitle(entry.customer).setItems(actions,(dialog,which)->{
            if(which==0)confirm("Change license status?",()->{
                JSONObject value=new JSONObject();value.put("status",entry.status.equals("ACTIVE")?"HOLD":"ACTIVE");
                database("PATCH","licenses/"+entry.hash,value,null);
            });
            else if(which==1)confirm("Reset claim? Another device could activate this key.",()->{
                JSONObject value=new JSONObject();value.put("claimed",false);value.put("uid","");value.put("deviceId","");
                database("PATCH","licenses/"+entry.hash,value,null);
            });
            else confirm("Permanently delete this customer's license?",()->{
                database("DELETE","licenses/"+entry.hash,null,null);
                if(vault!=null)vault.edit().remove(entry.hash).apply();
            });
        }).show();
    }
    private void confirm(String question,Work job){
        new AlertDialog.Builder(this).setTitle("Confirm admin action").setMessage(question)
            .setPositiveButton("Confirm",(dialog,which)->execute(job,this::refresh))
            .setNegativeButton("Cancel",null).show();
    }
}
