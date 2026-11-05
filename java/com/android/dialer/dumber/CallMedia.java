package com.android.dialer.dumber;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import chat.dumber.calls.CallSetup;
import java.util.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** One audio-only PeerConnection. All callbacks return to the call controller's main thread. */
final class CallMedia {
  interface Events {
    void candidate(IceCandidate c);

    void description(String type, String sdp);

    void state(PeerConnection.PeerConnectionState state);

    void failed();
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final PeerConnectionFactory factory;
  private final PeerConnection pc;
  private final AudioSource source;
  private final AudioTrack track;
  private final Events events;
  private final List<IceCandidate> pending = new ArrayList<>();
  private boolean remoteSet;
  private boolean closed;

  CallMedia(Context context, CallSetup setup, Events events) {
    this.events = events;
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
    JavaAudioDeviceModule adm =
        JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule();
    factory =
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
    adm.release();
    PeerConnection.RTCConfiguration config = configuration(setup);
    pc =
        factory.createPeerConnection(
            config,
            new PeerConnection.Observer() {
              public void onSignalingChange(PeerConnection.SignalingState s) {}

              public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (s == PeerConnection.IceConnectionState.CONNECTED
                    || s == PeerConnection.IceConnectionState.COMPLETED) {
                  main.post(
                      () -> {
                        // An ICE restart can complete while aggregate state stays CONNECTED.
                        // Never declare a call active until DTLS is connected as well.
                        if (!closed
                            && pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED)
                          events.state(PeerConnection.PeerConnectionState.CONNECTED);
                      });
                }
              }

              public void onIceConnectionReceivingChange(boolean r) {}

              public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}

              public void onIceCandidate(IceCandidate c) {
                main.post(
                    () -> {
                      if (!closed) events.candidate(c);
                    });
              }

              public void onIceCandidatesRemoved(IceCandidate[] c) {}

              public void onAddStream(MediaStream s) {}

              public void onRemoveStream(MediaStream s) {}

              public void onDataChannel(DataChannel d) {
                d.close();
              }

              public void onRenegotiationNeeded() {}

              public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}

              public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                main.post(
                    () -> {
                      if (!closed) events.state(s);
                    });
              }
            });
    if (pc == null) {
      factory.dispose();
      throw new IllegalStateException("Cannot create audio connection");
    }
    MediaConstraints constraints = new MediaConstraints();
    constraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
    constraints.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
    source = factory.createAudioSource(constraints);
    track = factory.createAudioTrack("audio", source);
    track.setEnabled(false);
    pc.addTrack(track, Collections.singletonList("voice"));
  }

  private static PeerConnection.RTCConfiguration configuration(CallSetup setup) {
    List<PeerConnection.IceServer> servers =
        setup.uris.length == 0
            ? Collections.emptyList()
            : Collections.singletonList(
                PeerConnection.IceServer.builder(Arrays.asList(setup.uris))
                    .setUsername(setup.username)
                    .setPassword(setup.password)
                    .createIceServer());
    PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
    config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
    return config;
  }

  void updateIce(CallSetup setup) {
    if (!closed && !pc.setConfiguration(configuration(setup))) events.failed();
  }

  private SdpObserver observer(Runnable done) {
    return new SdpObserver() {
      public void onCreateSuccess(SessionDescription d) {}

      public void onSetSuccess() {
        main.post(
            () -> {
              if (!closed) done.run();
            });
      }

      public void onCreateFailure(String error) {
        events.failed();
      }

      public void onSetFailure(String error) {
        events.failed();
      }
    };
  }

  void offer(boolean restart) {
    if (closed) return;
    MediaConstraints c = new MediaConstraints();
    if (restart) c.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
    pc.createOffer(createObserver(), c);
  }

  private SdpObserver createObserver() {
    return new SdpObserver() {
      public void onCreateSuccess(SessionDescription d) {
        main.post(
            () -> {
              if (!closed)
                pc.setLocalDescription(
                    observer(() -> events.description(d.type.canonicalForm(), d.description)), d);
            });
      }

      public void onSetSuccess() {}

      public void onCreateFailure(String error) {
        events.failed();
      }

      public void onSetFailure(String error) {
        events.failed();
      }
    };
  }

  void remote(String type, String sdp, boolean answer) {
    if (closed) return;
    pc.setRemoteDescription(
        observer(
            () -> {
              if (closed) return;
              remoteSet = true;
              for (IceCandidate c : pending) pc.addIceCandidate(c);
              pending.clear();
              if (answer) pc.createAnswer(createObserver(), new MediaConstraints());
            }),
        new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp));
  }

  void candidate(IceCandidate c) {
    if (closed) return;
    if (remoteSet) pc.addIceCandidate(c);
    else if (pending.size() < 128) pending.add(c);
  }

  void microphone(boolean enabled) {
    if (!closed) track.setEnabled(enabled);
  }

  void close() {
    if (closed) return;
    closed = true;
    pc.close();
    pc.dispose();
    track.dispose();
    source.dispose();
    factory.dispose();
    pending.clear();
  }
}
