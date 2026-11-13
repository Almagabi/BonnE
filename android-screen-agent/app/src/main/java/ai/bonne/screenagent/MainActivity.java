package ai.bonne.screenagent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;

public class MainActivity extends Activity {
    private TextView status;
    private EditText workflow;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean recordingUi=false;
    private final Runnable update=new Runnable(){
        @Override public void run(){
            ReplayService s=ReplayService.active;
            if(s!=null && s.isRecording()) {
                status.setText("● Recording actions: "+s.count()+"\nPerform your task in another app. Return here and press STOP.");
                handler.postDelayed(this,700);
            }
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},55);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,25,24,24);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);setContentView(scroll);
        TextView heading=new TextView(this);heading.setText("BonnE • Teach by Doing");
        heading.setTextSize(24);box.addView(heading);
        TextView intro=new TextView(this);
        intro.setText("No API. No cloud. Start recording, perform a task, return here and stop. Then replay it.");
        box.addView(intro);
        button(box,"1  Enable Accessibility",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(box,"2  ● START RECORDING",v->{
            ReplayService s=ReplayService.active;
            if(s==null){say("Enable BonnE Accessibility first.");return;}
            s.startRecording();recordingUi=true;
            say("Recording. Go to another app and perform your task.");
            handler.removeCallbacks(update);handler.post(update);
        });
        button(box,"3  ■ STOP AND SAVE",v->{
            ReplayService s=ReplayService.active;
            if(s==null||!s.isRecording()){say("Nothing is recording.");return;}
            JSONArray actions=s.stopRecording();
            handler.removeCallbacks(update);
            recordingUi=false;
            String serialized=actions.toString();
            workflow.setText(serialized);
            getPreferences(0).edit().putString("workflow",serialized).apply();
            say("Saved "+actions.length()+" steps. Ready to replay.");
        });
        button(box,"✕ CANCEL RECORDING (DISCARD)",v->{
            ReplayService service=ReplayService.active;
            if(service==null||!service.isRecording()){say("Nothing to cancel.");return;}
            service.cancelRecording();
            handler.removeCallbacks(update);
            recordingUi=false;
            say("Recording discarded. Previously saved workflow is unchanged.");
        });
        button(box,"▶ REPLAY",v->{
            ReplayService s=ReplayService.active;
            if(s==null){say("Enable BonnE Accessibility first.");return;}
            try{
                JSONArray steps=new JSONArray(workflow.getText().toString());
                if(steps.length()==0){say("Record a demonstration first.");return;}
                if(steps.length()>150){say("Workflow exceeds 150 steps.");return;}
                s.play(steps);
                say("Replaying "+steps.length()+" steps. The target app should open automatically.");
            }catch(Exception e){say("Invalid workflow: "+e.getMessage());}
        });
        button(box,"■ STOP REPLAY",v->{
            if(ReplayService.active!=null)ReplayService.active.stopReplay();
            say("Playback stopped.");
        });
        button(box,"Save edited workflow",v->{
            try {
                JSONArray j=new JSONArray(workflow.getText().toString());
                getPreferences(0).edit().putString("workflow",j.toString()).apply();
                say("Saved "+j.length()+" steps locally.");
            }catch(Exception e){say("Invalid workflow: "+e.getMessage());}
        });
        TextView label=new TextView(this);
        label.setText("Recorded workflow (editable advanced JSON)");box.addView(label);
        workflow=new EditText(this);
        workflow.setMinLines(5);
        workflow.setSingleLine(false);
        workflow.setGravity(android.view.Gravity.TOP);
        workflow.setText(getPreferences(0).getString("workflow","[]"));
        box.addView(workflow);
        status=new TextView(this);
        status.setPadding(0,14,0,14);
        status.setText("Ready. Enable Accessibility to begin.");
        box.addView(status);
        TextView limitations=new TextView(this);
        limitations.setText("Records accessible clicks, long presses, text edits and scroll events. Android does not expose every raw touch to Accessibility services, so canvas gestures and protected screens may not record. Password fields are excluded. Recordings stay on your phone.");
        box.addView(limitations);
    }
    private void button(LinearLayout box,String name,View.OnClickListener click){
        Button b=new Button(this);b.setText(name);b.setOnClickListener(click);box.addView(b);
    }
    private void say(String m){status.setText(m);}
    @Override protected void onResume(){super.onResume(); if(status!=null && (ReplayService.active==null || !ReplayService.active.isRecording())) {handler.removeCallbacks(update);} }
    @Override protected void onDestroy(){handler.removeCallbacks(update);super.onDestroy();}
}
