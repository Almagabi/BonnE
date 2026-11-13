package ai.bonne.screenagent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;

public class MainActivity extends Activity {
    private TextView status;
    private TextView saved;
    private Button startButton, saveButton, cancelButton, replayButton, stopReplayButton, accessButton;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final String WORKFLOW = "workflow";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshUI();
            handler.postDelayed(this, 450);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);

        int bg=Color.rgb(12,17,29);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(22),dp(20),dp(30));
        content.setBackgroundColor(bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);

        TextView title = label("BonnE", 34, Color.WHITE, true);
        content.addView(title);
        TextView subtitle = label("Teach by doing  •  Offline", 15, Color.rgb(170,185,210), false);
        content.addView(subtitle);
        gap(content, 24);

        status = label("Checking accessibility...", 19, Color.WHITE, true);
        status.setPadding(dp(15),dp(16),dp(15),dp(16));
        status.setBackground(shape(Color.rgb(29,39,59), 16));
        content.addView(status, new LinearLayout.LayoutParams(-1,-2));
        gap(content, 14);

        accessButton=button(content,"Enable Accessibility", Color.rgb(65,86,118),()->{
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        gap(content, 18);
        startButton=button(content,"●  START RECORDING", Color.rgb(187,37,72),()->{
            ReplayService service=ReplayService.active;
            if(service==null){showMessage("Enable Accessibility first.");return;}
            service.startRecording();
            showMessage("Recording started. Open another app and demonstrate your task.");
            refreshUI();
        });
        gap(content, 10);
        saveButton=button(content,"■  STOP & SAVE", Color.rgb(24,130,102),()->{
            ReplayService service=ReplayService.active;
            if(service==null || !service.isRecording()){showMessage("No active recording.");return;}
            JSONArray recorded=service.stopRecording();
            if(recorded.length()==0){showMessage("Nothing was captured. Try again.");refreshUI();return;}
            getPreferences(0).edit().putString(WORKFLOW,recorded.toString()).apply();
            showMessage("Saved "+recorded.length()+" recorded actions.");
            refreshUI();
        });
        gap(content, 10);
        cancelButton=button(content,"✕  CANCEL & DISCARD",Color.rgb(111,60,75),()->{
            ReplayService service=ReplayService.active;
            if(service==null || !service.isRecording()){showMessage("No active recording.");return;}
            service.cancelRecording();
            showMessage("Recording discarded. Previous saved task is safe.");
            refreshUI();
        });
        gap(content, 26);
        saved=label("",16,Color.rgb(170,185,210),false);
        content.addView(saved);
        gap(content, 10);
        replayButton=button(content,"▶  REPLAY SAVED TASK",Color.rgb(38,103,190),()->{
            ReplayService service=ReplayService.active;
            if(service==null){showMessage("Enable Accessibility first.");return;}
            try {
                JSONArray steps=new JSONArray(getPreferences(0).getString(WORKFLOW,"[]"));
                if(steps.length()==0){showMessage("Record and save a task first.");return;}
                service.play(steps);
                showMessage("Playing "+steps.length()+" steps. You can stop playback here.");
                refreshUI();
            } catch(Exception e){showMessage("Saved task is invalid.");}
        });
        gap(content,10);
        stopReplayButton=button(content,"■  STOP PLAYBACK",Color.rgb(70,75,92),()->{
            ReplayService service=ReplayService.active;
            if(service!=null) service.stopReplay();
            showMessage("Playback stopped.");
            refreshUI();
        });
        gap(content, 22);
        TextView explanation=label(
            "Record your actions in another app. Return here and choose Save or Discard. " +
            "A recording notification stays visible while recording.\n\n" +
            "BonnE records accessible buttons, typing and scrolling. " +
            "Some protected screens and custom gestures cannot be captured by Android Accessibility.",
            14, Color.rgb(167,179,199), false);
        content.addView(explanation);
        refreshUI();
    }

    private Button button(LinearLayout root, String title, int color, Runnable action) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(60));
        b.setBackground(shape(color,14));
        b.setOnClickListener(v->action.run());
        root.addView(b,new LinearLayout.LayoutParams(-1,dp(60)));
        return b;
    }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView label(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void gap(LinearLayout root,int height) {
        root.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
    }
    private int dp(int n) {return (int)(n*getResources().getDisplayMetrics().density+0.5f);}

    private void refreshUI() {
        if(status==null)return;
        ReplayService service=ReplayService.active;
        boolean connected=service!=null;
        boolean recording=connected && service.isRecording();
        boolean playing=connected && service.isReplaying();
        boolean hasSaved=false;
        int count=0;
        try {
            JSONArray steps=new JSONArray(getPreferences(0).getString(WORKFLOW,"[]"));
            count=steps.length();
            hasSaved=count>0;
        } catch(Exception ignored){}
        final boolean savedTask=hasSaved;
        accessButton.setVisibility(connected?View.GONE:View.VISIBLE);
        startButton.setEnabled(connected && !recording && !playing);
        startButton.setAlpha(startButton.isEnabled()?1f:0.4f);
        saveButton.setVisibility(recording?View.VISIBLE:View.GONE);
        cancelButton.setVisibility(recording?View.VISIBLE:View.GONE);
        replayButton.setEnabled(connected && savedTask && !recording && !playing);
        replayButton.setAlpha(replayButton.isEnabled()?1f:0.4f);
        stopReplayButton.setVisibility(playing?View.VISIBLE:View.GONE);
        saved.setText(savedTask?"Saved task: "+count+" actions":"No saved task yet");
        if(recording) status.setText("🔴 RECORDING  •  "+service.count()+" actions\nSwitch apps to demonstrate your task.");
        else if(playing) status.setText("▶ PLAYING SAVED TASK");
        else if(!connected)status.setText("Accessibility is OFF");
        else status.setText("Ready  •  Not recording");
    }
    private void showMessage(String message) {
        android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();
    }
    @Override protected void onResume() {super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause() {handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy() {handler.removeCallbacks(refresh);super.onDestroy();}
}
