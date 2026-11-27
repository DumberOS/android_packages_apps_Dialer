package com.android.dialer.dumber;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import chat.dumber.calls.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real CallManager, ConnectionService, Telecom and notification; signaling stays local. */
public final class OutgoingCallTest extends InstrumentationTestCase {
  private Context context;
  private TelecomManager telecom;
  private CallManager manager;
  private CallManager previous;
  private CallManager.Active call;
  private PhoneAccountHandle handle;
  private Field instance;
  private boolean registered;
  private boolean replaced;
  private final CountDownLatch preparationEntered = new CountDownLatch(1);
  private CountDownLatch releasePreparation = new CountDownLatch(0);
  private final AtomicInteger signalsSent = new AtomicInteger();

  private void startCall() throws Exception {
    startCall(false);
    assertTrue("Media setup must complete for an available recipient",
        awaitMain(() -> call.media != null));
  }

  private void startCall(boolean delayedPreparation) throws Exception {
    if (delayedPreparation) releasePreparation = new CountDownLatch(1);
    context = getInstrumentation().getTargetContext();
    telecom = context.getSystemService(TelecomManager.class);
    getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
        Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS);
    assertFalse("Do not run during a real call", telecom.isInCall());
    key(KeyEvent.KEYCODE_WAKEUP);
    Constructor<CallManager> constructor = CallManager.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    Context isolated = new ContextWrapper(context) {
      @Override public Context getApplicationContext() { return this; }
      @Override public boolean bindService(
          Intent intent, android.content.ServiceConnection connection, int flags) { return false; }
    };
    manager = constructor.newInstance(isolated);
    CallAccount account = new CallAccount();
    account.id = "local-outgoing-regression";
    account.partyId = "local-test-party";
    account.phone = "+12025550124";
    CallSetup setup = new CallSetup();
    setup.conversationId = "local-test-room";
    setup.uris = new String[0]; // No STUN/TURN service or remote peer; microphone remains disabled.
    setup.username = "";
    setup.password = "";
    setup.ttl = 3600;
    manager.accounts = new CallAccount[] {account};
    manager.broker = new ICallBroker.Stub() {
      public int version() { return 1; }
      public CallAccount[] accounts() { return new CallAccount[] {account}; }
      public void subscribe(ICallListener listener) {}
      public boolean supportsCall(String id, String phone) { return true; }
      public CallSetup prepare(String id, String phone, String conversation) {
        preparationEntered.countDown();
        try {
          if (!releasePreparation.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test preparation timed out");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
        return setup;
      }
      public void send(String id, String conversation, CallSignal signal, String transaction) {
        signalsSent.incrementAndGet();
      }
      public void acknowledge(String id, String event) {}
    };
    instance = CallManager.class.getDeclaredField("instance");
    instance.setAccessible(true);
    previous = CallManager.get(context);
    getInstrumentation().runOnMainSync(() -> {
      try { instance.set(null, manager); replaced = true; }
      catch (Exception e) { throw new AssertionError(e); }
    });
    handle = manager.handle(account.id);
    telecom.registerPhoneAccount(PhoneAccount.builder(handle, "Local outgoing regression")
        .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
        .setSupportedUriSchemes(Collections.singletonList(EncryptedCallAddress.SCHEME)).build());
    registered = true;
    telecom.enablePhoneAccount(handle, true);
    CountDownLatch attached = new CountDownLatch(1);
    Field active = CallManager.class.getDeclaredField("active");
    active.setAccessible(true);
    getInstrumentation().runOnMainSync(() -> {
      manager.start(account, "+12025550123", new Intent(), ready -> attached.countDown());
      try { call = (CallManager.Active) active.get(manager); }
      catch (Exception e) { throw new AssertionError(e); }
    });
    assertTrue("Production ConnectionService must attach", attached.await(10, TimeUnit.SECONDS));
    assertNotNull(call);
    assertTrue("Telecom must acknowledge creation before ringback starts", awaitMain(() ->
        call.connection != null && call.connection.getState() == Connection.STATE_DIALING
            && call.connection.isRingbackRequested()));
    assertTrue("Outgoing call screen must appear", awaitCallScreen());
  }

  public void testPowerKeyEndsOutgoingCall() throws Exception {
    startCall();
    checkHangup(KeyEvent.KEYCODE_POWER);
  }

  public void testEndCallKeyEndsOutgoingCall() throws Exception {
    startCall();
    checkHangup(KeyEvent.KEYCODE_ENDCALL);
  }

  public void testSlowPreparationShowsCallScreenAndCanBeCancelled() throws Exception {
    startCall(true);
    assertTrue(preparationEntered.await(2, TimeUnit.SECONDS));
    assertNull("No media before preparation finishes", call.media);
    assertEquals(0, signalsSent.get());
    checkHangup(KeyEvent.KEYCODE_ENDCALL);
    releasePreparation.countDown();
    manager.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    getInstrumentation().waitForIdleSync();
    assertNull("A late preparation result must not restart media", call.media);
    assertEquals("Cancelling during setup must not send an invitation", 0, signalsSent.get());
    assertTrue(call.ended);
  }

  public void testSlowPreparationContinuesOnExistingConnection() throws Exception {
    startCall(true);
    assertTrue(preparationEntered.await(2, TimeUnit.SECONDS));
    Connection original = call.connection;
    assertNull(call.media);
    releasePreparation.countDown();
    assertTrue("Media must start after preparation completes", awaitMain(() -> call.media != null));
    assertSame("Preparation must not create another Telecom call", original, call.connection);
    assertEquals(Connection.STATE_DIALING, original.getState());
    assertFalse(call.ended);
    checkHangup(KeyEvent.KEYCODE_ENDCALL);
  }

  private void checkHangup(int keyCode) throws Exception {
    key(keyCode);
    assertTrue("Hardware key must disconnect the production encrypted connection", awaitMain(() ->
        call.ended && call.connection.getState() == Connection.STATE_DISCONNECTED));
    assertTrue("Telecom must release the outgoing call", awaitMain(() -> !telecom.isInCall()));
  }

  public void testEncryptedNotificationReopensOutgoingCall() throws Exception {
    startCall();
    key(KeyEvent.KEYCODE_HOME);
    getInstrumentation().getUiAutomation().waitForIdle(300, 5000);
    Notification notification = null;
    for (StatusBarNotification item : context.getSystemService(NotificationManager.class)
        .getActiveNotifications()) {
      if (item.getId() == 8093) notification = item.getNotification();
    }
    assertNotNull("Audio service must post its foreground notification", notification);
    assertNotNull("Encrypted call notification must have a tap action", notification.contentIntent);
    assertTrue("Tap must launch an activity directly", notification.contentIntent.isActivity());
    notification.contentIntent.send();
    assertTrue("Tapping the encrypted notification must reopen the call screen", awaitCallScreen());
    assertFalse(call.ended);
  }

  private void key(int keyCode) {
    getInstrumentation().getUiAutomation().injectInputEvent(
        new KeyEvent(KeyEvent.ACTION_DOWN, keyCode), true);
    getInstrumentation().getUiAutomation().injectInputEvent(
        new KeyEvent(KeyEvent.ACTION_UP, keyCode), true);
  }

  private boolean awaitMain(BooleanSupplier condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < deadline) {
      boolean[] matched = new boolean[1];
      getInstrumentation().runOnMainSync(() -> matched[0] = condition.getAsBoolean());
      if (matched[0]) return true;
      Thread.sleep(50);
    }
    return false;
  }

  private boolean awaitCallScreen() throws Exception {
    String label = context.getString(R.string.encrypted_call_connection_label);
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < deadline) {
      AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        boolean visible = "com.android.dialer".contentEquals(root.getPackageName())
            && !root.findAccessibilityNodeInfosByText(label).isEmpty();
        root.recycle();
        if (visible) return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  @Override protected void tearDown() throws Exception {
    try {
      releasePreparation.countDown();
      if (manager != null) {
        getInstrumentation().runOnMainSync(() -> {
          manager.cancelOutgoing();
          manager.main.removeCallbacksAndMessages(null);
        });
        manager.io.shutdown();
        manager.io.awaitTermination(5, TimeUnit.SECONDS);
        manager.endings.shutdown();
        manager.endings.awaitTermination(5, TimeUnit.SECONDS);
      }
      if (registered) telecom.unregisterPhoneAccount(handle);
      if (replaced) {
        getInstrumentation().runOnMainSync(() -> {
          try { instance.set(null, previous); }
          catch (Exception e) { throw new AssertionError(e); }
        });
      }
      if (telecom != null) awaitMain(() -> !telecom.isInCall());
    } finally {
      getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
      super.tearDown();
    }
  }
}
