package ai.bonne.screenagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

public class ReplayService extends AccessibilityService {
    static ReplayService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int token = 0;
    @Override public void onServiceConnected() { super.onServiceConnected(); active = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { stop(); }
    @Override public void onDestroy() { stop(); if(active == this) active = null; super.onDestroy(); }
    public void stop() { token++; handler.removeCallbacksAndMessages(null); }

    public void play(JSONArray actions) {
        stop();
        final int run = token;
        next(actions, 0, run);
    }
    private void next(JSONArray actions, int i, int run) {
        if (run != token || i >= actions.length()) return;
        JSONObject a = actions.optJSONObject(i);
        if (a == null) { next(actions, i + 1, run); return; }
        String type = a.optString("type", "wait").toLowerCase();
        long delay = Math.max(100, Math.min(10000, a.optLong("delay_ms", 650)));
        if (type.equals("tap") || type.equals("swipe")) {
            float w = getResources().getDisplayMetrics().widthPixels;
            float h = getResources().getDisplayMetrics().heightPixels;
            Path path = new Path();
            path.moveTo(coord(a, "x", w), coord(a, "y", h));
            long duration = 70;
            if (type.equals("swipe")) {
                path.lineTo(coord(a, "x2", w), coord(a, "y2", h));
                duration = Math.max(100, Math.min(5000, a.optLong("duration_ms", 380)));
            }
            GestureDescription gesture = new GestureDescription.Builder()
              .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            dispatchGesture(gesture, null, null);
        } else if (type.equals("back")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else if (type.equals("home")) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if (type.equals("text")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("value", ""));
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
        }
        handler.postDelayed(() -> next(actions, i + 1, run), type.equals("wait") ? delay : delay + 200);
    }
    private float coord(JSONObject a, String k, float scale) {
        return Math.max(0, Math.min(scale - 1, (float)a.optDouble(k, 500) * scale / 1000f));
    }
}
