package ai.bonne.screenagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RecordingCancelReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent!=null && "ai.bonne.screenagent.CANCEL_RECORDING".equals(intent.getAction())) {
            ReplayService service=ReplayService.active;
            if(service!=null) service.cancelRecording();
        }
    }
}
