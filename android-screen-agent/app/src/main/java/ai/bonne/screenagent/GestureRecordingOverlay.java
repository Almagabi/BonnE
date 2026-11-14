package ai.bonne.screenagent;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;

/**
 * Transparent Accessibility overlay which observes raw finger touches
 * and forwards the SAME gesture to the underlying application.
 * This avoids losing the "open profile" tap when navigation destroys
 * the tapped view before Android delivers TYPE_VIEW_CLICKED.
 */
public final class GestureRecordingOverlay {
    private final ReplayService service;
    private final WindowManager wm;
    private View surface;
    private boolean showing=false;
    private final ArrayList<PointF> points=new ArrayList<>();
    private long started=0;

    public GestureRecordingOverlay(ReplayService s) {
        service=s;
        wm=(WindowManager)s.getSystemService(android.content.Context.WINDOW_SERVICE);
        surface=new View(s);
        surface.setBackgroundColor(Color.TRANSPARENT);
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surface.setOnTouchListener((v,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                started=SystemClock.uptimeMillis();
                points.clear();
                points.add(new PointF(event.getRawX(),event.getRawY()));
                return true;
            }
            if(action==MotionEvent.ACTION_MOVE){
                if(points.size()<65)points.add(new PointF(event.getRawX(),event.getRawY()));
                return true;
            }
            if(action==MotionEvent.ACTION_UP){
                points.add(new PointF(event.getRawX(),event.getRawY()));
                ArrayList<PointF> track=new ArrayList<>(points);
                long duration=Math.max(70,Math.min(2000,SystemClock.uptimeMillis()-started));
                points.clear();
                // Service removes overlay, injects the gesture, then restores it
                // only if another target app is still active.
                service.onCapturedTouch(track,duration);
                return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){
                points.clear();return true;
            }
            return true;
        });
    }
    public void show(){
        if(showing || !service.isRecording())return;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
        lp.setTitle("BonnE gesture capture");
        try{wm.addView(surface,lp);showing=true;}
        catch(Exception e){android.util.Log.w("BonnE","Gesture overlay unavailable",e);}
    }
    public void hide(){
        if(!showing)return;
        showing=false;
        try{wm.removeViewImmediate(surface);}catch(Exception ignored){}
    }
    public void destroy(){hide();}
}
