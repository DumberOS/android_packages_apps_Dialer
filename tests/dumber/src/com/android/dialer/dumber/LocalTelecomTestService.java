package com.android.dialer.dumber;

import android.telecom.*;
import java.util.concurrent.CountDownLatch;

/** Local-only Telecom regression: no radio, network, microphone or remote peer. */
public final class LocalTelecomTestService extends ConnectionService {
  static volatile CountDownLatch attached;
  static volatile Connection connection;
  static volatile ConnectionRequest request;

  @Override public Connection onCreateIncomingConnection(PhoneAccountHandle manager, ConnectionRequest incoming) {
    Connection local = new Connection() {
      @Override public void onAnswer() { EncryptedCallState.establishing(this); }
      @Override public void onAnswer(int videoState) { onAnswer(); }
      @Override public void onDisconnect() {
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
      }
    };
    local.setAddress(android.net.Uri.fromParts("tel", "5550100", null), TelecomManager.PRESENTATION_ALLOWED);
    local.setCallerDisplayName("Local encrypted-call test", TelecomManager.PRESENTATION_ALLOWED);
    local.setAudioModeIsVoip(true);
    local.setRinging();
    connection = local;
    attached.countDown();
    return local;
  }

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
