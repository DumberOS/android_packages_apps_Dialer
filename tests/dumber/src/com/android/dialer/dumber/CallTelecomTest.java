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
