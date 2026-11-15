package com.android.dialer.dumber;

import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.test.AndroidTestCase;

public final class EncryptedCallStateTest extends AndroidTestCase {
  public void testAnswerAcknowledgedBeforeMediaConnects() {
    Connection connection = new Connection() {};
    connection.setRinging();
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_RINGING, connection.getExtras()));
    EncryptedCallState.establishing(connection);
    assertEquals(Connection.STATE_RINGING, connection.getState());
    assertTrue(EncryptedCallState.isEstablishing(Call.STATE_RINGING, connection.getExtras()));
    EncryptedCallState.connected(connection);
    assertEquals(Connection.STATE_ACTIVE, connection.getState());
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_ACTIVE, connection.getExtras()));
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_RINGING, connection.getExtras()));
  }

  public void testHangupAndFailureOverrideEstablishingHint() {
    Connection connection = new Connection() {};
    EncryptedCallState.establishing(connection);
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_DISCONNECTING, connection.getExtras()));
    connection.setDisconnected(new DisconnectCause(DisconnectCause.ERROR));
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_DISCONNECTED, connection.getExtras()));
    assertFalse(EncryptedCallState.isEstablishing(Call.STATE_ACTIVE, connection.getExtras()));
  }
}
