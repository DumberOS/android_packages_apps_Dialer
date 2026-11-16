package com.android.dialer.dumber;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.CallLog.Calls;
import android.telephony.PhoneNumberUtils;

/** Remembers whether the next cellular call to a previously encrypted peer needs confirmation. */
final class CallFallbackHistory {
  private static final String PREFERENCES = "encrypted_call_fallback";
  private static final String COMPONENT =
      "com.android.dialer/com.android.dialer.dumber.EncryptedConnectionService";

  private CallFallbackHistory() {}

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
  }

  static void encryptedConnected(Context context, String phone) {
    remember(context, phone, true);
  }

  static void cellularPlaced(Context context, String phone) {
    remember(context, phone, false);
  }

  private static synchronized void remember(Context context, String phone, boolean warn) {
    preferences(context).edit().putBoolean(PhoneNumberUtils.normalizeNumber(phone), warn).apply();
  }

  /** Runs on the routing worker: an existing installation starts from its call history. */
  static boolean requiresWarning(Context context, String phone) {
    String key = PhoneNumberUtils.normalizeNumber(phone);
    SharedPreferences preferences = preferences(context);
    synchronized (CallFallbackHistory.class) {
      if (preferences.contains(key)) return preferences.getBoolean(key, false);
    }
    boolean warn = previouslyEncrypted(context, phone);
    synchronized (CallFallbackHistory.class) {
      // A call may connect or be placed while the history query is running.
      if (preferences.contains(key)) return preferences.getBoolean(key, false);
      preferences.edit().putBoolean(key, warn).apply();
      return warn;
    }
  }

  private static boolean previouslyEncrypted(Context context, String phone) {
    // Successful incoming/outgoing encrypted calls arm the warning. An outgoing
    // cellular call clears it; missed, rejected and failed encrypted calls do neither.
    String selection = "(" + Calls.PHONE_ACCOUNT_COMPONENT_NAME + " = ? AND "
        + Calls.TYPE + " IN (?, ?) AND " + Calls.DURATION + " > 0) OR ("
        + Calls.TYPE + " = ? AND (" + Calls.PHONE_ACCOUNT_COMPONENT_NAME
        + " IS NULL OR " + Calls.PHONE_ACCOUNT_COMPONENT_NAME + " != ?))";
    try (Cursor cursor = context.getContentResolver().query(
        Calls.CONTENT_URI,
        new String[] {Calls.PHONE_ACCOUNT_COMPONENT_NAME, Calls.NUMBER, Calls.COUNTRY_ISO}, selection,
        new String[] {COMPONENT, String.valueOf(Calls.INCOMING_TYPE),
            String.valueOf(Calls.OUTGOING_TYPE), String.valueOf(Calls.OUTGOING_TYPE), COMPONENT},
        Calls.DATE + " DESC, " + Calls._ID + " DESC")) {
      if (cursor == null) throw new IllegalStateException("Call history unavailable");
      // Compare locally: some CallLog providers bind the filter URI's number
      // incorrectly when additional selection arguments are present.
      while (cursor.moveToNext()) {
        String number = cursor.getString(1);
        String country = cursor.getString(2);
        if (country == null || country.isEmpty()) country = java.util.Locale.getDefault().getCountry();
        if (number != null && PhoneNumberUtils.areSamePhoneNumber(number, phone, country)) {
          return COMPONENT.equals(cursor.getString(0));
        }
      }
      return false;
    }
  }
}
