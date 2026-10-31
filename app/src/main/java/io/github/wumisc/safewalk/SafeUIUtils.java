package io.github.wumisc.safewalk;

import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Build;
import android.widget.Toast;

public class SafeUIUtils {
    private static final String CHANNEL_ID = "safewalk_remind";

    public static void sendNotify(Context ctx, String title, String msg){
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "SafeWalk提醒", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder builder;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            builder = new Notification.Builder(ctx, CHANNEL_ID);
        }else{
            builder = new Notification.Builder(ctx);
        }
        builder.setContentTitle(title).setContentText(msg).setSmallIcon(android.R.drawable.ic_menu_info_details);
        nm.notify(1001, builder.build());
    }

    public static void showToast(Context ctx, String text){
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
