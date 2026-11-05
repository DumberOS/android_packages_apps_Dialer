package com.android.dialer.dumber;

import android.telecom.*;
import java.util.concurrent.CountDownLatch;

/** Local-only Telecom regression: no radio, network, microphone or remote peer. */
public final class LocalTelecomTestService extends ConnectionService {
  static volatile CountDownLatch attached;
  static volatile Connection connection;
  static volatile ConnectionRequest request;

  @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle manager, ConnectionRequest incoming) {
    request = incoming;
    Connection local = new Connection() {
      @Override public void onDisconnect() {
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
      }
    };
    local.setAddress(incoming.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
    local.setCallerDisplayName("Local encrypted-call test", TelecomManager.PRESENTATION_ALLOWED);
    local.setAudioModeIsVoip(true);
    local.setDialing();
    connection = local;
    attached.countDown();
    return local;
  }
}
