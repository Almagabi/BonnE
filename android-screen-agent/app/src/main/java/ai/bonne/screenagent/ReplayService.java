package ai.bonne.screenagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class ReplayService extends AccessibilityService {
    public static ReplayService active;
    private static final String CHANNEL="bonne_agent_status";
    private static final int NOTIFICATION_ID=1207;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private JSONArray recorded=new JSONArray();
    private boolean recording=false;
    private boolean replaying=false;
    private int runId=0,loops=1,round=0,executed=0;
    private String firstPackage="";
    private long lastTime=0;
    private Runnable pendingText;
    private JSONObject latestText;
    private String lastResult="Idle";

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active=this;
        AccessibilityServiceInfo info=getServiceInfo();
        info.eventTypes=AccessibilityEvent.TYPE_VIEW_CLICKED|AccessibilityEvent.TYPE_VIEW_LONG_CLICKED|
                AccessibilityEvent.TYPE_VIEW_SCROLLED|AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED|
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout=60;
        setServiceInfo(info);
    }

    public boolean isRecording(){return recording;}
    public boolean isReplaying(){return replaying;}
    public int count(){return recorded.length();}
    public int executed(){return executed;}
    public String lastResult(){return lastResult;}

    public void startRecording() {
        stopReplay();
        recorded=new JSONArray();
        firstPackage="";
        latestText=null;
        lastTime=SystemClock.uptimeMillis();
        recording=true;
        WorkflowStore.saveDraft(this,recorded);
        showNotification(true);
        lastResult="Recording";
    }
    public JSONArray stopRecording(){
        if(!recording)return new JSONArray();
        flushText();
        recording=false;
        removeNotification();
        WorkflowStore.saveDraft(this,recorded);
        lastResult="Captured "+recorded.length()+" steps";
        return recorded;
    }
    public void cancelRecording(){
        recording=false;
        if(pendingText!=null)handler.removeCallbacks(pendingText);
        pendingText=null;latestText=null;
        recorded=new JSONArray();firstPackage="";
        WorkflowStore.clearDraft(this);
        removeNotification();lastResult="Discarded";
    }
    private void interruptRecording(){
        if(recording) {
            flushText();
            recording=false;
            // Draft remains on disk if Android kills or disconnects the service.
            WorkflowStore.saveDraft(this,recorded);
            lastResult="Recording interrupted; draft preserved";
        }
        removeNotification();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!recording || replaying || event==null || recorded.length()>=200)return;
        CharSequence srcPkg=event.getPackageName();
        if(srcPkg==null)return;
        String pkg=srcPkg.toString();
        if(pkg.equals(getPackageName()) || pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod") || pkg.contains("latininput")) return;
        int kind=event.getEventType();
        // Ignore launcher gestures; capture the target's first displayed window instead.
        if(firstPackage.isEmpty() && kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
           !isLauncher(pkg)) {
            firstPackage=pkg;
            JSONObject launch=new JSONObject();
            try{launch.put("type","launch").put("package",pkg);}catch(Exception ignored){}
            append(launch);
            return;
        }
        if(kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        if(firstPackage.isEmpty() && isLauncher(pkg))return;
        if(firstPackage.isEmpty()){
            firstPackage=pkg;
            JSONObject launch=new JSONObject();
            try{launch.put("type","launch").put("package",pkg);}catch(Exception ignored){}
            append(launch);
        }
        AccessibilityNodeInfo node=event.getSource();
        if(node==null)return;
        try{
            if(kind==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED){
                if(node.isPassword() || !node.isEditable())return;
                CharSequence text=node.getText();
                if(text==null || text.length()>500)return;
                JSONObject step=base(node,pkg);
                step.put("type","text");
                step.put("value",text.toString());
                if(pendingText!=null)handler.removeCallbacks(pendingText);
                latestText=step;
                pendingText=()->flushText();
                handler.postDelayed(pendingText,700);
                return;
            }
            JSONObject step=base(node,pkg);
            if(kind==AccessibilityEvent.TYPE_VIEW_CLICKED)step.put("type","tap");
            else if(kind==AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)step.put("type","long");
            else if(kind==AccessibilityEvent.TYPE_VIEW_SCROLLED){
                step.put("type","scroll");
                step.put("direction",event.getScrollDeltaY()<0?"backward":"forward");
            }else return;
            flushText();
            append(step);
        }catch(Exception ignored){}
        finally{node.recycle();}
    }
    private boolean isLauncher(String pkg) {
        return pkg.contains("launcher") || pkg.equals("com.google.android.apps.nexuslauncher");
    }
    private JSONObject base(AccessibilityNodeInfo node,String pkg)throws Exception{
        Rect b=new Rect();node.getBoundsInScreen(b);
        int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
        int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        JSONObject a=new JSONObject();
        a.put("package",pkg);
        a.put("x",Math.max(0,Math.min(1000,b.centerX()*1000/w)));
        a.put("y",Math.max(0,Math.min(1000,b.centerY()*1000/h)));
        if(node.getViewIdResourceName()!=null)a.put("view_id",node.getViewIdResourceName());
        CharSequence label=node.getContentDescription();
        if(label!=null && label.length()>0 && label.length()<120)a.put("label",label.toString());
        CharSequence text=node.getText();
        if(!node.isEditable() && !node.isPassword() && text!=null && text.length()>0 && text.length()<100)
            a.put("text",text.toString());
        return a;
    }
    private void flushText(){
        if(pendingText!=null)handler.removeCallbacks(pendingText);
        pendingText=null;
        if(latestText!=null && recording)append(latestText);
        latestText=null;
    }
    private void append(JSONObject step){
        if(!recording || recorded.length()>=200)return;
        long now=SystemClock.uptimeMillis();
        try{step.put("delay_ms",Math.max(160,Math.min(15000,now-lastTime)));}catch(Exception ignored){}
        recorded.put(step);lastTime=now;
        WorkflowStore.saveDraft(this,recorded);
    }

    public void stopReplay(){
        runId++;
        replaying=false;
        handler.removeCallbacksAndMessages(null);
        removeNotification();
        lastResult="Playback stopped";
    }
    // repeats=1: once; repeats=3: three total runs; repeats=0: repeat until Stop.
    public void play(JSONArray steps,int repeats){
        if(recording)stopRecording();
        stopReplay();
        if(steps==null || steps.length()==0 || steps.length()>200)return;
        loops=Math.max(0,Math.min(999,repeats));
        round=0;executed=0;replaying=true;
        lastResult="Playing";
        showNotification(false);
        int id=runId;
        runNext(steps,0,id);
    }
    public void play(JSONArray steps){play(steps,1);}
    private void runNext(JSONArray steps,int index,int id) {
        if(id!=runId || !replaying)return;
        if(index>=steps.length()){
            round++;
            if(loops==0 || round<loops){
                handler.postDelayed(()->runNext(steps,0,id),1300);
            }else{
                replaying=false;removeNotification();
                lastResult="Completed "+round+" run(s), "+executed+" step(s)";
            }
            return;
        }
        JSONObject action=steps.optJSONObject(index);
        if(action==null){runNext(steps,index+1,id);return;}
        long delay=Math.max(150,Math.min(15000,action.optLong("delay_ms",600)));
        handler.postDelayed(()->{
            if(id!=runId || !replaying)return;
            execute(action);
            executed++;
            if(index%4==0)showNotification(false);
            handler.postDelayed(()->runNext(steps,index+1,id),Math.max(350,
                action.optString("type").equals("launch")?1100:450));
        },delay);
    }
    private void execute(JSONObject a){
        String type=a.optString("type","");
        String pkg=a.optString("package","");
        android.util.Log.i("BonnE","EXECUTE "+type+" target="+pkg+" step="+executed);
        try {
            if(type.equals("launch")){
                Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
                if(i!=null){
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(i);
                }else android.util.Log.w("BonnE","No launchable intent for "+pkg);
                return;
            }
            if(type.equals("back")){performGlobalAction(GLOBAL_ACTION_BACK);return;}
            if(type.equals("home")){performGlobalAction(GLOBAL_ACTION_HOME);return;}
            if(type.equals("wait"))return;
            AccessibilityNodeInfo node=findNode(a);
            boolean worked=false;
            if(node!=null){
                if(type.equals("tap"))worked=clickAncestor(node,AccessibilityNodeInfo.ACTION_CLICK);
                if(type.equals("long"))worked=clickAncestor(node,AccessibilityNodeInfo.ACTION_LONG_CLICK);
                if(type.equals("scroll"))worked=clickAncestor(node,
                    a.optString("direction","forward").equals("backward")?
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if(type.equals("text") && node.isEditable() && !node.isPassword()){
                    Bundle args=new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.optString("value",""));
                    worked=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                }
                node.recycle();
            }
            if(!worked && (type.equals("tap") || type.equals("long")))tap(a,type.equals("long")?850:75);
            if(!worked && type.equals("scroll"))swipe(a);
            if(!worked && type.equals("text"))android.util.Log.w("BonnE","Could not locate editable text field");
        }catch(Exception e){android.util.Log.e("BonnE","Action failed "+type,e);}
    }
    private AccessibilityNodeInfo findNode(JSONObject a){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return null;
        String id=a.optString("view_id","");
        int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
        int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        int x=a.optInt("x",500)*w/1000, y=a.optInt("y",500)*h/1000;
        String expectedText=a.optString("text","");
        String expectedLabel=a.optString("label","");
        if(!id.isEmpty()){
            try{
                List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByViewId(id);
                if(matches!=null && !matches.isEmpty()){
                    AccessibilityNodeInfo best=null;
                    long bestScore=Long.MAX_VALUE;
                    for(AccessibilityNodeInfo n:matches) {
                        Rect rect=new Rect();n.getBoundsInScreen(rect);
                        long dx=rect.centerX()-x, dy=rect.centerY()-y;
                        long score=dx*dx+dy*dy;
                        if(!expectedText.isEmpty() && expectedText.equals(String.valueOf(n.getText())))score-=10000000;
                        if(!expectedLabel.isEmpty() && expectedLabel.equals(String.valueOf(n.getContentDescription())))score-=10000000;
                        if(score<bestScore){bestScore=score;best=n;}
                    }
                    if(best!=null)return best;
                }
            }catch(Exception ignored){}
        }
        if(!expectedText.isEmpty()){
            try{
                List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(expectedText);
                for(AccessibilityNodeInfo n:matches)
                    if(expectedText.equals(String.valueOf(n.getText())))return n;
            }catch(Exception ignored){}
        }
        return nearest(root,x,y,0);
    }
    private AccessibilityNodeInfo nearest(AccessibilityNodeInfo n,int x,int y,int depth){
        if(n==null || depth>24)return null;
        Rect bounds=new Rect();n.getBoundsInScreen(bounds);
        if(!bounds.contains(x,y))return null;
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo child=n.getChild(i);
            AccessibilityNodeInfo found=nearest(child,x,y,depth+1);
            if(found!=null)return found;
        }
        return n;
    }
    private boolean clickAncestor(AccessibilityNodeInfo node,int action){
        AccessibilityNodeInfo cur=node;
        for(int i=0;cur!=null && i<12;i++){
            if(cur.performAction(action))return true;
            cur=cur.getParent();
        }
        return false;
    }
    private void tap(JSONObject a,long ms){
        int w=getResources().getDisplayMetrics().widthPixels;
        int h=getResources().getDisplayMetrics().heightPixels;
        Path p=new Path();p.moveTo(a.optInt("x",500)*w/1000f,a.optInt("y",500)*h/1000f);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,ms)).build(),null,null);
    }
    private void swipe(JSONObject a){
        int w=getResources().getDisplayMetrics().widthPixels;
        int h=getResources().getDisplayMetrics().heightPixels;
        float x=a.optInt("x",500)*w/1000f,y=a.optInt("y",500)*h/1000f;
        Path p=new Path();
        if(a.optString("direction","forward").equals("backward")){
            p.moveTo(x,y-h*.19f);p.lineTo(x,y+h*.19f);
        }else{p.moveTo(x,y+h*.19f);p.lineTo(x,y-h*.19f);}
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,420)).build(),null,null);
    }
    private void showNotification(boolean isRecording){
        NotificationManager mgr=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"BonnE automation",
            NotificationManager.IMPORTANCE_DEFAULT);
        mgr.createNotificationChannel(channel);
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent opening=PendingIntent.getActivity(this,1,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,RecordingCancelReceiver.class);
        stop.setAction(isRecording?"ai.bonne.screenagent.CANCEL_RECORDING":
            "ai.bonne.screenagent.STOP_REPLAY");
        PendingIntent stopping=PendingIntent.getBroadcast(this,isRecording?2:3,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(isRecording?"● BonnE is recording":"▶ BonnE is replaying")
            .setContentText(isRecording?"Tap to return and save or discard.":("Executed "+executed+" actions."))
            .setContentIntent(opening).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                isRecording?"Discard recording":"STOP playback",stopping)
            .build();
        try{mgr.notify(NOTIFICATION_ID,n);}catch(SecurityException ignored){}
    }
    private void removeNotification(){
        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
    }
    @Override public void onInterrupt(){interruptRecording();stopReplay();}
    @Override public void onDestroy(){
        interruptRecording();stopReplay();
        if(active==this)active=null;
        super.onDestroy();
    }
}
