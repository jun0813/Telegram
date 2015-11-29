package org.telegramsecureplus.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by jun0813 on 2015. 11. 22..
 */
public class DeleteMessageBroadcastReceiver extends BroadcastReceiver{
    private static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            DeleteMessageController.getInstance().scheduleDeleteMessageRepeat(context);
        }

        Intent service = new Intent(context, DeleteMessageRepeat.class);
        context.startService(service);
    }
}
