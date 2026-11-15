package com.android.dialer.dumber;

import android.os.Bundle;
import android.telecom.Call;
import android.telecom.Connection;

/** Presents an answered encrypted call while media setup is still pending. */
public final class EncryptedCallState {
  private static final String ESTABLISHING = "chat.dumber.calls.ESTABLISHING";

  private EncryptedCallState() {}

  public static void establishing(Connection connection) {
    Bundle extras = new Bundle();
    extras.putBoolean(ESTABLISHING, true);
    connection.putExtras(extras);
  }

  public static void connected(Connection connection) {
    // Publish ACTIVE first so clearing the hint cannot briefly restore the answer screen.
    connection.setActive();
    connection.removeExtras(ESTABLISHING);
  }

  public static boolean isEstablishing(int telecomState, Bundle extras) {
    // Telecom exposes its internal ANSWERED state as RINGING until media connects.
    // Terminal states must win even if the hint has not been cleared yet.
    return telecomState == Call.STATE_RINGING
        && extras != null
        && extras.getBoolean(ESTABLISHING, false);
  }
}
