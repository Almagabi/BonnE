package ai.bonne.screenagent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private EditText endpoint, model, key, actions;
    private TextView status;
    private Uri video;
    private final int PICK_VIDEO = 4;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 20, 28, 25);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this); title.setText("BonnE Screen Agent • Android"); title.setTextSize(23); layout.addView(title);
        TextView info = new TextView(this);
        info.setText("Import a screen recording, ask your own vision model for actions, review JSON, then replay. Coordinates are 0–1000. Never run unattended on sensitive apps.");
        layout.addView(info);
        endpoint = input(layout, "Chat completions HTTPS URL", "https://api.openai.com/v1/chat/completions");
        model = input(layout, "Vision model", "gpt-4.1-mini");
        key = input(layout, "API key (kept only in current session)", "");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(layout, "1. Choose screen recording (MP4)", v -> chooseVideo());
        button(layout, "2. Analyze video with your AI", v -> analyze());
        button(layout, "3. Enable Accessibility", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        actions = input(layout, "Editable workflow JSON", "[{\"type\":\"tap\",\"x\":500,\"y\":500,\"delay_ms\":700}]");
        actions.setMinLines(9); actions.setGravity(android.view.Gravity.TOP);
        button(layout, "Save workflow locally", v -> {
            try { new JSONArray(actions.getText().toString()); getPreferences(0).edit().putString("workflow", actions.getText().toString()).apply(); say("Saved."); }
            catch (Exception e) { say("Invalid JSON: "+e.getMessage()); }
        });
        button(layout, "▶ Replay workflow", v -> {
            if (ReplayService.active == null) { say("Enable BonnE Screen Agent in Accessibility settings first."); return; }
            try { JSONArray workflow = new JSONArray(actions.getText().toString()); 
                if(workflow.length() > 100) { say("Maximum 100 actions."); return; }
                ReplayService.active.play(workflow); say("Replaying. Open the target app now.");
            } catch(Exception e) { say("Invalid workflow: " + e.getMessage()); }
        });
        button(layout, "■ Stop playback", v -> { if(ReplayService.active!=null) ReplayService.active.stop(); say("Stopped."); });
        status = new TextView(this); status.setText("Ready. AI analysis is optional. You can edit JSON manually."); status.setPadding(0,20,0,0); layout.addView(status);
        String saved = getPreferences(0).getString("workflow", "");
        if (!saved.isEmpty()) actions.setText(saved);
    }
    private EditText input(LinearLayout l,String hint,String value) {
        TextView name = new TextView(this); name.setText(hint); l.addView(name);
        EditText box = new EditText(this); box.setSingleLine(false); box.setText(value); l.addView(box); return box;
    }
    private void button(LinearLayout l,String label,View.OnClickListener c) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(c); l.addView(b);
    }
    private void say(String s) { runOnUiThread(() -> status.setText(s)); }
    private void chooseVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/*"); startActivityForResult(i, PICK_VIDEO);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PICK_VIDEO && result==RESULT_OK && data!=null) {
            video=data.getData(); say("Video selected. Press Analyze.");
        }
    }
    private void analyze() {
        if (video==null) { say("Choose a video first."); return; }
        String url = endpoint.getText().toString().trim();
        if (!url.startsWith("https://")) { say("Use an HTTPS model endpoint."); return; }
        String m=model.getText().toString().trim(), secret=key.getText().toString().trim();
        if(m.isEmpty()) { say("Enter model name."); return; }
        say("Analyzing selected frames…");
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("model",m); req.put("max_tokens",1600);
                JSONArray content=new JSONArray();
                content.put(new JSONObject().put("type","text").put("text",
                  "These are chronological screenshots sampled from a user's Android screen recording. Infer a proposed automation workflow. Return ONLY JSON array of actions, no markdown. Allowed types tap(x,y), swipe(x,y,x2,y2,duration_ms), text(value), back, home, wait(delay_ms). Every tap/swipe coordinate must be normalized 0..1000 based on image dimensions. Use delay_ms for waits, usually 900. Max 30 steps. Do not invent passwords, hidden taps or missing transitions. If ambiguous output [] instead. Do not include financial confirmations or irreversible actions."));
                MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this,video);
                    String len=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long duration=(len==null?1000:Long.parseLong(len))*1000L;
                    for(int i=0;i<6;i++) {
                        Bitmap frame=retriever.getFrameAtTime(duration*i/6,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if(frame==null) continue;
                        Bitmap resized=Bitmap.createScaledBitmap(frame, Math.min(720,frame.getWidth()), Math.min(1280,frame.getHeight()),true);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); resized.compress(Bitmap.CompressFormat.JPEG,65,bytes);
                        String base64=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
                        content.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/jpeg;base64,"+base64)));
                        resized.recycle();
                        if(frame!=resized) frame.recycle();
                    }
                } finally { retriever.release(); }
                if(content.length()<2) throw new Exception("Could not extract video frames.");
                req.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",content)));
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(90000);
                c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                if(!secret.isEmpty())c.setRequestProperty("Authorization","Bearer "+secret);
                try(OutputStream o=c.getOutputStream()){o.write(req.toString().getBytes(StandardCharsets.UTF_8));}
                int code=c.getResponseCode();
                InputStream in=code>=200 && code<300?c.getInputStream():c.getErrorStream();
                String result;
                try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
                    StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);result=sb.toString();
                }
                if(code<200 || code>=300)throw new Exception("Model HTTP "+code+": "+result.substring(0,Math.min(160,result.length())));
                JSONObject response=new JSONObject(result);
                String raw=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
                if(raw.startsWith("```")) { raw=raw.substring(3).replaceFirst("^json", "").replaceFirst("```$", "").trim(); }
                JSONArray parsed=new JSONArray(raw);
                if(parsed.length()>100)throw new Exception("Too many actions");
                String pretty=parsed.toString(2);
                runOnUiThread(()->actions.setText(pretty));
                say("Review every step and coordinate, then Save and Replay.");
            } catch(Exception e) { say("Analysis failed: "+e.getMessage()); }
        }).start();
    }
}
