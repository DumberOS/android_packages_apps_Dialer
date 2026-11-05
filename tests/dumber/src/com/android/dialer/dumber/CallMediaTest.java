package com.android.dialer.dumber;

import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import chat.dumber.calls.CallSetup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;

/** Real JNI/DTLS/ICE tests, with two local peers and disabled microphone tracks. */
public final class CallMediaTest extends InstrumentationTestCase {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final CallMedia[] peers = new CallMedia[2];
  private final AtomicReference<String> failure = new AtomicReference<>();
  private final String[] descriptions = new String[2];
  private CountDownLatch connected;
  private CountDownLatch negotiated;
  private CountDownLatch failed;
  private boolean corruptFingerprint;
  private final boolean[] connectedPeers = new boolean[2];

  private void createPeers(boolean corrupt) {
    corruptFingerprint = corrupt;
    connected = new CountDownLatch(2);
    negotiated = new CountDownLatch(2);
    failed = new CountDownLatch(1);
    CallSetup setup = new CallSetup();
    setup.uris = new String[0]; // No discovery service, account, or remote telephone involved.
    setup.username = "";
    setup.password = "";
    getInstrumentation()
        .runOnMainSync(
            () -> {
              for (int i = 0; i < 2; i++) {
                final int local = i;
                final int remote = 1 - i;
                peers[i] =
                    new CallMedia(
                        getInstrumentation().getTargetContext(),
                        setup,
                        new CallMedia.Events() {
                          public void candidate(IceCandidate candidate) {
                            main.post(
                                () -> {
                                  if (peers[remote] != null) peers[remote].candidate(candidate);
                                });
                          }

                          public void description(String type, String sdp) {
                            descriptions[local] = sdp;
                            negotiated.countDown();
                            if (corruptFingerprint && local == 0) {
                              sdp =
                                  sdp.replaceAll(
                                      "a=fingerprint:sha-256 [^\\r\\n]+",
                                      "a=fingerprint:sha-256 "
                                          + String.join(
                                              ":", java.util.Collections.nCopies(32, "00")));
                            }
                            peers[remote].remote(type, sdp, "offer".equals(type));
                          }

                          public void state(PeerConnection.PeerConnectionState state) {
                            if (state == PeerConnection.PeerConnectionState.CONNECTED)
                              connected.countDown();
                            if (state == PeerConnection.PeerConnectionState.FAILED)
                              failed.countDown();
                          }

                          public void failed() {
                            failure.set("SDP operation failed");
                            failed.countDown();
                          }
                        });
              }
              peers[0].offer(false);
            });
  }

  public void testAudioDtlsConnectsAndIceRestarts() throws Exception {
    createPeers(false);
    assertTrue("Both peers must connect", connected.await(20, TimeUnit.SECONDS));
    assertNull(failure.get());
    for (String sdp : descriptions) {
      assertTrue(sdp.contains("m=audio "));
      assertTrue(sdp.contains("UDP/TLS/RTP/SAVPF"));
      assertTrue(sdp.contains("a=fingerprint:sha-256 "));
      assertTrue(sdp.contains("opus/48000/2"));
      assertFalse(sdp.contains("m=video "));
      assertFalse(sdp.contains("m=application "));
    }
    String originalOffer = descriptions[0];
    negotiated = new CountDownLatch(2);
    getInstrumentation().runOnMainSync(() -> {
      connected = new CountDownLatch(2);
      java.util.Arrays.fill(connectedPeers, false);
      peers[0].offer(true);
    });
    assertTrue(
        "Restart must renegotiate both descriptions", negotiated.await(10, TimeUnit.SECONDS));
    assertFalse(
        "ICE restart must change credentials",
        iceUfrag(originalOffer).equals(iceUfrag(descriptions[0])));
    assertTrue("Restart must reconnect both transports", connected.await(15, TimeUnit.SECONDS));
    assertNull(failure.get());
  }

  public void testWrongFingerprintCannotConnect() throws Exception {
    createPeers(true);
    assertTrue("Tampered DTLS identity must fail", failed.await(20, TimeUnit.SECONDS));
    assertEquals("No peer may connect with a mismatching fingerprint", 2L, connected.getCount());
  }

  public void testHangupDuringOfferIsSafe() {
    createPeers(false);
    closePeers();
    getInstrumentation().waitForIdleSync();
  }

  private static String iceUfrag(String sdp) {
    for (String line : sdp.split("\\r?\\n")) if (line.startsWith("a=ice-ufrag:")) return line;
    throw new AssertionError("Missing ICE credentials");
  }

  private void closePeers() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              for (int i = 0; i < peers.length; i++) {
                if (peers[i] != null) {
                  peers[i].close();
                  peers[i] = null;
                }
              }
            });
  }

  @Override
  protected void tearDown() throws Exception {
    closePeers();
    super.tearDown();
  }
}
