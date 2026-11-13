package ai.bonne.screenagent;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.widget.*;
import android.view.View;
import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private EditText command;
    private TextView status;
    private final String example =
        "# Coordinates 0-1000 relative to screen size\n" +
        "wait 5000\n" +
        "tap 500 500\n" +
        "wait 700\n" +
        "swipe 500 800 500 200 400\n" +
        "# text hello world\n" +
        "# back\n";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        TextView heading=new TextView(this);
        heading.setText("BonnE Offline Automation");
        heading.setTextSize(24);
        root.addView(heading);
        TextView sub=new TextView(this);
        sub.setText("No API key. No internet. Enter commands one per line. Press RUN and switch to your target app during the first 5-second wait. Touch coordinates are percentages scaled 0 to 1000.");
        root.addView(sub);
        button(root,"1. Enable Android Accessibility",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        command=new EditText(this);
        command.setSingleLine(false);
        command.setMinLines(12);
        command.setGravity(android.view.Gravity.TOP);
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setText(getPreferences(0).getString("commands",example));
        root.addView(command);
        button(root,"2. Save commands",v->{
            try { parse(command.getText().toString()); 
                getPreferences(0).edit().putString("commands",command.getText().toString()).apply(); say("Saved to this phone."); 
            } catch(Exception e) { say(e.getMessage()); }
        });
        button(root,"▶ RUN",v->{
            if(ReplayService.active==null) {say("Enable BonnE in Accessibility settings first.");return;}
            try{
                JSONArray actions=parse(command.getText().toString());
                if(actions.length()==0){say("No actions.");return;}
                getPreferences(0).edit().putString("commands",command.getText().toString()).apply();
                ReplayService.active.play(actions);
                say("Running "+actions.length()+" commands. Switch to the target app now.");
            } catch(Exception e) {say(e.getMessage());}
        });
        button(root,"■ STOP",v->{
            if(ReplayService.active!=null)ReplayService.active.stop();
            say("Stopped.");
        });
        button(root,"Load example",v->command.setText(example));
        TextView help = new TextView(this);
        help.setText("\nCOMMANDS\n" +
            "tap X Y\n" +
            "swipe X Y X2 Y2 DURATION_MS\n" +
            "text words to type\n" +
            "wait MILLISECONDS\n" +
            "back\n" +
            "home\n" +
            "Lines starting with # are comments.\n" +
            "Example: tap 500 500 = center of screen.\n" +
            "The text command needs an editable focused field.\n" +
            "Protected screens may not allow automation.");
        root.addView(help);
        status=new TextView(this);
        status.setText("Ready.");
        status.setPadding(0,16,0,28);
        root.addView(status);
    }
    private void button(LinearLayout l,String title,View.OnClickListener listener) {
        Button b=new Button(this);b.setText(title);b.setOnClickListener(listener);l.addView(b);
    }
    private void say(String message) {status.setText(message);}
    private JSONArray parse(String source) throws Exception {
        JSONArray list=new JSONArray();
        String[] lines=source.split("\\r?\\n");
        for(int i=0;i<lines.length;i++){
            String line=lines[i].trim();
            if(line.isEmpty()||line.startsWith("#"))continue;
            String[] a=line.split("\\s+");
            String op=a[0].toLowerCase(java.util.Locale.ROOT);
            JSONObject step=new JSONObject();
            try {
                switch(op) {
                    case "tap":
                        if(a.length!=3)throw new Exception("tap needs X Y");
                        step.put("type","tap");step.put("x",pos(a[1]));step.put("y",pos(a[2]));
                        break;
                    case "swipe":
                        if(a.length!=6)throw new Exception("swipe needs X Y X2 Y2 DURATION_MS");
                        step.put("type","swipe");step.put("x",pos(a[1]));step.put("y",pos(a[2]));
                        step.put("x2",pos(a[3]));step.put("y2",pos(a[4]));step.put("duration_ms",ms(a[5]));
                        break;
                    case "text":
                        if(line.length()<6)throw new Exception("text needs words");
                        step.put("type","text");step.put("value",line.substring(5));
                        break;
                    case "wait":
                        if(a.length!=2)throw new Exception("wait needs milliseconds");
                        step.put("type","wait");step.put("delay_ms",ms(a[1]));
                        break;
                    case "back":case "home":
                        if(a.length!=1)throw new Exception(op+" needs no parameters");
                        step.put("type",op);break;
                    default:throw new Exception("Unknown command: "+op);
                }
                list.put(step);
                if(list.length()>100)throw new Exception("Maximum 100 commands per workflow");
            }catch(Exception e){throw new Exception("Line "+(i+1)+": "+e.getMessage());}
        }
        return list;
    }
    private int pos(String s) {int n=Integer.parseInt(s);if(n<0||n>1000)throw new IllegalArgumentException("Position must be 0 to 1000");return n;}
    private int ms(String s) {int n=Integer.parseInt(s);if(n<0||n>10000)throw new IllegalArgumentException("Duration must be 0 to 10000");return n;}
}
