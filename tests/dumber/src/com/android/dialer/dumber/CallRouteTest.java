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
    CallFallbackHistory.cellularPlaced(getInstrumentation().getTargetContext(), "+12025550123");
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
    fail("Expected a visible " + name);
    return null;
  }

  private void confirmNothingDialed() throws Exception {
    dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
    assertFalse(activity.isFinishing());
  }

  public void testMissingAccountWaitsForConfirmation() throws Exception {
    previouslyEncrypted();
    accounts = new CallAccount[0];
    launch();
    confirmNothingDialed();
  }

  public void testUnsupportedRecipientPlacesCellularCallWithoutWarning() throws Exception {
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    awaitCellularCalls(1);
    assertEquals(1, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
    Field confirmation = CallRouteActivity.class.getDeclaredField("confirmation");
    confirmation.setAccessible(true);
    assertNull(confirmation.get(activity));
    assertTrue(activity.isFinishing());
  }

  public void testFailedLookupWaitsForConfirmation() throws Exception {
    previouslyEncrypted();
    failLookup = true;
    launch();
    assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
    confirmNothingDialed();
  }

  public void testWarningReturnsOnlyAfterAnotherSuccessfulEncryptedCall() throws Exception {
    android.content.Context context = getInstrumentation().getTargetContext();
    CallFallbackHistory.encryptedConnected(context, "+12025550123");
    launch();
    AlertDialog confirmation = dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
    getInstrumentation().runOnMainSync(() ->
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    getInstrumentation().waitForIdleSync();
    assertEquals(1, RouteTestActivity.cellularCalls);

    // A further regular call needs no warning, even across Activity instances.
    launch();
    awaitCellularCalls(2);
    assertEquals(2, RouteTestActivity.cellularCalls);
    assertTrue(activity.isFinishing());

    CallFallbackHistory.encryptedConnected(context, "+12025550123");
    launch();
    dialog("confirmation");
    assertEquals(2, RouteTestActivity.cellularCalls);
    assertFalse(activity.isFinishing());
  }

  public void testCancellingWarningDoesNotClearIt() throws Exception {
    android.content.Context context = getInstrumentation().getTargetContext();
    CallFallbackHistory.encryptedConnected(context, "+12025550123");
    launch();
    AlertDialog confirmation = dialog("confirmation");
    getInstrumentation().runOnMainSync(() ->
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
    getInstrumentation().waitForIdleSync();
    launch();
    dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
  }

  public void testBrokerTimeoutWaitsForConfirmation() throws Exception {
    previouslyEncrypted();
    CallManager.instance.broker = null;
    launch();
    confirmNothingDialed();
  }

  public void testFailedLookupPositiveButtonPlacesCellularCall() throws Exception {
    previouslyEncrypted();
    failLookup = true;
    launch();
    AlertDialog confirmation = dialog("confirmation");
    assertEquals(0, RouteTestActivity.cellularCalls);
    getInstrumentation().runOnMainSync(() -> confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    getInstrumentation().waitForIdleSync();
    assertEquals(1, RouteTestActivity.cellularCalls);
    assertEquals(0, CallManager.instance.started);
  }

  public void testCancelPlacesNoCall() throws Exception {
    previouslyEncrypted();
    failLookup = true;
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

  private void previouslyEncrypted() {
    CallFallbackHistory.encryptedConnected(getInstrumentation().getTargetContext(), "+12025550123");
  }

  private void awaitCellularCalls(int expected) throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      getInstrumentation().waitForIdleSync();
      if (RouteTestActivity.cellularCalls == expected) return;
      Thread.sleep(25);
    }
    assertEquals(expected, RouteTestActivity.cellularCalls);
  }

  public void testUnmatchedAccountsRequireExplicitAccountSelection() throws Exception {
    accounts[0].subscription = Integer.MAX_VALUE - 1;
    CallAccount other = new CallAccount();
    other.id = "other-account";
    other.phone = "+12025550125";
    other.subscription = Integer.MAX_VALUE - 2;
    // With no default voice SIM, a single account is intentionally selected automatically.
    accounts = new CallAccount[] {accounts[0], other};
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
