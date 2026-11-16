package com.android.dialer.dumber;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.test.InstrumentationTestCase;
import java.util.ArrayList;
import java.util.List;

/** Existing call-log history initializes the warning without dialing any number. */
public final class CallFallbackHistoryTest extends InstrumentationTestCase {
  private static final String PHONE = "+12025550129";
  private static final String ENCRYPTED =
      "com.android.dialer/com.android.dialer.dumber.EncryptedConnectionService";
  private final List<Uri> inserted = new ArrayList<>();
  private Context context;
  private long date;

  @Override protected void setUp() throws Exception {
    super.setUp();
    context = getInstrumentation().getTargetContext();
    date = System.currentTimeMillis();
    getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG);
  }

  private void add(int type, long duration, boolean encrypted) {
    ContentValues values = new ContentValues();
    values.put(Calls.NUMBER, "+1 (202) 555-0129");
    values.put(Calls.TYPE, type);
    values.put(Calls.DURATION, duration);
    values.put(Calls.DATE, date++);
    values.put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, encrypted ? ENCRYPTED : "test/Cellular");
    Uri uri = context.getContentResolver().insert(Calls.CONTENT_URI, values);
    assertNotNull(uri);
    inserted.add(uri);
  }

  private boolean fromExistingHistory() {
    context.getSharedPreferences("encrypted_call_fallback", Context.MODE_PRIVATE)
        .edit().remove(PHONE).commit();
    return CallFallbackHistory.requiresWarning(context, PHONE);
  }

  public void testExistingEncryptedThenCellularThenEncryptedHistory() {
    assertFalse(fromExistingHistory());
    add(Calls.OUTGOING_TYPE, 30, true);
    assertTrue("Formatting differences must still match the number", fromExistingHistory());
    add(Calls.OUTGOING_TYPE, 0, false);
    assertFalse(fromExistingHistory());
    add(Calls.OUTGOING_TYPE, 0, true);
    assertFalse("Unanswered encrypted calls must not rearm the warning", fromExistingHistory());
    add(Calls.OUTGOING_TYPE, 20, true);
    assertTrue(fromExistingHistory());
  }

  public void testIncomingSuccessfulEncryptedCallAlsoArmsWarning() {
    add(Calls.MISSED_TYPE, 0, true);
    assertFalse(fromExistingHistory());
    add(Calls.INCOMING_TYPE, 15, true);
    assertTrue(fromExistingHistory());
    add(Calls.INCOMING_TYPE, 15, false);
    assertTrue("An incoming cellular call does not consume outgoing-call consent",
        fromExistingHistory());
  }

  @Override protected void tearDown() throws Exception {
    try {
      for (Uri uri : inserted) context.getContentResolver().delete(
          Calls.CONTENT_URI, Calls._ID + " = ?",
          new String[] {String.valueOf(android.content.ContentUris.parseId(uri))});
      context.getSharedPreferences("encrypted_call_fallback", Context.MODE_PRIVATE)
          .edit().remove(PHONE).commit();
    } finally {
      getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
      super.tearDown();
    }
  }
}
