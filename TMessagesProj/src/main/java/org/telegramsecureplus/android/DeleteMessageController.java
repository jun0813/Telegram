package org.telegramsecureplus.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import org.telegramsecureplus.messenger.ApplicationLoader;

import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * Created by jun0813 on 2015. 11. 22..
 */
public class DeleteMessageController {

    private static volatile DeleteMessageController Instance = null;
    public static DeleteMessageController getInstance() {
        DeleteMessageController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new DeleteMessageController();
                }
            }
        }
        return localInstance;
    }

    public void scheduleDeleteMessageRepeat(Context context) {

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(context, DeleteMessageBroadcastReceiver.class);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Activity.MODE_PRIVATE);
        int minutes = preferences.getInt("repeat_delete_time", 0);
        if (minutes > 0) {
            alarmManager.cancel(deleteIntent);

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.add(Calendar.MINUTE, minutes);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
            Log.d("DeleteMessageController", "minutes = " + minutes + " delete time=" + sdf.format(calendar.getTime()));

            //preferences.edit().putLong("repeat_start_time", calendar.getTimeInMillis()).commit();

            enableAlarmOnBootComplete(context);
            alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), minutes * 60 * 1000, deleteIntent);
        }
        else {
            disableAlarmBootComplete(context);
            alarmManager.cancel(deleteIntent);
        }
    }

    public void enableAlarmOnBootComplete(Context context) {
        ComponentName receiver = new ComponentName(context, DeleteMessageBroadcastReceiver.class);
        PackageManager pm = context.getPackageManager();

        pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    public void disableAlarmBootComplete(Context context) {
        ComponentName receiver = new ComponentName(context, DeleteMessageBroadcastReceiver.class);
        PackageManager pm = context.getPackageManager();

        pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
