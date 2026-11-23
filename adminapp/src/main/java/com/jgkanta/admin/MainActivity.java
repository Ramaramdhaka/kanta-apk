package com.jgkanta.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
    private Spinner plan,type,filter;
    private EditText bannerMessage,bannerLink,updateVersion,updateTitle,updateMessage,updateLink;
    private CheckBox bannerEnabled,updateEnabled;
    private TextView noticesHint;
    private boolean busy=false;
    private int visibleLimit=25;
    private TextView recordsHeader;
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private final ArrayList<Entry> entries=new ArrayList<>();

    private static class Entry {
        String hash,customer,status,type,expiry,uid,deviceId,plan,collection;
        boolean claimed;
        Entry(String key,JSONObject data,String source) {
            hash=key;collection=source;customer=data.optString("customer","-");
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
    @Override protected void onDestroy() {if(searchRunnable!=null)uiHandler.removeCallbacks(searchRunnable);pool.shutdownNow();super.onDestroy();}

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
        LinearLayout identity=row();
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.jg_logo);
        logo.setContentDescription("JG Logo");logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        identity.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(64)));
        LinearLayout brand=column();LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);blp.leftMargin=dp(12);
        identity.addView(brand,blp);brand.addView(label("JG ADMIN",17,ACCENT,true));
        brand.addView(label("PRIVATE LICENSE CONSOLE",11,SUB,true));
        content.addView(identity);
        space(content,14);content.addView(label(title,29,TEXT,true));space(content,3);
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
        LinearLayout notice=card(content);
        notice.addView(label("CUSTOMER BANNER & APP UPDATES",13,ACCENT,true));space(notice,7);
        notice.addView(label("Set messages remotely without changing JG Kanta Bluetooth settings.",12,SUB,false));
        space(notice,11);
        bannerEnabled=new CheckBox(this);
        bannerEnabled.setText("Show announcement banner");bannerEnabled.setTextColor(TEXT);
        notice.addView(bannerEnabled);
        bannerMessage=field("Banner message (up to 180 characters)",false);
        bannerMessage.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(180)});
        notice.addView(bannerMessage);space(notice,8);
        bannerLink=field("Optional HTTPS banner link",false);notice.addView(bannerLink);
        space(notice,12);
        updateEnabled=new CheckBox(this);
        updateEnabled.setText("Announce new JG Kanta APK version");updateEnabled.setTextColor(TEXT);
        notice.addView(updateEnabled);
        updateVersion=field("New APK versionCode (must be greater than installed)",false);
        updateVersion.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        notice.addView(updateVersion);space(notice,8);
        updateTitle=field("Update title",false);notice.addView(updateTitle);space(notice,8);
        updateMessage=field("Update notice text",false);notice.addView(updateMessage);space(notice,8);
        updateLink=field("HTTPS link to new APK / official download page",false);
        notice.addView(updateLink);space(notice,10);
        noticesHint=label("Remote notices require updated Firebase database Rules.",12,SUB,false);
        notice.addView(noticesHint);space(notice,10);
        notice.addView(button("LOAD SAVED NOTICES",SURFACE,this::loadNotices));space(notice,7);
        notice.addView(button("SAVE & PUBLISH CUSTOMER NOTICES",ACCENT,this::saveNotices));
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
        history.addView(label("ALL LICENSE RECORDS",12,ACCENT,true));space(history,10);
        search=field("Search by customer, key or hash",false);history.addView(search);space(history,9);
        filter=dropdown(new String[]{"All","Active","On Hold","Used / Claimed","Expired"});history.addView(filter);space(history,8);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){visibleLimit=25;render();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                if(searchRunnable!=null)uiHandler.removeCallbacks(searchRunnable);
                searchRunnable=()->{visibleLimit=25;render();};
                uiHandler.postDelayed(searchRunnable,180);
            }
            public void afterTextChanged(Editable e){}
        });
        recordsHeader=label("Loading records…",12,SUB,false);history.addView(recordsHeader);space(history,9);
        records=column();history.addView(records);
        content.addView(label("Original keys are encrypted on this phone. Keep an external backup; hashes cannot be reversed.",11,SUB,false));
    }
    private void refresh() {
        if(idToken.isEmpty())return;
        final ArrayList<Entry> loaded=new ArrayList<>();
        final boolean[] canReadOld={true};
        final JSONObject[] remoteNotices={null};
        execute(()->{
            try {remoteNotices[0]=database("GET","appPublic/jgKanta",null,null);}
            catch(IOException ignored){/* Optional while Rules are not yet published. */}
            addRecords(loaded,database("GET","licenses",null,null),"licenses");
            try { addRecords(loaded,database("GET","keys",null,null),"keys"); }
            catch(IOException denied) {canReadOld[0]=false;}
            Collections.sort(loaded,(a,b)->a.customer.compareToIgnoreCase(b.customer));
        },()->{
            entries.clear();entries.addAll(loaded);
            int active=0,hold=0,used=0,old=0;
            for(Entry e:entries){
                if("keys".equals(e.collection))old++;
                if("HOLD".equals(e.status))hold++;
                else if(e.claimed||"USED".equals(e.status))used++;
                else if("ACTIVE".equals(e.status))active++;
            }
            stats.setText("Total "+entries.size()+"   Active "+active+"   Hold "+hold+
                "\nUsed / Claimed "+used+"   Old keys "+old+
                (canReadOld[0]?"":" (not readable)"));
            visibleLimit=25;render();
            if(remoteNotices[0]!=null)populateNotices(remoteNotices[0]);
        });
    }
    private void addRecords(ArrayList<Entry> output,JSONObject json,String source) {
        Iterator<String> it=json.keys();
        while(it.hasNext()){
            String k=it.next();
            if(!k.matches("licenses".equals(source)?"[a-f0-9]{64}":"[0-9]{8}"))continue;
            JSONObject value=json.optJSONObject(k);
            if(value!=null)output.add(new Entry(k,value,source));
        }
    }
    private boolean expired(Entry e){
        try{return LocalDate.parse(e.expiry).isBefore(LocalDate.now());}
        catch(Exception ignored){return false;}
    }

    private static String cleanUrl(String value)throws IOException{
        String input=value==null?"":value.trim();
        if(input.isEmpty())return "";
        if(input.length()>500)throw new IOException("Link too long");
        try {
            java.net.URI parsed=new java.net.URI(input);
            if(!"https".equalsIgnoreCase(parsed.getScheme()) ||
                parsed.getHost()==null || parsed.getUserInfo()!=null) {
                throw new IOException("Use a valid HTTPS link");
            }
            return parsed.toString();
        } catch(java.net.URISyntaxException error){throw new IOException("Invalid HTTPS link");}
    }
    private void populateNotices(JSONObject notice){
        JSONObject banner=notice.optJSONObject("banner");
        JSONObject update=notice.optJSONObject("update");
        if(banner!=null) {
            bannerEnabled.setChecked(banner.optBoolean("enabled",false));
            bannerMessage.setText(banner.optString("text",""));
            bannerLink.setText(banner.optString("url",""));
        }
        if(update!=null) {
            updateEnabled.setChecked(update.optBoolean("enabled",false));
            int code=update.optInt("versionCode",0);
            updateVersion.setText(code==0?"":String.valueOf(code));
            updateTitle.setText(update.optString("title",""));
            updateMessage.setText(update.optString("message",""));
            updateLink.setText(update.optString("url",""));
        }
        if(noticesHint!=null)noticesHint.setText("Current notice configuration loaded from Firebase.");
    }
    private void loadNotices(){
        final JSONObject[] loaded={null};
        execute(()->loaded[0]=database("GET","appPublic/jgKanta",null,null),
            ()->{
                if(loaded[0]!=null)populateNotices(loaded[0]);
                else noticesHint.setText("No published notices yet. Configure and Save.");
            });
    }
    private void saveNotices(){
        String announcement=bannerMessage.getText().toString().trim();
        String bannerHref=bannerLink.getText().toString().trim();
        String title=updateTitle.getText().toString().trim();
        String description=updateMessage.getText().toString().trim();
        String updateHref=updateLink.getText().toString().trim();
        boolean showBanner=bannerEnabled.isChecked(),showUpdate=updateEnabled.isChecked();
        int code=0;
        String rawCode=updateVersion.getText().toString().trim();
        try {if(!rawCode.isEmpty())code=Integer.parseInt(rawCode);}
        catch(NumberFormatException error){feedback.setText("Invalid update versionCode");return;}
        if(showBanner&&announcement.isEmpty()){feedback.setText("Banner text is required");return;}
        if(showUpdate&&(code<1||updateHref.isEmpty())){
            feedback.setText("Update needs versionCode and HTTPS download link");return;
        }
        if(title.length()>80||description.length()>260){
            feedback.setText("Update title/message too long");return;
        }
        final int requestedVersion=code;
        execute(()->{
            JSONObject banner=new JSONObject();
            banner.put("enabled",showBanner);
            banner.put("id",String.valueOf(System.currentTimeMillis()));
            banner.put("text",announcement);
            banner.put("url",cleanUrl(bannerHref));
            JSONObject update=new JSONObject();
            update.put("enabled",showUpdate);
            update.put("versionCode",requestedVersion);
            update.put("title",title.isEmpty()?"New JG Kanta update available":title);
            update.put("message",description.isEmpty()?"A newer version is available.":description);
            update.put("url",cleanUrl(updateHref));
            JSONObject publicInfo=new JSONObject();
            publicInfo.put("banner",banner);publicInfo.put("update",update);
            database("PUT","appPublic/jgKanta",publicInfo,null);
        },()->{
            noticesHint.setText("Saved successfully. Users will see updates on the next app open.");
            Toast.makeText(this,"Notices published",Toast.LENGTH_LONG).show();
        });
    }

    private void generate(){
        final String name=customer.getText().toString().trim();
        if(name.isEmpty()||name.length()>120){feedback.setText("Enter customer name (max 120 chars)");return;}
        final int period=plan.getSelectedItemPosition(),kind=type.getSelectedItemPosition();
        final String[] output=new String[1];
        final Entry[] newLicense=new Entry[1];
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
                    output[0]=plain;newLicense[0]=new Entry(hashed,node,"licenses");return;
                } catch(IOException collision) {
                    if("KEY_COLLISION".equals(collision.getMessage()))continue;
                    throw collision;
                }
            }
            throw new IOException("Could not generate unique key");
        },()->{
            customer.setText("");
            copySilently(output[0]);
            new AlertDialog.Builder(this).setTitle("JG License Created ✓")
                .setMessage(message(newLicense[0],output[0])+"\n\n8-digit key copied to clipboard.")
                .setPositiveButton("WhatsApp Share",(dlg,which)->share(newLicense[0],output[0]))
                .setNeutralButton("Copy Key",(dlg,which)->copy(output[0]))
                .setNegativeButton("Done",null).show();
            refresh();
        });
    }
    private void copySilently(String value){
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("JG License Key",value));
    }
    private void copy(String value){
        copySilently(value);Toast.makeText(this,"License key copied",Toast.LENGTH_SHORT).show();
    }
    private String known(Entry e){
        if("keys".equals(e.collection))return e.hash;
        return vault==null?null:vault.getString(e.hash,null);
    }
    private String message(Entry e,String key){
        String p=e.plan;
        if("30 Days".equals(p))p+=" (₹300)";
        else if("1 Year".equals(p))p+=" (₹1200)";
        else if("Lifetime".equals(p))p+=" (₹2000)";
        return "🔐 *JG Kanta License*\n\n"+
            "👤 Customer: "+e.customer+"\n"+
            "🔑 *License Key: "+key+"*\n"+
            "📦 Plan: "+p+"\n"+
            "📅 Validity: "+("Lifetime".equals(e.plan)?"Lifetime (system expiry "+e.expiry+")":e.expiry)+"\n"+
            "📱 Type: "+("ONE_TIME".equals(e.type)?"One-Time":"Device Lock (1 mobile)")+"\n\n"+
            "Open JG Kanta and enter the 8-digit key to activate your license.\nThank you!";
    }
    private void share(Entry e,String key){
        if(key==null||!key.matches("[0-9]{8}"))return;
        copySilently(key);
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,message(e,key));
        try {startActivity(Intent.createChooser(intent,"Share license via WhatsApp"));}
        catch(Exception error){Toast.makeText(this,"WhatsApp/share app not installed",Toast.LENGTH_LONG).show();}
    }
    private void linkOriginal(Entry e){
        EditText key=field("Original 8-digit number",false);
        key.setInputType(InputType.TYPE_CLASS_NUMBER);
        key.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        new AlertDialog.Builder(this).setTitle("Link original key")
            .setMessage("For keys created earlier on the website, enter the original 8-digit number once. It is stored encrypted on this phone.")
            .setView(key).setPositiveButton("Link",(dialog,which)->{
                String value=key.getText().toString().trim();
                try{
                    if(!e.hash.equals(hashKey(value)))throw new IOException("Key does not match this record");
                    if(vault==null)throw new IOException("Encrypted storage unavailable");
                    vault.edit().putString(e.hash,value).apply();
                    render();
                }catch(Exception err){Toast.makeText(this,err.getMessage(),Toast.LENGTH_LONG).show();}
            }).setNegativeButton("Cancel",null).show();
    }
    private void render(){
        if(records==null||search==null)return;
        records.removeAllViews();
        String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String queryHash="";
        if(q.matches("[0-9]{8}"))try{queryHash=hashKey(q);}catch(Exception ignored){}
        int selection=filter==null?0:filter.getSelectedItemPosition();
        int matching=0,shown=0;
        for(Entry e:entries){
            String original=known(e);
            if(!q.isEmpty()&&!e.customer.toLowerCase(Locale.ROOT).contains(q)&&
                !e.hash.contains(q)&&!(original!=null&&original.contains(q))&&!e.hash.equals(queryHash))continue;
            if(selection==1&&(!"ACTIVE".equals(e.status)||e.claimed||expired(e)))continue;
            if(selection==2&&!"HOLD".equals(e.status))continue;
            if(selection==3&&!e.claimed&&!"USED".equals(e.status))continue;
            if(selection==4&&!expired(e))continue;
            matching++;
            if(shown>=visibleLimit)continue;
            shown++;
            LinearLayout item=column();item.setPadding(dp(14),dp(14),dp(14),dp(14));
            item.setBackground(bg(SURFACE,15));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=dp(10);records.addView(item,lp);
            item.addView(label(e.customer,17,TEXT,true));space(item,5);
            item.addView(label((original==null?"HASH "+e.hash.substring(0,12)+"…":original)+
                "  •  "+e.plan,14,ACCENT,true));space(item,5);
            item.addView(label(("keys".equals(e.collection)?"Old Key":"Secure Key")+" • "+
                e.status+(expired(e)?" • EXPIRED":"")+" • "+e.type,12,SUB,false));
            item.addView(label("Validity until: "+e.expiry+
                (e.claimed?" • Claimed":" • Unclaimed"),12,SUB,false));
            space(item,10);
            LinearLayout actions=row();item.addView(actions);
            if(original!=null){
                actions.addView(button("Copy",0xFF23765B,()->copy(original)),
                    new LinearLayout.LayoutParams(0,dp(48),1f));
                LinearLayout.LayoutParams wa=new LinearLayout.LayoutParams(0,dp(48),1.2f);
                wa.leftMargin=dp(4);
                actions.addView(button("WhatsApp",0xFF16865A,()->share(e,original)),wa);
            }else{
                actions.addView(button("Link key",0xFF23765B,()->linkOriginal(e)),
                    new LinearLayout.LayoutParams(0,dp(48),1f));
            }
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(48),1f);mp.leftMargin=dp(4);
            actions.addView(button("Manage",CARD,()->manage(e)),mp);
        }
        if(recordsHeader!=null)recordsHeader.setText("Showing "+shown+" of "+matching+
            " matching • Total records "+entries.size());
        if(matching>visibleLimit)records.addView(button(
            "Show next "+Math.min(25,matching-visibleLimit)+" keys",SURFACE,
            ()->{visibleLimit+=25;render();}));
        if(matching==0)records.addView(label("No records found.",13,SUB,false));
    }
    private void manage(Entry entry){
        String[] actions={entry.status.equals("ACTIVE")?"Put on HOLD":"Activate","Reset Device + Owner","Delete License"};
        new AlertDialog.Builder(this).setTitle(entry.customer).setItems(actions,(dialog,which)->{
            if(which==0)confirm("Change license status?",()->{
                JSONObject value=new JSONObject();value.put("status",entry.status.equals("ACTIVE")?"HOLD":"ACTIVE");
                database("PATCH",entry.collection+"/"+entry.hash,value,null);
            });
            else if(which==1)confirm("Reset claim? Another device could activate this key.",()->{
                JSONObject value=new JSONObject();value.put("claimed",false);value.put("uid","");value.put("deviceId","");
                database("PATCH",entry.collection+"/"+entry.hash,value,null);
            });
            else confirm("Permanently delete this customer's license?",()->{
                database("DELETE",entry.collection+"/"+entry.hash,null,null);
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
