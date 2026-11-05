package com.android.dialer.dumber;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class CallAudioService extends Service {
  public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            "dumber_calls", "Encrypted calls", NotificationManager.IMPORTANCE_LOW));
    Notification n =
        new Notification.Builder(this, "dumber_calls")
            .setContentTitle("Encrypted call")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build();
    startForeground(
        8093,
        n,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
  }

  public IBinder onBind(Intent i) {
    return null;
  }
}
