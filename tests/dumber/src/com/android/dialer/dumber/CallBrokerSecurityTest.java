package com.android.dialer.dumber;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import chat.dumber.calls.ICallBroker;
import chat.dumber.calls.ICallWake;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Requires the matching Dialer and Messaging APKs installed; no accounts or calls are created. */
public final class CallBrokerSecurityTest extends InstrumentationTestCase {
  private interface Probe {
    void run(IBinder binder) throws Exception;
  }

  private void assertOtherUidRejected(String pkg, String service, Probe probe) throws Exception {
    Context context = getInstrumentation().getTargetContext();
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<IBinder> binder = new AtomicReference<>();
    ServiceConnection connection =
        new ServiceConnection() {
          public void onServiceConnected(ComponentName name, IBinder value) {
            binder.set(value);
            ready.countDown();
          }

          public void onServiceDisconnected(ComponentName name) {}
        };
    assertTrue(
        "Install matching " + pkg + " first",
        context.bindService(
            new Intent().setComponent(new ComponentName(pkg, service)),
            connection,
            Context.BIND_AUTO_CREATE));
    try {
      assertTrue("Service did not bind", ready.await(10, TimeUnit.SECONDS));
      try {
        probe.run(binder.get());
        fail("Another application's UID must not use call IPC, even with a platform signature");
      } catch (SecurityException expected) {
        // The actual Binder calling UID, not an intent extra or shared certificate, is checked.
      }
    } finally {
      context.unbindService(connection);
    }
  }

  public void testMessagingBrokerRejectsOtherUid() throws Exception {
    assertOtherUidRejected(
        "com.android.messaging",
        "com.android.messaging.dumber.ChatCallService",
        binder -> ICallBroker.Stub.asInterface(binder).version());
  }

  public void testDialerWakeRejectsOtherUid() throws Exception {
    assertOtherUidRejected(
        "com.android.dialer",
        "com.android.dialer.dumber.CallWakeService",
        binder -> ICallWake.Stub.asInterface(binder).wake());
  }
}
