package ai.bonne.screenagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int BG=0xff0e1628;
    private static final int PANEL=0xff1b2a45;
    private TextView status;
    private LinearLayout tasks;
    private TextView draftStatus;
    private Button draftButton;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastSavedFingerprint="";
    private final Runnable refresh=new Runnable(){
        @Override public void run() {
            refreshState();
            handler.postDelayed(this,600);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},55);
        }
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(18),dp(16),dp(30));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("BonnE",32,true,Color.WHITE));
        root.addView(text("My Automations  •  Offline",15,false,0xffb0bdd0));
        space(root,16);
        status=text("Checking accessibility...",16,true,Color.WHITE);
        status.setPadding(dp(13),dp(15),dp(13),dp(15));
        status.setBackground(shape(PANEL,12));
        root.addView(status,new LinearLayout.LayoutParams(-1,-2));
        space(root,12);
        button(root,"Enable Accessibility",0xff526784,()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        space(root,10);
        button(root,"●  START RECORDING",0xffba3153,()->{
            ReplayService service=ReplayService.active;
            if(service==null){message("Enable Accessibility in Android settings first.");return;}
            service.startRecording();
            message("Recording started. Go to the phone Home screen and perform your task.");
            refreshState();
        });
        space(root,8);
        button(root,"⌂  OPEN PHONE HOME",0xff53627b,()->{
            Intent home=new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        });
        space(root,8);
        button(root,"■  STOP & SAVE RECORDING",0xff168469,()->{
            ReplayService service=ReplayService.active;
            if(service==null || !service.isRecording()){message("No active recording.");return;}
            JSONArray steps=service.stopRecording();
            if(actionCount(steps)==0) {
                message("No taps, typing, or scrolls were captured. Nothing saved.");
                refreshState();
                return;
            }
            JSONObject saved=WorkflowStore.add(this,steps);
            message("Saved "+saved.optString("name")+" ("+actionCount(steps)+" actions).");
            updateLibrary(true);
            refreshState();
        });
        space(root,8);
        button(root,"✕  CANCEL & DISCARD RECORDING",0xff754254,()->{
            ReplayService s=ReplayService.active;
            if(s==null || !s.isRecording()){message("No active recording.");return;}
            s.cancelRecording();
            message("Recording discarded. Saved automations unchanged.");
            refreshState();
        });
        space(root,14);
        draftStatus=text("",14,false,0xffb0bdd0);
        root.addView(draftStatus);
        draftButton=button(root,"Save interrupted recording",0xff576a8a,()->{
            JSONArray steps=WorkflowStore.draft(this);
            if(actionCount(steps)==0){message("No usable recording in draft.");return;}
            JSONObject item=WorkflowStore.add(this,steps);
            message("Recovered "+item.optString("name"));
            updateLibrary(true);
            refreshState();
        });
        space(root,18);
        root.addView(text("SAVED AUTOMATIONS",20,true,Color.WHITE));
        space(root,7);
        root.addView(text("Run once, repeat the SAME task, or visit DIFFERENT profiles until Stop.",13,false,0xffb0bdd0));
        space(root,10);
        tasks=new LinearLayout(this);
        tasks.setOrientation(LinearLayout.VERTICAL);
        root.addView(tasks,new LinearLayout.LayoutParams(-1,-2));
        space(root,16);
        button(root,"■  STOP PLAYBACK / LOOP",0xff9c3245,()->{
            ReplayService service=ReplayService.active;
            if(service!=null)service.stopReplay();
            message("Playback stopped.");
            refreshState();
        });
        space(root,12);
        root.addView(text("NEXT PROFILES: Start recording while the search results are visible. Open one profile, do the desired action, go Back to results, and Stop & Save. Choose NEXT PROFILES and return to results within 5 seconds. BonnE selects different accessible profiles until stopped or exhausted.", 13,false,0xffc9d8ee));
        space(root,10);
        root.addView(text("Recordings stay in this app's private storage, even after closing it. "+
            "An unfinished recording can be recovered as a draft. "+
            "Uninstalling or clearing app data deletes stored automations. "+
            "Some protected or custom-control screens cannot be automated using Accessibility.",
            12,false,0xff8e9bb4));
        updateLibrary(true);
        refreshState();
    }
    private int actionCount(JSONArray steps){
        int count=0;
        for(int i=0;i<steps.length();i++){
            JSONObject x=steps.optJSONObject(i);
            if(x!=null && !x.optString("type","").equals("launch"))count++;
        }
        return count;
    }
    private void updateLibrary(boolean force){
        if(tasks==null)return;
        JSONArray lib=WorkflowStore.all(this);
        String fingerprint=lib.toString();
        if(!force && fingerprint.equals(lastSavedFingerprint))return;
        lastSavedFingerprint=fingerprint;
        tasks.removeAllViews();
        if(lib.length()==0){
            tasks.addView(text("No automations saved yet. Record one above.",15,false,0xff9cacc9));
            return;
        }
        for(int i=0;i<lib.length();i++){
            JSONObject item=lib.optJSONObject(i);
            if(item==null)continue;
            String id=item.optString("id");
            String name=item.optString("name","Automation");
            JSONArray steps=item.optJSONArray("steps");
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12),dp(12),dp(12),dp(12));
            card.setBackground(shape(PANEL,12));
            tasks.addView(card,new LinearLayout.LayoutParams(-1,-2));
            card.addView(text(name,19,true,Color.WHITE));
            card.addView(text(actionCount(steps==null?new JSONArray():steps)+" recorded actions",13,false,0xffb0bdd0));
            space(card,8);
            LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(top);
            smallButton(top,"▶ RUN",0xff286ab8,()->runTask(id,1));
            smallButton(top,"↻ REPEAT",0xff59667c,()->chooseLoop(id));
            space(card,8);
            button(card,"∞  NEXT PROFILES • UNTIL STOP",0xff168469,()->runNextProfiles(id));
            space(card,7);
            LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(bottom);
            smallButton(bottom,"Rename",0xff495a7a,()->renameTask(id,name));
            smallButton(bottom,"Delete",0xff793c53,()->deleteTask(id,name));
            space(tasks,10);
        }
    }
    private JSONObject find(String id){
        JSONArray all=WorkflowStore.all(this);
        for(int i=0;i<all.length();i++){
            JSONObject x=all.optJSONObject(i);
            if(x!=null && x.optString("id").equals(id))return x;
        }
        return null;
    }
    private void runTask(String id,int repeats){
        ReplayService s=ReplayService.active;
        if(s==null){message("Enable Accessibility first.");return;}
        JSONObject item=find(id);
        if(item==null){message("Automation not found.");updateLibrary(true);return;}
        JSONArray steps=item.optJSONArray("steps");
        if(steps==null || actionCount(steps)==0){message("This recording has no executable actions. Re-record it.");return;}
        if(s.isRecording()){message("Save or cancel your current recording first.");return;}
        s.play(steps,repeats);
        message(repeats==1?"Playing "+item.optString("name"):
            ("Looping "+item.optString("name")+" ("+(repeats==0?"until stopped":repeats+" times")+")"));
        refreshState();
    }

    private void runNextProfiles(String id) {
        ReplayService service=ReplayService.active;
        if(service==null){message("Enable Accessibility first.");return;}
        if(service.isRecording()){message("Stop and save the recording first.");return;}
        JSONObject item=find(id);
        JSONArray steps=item==null?null:item.optJSONArray("steps");
        if(steps==null || !service.startSmartProfiles(steps)){
            message("Teach one profile from results, perform its action, return and save.");
            return;
        }
        message("Switch to your RESULTS PAGE within 5 seconds. Stop anytime.");
        refreshState();
    }
    private void chooseLoop(String id){
        EditText count=new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setText("3");
        count.setSelectAllOnFocus(true);
        LinearLayout panel=new LinearLayout(this);
        panel.setPadding(dp(22),0,dp(22),0);
        panel.addView(count,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this)
            .setTitle("How many repeats?")
            .setMessage("Enter 3 for three runs, or 0 to repeat until you press Stop.")
            .setView(panel)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Run", (dialog,which)->{
                try {
                    int n=Integer.parseInt(count.getText().toString().trim());
                    if(n<0||n>999){message("Choose 0 to 999.");return;}
                    runTask(id,n);
                }catch(Exception ignored){message("Enter a repeat count.");}
            }).show();
    }
    private void renameTask(String id,String current){
        EditText input=new EditText(this);input.setText(current);input.selectAll();
        new AlertDialog.Builder(this).setTitle("Rename automation")
            .setView(input).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->{
                String name=input.getText().toString().trim();
                if(name.isEmpty()){message("Name cannot be empty.");return;}
                WorkflowStore.rename(this,id,name);
                updateLibrary(true);
            }).show();
    }
    private void deleteTask(String id,String name){
        new AlertDialog.Builder(this).setTitle("Delete "+name+"?")
            .setMessage("This only deletes the selected automation.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Delete",(d,w)->{
                WorkflowStore.remove(this,id);
                updateLibrary(true);
            }).show();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,boolean bold,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private GradientDrawable shape(int color,int r){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(r));return d;
    }
    private void space(LinearLayout root,int height){
        root.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
    }
    private Button button(LinearLayout root,String title,int color,Runnable action){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setTextColor(Color.WHITE);b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setGravity(Gravity.CENTER);b.setBackground(shape(color,10));
        root.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));
        b.setOnClickListener(v->action.run());
        return b;
    }
    private void smallButton(LinearLayout row,String title,int color,Runnable action){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setTextColor(Color.WHITE);b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(shape(color,8));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(46),1);
        params.setMargins(dp(3),0,dp(3),0);
        row.addView(b,params);
        b.setOnClickListener(v->action.run());
    }
    private void refreshState(){
        if(status==null)return;
        ReplayService s=ReplayService.active;
        if(s==null)status.setText("Accessibility OFF. Enable it to record and run.");
        else if(s.isRecording())status.setText("🔴 RECORDING  •  "+s.count()+" steps captured\nUse Stop & Save or Cancel.");
        else if(s.isSmartLoop())status.setText("∞ NEXT PROFILES  •  "+s.profilesProcessed()+" processed\nSwitch back to the results page. Stop anytime.");
        else if(s.isReplaying())status.setText("▶ RUNNING  •  "+s.executed()+" actions executed\nUse Stop Playback to end loops.");
        else status.setText("READY  •  "+s.lastResult());
        JSONArray draft=WorkflowStore.draft(this);
        boolean active=s!=null && s.isRecording();
        if(draftButton!=null)draftButton.setVisibility(!active && actionCount(draft)>0?View.VISIBLE:View.GONE);
        if(draftStatus!=null)draftStatus.setVisibility(!active && actionCount(draft)>0?View.VISIBLE:View.GONE);
        if(draftStatus!=null && !active && actionCount(draft)>0)
            draftStatus.setText("Unfinished recording recovered: "+actionCount(draft)+" actions.");
        updateLibrary(false);
    }
    private void message(String m){Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
