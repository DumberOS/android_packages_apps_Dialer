package com.android.dialer.dumber;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SubscriptionManager;
import android.test.InstrumentationTestCase;
import chat.dumber.calls.*;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercise the actual routing screen without permission to place telephone calls. */
public final class CallRouteTest extends InstrumentationTestCase {
  private RouteTestActivity activity;
  private volatile boolean supported;
  private volatile boolean failLookup;
  private volatile CallAccount[] accounts;
  private CountDownLatch lookupEntered;
  private CountDownLatch lookupRelease;

  @Override protected void setUp() throws Exception {
    super.setUp();
    supported = false;
    failLookup = false;
    lookupEntered = new CountDownLatch(1);
    lookupRelease = new CountDownLatch(0);
    RouteTestActivity.cellularCalls = 0;
    CallManager.instance.started = 0;
    CallAccount account = new CallAccount();
    account.id = "test-account";
    account.phone = "+12025550124";
    account.subscription = SubscriptionManager.getDefaultVoiceSubscriptionId();
    accounts = new CallAccount[] {account};
    CallManager.instance.broker = new ICallBroker.Stub() {
      public int version() { return 1; }
      public CallAccount[] accounts() { return accounts; }
      public void subscribe(ICallListener listener) {}
      public boolean supportsCall(String id, String phone) {
        lookupEntered.countDown();
        try { if (!lookupRelease.await(8, TimeUnit.SECONDS)) throw new IllegalStateException(); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
        if (failLookup) throw new IllegalStateException("offline");
        return supported;
      }
      public CallSetup prepare(String id, String phone, String conversation) { throw new AssertionError(); }
      public void send(String id, String conversation, CallSignal signal, String transaction) { throw new AssertionError(); }
      public void acknowledge(String id, String event) {}
    };
  }

  private void launch() {
    Intent original = new Intent(Intent.ACTION_CALL, Uri.parse("tel:+12025550123"));
    Intent intent = new Intent(getInstrumentation().getTargetContext(), RouteTestActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("original", original);
    activity = (RouteTestActivity) getInstrumentation().startActivitySync(intent);
  }

  private AlertDialog dialog(String name) throws Exception {
    Field field = CallRouteActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    long end = android.os.SystemClock.elapsedRealtime() + 6000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      getInstrumentation().waitForIdleSync();
      AlertDialog result = (AlertDialog) field.get(activity);
      if (result != null && result.isShowing()) return result;
      Thread.sleep(25);
    }
    fail("Routing must wait for a visible " + name + ", never dial automatically");
    return null;
  }

  private void confirmNothingDialed() throws Exception {
    dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
    assertFalse(activity.isFinishing());
  }

  public void testMissingAccountWaitsForConfirmation() throws Exception {
    accounts = new CallAccount[0];
    launch();
    confirmNothingDialed();
  }

  public void testUnsupportedRecipientWaitsForConfirmation() throws Exception {
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    confirmNothingDialed();
  }

  public void testFailedLookupWaitsForConfirmation() throws Exception {
    failLookup = true;
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    confirmNothingDialed();
  }

  public void testBrokerTimeoutWaitsForConfirmation() throws Exception {
    CallManager.instance.broker = null;
    launch();
    confirmNothingDialed();
  }

  public void testOnlyExplicitPositiveButtonPlacesCellularCall() throws Exception {
    launch();
    AlertDialog confirmation = dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
    getInstrumentation().runOnMainSync(() -> confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    getInstrumentation().waitForIdleSync();
    assertEquals(1, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
  }

  public void testCancelPlacesNoCall() throws Exception {
    launch();
    AlertDialog confirmation = dialog("confirmation");
    getInstrumentation().runOnMainSync(() -> confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
    getInstrumentation().waitForIdleSync();
    assertEquals(0, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
    assertTrue(activity.isFinishing());
  }

  public void testSupportedRecipientUsesEncryptedRoute() throws Exception {
    supported = true;
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    CallManager.instance.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    getInstrumentation().waitForIdleSync();
    assertEquals(1, CallManager.instance.started);
    assertEquals(0, RouteTestActivity.cellularCalls);
  }

  public void testCancelDuringLookupPreventsLateCall() throws Exception {
    supported = true;
    lookupRelease = new CountDownLatch(1);
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    getInstrumentation().runOnMainSync(() -> activity.onBackPressed());
    lookupRelease.countDown();
    CallManager.instance.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    getInstrumentation().waitForIdleSync();
    assertEquals(0, CallManager.instance.started);
    assertEquals(0, RouteTestActivity.cellularCalls);
  }

  public void testDifferentSimRequiresExplicitAccountSelection() throws Exception {
    accounts[0].subscription = Integer.MAX_VALUE - 1;
    supported = true;
    launch();
    AlertDialog picker = dialog("accountPicker");
    assertEquals(0, CallManager.instance.started);
    assertEquals(0, RouteTestActivity.cellularCalls);
    getInstrumentation().runOnMainSync(() -> picker.getListView().performItemClick(null, 0, 0));
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    CallManager.instance.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    getInstrumentation().waitForIdleSync();
    assertEquals(1, CallManager.instance.started);
    assertEquals(0, RouteTestActivity.cellularCalls);
  }

  @Override protected void tearDown() throws Exception {
    lookupRelease.countDown();
    CallManager.instance.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
    if (activity != null) getInstrumentation().runOnMainSync(() -> activity.finish());
    getInstrumentation().waitForIdleSync();
    super.tearDown();
  }
}
