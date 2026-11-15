package com.android.dialer.dumber;

import android.telecom.*;

public final class EncryptedConnectionService extends ConnectionService {
  public Connection onCreateOutgoingConnection(
      PhoneAccountHandle manager, ConnectionRequest request) {
    return CallManager.get(this).attach(request, false);
  }

  public Connection onCreateIncomingConnection(
      PhoneAccountHandle manager, ConnectionRequest request) {
    return CallManager.get(this).attach(request, true);
  }

  @Override
  public void onCreateConnectionComplete(Connection connection) {
    // Telecom applies the initial ringback flag before changing CONNECTING to DIALING.
    // Requesting it in the initial connection makes RingbackPlayer throw and abort setup.
    if (connection.getState() == Connection.STATE_DIALING) {
      connection.setRingbackRequested(true);
    }
  }

  public void onCreateIncomingConnectionFailed(
      PhoneAccountHandle manager, ConnectionRequest request) {
    CallManager.get(this).failedIncoming();
  }

  public void onCreateOutgoingConnectionFailed(
      PhoneAccountHandle manager, ConnectionRequest request) {
    CallManager.get(this).failedOutgoing();
  }
}
