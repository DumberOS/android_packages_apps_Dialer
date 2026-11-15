package com.android.dialer.dumber;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.telecom.*;
import android.telephony.PhoneNumberUtils;
import android.test.InstrumentationTestCase;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CallTelecomTest extends InstrumentationTestCase {
  @Override protected void setUp() throws Exception {
    super.setUp();
    // Hardware hangup can turn the screen off. Each UI test starts with it awake.
    getInstrumentation().getUiAutomation().injectInputEvent(
        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_WAKEUP), true);
    getInstrumentation().getUiAutomation().injectInputEvent(
        new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_WAKEUP), true);
  }

  @Override protected void tearDown() throws Exception {
    try {
      // Telecom disconnect and the call-ended screen finish asynchronously.
      long deadline = android.os.SystemClock.elapsedRealtime() + 7000;
      boolean closed = false;
      while (android.os.SystemClock.elapsedRealtime() < deadline) {
        android.view.accessibility.AccessibilityNodeInfo root =
            getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root != null) {
          closed = root.findAccessibilityNodeInfosByText("Local encrypted-call test").isEmpty();
          root.recycle();
          if (closed) break;
        }
        Thread.sleep(100);
      }
      assertTrue("Previous call screen must close before the next test", closed);
      getInstrumentation().getUiAutomation().waitForIdle(500, 5000);
    } finally {
      super.tearDown();
    }
  }

  public void testAcceptedIncomingCallShowsEstablishingUntilConnected() throws Exception {
    checkAcceptedIncomingCall(true);
  }

  public void testHangupWhileEstablishing() throws Exception {
    checkAcceptedIncomingCall(false);
  }

  private void checkAcceptedIncomingCall(boolean connect) throws Exception {
    Context context = getInstrumentation().getTargetContext();
    TelecomManager telecom = context.getSystemService(TelecomManager.class);
    PhoneAccountHandle handle = new PhoneAccountHandle(
        new ComponentName(context, LocalTelecomTestService.class), "local-incoming-test");
    getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
        Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.ANSWER_PHONE_CALLS);
    boolean registered = false;
    try {
      assertFalse("Do not run during a real call", telecom.isInCall());
      LocalTelecomTestService.attached = new CountDownLatch(1);
      LocalTelecomTestService.connection = null;
      telecom.registerPhoneAccount(PhoneAccount.builder(handle, "Local encrypted-call test")
          .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
          .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL)).build());
      registered = true;
      telecom.enablePhoneAccount(handle, true);
      telecom.addNewIncomingCall(handle, new Bundle());
      assertTrue(LocalTelecomTestService.attached.await(10, TimeUnit.SECONDS));
      long ringingDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
      while (!telecom.isRinging() && android.os.SystemClock.elapsedRealtime() < ringingDeadline) {
        Thread.sleep(50);
      }
      assertTrue("Telecom must finish attaching the ringing call before answering", telecom.isRinging());
      assertTrue("Incoming screen must appear",
          waitForText("Local encrypted-call test", true, telecom));
      telecom.acceptRingingCall();
      assertTrue("Accept must show progress while media is pending", waitForText("Establishing call", true));
      assertEquals("Media has not connected", Connection.STATE_RINGING,
          LocalTelecomTestService.connection.getState());
      // Keep media pending to catch UI updates that restore the answer screen or start the timer.
      Thread.sleep(1500);
      assertTrue(waitForText("Establishing call", true));
      assertFalse("Duration must not start before media connects", hasVisibleTimer());
      if (connect) {
        getInstrumentation().runOnMainSync(
            () -> EncryptedCallState.connected(LocalTelecomTestService.connection));
        assertTrue("Progress must disappear after connection", waitForText("Establishing call", false));
        assertTrue("Connected call must show its duration", hasVisibleTimer());
      } else {
        // Exercise the phone's hardware hangup action while media is still pending.
        getInstrumentation().getUiAutomation().injectInputEvent(
            new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENDCALL), true);
        getInstrumentation().getUiAutomation().injectInputEvent(
            new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENDCALL), true);
        assertTrue("Progress must disappear on hangup", waitForText("Establishing call", false));
        long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        while (LocalTelecomTestService.connection.getState() != Connection.STATE_DISCONNECTED
            && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertEquals(Connection.STATE_DISCONNECTED, LocalTelecomTestService.connection.getState());
      }
    } finally {
      if (registered) {
        if (LocalTelecomTestService.connection != null) {
          getInstrumentation().runOnMainSync(() -> LocalTelecomTestService.connection.onDisconnect());
        }
        telecom.unregisterPhoneAccount(handle);
      }
      getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }
  }

  private boolean hasVisibleTimer() {
    android.view.accessibility.AccessibilityNodeInfo root =
        getInstrumentation().getUiAutomation().getRootInActiveWindow();
    if (root == null) return false;
    try {
      return hasVisibleTimer(root);
    } finally {
      root.recycle();
    }
  }

  private boolean hasVisibleTimer(android.view.accessibility.AccessibilityNodeInfo node) {
    if ("android.widget.Chronometer".contentEquals(node.getClassName()) && node.isVisibleToUser()) {
      return true;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      boolean found = hasVisibleTimer(child);
      child.recycle();
      if (found) return true;
    }
    return false;
  }

  private boolean waitForText(String text, boolean present) throws Exception {
    return waitForText(text, present, null);
  }

  private boolean waitForText(String text, boolean present, TelecomManager telecom) throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 7000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      // RINGING can precede InCallService binding, so the first foreground request can be lost.
      if (telecom != null) telecom.showInCallScreen(false);
      android.view.accessibility.AccessibilityNodeInfo root =
          getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        boolean dialer = "com.android.dialer".contentEquals(root.getPackageName());
        boolean found = !root.findAccessibilityNodeInfosByText(text).isEmpty();
        root.recycle();
        if (dialer && found == present) return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  public void testOutgoingUriReachesManagedConnectionAndCallScreen() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    TelecomManager telecom = context.getSystemService(TelecomManager.class);
    PhoneAccountHandle handle = new PhoneAccountHandle(new ComponentName(context, LocalTelecomTestService.class), "local-regression-test");
    android.net.Uri address = EncryptedCallAddress.outgoing("local-regression-test");
    assertEquals(PhoneAccount.SCHEME_SIP, address.getScheme());
    assertTrue(PhoneNumberUtils.isUriNumber(address.getSchemeSpecificPart()));
    LocalTelecomTestService.attached = new CountDownLatch(1);
    LocalTelecomTestService.connection = null;
    getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
        Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS);
    try {
      assertFalse("Do not run during a real call", telecom.isInCall());
      telecom.registerPhoneAccount(PhoneAccount.builder(handle, "Local encrypted-call test")
          .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
          .setSupportedUriSchemes(Collections.singletonList(EncryptedCallAddress.SCHEME)).build());
      telecom.enablePhoneAccount(handle, true);
      assertTrue(telecom.getPhoneAccount(handle).isEnabled());
      Bundle extras = new Bundle();
      extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
      Bundle outgoing = new Bundle();
      outgoing.putString("chat.dumber.calls.ID", "local-regression-test");
      extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoing);
      telecom.placeCall(address, extras);
      assertTrue("Telecom must attach the outgoing call", LocalTelecomTestService.attached.await(10, TimeUnit.SECONDS));
      assertEquals(handle, LocalTelecomTestService.request.getAccountHandle());
      assertEquals(address, LocalTelecomTestService.request.getAddress());
      assertEquals("local-regression-test", LocalTelecomTestService.request.getExtras().getString("chat.dumber.calls.ID"));
      long end = android.os.SystemClock.elapsedRealtime() + 7000;
      boolean shown = false;
      while (android.os.SystemClock.elapsedRealtime() < end) {
        android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root != null) {
          shown = "com.android.dialer".contentEquals(root.getPackageName())
              && !root.findAccessibilityNodeInfosByText("Local encrypted-call test").isEmpty();
          root.recycle();
        }
        if (shown) break;
        Thread.sleep(100);
      }
      assertTrue("Outgoing call screen must be visible", shown);
    } finally {
      if (LocalTelecomTestService.connection != null) {
        getInstrumentation().runOnMainSync(() -> LocalTelecomTestService.connection.onDisconnect());
      }
      telecom.unregisterPhoneAccount(handle);
      getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }
  }
}
