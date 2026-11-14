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
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import android.text.TextUtils;

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
    private boolean smartMode=false;
    private JSONArray profileTemplate;
    private JSONObject profileExample;
    private final Set<String> processedProfiles=new HashSet<>();
    private int profileCount=0, noNewPages=0;
    private String smartListPackage="";
    private String lastProfileKey="";
    private final Map<String,JSONObject> visibleClickTargets=new HashMap<>();
    private GestureRecordingOverlay gestureOverlay;
    private boolean forwardingTouch=false;
    private long lastForwardedTouchAt=0;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active=this;
        gestureOverlay=new GestureRecordingOverlay(this);
        AccessibilityServiceInfo info=getServiceInfo();
        info.eventTypes=AccessibilityEvent.TYPE_VIEW_CLICKED|AccessibilityEvent.TYPE_VIEW_LONG_CLICKED|
                AccessibilityEvent.TYPE_VIEW_SCROLLED|AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED|
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED|
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout=60;
        setServiceInfo(info);
    }

    public boolean isRecording(){return recording;}
    public boolean isReplaying(){return replaying;}
    public int count(){return recorded.length();}
    public int executed(){return executed;}
    public int profilesProcessed(){return profileCount;}
    public boolean isSmartLoop(){return replaying && smartMode;}
    public String lastResult(){return lastResult;}

    public void startRecording() {
        stopReplay();
        recorded=new JSONArray();
        firstPackage="";
        latestText=null;
        visibleClickTargets.clear();
        lastTime=SystemClock.uptimeMillis();
        recording=true;
        forwardingTouch=false;
        WorkflowStore.saveDraft(this,recorded);
        showNotification(true);
        lastResult="Recording";
    }
    public JSONArray stopRecording(){
        if(!recording)return new JSONArray();
        flushText();
        recording=false;
        if(gestureOverlay!=null)gestureOverlay.hide();
        removeNotification();
        WorkflowStore.saveDraft(this,recorded);
        lastResult="Captured "+recorded.length()+" steps";
        return recorded;
    }
    public void cancelRecording(){
        recording=false;
        if(gestureOverlay!=null)gestureOverlay.hide();
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
        if(gestureOverlay!=null)gestureOverlay.hide();
        removeNotification();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!recording || replaying || event==null || recorded.length()>=200)return;
        CharSequence srcPkg=event.getPackageName();
        if(srcPkg==null)return;
        String pkg=srcPkg.toString();
        if(pkg.equals(getPackageName())){
            if(gestureOverlay!=null)gestureOverlay.hide();
            return;
        }
        if(pkg.startsWith("com.android.systemui"))return;
        int kind=event.getEventType();
        if(pkg.contains("inputmethod") || pkg.contains("latininput")){
            if(gestureOverlay!=null)gestureOverlay.hide();
            return;
        }
        if(gestureOverlay!=null && !forwardingTouch)gestureOverlay.show();
        if((kind==AccessibilityEvent.TYPE_VIEW_CLICKED ||
            kind==AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            kind==AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) &&
            SystemClock.uptimeMillis()-lastForwardedTouchAt<1000) return;
        // Ignore launcher gestures; capture the target's first displayed window instead.
        if(firstPackage.isEmpty() && kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
           !isLauncher(pkg)) {
            firstPackage=pkg;
            JSONObject launch=new JSONObject();
            try{launch.put("type","launch").put("package",pkg);}catch(Exception ignored){}
            append(launch);
            snapshotClickTargets(pkg);
            return;
        }
        if(kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
           kind==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            snapshotClickTargets(pkg);
            return;
        }
        if(firstPackage.isEmpty() && isLauncher(pkg))return;
        if(firstPackage.isEmpty()){
            firstPackage=pkg;
            JSONObject launch=new JSONObject();
            try{launch.put("type","launch").put("package",pkg);}catch(Exception ignored){}
            append(launch);
        }
        AccessibilityNodeInfo node=event.getSource();
        if(node==null) {
            if(kind==AccessibilityEvent.TYPE_VIEW_CLICKED)recoverClickWithoutSource(event,pkg);
            return;
        }
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
        }catch(Exception ignored) {
            if(kind==AccessibilityEvent.TYPE_VIEW_CLICKED)recoverClickWithoutSource(event,pkg);
        }
        finally{node.recycle();}
    }

    // Buttons may be destroyed by navigation before AccessibilityEvent.getSource()
    // can resolve them. Store their accessible label and bounds BEFORE navigation.
    private void snapshotClickTargets(String pkg){
        if(!recording || visibleClickTargets.size()>180)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        CharSequence activePkg=root.getPackageName();
        if(activePkg==null || !pkg.equals(activePkg.toString()))return;
        snapshotNodes(root,pkg,0);
    }
    private void snapshotNodes(AccessibilityNodeInfo node,String pkg,int depth){
        if(node==null || depth>18 || visibleClickTargets.size()>180)return;
        if(node.isClickable() && !node.isPassword()){
            try{
                JSONObject a=base(node,pkg);
                String key=sampleIdentity(a);
                if(!key.isEmpty())visibleClickTargets.put(key,a);
            }catch(Exception ignored){}
        }
        for(int i=0;i<node.getChildCount();i++)
            snapshotNodes(node.getChild(i),pkg,depth+1);
    }
    private void recoverClickWithoutSource(AccessibilityEvent event,String pkg){
        for(CharSequence text:event.getText()){
            if(text==null)continue;
            String key=text.toString().trim().toLowerCase(java.util.Locale.ROOT);
            JSONObject before=visibleClickTargets.get(key);
            if(before!=null && pkg.equals(before.optString("package"))){
                try{
                    JSONObject step=new JSONObject(before.toString());
                    step.put("type","tap");
                    flushText();
                    append(step);
                    android.util.Log.i("BonnE","Recovered navigation tap "+key);
                }catch(Exception ignored){}
                return;
            }
        }
        android.util.Log.w("BonnE","Unresolved click without source in "+pkg);
    }

    // Finger coordinates are captured before the view is destroyed by
    // navigation, then forwarded to the actual app using dispatchGesture.
    public void onCapturedTouch(ArrayList<PointF> track,long duration) {
        if(track==null || track.isEmpty() || gestureOverlay==null)return;
        PointF first=track.get(0), last=track.get(track.size()-1);
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root==null?"":String.valueOf(root.getPackageName());
        boolean target=!pkg.isEmpty() && !pkg.equals(getPackageName()) &&
            !pkg.startsWith("com.android.systemui") && !isLauncher(pkg);
        if(recording && target){
            try{
                int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
                int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
                AccessibilityNodeInfo touchNode=nearest(root,(int)first.x,(int)first.y,0);
                JSONObject step=touchNode==null?new JSONObject():base(touchNode,pkg);
                step.put("package",pkg);
                step.put("x",Math.max(0,Math.min(1000,(int)(first.x*1000/w))));
                step.put("y",Math.max(0,Math.min(1000,(int)(first.y*1000/h))));
                if(touchNode!=null)touchNode.recycle();
                float dist=(float)Math.hypot(last.x-first.x,last.y-first.y);
                if(dist>35) {
                    step.put("type","swipe");
                    step.put("x2",Math.max(0,Math.min(1000,(int)(last.x*1000/w))));
                    step.put("y2",Math.max(0,Math.min(1000,(int)(last.y*1000/h))));
                    step.put("duration_ms",duration);
                }else step.put("type",duration>600?"long":"tap");
                flushText();
                if(firstPackage.isEmpty()){
                    firstPackage=pkg;
                    JSONObject launch=new JSONObject();
                    launch.put("type","launch");launch.put("package",pkg);
                    append(launch);
                }
                append(step);
                android.util.Log.i("BonnE","CAPTURED TOUCH "+step.optString("type")+" "+step.optString("text"));
            }catch(Exception e){android.util.Log.w("BonnE","Couldn't record gesture",e);}
        }
        if(root!=null)root.recycle();
        gestureOverlay.hide();
        forwardingTouch=true;
        lastForwardedTouchAt=SystemClock.uptimeMillis();
        Path path=new Path();
        path.moveTo(first.x,first.y);
        for(int i=1;i<track.size();i++)path.lineTo(track.get(i).x,track.get(i).y);
        long ms=Math.max(70,Math.min(1800,duration));
        GestureDescription gesture=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path,0,ms)).build();
        // Inject AFTER the finger-up event has fully returned to Android's
        // input dispatcher. Injecting synchronously inside onTouch often
        // cancels or drops the forwarded tap.
        handler.postDelayed(()->{
            try{
                boolean accepted=dispatchGesture(gesture,new GestureResultCallback(){
                    @Override public void onCompleted(GestureDescription d){
                        android.util.Log.i("BonnE","TOUCH FORWARDED completed");
                        restoreOverlay();
                    }
                    @Override public void onCancelled(GestureDescription d){
                        android.util.Log.w("BonnE","TOUCH FORWARDED cancelled");
                        restoreOverlay();
                    }
                },handler);
                android.util.Log.i("BonnE","TOUCH dispatch accepted="+accepted);
                if(!accepted)restoreOverlay();
            }catch(Exception e){
                android.util.Log.w("BonnE","TOUCH forwarding failed",e);
                restoreOverlay();
            }
        },85);
    }
    private void restoreOverlay(){
        forwardingTouch=false;
        handler.postDelayed(()->{
            if(!recording || gestureOverlay==null)return;
            AccessibilityNodeInfo root=getRootInActiveWindow();
            String pkg=root==null?"":String.valueOf(root.getPackageName());
            if(!pkg.equals(getPackageName()) &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.contains("inputmethod"))gestureOverlay.show();
        },120);
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
        a.put("class_name",String.valueOf(node.getClassName()));
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
        smartMode=false;
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
        smartMode=false;
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

    // Teach a profile once, then choose DISTINCT results from the live list.
    // Record starting on the results page: first tap opens a profile; later taps
    // are the profile actions. Return to the results page, then Stop & Save.
    // This mode intentionally NEVER loops over the first tap's coordinates.
    public boolean startSmartProfiles(JSONArray taught) {
        if(taught==null || taught.length()<2 || recording)return false;
        int opener=-1;
        for(int i=0;i<taught.length();i++){
            JSONObject a=taught.optJSONObject(i);
            if(a!=null && a.optString("type").equals("tap")){
                opener=i;break;
            }
        }
        if(opener<0 || opener>=taught.length()-1)return false;
        JSONObject opening=taught.optJSONObject(opener);
        JSONArray actions=new JSONArray();
        for(int i=opener+1;i<taught.length();i++){
            JSONObject action=taught.optJSONObject(i);
            if(action==null)continue;
            String kind=action.optString("type");
            if(kind.equals("launch"))continue;
            // Return to list is done by the agent: avoid duplicating it.
            if(kind.equals("back") || kind.equals("home"))continue;
            actions.put(action);
        }
        if(actions.length()==0)return false;
        stopReplay();
        profileExample=opening;
        profileTemplate=actions;
        smartListPackage=opening.optString("package");
        processedProfiles.clear();
        String firstKey=sampleIdentity(opening);
        if(!firstKey.isEmpty())processedProfiles.add(firstKey);
        noNewPages=0;profileCount=0;executed=0;
        smartMode=true;replaying=true;
        int id=runId;
        lastResult="Smart loop starting. Return to the results screen.";
        showNotification(false);
        // Five seconds to switch back to the search-results application.
        handler.postDelayed(()->searchNextProfile(id),5500);
        return true;
    }

    private String sampleIdentity(JSONObject a){
        String s=a.optString("text","").trim();
        if(s.isEmpty())s=a.optString("label","").trim();
        return s.isEmpty()?"":s.toLowerCase(java.util.Locale.ROOT);
    }
    private boolean canContinue(int id){return id==runId && replaying && smartMode;}

    private static class Candidate{
        final AccessibilityNodeInfo node;
        final String key;
        final int x,y;
        Candidate(AccessibilityNodeInfo n,String k,int xx,int yy){
            node=n;key=k;x=xx;y=yy;
        }
    }
    private String uniqueText(AccessibilityNodeInfo node,int depth){
        if(node==null || depth>3)return "";
        CharSequence t=node.getText(), d=node.getContentDescription();
        if(t!=null && !t.toString().trim().isEmpty() && !node.isPassword())
            return t.toString().trim();
        if(d!=null && !d.toString().trim().isEmpty())
            return d.toString().trim();
        for(int i=0;i<Math.min(node.getChildCount(),5);i++){
            String result=uniqueText(node.getChild(i),depth+1);
            if(!result.isEmpty())return result;
        }
        return "";
    }
    private void collectCandidates(AccessibilityNodeInfo node,JSONObject example,
                                   List<Candidate> output,int depth) {
        if(node==null || depth>22 || output.size()>160)return;
        Rect b=new Rect();
        node.getBoundsInScreen(b);
        int w=Math.max(1,getResources().getDisplayMetrics().widthPixels),
            h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
        String wantedId=example.optString("view_id","");
        String wantedClass=example.optString("class_name","");
        String nodeId=node.getViewIdResourceName();
        String nodeClass=String.valueOf(node.getClassName());
        int expectedX=example.optInt("x",500)*w/1000;
        boolean idMatch=!wantedId.isEmpty() && wantedId.equals(nodeId);
        boolean classMatch=!wantedClass.isEmpty() && wantedClass.equals(nodeClass) &&
            node.isClickable() && Math.abs(b.centerX()-expectedX)<w*0.28f;
        if(b.width()>15 && b.height()>15 && (idMatch || classMatch)) {
            String identity=uniqueText(node,0).toLowerCase(java.util.Locale.ROOT);
            if(!identity.isEmpty() && !identity.equals(getPackageName()) &&
                    !identity.equals("back") && !identity.equals("search")) {
                output.add(new Candidate(node,identity,b.centerX(),b.centerY()));
            }
        }
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);
            collectCandidates(child,example,output,depth+1);
        }
    }
    private AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo node,int depth){
        if(node==null || depth>22)return null;
        if(node.isScrollable())return node;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo result=firstScrollable(node.getChild(i),depth+1);
            if(result!=null)return result;
        }
        return null;
    }
    private void searchNextProfile(int id){
        if(!canContinue(id))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){
            finishSmart("Cannot read result page. Reopen the results and retry.");
            return;
        }
        CharSequence currentPkg=root.getPackageName();
        if(currentPkg==null || !smartListPackage.equals(currentPkg.toString())){
            finishSmart("Not on the recorded search results app. Stopped safely.");
            return;
        }
        List<Candidate> candidates=new ArrayList<>();
        collectCandidates(root,profileExample,candidates,0);
        // Candidates are in reading order; skip the taught profile and anything processed.
        java.util.Collections.sort(candidates,(a,b)->{
            int y=Integer.compare(a.y,b.y);
            return y!=0?y:Integer.compare(a.x,b.x);
        });
        // A lone generic Button on a detail screen is NOT a list of profiles.
        // Do not mistake the profile's action button for the next profile.
        java.util.HashSet<String> available=new java.util.HashSet<>();
        for(Candidate c:candidates)available.add(c.key);
        if(available.size()<2) {
            finishSmart("Results list not visible or profiles not distinguishable. Stopped safely.");
            return;
        }
        Candidate selected=null;
        for(Candidate c:candidates){
            if(!processedProfiles.contains(c.key)){
                selected=c;break;
            }
        }
        if(selected==null){
            AccessibilityNodeInfo list=firstScrollable(root,0);
            boolean scrolled=list!=null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if(scrolled && noNewPages<4){
                noNewPages++;
                lastResult="Checking next results...";
                handler.postDelayed(()->searchNextProfile(id),1100);
            }else finishSmart("No more distinct accessible profiles. "+profileCount+" processed.");
            return;
        }
        noNewPages=0;
        Candidate target=selected;
        processedProfiles.add(target.key);
        lastProfileKey=target.key;
        boolean clicked=clickAncestor(target.node,AccessibilityNodeInfo.ACTION_CLICK);
        if(!clicked){
            int w=Math.max(1,getResources().getDisplayMetrics().widthPixels);
            int h=Math.max(1,getResources().getDisplayMetrics().heightPixels);
            JSONObject point=new JSONObject();
            try{
                point.put("x",target.x*1000/w);
                point.put("y",target.y*1000/h);
                tap(point,75);
                clicked=true;
            }catch(Exception ignored){}
        }
        if(!clicked){
            finishSmart("Couldn't open next profile. Stopped.");
            return;
        }
        executed++;
        lastResult="Opened "+target.key+" ("+(profileCount+1)+")";
        showNotification(false);
        handler.postDelayed(()->executeProfileSteps(0,id),1400);
    }
    private void executeProfileSteps(int index,int id){
        if(!canContinue(id))return;
        if(index>=profileTemplate.length()){
            // Return to the results screen after the taught action sequence.
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(()->{
                if(!canContinue(id))return;
                profileCount++;
                lastResult="Processed "+profileCount+" profiles";
                showNotification(false);
                searchNextProfile(id);
            },1450);
            return;
        }
        JSONObject action=profileTemplate.optJSONObject(index);
        if(action==null){executeProfileSteps(index+1,id);return;}
        // Stop rather than accidentally acting on the search page.
        String type=action.optString("type","");
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){finishSmart("Lost target screen; stopped.");return;}
        CharSequence pkg=root.getPackageName();
        if(pkg==null || !smartListPackage.equals(pkg.toString())){
            finishSmart("Switched away from target app; stopped.");
            return;
        }
        // The opener must actually navigate away from the results list.
        // No blind actions when results have not changed.
        long wait=Math.max(200,Math.min(3500,action.optLong("delay_ms",600)));
        handler.postDelayed(()->{
            if(!canContinue(id))return;
            execute(action);
            executed++;
            handler.postDelayed(()->executeProfileSteps(index+1,id),550);
        },wait);
    }
    private void finishSmart(String message) {
        smartMode=false;
        replaying=false;
        runId++;
        handler.removeCallbacksAndMessages(null);
        removeNotification();
        lastResult=message;
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
            if(type.equals("swipe")) {
                int w=getResources().getDisplayMetrics().widthPixels,
                    h=getResources().getDisplayMetrics().heightPixels;
                Path path=new Path();
                path.moveTo(a.optInt("x",500)*w/1000f,a.optInt("y",700)*h/1000f);
                path.lineTo(a.optInt("x2",500)*w/1000f,a.optInt("y2",300)*h/1000f);
                dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path,0,
                        Math.max(100,Math.min(1800,a.optLong("duration_ms",400))))).build(),null,null);
            }
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
            .setContentTitle(isRecording?"● BonnE is recording":(smartMode?"↻ BonnE next-profile loop":"▶ BonnE is replaying"))
            .setContentText(isRecording?"Tap to return and save or discard.":(smartMode?"Profiles: "+profileCount+" • "+lastProfileKey:("Executed "+executed+" actions.")))
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
        if(gestureOverlay!=null)gestureOverlay.destroy();
        if(active==this)active=null;
        super.onDestroy();
    }
}
