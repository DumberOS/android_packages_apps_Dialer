package com.android.dialer.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.test.InstrumentationTestCase;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.dumber.CallAudioService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the real Dialer processes and foreground notification, without placing or recording calls. */
public final class NotificationChannelTest extends InstrumentationTestCase {
  public void testStartupPreservesEncryptedCallForegroundChannel() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    assertFalse("Do not run during a real call",
        context.getSystemService(TelecomManager.class).isInCall());
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    NotificationChannel original = manager.getNotificationChannel(NotificationChannelId.ENCRYPTED_CALL);
    assertNotNull("Application startup must create the encrypted-call channel", original);

    Intent audio = new Intent(context, CallAudioService.class);
    String obsolete = "notification_channel_regression_obsolete";
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<IBinder> recorder = new AtomicReference<>();
    ServiceConnection connection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        recorder.set(binder);
        ready.countDown();
      }
      @Override public void onServiceDisconnected(ComponentName name) {}
    };
    boolean bound = false;
    try {
      context.startForegroundService(audio);
      long deadline = SystemClock.elapsedRealtime() + 5000;
      while (!hasCallNotification(manager) && SystemClock.elapsedRealtime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue("The actual encrypted-call foreground notification must be active",
          hasCallNotification(manager));

      // Force reconciliation instead of the unchanged-channel fast path.
      manager.createNotificationChannel(new NotificationChannel(obsolete, "Obsolete test channel",
          NotificationManager.IMPORTANCE_LOW));
      NotificationChannelManager.initChannels(context);
      assertNull("Obsolete channels should still be removed", manager.getNotificationChannel(obsolete));
      NotificationChannel kept = manager.getNotificationChannel(NotificationChannelId.ENCRYPTED_CALL);
      assertNotNull(kept);
      assertEquals("Preserve the channel's existing importance", original.getImportance(), kept.getImportance());
      assertTrue(hasCallNotification(manager));

      // This service starts com.android.incallui, whose Application also initializes channels.
      // Binding alone does not start a recorder or access microphone audio.
      bound = context.bindService(new Intent(context, CallRecorderService.class), connection,
          Context.BIND_AUTO_CREATE);
      assertTrue(bound);
      assertTrue("The secondary process must start without crashing", ready.await(10, TimeUnit.SECONDS));
      assertFalse(ICallRecorderService.Stub.asInterface(recorder.get()).isRecording());
      assertTrue(recorder.get().isBinderAlive());
      assertTrue(hasCallNotification(manager));
      assertNotNull(manager.getNotificationChannel(NotificationChannelId.ENCRYPTED_CALL));
    } finally {
      if (bound) context.unbindService(connection);
      context.stopService(audio);
      manager.deleteNotificationChannel(obsolete);
    }
  }

  private boolean hasCallNotification(NotificationManager manager) {
    for (StatusBarNotification notification : manager.getActiveNotifications()) {
      if (NotificationChannelId.ENCRYPTED_CALL.equals(notification.getNotification().getChannelId())
          && (notification.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
        return true;
      }
    }
    return false;
  }
}
