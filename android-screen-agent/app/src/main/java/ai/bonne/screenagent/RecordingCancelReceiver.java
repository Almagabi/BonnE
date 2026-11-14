package ai.bonne.screenagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RecordingCancelReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        ReplayService service=ReplayService.active;
        if(service==null)return;
        String action=intent.getAction();
        if("ai.bonne.screenagent.CANCEL_RECORDING".equals(action))service.cancelRecording();
        if("ai.bonne.screenagent.STOP_REPLAY".equals(action))service.stopReplay();
    }
}
