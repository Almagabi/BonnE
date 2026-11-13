package ai.bonne.screenagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class ReplayService extends AccessibilityService {
    static ReplayService active;
    private static final String CHANNEL_ID = "bonne_recording";
    private static final int NOTIFICATION_ID = 1207;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray recorded = new JSONArray();
    private boolean recording = false, replaying = false;
    private long lastTime = 0;
    private String firstPackage = "";
    private int runId = 0;
    private Runnable pendingText;
    private JSONObject lastText;
    private long lastTextEvent = 0;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active=this;
        android.util.Log.i("BonnE", "ReplayService CONNECTED pid="+android.os.Process.myPid()+" active="+System.identityHashCode(active));
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED |
            AccessibilityEvent.TYPE_VIEW_SCROLLED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 30;
        setServiceInfo(info);
    }

    public void startRecording() {
        stopReplay();
        recorded = new JSONArray();
        firstPackage = "";
        lastTime = android.os.SystemClock.uptimeMillis();
        recording=true;
        lastText=null;
        showRecordingNotification();
    }

    public void cancelRecording() {
        recording=false;
        clearRecordingNotification();
        if(pendingText!=null) handler.removeCallbacks(pendingText);
        pendingText=null;
        lastText=null;
        recorded=new JSONArray();
        firstPackage="";
    }

    private void showRecordingNotification() {
        NotificationManager manager=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL_ID,"BonnE recording",NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Indicates an active BonnE screen action recording");
            manager.createNotificationChannel(channel);
        }
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel=new Intent(this,RecordingCancelReceiver.class);
        cancel.setAction("ai.bonne.screenagent.CANCEL_RECORDING");
        PendingIntent cancelPi=PendingIntent.getBroadcast(this,2,cancel,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        Notification notice=builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("● BonnE is recording")
            .setContentText("Recording screen actions. Tap to return to BonnE.")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancel & discard",cancelPi)
            .build();
        try {manager.notify(NOTIFICATION_ID,notice);}catch(SecurityException ignored){}
    }
    private void clearRecordingNotification() {
        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
    }

    public JSONArray stopRecording() {
        recording=false;
        clearRecordingNotification();
        if(pendingText!=null) handler.removeCallbacks(pendingText);
        pendingText=null;
        if(lastText!=null) {append(lastText, true);lastText=null;}
        return recorded;
    }

    public boolean isRecording() {return recording;}
    public boolean isReplaying() {return replaying;}
    public int count() {return recorded.length();}

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(!recording || replaying || event==null || recorded.length() >= 150) return;
        CharSequence pkg=event.getPackageName();
        if(pkg==null) return;
        String packageName=pkg.toString();
        if(packageName.equals(getPackageName()) || packageName.startsWith("com.android.systemui")) return;
        if(firstPackage.isEmpty()) {
            firstPackage=packageName;
            try { recorded.put(new JSONObject().put("type","launch").put("package",packageName).put("delay_ms",2000)); }
            catch(Exception ignored){}
            lastTime=android.os.SystemClock.uptimeMillis();
        }
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        AccessibilityNodeInfo src=event.getSource();
        if(src==null) return;
        try {
            if(type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                if(src.isPassword() || !src.isEditable()) return;
                CharSequence text=src.getText();
                if(text==null || text.length()>500) return;
                JSONObject a=base(src,packageName).put("type","text").put("value",text.toString());
                if(pendingText!=null) handler.removeCallbacks(pendingText);
                lastText=a;
                lastTextEvent=android.os.SystemClock.uptimeMillis();
                pendingText=()->{if(recording && lastText!=null){append(lastText,false);lastText=null;}};
                handler.postDelayed(pendingText,750);
                return;
            }
            JSONObject a=base(src,packageName);
            if(type==AccessibilityEvent.TYPE_VIEW_CLICKED) a.put("type","tap");
            else if(type==AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) a.put("type","long");
            else if(type==AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                a.put("type","scroll");
                int dy=event.getScrollDeltaY();
                a.put("direction",dy<0?"backward":"forward");
            } else return;
            append(a,false);
        } catch(Exception ignored) {} finally {src.recycle();}
    }
    private JSONObject base(AccessibilityNodeInfo src,String pkg) throws Exception {
        Rect r=new Rect();src.getBoundsInScreen(r);
        int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
        int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        JSONObject a=new JSONObject();
        a.put("package",pkg);
        a.put("x",Math.max(0,Math.min(1000,r.centerX()*1000/w)));
        a.put("y",Math.max(0,Math.min(1000,r.centerY()*1000/h)));
        if(src.getViewIdResourceName()!=null) a.put("view_id",src.getViewIdResourceName());
        CharSequence desc=src.getContentDescription();
        if(desc!=null && desc.length()>0 && desc.length()<120) a.put("label",desc.toString());
        return a;
    }
    private void append(JSONObject a, boolean force) {
        if(!recording && !force) return;
        if(recorded.length()>=150)return;
        long now=android.os.SystemClock.uptimeMillis();
        try {
            a.put("delay_ms",Math.min(10000,Math.max(150,now-lastTime)));
            recorded.put(a);lastTime=now;
        } catch(Exception ignored){}
    }

    public void stopReplay() {runId++;replaying=false;handler.removeCallbacksAndMessages(null);}
    public void play(JSONArray actions) {
        if(recording) cancelRecording();
        stopReplay();
        replaying=true;
        int id=runId;
        next(actions,0,id);
    }
    private void next(JSONArray actions,int index,int id) {
        if(id!=runId || !replaying) return;
        if(index>=actions.length()) {replaying=false;return;}
        JSONObject a=actions.optJSONObject(index);
        if(a==null){next(actions,index+1,id);return;}
        long delay=Math.max(200,Math.min(12000,a.optLong("delay_ms",750)));
        handler.postDelayed(()->{
            if(id!=runId || !replaying)return;
            execute(a);
            handler.postDelayed(()->next(actions,index+1,id),300);
        },delay);
    }
    private void execute(JSONObject a) {
        String type=a.optString("type","");
        String pkg=a.optString("package","");
        if(type.equals("launch")) {
            Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
            if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}
            return;
        }
        if(type.equals("back")) {performGlobalAction(GLOBAL_ACTION_BACK);return;}
        if(type.equals("home")) {performGlobalAction(GLOBAL_ACTION_HOME);return;}
        if(type.equals("wait"))return;
        AccessibilityNodeInfo node=findNode(a);
        boolean success=false;
        if(node!=null) {
            if(type.equals("tap")) success=clickAncestor(node,AccessibilityNodeInfo.ACTION_CLICK);
            if(type.equals("long")) success=clickAncestor(node,AccessibilityNodeInfo.ACTION_LONG_CLICK);
            if(type.equals("scroll")) success=clickAncestor(node,
                a.optString("direction","forward").equals("backward")?
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if(type.equals("text") && node.isEditable() && !node.isPassword()) {
                Bundle args=new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.optString("value",""));
                success=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            }
            node.recycle();
        }
        if(!success && (type.equals("tap") || type.equals("long"))) tap(a,type.equals("long")?900:70);
        if(!success && type.equals("scroll")) swipe(a);
    }
    private AccessibilityNodeInfo findNode(JSONObject a) {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return null;
        String viewId=a.optString("view_id","");
        if(!viewId.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByViewId(viewId);
                if(!matches.isEmpty())return matches.get(0);
            }catch(Exception ignored){}
        }
        int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
        int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        return nearest(root,a.optInt("x",500)*w/1000,a.optInt("y",500)*h/1000,0);
    }
    private AccessibilityNodeInfo nearest(AccessibilityNodeInfo node,int x,int y,int depth) {
        if(node==null||depth>25)return null;
        Rect r=new Rect();node.getBoundsInScreen(r);
        if(!r.contains(x,y))return null;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            AccessibilityNodeInfo found=nearest(child,x,y,depth+1);
            if(found!=null)return found;
        }
        return node;
    }
    private boolean clickAncestor(AccessibilityNodeInfo node,int action) {
        AccessibilityNodeInfo current=node;
        for(int i=0;current!=null&&i<12;i++) {
            if(current.performAction(action))return true;
            current=current.getParent();
        }
        return false;
    }
    private void tap(JSONObject a,long duration) {
        int w=getResources().getDisplayMetrics().widthPixels, h=getResources().getDisplayMetrics().heightPixels;
        Path p=new Path();p.moveTo(a.optInt("x",500)*w/1000f,a.optInt("y",500)*h/1000f);
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,duration)).build(),null,null);
    }
    private void swipe(JSONObject a) {
        int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
        float x=a.optInt("x",500)*w/1000f;
        float y=a.optInt("y",500)*h/1000f;
        Path p=new Path();
        if(a.optString("direction","forward").equals("backward")){p.moveTo(x,y-h*.19f);p.lineTo(x,y+h*.19f);}
        else {p.moveTo(x,y+h*.19f);p.lineTo(x,y-h*.19f);}
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,420)).build(),null,null);
    }
    @Override public void onInterrupt(){cancelRecording();stopReplay();}
    @Override public void onDestroy(){android.util.Log.i("BonnE","ReplayService DESTROY pid="+android.os.Process.myPid());cancelRecording();stopReplay();if(active==this)active=null;super.onDestroy();}
}
