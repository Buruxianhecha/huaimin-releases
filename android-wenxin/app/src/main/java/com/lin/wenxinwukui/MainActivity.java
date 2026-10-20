package com.lin.wenxinwukui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.util.Base64;
import android.graphics.Color;
import android.widget.Toast;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.*;
import org.json.*;

public class MainActivity extends Activity {
    WebView web;
    ValueCallback<Uri[]> chooser;
    Handler ui = new Handler(Looper.getMainLooper());
    static final String PREF = "wenxin_private";
    static final String KEY_ALIAS = "wenxin_ledger_api_keys";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(24,23,24));
        getWindow().setNavigationBarColor(Color.BLACK);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","application/json","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/plain"});
                    startActivityForResult(Intent.createChooser(intent, "选择账单或备份文件"), 101);
                    return true;
                } catch (Exception e) { chooser=null; callback.onReceiveValue(null); return false; }
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onActivityResult(int req,int code,Intent data) {
        super.onActivityResult(req,code,data);
        if (req==101 && chooser!=null) {
            chooser.onReceiveValue(code==RESULT_OK && data!=null ? new Uri[]{data.getData()} : null);
            chooser=null;
        }
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    void event(JSONObject obj) {
        final String script="window.onNativeAIEvent && window.onNativeAIEvent("+JSONObject.quote(obj.toString())+")";
        ui.post(()->web.evaluateJavascript(script,null));
    }
    void notifyText(final String text){ui.post(()->Toast.makeText(this,text,Toast.LENGTH_SHORT).show());}
    static String safeName(String name) {
        String n=(name==null?"export":name).replaceAll("[^\\p{L}\\p{N}._-]","_");
        return n.length()>96?n.substring(n.length()-96):n;
    }
    Uri saveMedia(String file,String b64,String mime) throws Exception {
        byte[] bytes=Base64.decode(b64,Base64.DEFAULT);
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,safeName(file));
        v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/WenXinWuKui");
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new IOException("无法创建导出文件");
        try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入文件");out.write(bytes);}
        v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,v,null,null);
        return uri;
    }
    JSONObject encryptedState() {
        try {
            String stored=getSharedPreferences(PREF,MODE_PRIVATE).getString("config","");
            if(stored.isEmpty())return new JSONObject().put("provider","suixiang").put("profiles",new JSONObject());
            String[] parts=stored.split(":",2);
            byte[] iv=Base64.decode(parts[0],Base64.DEFAULT),cipher=Base64.decode(parts[1],Base64.DEFAULT);
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            SecretKey key=(SecretKey)store.getKey(KEY_ALIAS,null);
            Cipher dec=Cipher.getInstance("AES/GCM/NoPadding");dec.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return new JSONObject(new String(dec.doFinal(cipher),StandardCharsets.UTF_8));
        }catch(Exception e){return new JSONObject();}
    }
    void storeState(JSONObject state) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)){
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());kg.generateKey();
        }
        store.load(null);
        SecretKey key=(SecretKey)store.getKey(KEY_ALIAS,null);
        Cipher enc=Cipher.getInstance("AES/GCM/NoPadding");enc.init(Cipher.ENCRYPT_MODE,key);
        String payload=Base64.encodeToString(enc.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(enc.doFinal(state.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("config",payload).commit())throw new IOException("保存密钥失败");
    }
    JSONObject aiEvent(String type, boolean ok,String text) throws Exception {
        return new JSONObject().put("type",type).put("ok",ok).put(ok?"text":"error",text);
    }
    public class Bridge {
        @JavascriptInterface public void saveFile(String name,String base64){saveTextFile(name,base64);}
        @JavascriptInterface public void saveTextFile(String name,String base64){
            new Thread(()->{try{saveMedia(name,base64,name.endsWith(".json")?"application/json":"text/csv");notifyText("已保存到下载/WenXinWuKui");}catch(Exception e){notifyText("导出失败："+e.getMessage());}}).start();
        }
        @JavascriptInterface public void shareCsv(String name,String base64,String email){
            new Thread(()->{try{
                Uri uri=saveMedia(name,base64,"text/csv");
                ui.post(()->{Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/csv");intent.putExtra(Intent.EXTRA_STREAM,uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if(email!=null&&!email.isEmpty())intent.putExtra(Intent.EXTRA_EMAIL,new String[]{email});
                    startActivity(Intent.createChooser(intent,"分享账单"));
                });
            }catch(Exception e){notifyText("分享失败："+e.getMessage());}}).start();
        }
        @JavascriptInterface public void shareFeedback(String title,String body) {
            ui.post(()->{Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_SUBJECT,title);intent.putExtra(Intent.EXTRA_TEXT,body);startActivity(Intent.createChooser(intent,"发送反馈"));});
        }
        @JavascriptInterface public String getAIConfig(){
            JSONObject state=encryptedState();
            try{
                JSONObject raw=state.optJSONObject("profiles"), visible=new JSONObject();
                boolean configured=false;
                if(raw!=null){
                    String[] names={"deepseek","zhipu","openai","siliconflow","kimi","suixiang"};
                    for(String p:names){JSONObject item=raw.optJSONObject(p);if(item==null)continue;
                        String[] keys=item.optString("keys","").split("\\n");int count=0;for(String k:keys)if(!k.trim().isEmpty())count++;
                        visible.put(p,new JSONObject().put("baseUrl",item.optString("baseUrl")).put("model",item.optString("model")).put("keyCount",count));
                        if(count>0)configured=true;
                    }
                }
                String provider=state.optString("provider","suixiang");
                JSONObject chosen=raw==null?null:raw.optJSONObject(provider);
                boolean current=chosen!=null&&!chosen.optString("keys","").trim().isEmpty();
                return new JSONObject().put("provider",provider).put("profiles",visible).put("configured",current||provider.equals("auto")&&configured).put("anyConfigured",configured).toString();
            }catch(Exception e){return "{}";}
        }
        @JavascriptInterface public void setAIConfig(String json){
            new Thread(()->{try{
                JSONObject incoming=new JSONObject(json),state=encryptedState();
                String provider=incoming.optString("provider","suixiang");
                JSONObject profiles=state.optJSONObject("profiles");if(profiles==null)profiles=new JSONObject();
                if(!provider.equals("auto")) {
                    String url=incoming.optString("baseUrl").trim().replaceAll("/+$","");
                    String model=incoming.optString("model").trim();
                    if(!url.startsWith("https://"))throw new IOException("API 地址必须为 HTTPS");
                    if(model.isEmpty())throw new IOException("模型 ID 不能为空");
                    JSONObject profile=profiles.optJSONObject(provider);if(profile==null)profile=new JSONObject();
                    profile.put("baseUrl",url).put("model",model);
                    String keys=incoming.optString("keys","").trim();
                    if(!keys.isEmpty())profile.put("keys",keys);
                    if(profile.optString("keys","").isEmpty())throw new IOException("请提供至少一把 API Key");
                    profiles.put(provider,profile);
                }
                state.put("profiles",profiles).put("provider",provider);
                storeState(state);
                event(aiEvent("saved",true,"模型配置已加密保存").put("config",new JSONObject().put("provider",provider)));
            }catch(Exception e){try{event(aiEvent("saved",false,e.getMessage()));}catch(Exception ignore){}}}).start();
        }
        @JavascriptInterface public void clearAIProviderKeys(String baseUrl){
            new Thread(()->{try{
                JSONObject state=encryptedState(), profiles=state.optJSONObject("profiles");
                if(profiles!=null){java.util.Iterator<String> names=profiles.keys();while(names.hasNext()){String name=names.next();JSONObject p=profiles.optJSONObject(name);if(p!=null&&p.optString("baseUrl").equals(baseUrl))p.remove("keys");}}
                storeState(state);event(aiEvent("keysCleared",true,"已清除该接口密钥"));
            }catch(Exception e){try{event(aiEvent("keysCleared",false,e.getMessage()));}catch(Exception ignore){}}}).start();
        }
        @JavascriptInterface public void askAI(String json) {
            new Thread(()->{
                String id="";String mode="chat";
                try{
                    JSONObject request=new JSONObject(json);
                    id=request.optString("id","");mode=request.optString("mode","chat");
                    JSONObject state=encryptedState(), profiles=state.optJSONObject("profiles");
                    String selected=state.optString("provider","suixiang");
                    String[] order=selected.equals("auto")?new String[]{"deepseek","zhipu","openai","siliconflow","kimi","suixiang"}:new String[]{selected};
                    String error="尚未设置可用的模型";
                    for(String provider:order) {
                        JSONObject p=profiles==null?null:profiles.optJSONObject(provider);if(p==null)continue;
                        String base=p.optString("baseUrl"), model=p.optString("model");
                        if(!base.startsWith("https://")||model.isEmpty())continue;
                        String[] keys=p.optString("keys","").split("\\n");
                        for(int ki=0;ki<keys.length;ki++) {
                            String apiKey=keys[ki].trim();if(apiKey.isEmpty())continue;
                            try {
                                String response=requestAI(base,model,apiKey,mode,request.optString("text",""));
                                JSONObject result=new JSONObject().put("type",mode).put("ok",true).put("id",id);
                                if(mode.equals("models")) {JSONArray arr=new JSONObject(response).optJSONArray("data"), names=new JSONArray();if(arr!=null)for(int j=0;j<Math.min(arr.length(),80);j++)names.put(arr.getJSONObject(j).optString("id"));result.put("models",names);}
                                else {JSONObject obj=new JSONObject(response);JSONArray choices=obj.optJSONArray("choices");if(choices==null||choices.length()==0)throw new IOException("模型未返回内容");result.put("text",choices.getJSONObject(0).getJSONObject("message").optString("content"));}
                                if(ki>0)result.put("switched",true).put("activeKey",ki+1);
                                if(!provider.equals(selected))result.put("providerSwitched",true).put("activeProvider",provider);
                                event(result);return;
                            }catch(Exception e){error=e.getMessage();}
                        }
                    }
                    event(new JSONObject().put("type",mode).put("ok",false).put("id",id).put("error",error));
                }catch(Exception e){try{event(new JSONObject().put("type",mode).put("ok",false).put("id",id).put("error",e.getMessage()));}catch(Exception ignore){}}
            }).start();
        }
    }
    String requestAI(String base,String model,String key,String mode,String text) throws Exception{
        String endpoint=base;
        if(endpoint.endsWith("/chat/completions"))endpoint=endpoint.substring(0,endpoint.length()-17);
        if(endpoint.endsWith("/models"))endpoint=endpoint.substring(0,endpoint.length()-7);
        if(!endpoint.endsWith("/v1")&&!endpoint.matches(".*/v[0-9]+"))endpoint+="/v1";
        URL url=new URL(endpoint+(mode.equals("models")?"/models":"/chat/completions"));
        if(!url.getProtocol().equals("https"))throw new IOException("只允许 HTTPS 接口");
        HttpURLConnection con=(HttpURLConnection)url.openConnection();
        con.setConnectTimeout(12000);con.setReadTimeout(35000);
        con.setRequestProperty("Authorization","Bearer "+key);
        con.setRequestProperty("Accept","application/json");
        if(!mode.equals("models")) {
            con.setRequestMethod("POST");con.setDoOutput(true);con.setRequestProperty("Content-Type","application/json; charset=utf-8");
            String instruction=mode.equals("parse")?"你是记账识别助手。仅输出 JSON 对象，字段 amount 数字，kind 为 expense 或 income，category 中文分类，date YYYY-MM-DD，note，merchant，accountName。不要 markdown。":"你是记账助手。用简洁中文回答，不要杜撰用户未提供的账目数据。";
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",instruction))
                .put(new JSONObject().put("role","user").put("content",mode.equals("test")?"请仅回复连接成功":text));
            byte[] payload=new JSONObject().put("model",model).put("messages",messages).toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=con.getOutputStream()){out.write(payload);}
        }
        int status=con.getResponseCode();
        InputStream input=status>=200&&status<300?con.getInputStream():con.getErrorStream();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        if(input!=null){while((count=input.read(buffer))!=-1&&bytes.size()<2_000_000)bytes.write(buffer,0,count);input.close();}
        con.disconnect();
        String response=bytes.toString("UTF-8");
        if(status<200||status>=300)throw new IOException("HTTP "+status+"："+response.substring(0,Math.min(response.length(),150)));
        return response;
    }
    @Override public void onDestroy(){if(web!=null){web.removeJavascriptInterface("NativeBridge");web.destroy();}super.onDestroy();}
}
