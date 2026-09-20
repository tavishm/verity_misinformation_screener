package org.fairc.forwardcheck;
import android.app.*;
import android.content.*;
import android.os.IBinder;
/** Keeps an already approved check alive when the user returns to WhatsApp. */
public final class ResearchService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("checking", "Message checks", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 7, new Intent(this, MainActivity.class).putExtra("open_latest", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(7, new Notification.Builder(this, "checking").setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Checking your message…").setContentText("Tap to see the result.").setContentIntent(open).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
