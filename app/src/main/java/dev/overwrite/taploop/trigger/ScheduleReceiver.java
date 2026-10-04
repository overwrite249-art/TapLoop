package dev.overwrite.taploop.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ScheduleReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.overwrite.taploop.SCHEDULED_RUN";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION.equals(intent.getAction())) Schedule.fire(c);
    }
}
