package com.android.dialer.dumber;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import com.android.dialer.notification.NotificationChannelId;
import com.android.incallui.InCallActivity;

public final class CallAudioService extends Service {
  public void onCreate() {
    super.onCreate();
    Notification n =
        new Notification.Builder(this, NotificationChannelId.ENCRYPTED_CALL)
            .setContentTitle(getString(R.string.encrypted_call_connection_label))
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(PendingIntent.getActivity(
                this,
                8093,
                InCallActivity.getIntent(this, false, false, false),
                PendingIntent.FLAG_IMMUTABLE))
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
