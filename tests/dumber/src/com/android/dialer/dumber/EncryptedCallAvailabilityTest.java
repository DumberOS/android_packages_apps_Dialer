package com.android.dialer.dumber;

import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.test.InstrumentationTestCase;
import chat.dumber.calls.*;

/** Verify the picker only labels SIMs for which this recipient supports encrypted calls. */
public final class EncryptedCallAvailabilityTest extends InstrumentationTestCase {
  private CallAccount[] accounts;
  private String supportedAccount;
  private String queriedAccount;
  private String queriedPhone;
  private boolean failLookup;

  private final ICallBroker broker = new ICallBroker.Stub() {
    public int version() { return 1; }
    public CallAccount[] accounts() { return accounts; }
    public void subscribe(ICallListener listener) {}
    public boolean supportsCall(String id, String phone) {
      queriedAccount = id;
      queriedPhone = phone;
      if (failLookup) throw new IllegalStateException("offline");
      return id.equals(supportedAccount);
    }
    public CallSetup prepare(String id, String phone, String conversation) { throw new AssertionError(); }
    public void send(String id, String conversation, CallSignal signal, String transaction) { throw new AssertionError(); }
    public void acknowledge(String id, String event) { throw new AssertionError(); }
  };

  @Override protected void setUp() throws Exception {
    super.setUp();
    CallAccount first = new CallAccount();
    first.id = "first";
    first.subscription = 1;
    CallAccount second = new CallAccount();
    second.id = "second";
    second.subscription = 2;
    accounts = new CallAccount[] {first, second};
    supportedAccount = "second";
    queriedAccount = null;
    queriedPhone = null;
    failLookup = false;
  }

  private boolean supports(int subscription) {
    return EncryptedCallAvailability.supportsCall(
        broker, accounts, subscription, "+12025550123", "us");
  }

  public void testRecipientCapabilityIsCheckedForEachSim() {
    assertFalse(supports(1));
    assertEquals("first", queriedAccount);
    assertTrue(supports(2));
    assertEquals("second", queriedAccount);
  }

  public void testUnregisteredSimDoesNotUseOtherSimsAccount() {
    accounts = new CallAccount[] {accounts[1]};
    assertFalse(supports(1));
    assertNull(queriedAccount);
    assertTrue(supports(2));
  }

  public void testNoEnabledAccountDoesNotShowEncryption() {
    accounts = new CallAccount[0];
    assertFalse(supports(2));
    assertNull(queriedAccount);
  }

  public void testUnknownSubscriptionDoesNotSelectRetainedAccount() {
    accounts[1].subscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    assertFalse(supports(SubscriptionManager.INVALID_SUBSCRIPTION_ID));
    assertNull(queriedAccount);
  }

  public void testFailedLookupDoesNotShowEncryption() {
    failLookup = true;
    assertFalse(supports(2));
  }

  public void testLocalNumberUsesSimCountry() {
    assertTrue(EncryptedCallAvailability.supportsCall(broker, accounts, 2, "2025550123", "us"));
    assertEquals("+12025550123", queriedPhone);
    assertTrue(EncryptedCallAvailability.supportsCall(broker, accounts, 2, "0701234567", "se"));
    assertEquals("+46701234567", queriedPhone);
  }

  public void testInternationalNumberIsNormalized() {
    assertTrue(EncryptedCallAvailability.supportsCall(
        broker, accounts, 2, "+1 (202) 555-0123", "se"));
    assertEquals("+12025550123", queriedPhone);
  }

  public void testInvalidNumberDoesNotQueryRecipient() {
    assertFalse(EncryptedCallAvailability.supportsCall(broker, accounts, 2, "1", "us"));
    assertNull(queriedAccount);
  }

  private boolean canRoute(Intent call) {
    return CallRouteActivity.canRoute(getInstrumentation().getTargetContext(), call);
  }

  private Intent call(String number) {
    return new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null));
  }

  public void testCellularOnlyCallsAreNotEligibleForLabels() {
    assertFalse(canRoute(call("+12025550123").putExtra(CallManager.BYPASS, true)));
    assertFalse(canRoute(call("+12025550123")
        .putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 3)));
    assertFalse(canRoute(call("*123#")));
    assertFalse(canRoute(call("+12025550123,1")));
    assertFalse(canRoute(call("+12025550123;1")));
    assertFalse(canRoute(new Intent(Intent.ACTION_CALL, Uri.parse("voicemail:"))));
  }
}
