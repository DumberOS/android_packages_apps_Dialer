package com.android.dialer.dumber;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.BlockedNumberContract;
import android.telecom.*;
import chat.dumber.calls.*;
import java.util.*;
import java.util.concurrent.*;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;

/** Main-thread call state; a separate ordered executor keeps Binder/network work off the UI. */
public final class CallManager {
  static final String SCHEME = EncryptedCallAddress.SCHEME;
  static final String BYPASS = "chat.dumber.calls.CELLULAR";
  static final String CALL_ID = "chat.dumber.calls.ID";
  private static CallManager instance;
  final Context context;
  final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService io = Executors.newSingleThreadExecutor();
  volatile ICallBroker broker;
  volatile CallAccount[] accounts = new CallAccount[0];
  private boolean bound;
  private Active active;
  private final Map<String, Long> seen = new HashMap<>();
  private final Map<String, Long> ended = new HashMap<>();
  private final Map<String, List<CallEvent>> early = new HashMap<>();

  private CallManager(Context c) {
    context = c.getApplicationContext();
  }

  public static synchronized CallManager get(Context c) {
    if (instance == null) instance = new CallManager(c);
    return instance;
  }

  private final ICallListener listener =
      new ICallListener.Stub() {
        public void onEvent(CallEvent event) {
          main.post(() -> receive(event));
        }

        public void onAccountsChanged() {
          refresh();
        }
      };
  private final ServiceConnection service =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          broker = ICallBroker.Stub.asInterface(b);
          refresh();
        }

        public void onServiceDisconnected(ComponentName n) {
          broker = null;
          accounts = new CallAccount[0];
          if (active != null) end(active, "connection_failed", true);
        }

        public void onBindingDied(ComponentName n) {
          context.unbindService(this);
          bound = false;
          onServiceDisconnected(n);
          main.postDelayed(() -> connect(), 2000);
        }

        public void onNullBinding(ComponentName n) {
          onBindingDied(n);
        }
      };

  public void connect() {
    main.post(
        () -> {
          if (bound) {
            if (broker != null) refresh();
            return;
          }
          try {
            bound =
                context.bindService(
                    new Intent()
                        .setComponent(
                            new ComponentName(
                                "com.android.messaging",
                                "com.android.messaging.dumber.ChatCallService")),
                    service,
                    Context.BIND_AUTO_CREATE);
          } catch (RuntimeException e) {
            bound = false;
          }
        });
  }

  private void refresh() {
    io.execute(
        () -> {
          try {
            ICallBroker b = broker;
            if (b == null) return;
            if (b.version() != 1) throw new IllegalStateException();
            b.subscribe(listener);
            CallAccount[] loaded = b.accounts();
            main.post(
                () -> {
                  accounts = loaded;
                  android.util.Log.println(android.util.Log.INFO, "DumberCallRoute", "Enabled accounts=" + loaded.length
                      + "; voice subscription=" + android.telephony.SubscriptionManager.getDefaultVoiceSubscriptionId()
                      + "; account subscriptions=" + Arrays.toString(Arrays.stream(loaded).mapToInt(a -> a.subscription).toArray()));
                  TelecomManager tm = context.getSystemService(TelecomManager.class);
                  Set<String> ids = new HashSet<>();
                  for (CallAccount a : loaded) {
                    ids.add(a.id);
                    PhoneAccount p =
                        PhoneAccount.builder(handle(a.id), "Encrypted call · " + a.phone)
                            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                            .setSupportedUriSchemes(Collections.singletonList(SCHEME))
                            .setAddress(Uri.fromParts("tel", a.phone, null))
                            .setShortDescription("dumber.chat")
                            .build();
                    tm.registerPhoneAccount(p);
                    tm.enablePhoneAccount(p.getAccountHandle(), true);
                  }
                  for (PhoneAccountHandle h : tm.getCallCapablePhoneAccounts())
                    if (isEncrypted(h) && !ids.contains(h.getId())) tm.unregisterPhoneAccount(h);
                  if (active != null
                      && Arrays.stream(loaded)
                          .noneMatch(
                              a ->
                                  a.id.equals(active.account.id)
                                      && a.partyId.equals(active.account.partyId)))
                    end(active, "connection_failed", true);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  accounts = new CallAccount[0];
                  if (active != null) end(active, "connection_failed", true);
                });
          }
        });
  }

  public static boolean isEncrypted(PhoneAccountHandle h) {
    return h != null
        && h.getComponentName().getClassName().equals(EncryptedConnectionService.class.getName());
  }

  PhoneAccountHandle handle(String id) {
    return new PhoneAccountHandle(new ComponentName(context, EncryptedConnectionService.class), id);
  }

  public void observeTelecom(android.telecom.Call call) {
    call.registerCallback(
        new android.telecom.Call.Callback() {
          public void onStateChanged(android.telecom.Call c, int state) {
            if (state == android.telecom.Call.STATE_ACTIVE
                && !isEncrypted(c.getDetails().getAccountHandle())
                && active != null) end(active, "user_hangup", true);
          }
        },
        main);
  }

  static String uuid() {
    return UUID.randomUUID().toString();
  }

  static final class Active {
    CallAccount account;
    String phone, id, room, remoteParty;
    CallSignal offer;
    boolean incoming, accepted, connected, restarting, ended;
    java.util.function.Consumer<Boolean> onReady;
    final Object iceRefreshToken = new Object();
    CallMedia media;
    CallSetup setup;
    Connection connection;
    Intent cellular;
    final List<IceCandidate> candidates = new ArrayList<>();
  }

  void start(
      CallAccount account,
      String phone,
      Intent original,
      java.util.function.Consumer<Boolean> ready) {
    if (active != null || context.getSystemService(TelecomManager.class).isInCall()) {
      ready.accept(false);
      return;
    }
    Active a = new Active();
    a.account = account;
    a.phone = phone;
    a.id = uuid();
    a.cellular = new Intent(original);
    a.onReady = ready;
    active = a;
    timeout(a, 15000, "connection_failed");
    io.execute(
        () -> {
          try {
            CallSetup setup = broker.prepare(account.id, phone, null);
            main.post(
                () -> {
                  if (active != a || a.ended) return;
                  a.room = setup.conversationId;
                  a.setup = setup;
                  try {
                    Bundle extras = new Bundle();
                    extras.putParcelable(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(account.id));
                    Bundle callExtras = new Bundle();
                    callExtras.putString(CALL_ID, a.id);
                    extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras);
                    context
                        .getSystemService(TelecomManager.class)
                        .placeCall(EncryptedCallAddress.outgoing(a.id), extras);
                    // placeCall can return even when Telecom rejects the request.
                    // Start signaling only after our ConnectionService attaches it.
                  } catch (RuntimeException e) {
                    end(a, "connection_failed", true);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (active == a) {
                    end(a, "connection_failed", true);
                  }
                });
          }
        });
  }

  private void media(Active a, CallSetup setup) {
    context.startForegroundService(new Intent(context, CallAudioService.class));
    a.media =
        new CallMedia(
            context,
            setup,
            new CallMedia.Events() {
              public void candidate(IceCandidate c) {
                main.post(
                    () -> {
                      if (active != a || a.ended) return;
                      CallSignal s = signal(a, "CANDIDATES");
                      s.candidates = new String[] {c.sdp};
                      s.mids = new String[] {c.sdpMid};
                      s.lines = new int[] {c.sdpMLineIndex};
                      send(a, s, false);
                    });
              }

              public void description(String type, String sdp) {
                main.post(
                    () -> {
                      if (active != a || a.ended) return;
                      CallSignal s =
                          signal(
                              a,
                              a.connected
                                  ? "NEGOTIATE"
                                  : ("offer".equals(type) ? "INVITE" : "ANSWER"));
                      s.sdp = sdp;
                      s.descriptionType = type;
                      send(a, s, true);
                      timeout(
                          a,
                          a.connected
                              ? 15000
                              : (!a.incoming && "offer".equals(type) ? 45000 : 20000),
                          "connection_timeout");
                    });
              }

              public void state(PeerConnection.PeerConnectionState state) {
                main.post(
                    () -> {
                      if (active != a || a.ended) return;
                      if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        a.connected = true;
                        a.restarting = false;
                        main.removeCallbacksAndMessages(a);
                        if (a.connection != null) {
                          a.connection.setRingbackRequested(false);
                          a.connection.setActive();
                        }
                        a.media.microphone(a.accepted && !muted());
                      } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED
                          || state == PeerConnection.PeerConnectionState.FAILED) {
                        if (!a.connected) {
                          if (state == PeerConnection.PeerConnectionState.FAILED)
                            end(a, "connection_failed", true);
                          return;
                        }
                        if (!a.restarting) {
                          a.restarting = true;
                          timeout(a, 15000, "connection_failed");
                          if (!a.incoming) a.media.offer(true);
                        }
                      }
                    });
              }

              public void failed() {
                main.post(
                    () -> {
                      if (active == a) end(a, "connection_failed", true);
                    });
              }
            });
    refreshIce(a, setup.ttl);
    for (IceCandidate c : a.candidates) a.media.candidate(c);
    a.candidates.clear();
  }

  private void refreshIce(Active a, long ttl) {
    // Refresh well before expiry. Only the original caller originates restarts, avoiding glare.
    main.removeCallbacksAndMessages(a.iceRefreshToken);
    long delay = Math.max(1000, Math.min(ttl, 86400) * 500);
    main.postAtTime(
        () -> {
          if (active != a || a.ended) return;
          io.execute(
              () -> {
                try {
                  CallSetup setup = broker.prepare(a.account.id, a.phone, a.room);
                  main.post(
                      () -> {
                        if (active != a || a.ended) return;
                        a.media.updateIce(setup);
                        refreshIce(a, setup.ttl);
                        if (!a.incoming && a.connected && !a.restarting) {
                          a.restarting = true;
                          timeout(a, 15000, "connection_failed");
                          a.media.offer(true);
                        }
                      });
                } catch (Exception e) {
                  main.post(
                      () -> {
                        if (active == a) end(a, "connection_failed", true);
                      });
                }
              });
        },
        a.iceRefreshToken,
        SystemClock.uptimeMillis() + delay);
  }

  private boolean muted() {
    return context.getSystemService(AudioManager.class).isMicrophoneMute();
  }

  Connection attach(ConnectionRequest request, boolean incoming) {
    Active a = active;
    String id = request.getExtras() == null ? null : request.getExtras().getString(CALL_ID);
    if (a == null
        || a.ended
        || a.incoming != incoming
        || !a.account.id.equals(request.getAccountHandle().getId())
        || !a.id.equals(id))
      return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
    Connection c =
        new Connection() {
          public void onAnswer() {
            answer(a);
          }

          public void onAnswer(int videoState) {
            answer(a);
          }

          public void onReject() {
            end(a, "rejected", true);
          }

          public void onDisconnect() {
            end(a, "user_hangup", true);
          }

          public void onAbort() {
            end(a, "user_hangup", true);
          }

          public void onCallAudioStateChanged(CallAudioState s) {
            if (a.media != null) a.media.microphone(a.accepted && a.connected && !s.isMuted());
          }
        };
    a.connection = c;
    c.setAddress(Uri.fromParts("tel", a.phone, null), TelecomManager.PRESENTATION_ALLOWED);
    c.setCallerDisplayName(a.phone, TelecomManager.PRESENTATION_ALLOWED);
    c.setAudioModeIsVoip(true);
    c.setConnectionCapabilities(Connection.CAPABILITY_MUTE);
    c.setStatusHints(new StatusHints("Encrypted call", null, null));
    if (a.connected) c.setActive();
    else if (incoming) c.setRinging();
    else {
      c.setDialing();
      c.setRingbackRequested(true);
      main.post(() -> {
        if (active != a || a.ended) return;
        try {
          a.accepted = true;
          media(a, a.setup);
          a.media.offer(false);
          java.util.function.Consumer<Boolean> ready = a.onReady;
          a.onReady = null;
          if (ready != null) ready.accept(true);
        } catch (RuntimeException e) {
          end(a, "connection_failed", true);
        }
      });
    }
    return c;
  }

  private void answer(Active a) {
    if (active != a || a.accepted || a.ended) return;
    a.accepted = true;
    timeout(a, 20000, "connection_failed");
    io.execute(
        () -> {
          try {
            CallSetup setup = broker.prepare(a.account.id, a.phone, a.room);
            main.post(
                () -> {
                  if (active != a || a.ended) return;
                  try {
                    media(a, setup);
                    a.media.remote("offer", a.offer.sdp, true);
                  } catch (RuntimeException e) {
                    end(a, "connection_failed", true);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (active == a) end(a, "connection_failed", true);
                });
          }
        });
  }

  private CallSignal signal(Active a, String kind) {
    CallSignal s = new CallSignal();
    s.kind = kind;
    s.callId = a.id;
    s.partyId = a.account.partyId;
    s.sentAtMs = System.currentTimeMillis();
    s.candidates = new String[0];
    s.mids = new String[0];
    s.lines = new int[0];
    return s;
  }

  private void send(Active a, CallSignal signal, boolean fatal) {
    if (a.room == null) return;
    String transaction = uuid();
    io.execute(
        () -> {
          try {
            broker.send(a.account.id, a.room, signal, transaction);
          } catch (Exception first) {
            try {
              broker.send(a.account.id, a.room, signal, transaction);
            } catch (Exception second) {
              if (fatal)
                main.post(
                    () -> {
                      if (active == a) end(a, "connection_failed", false);
                    });
            }
          }
        });
  }

  private void receive(CallEvent e) {
    long now = System.currentTimeMillis();
    seen.values().removeIf(t -> t < now);
    ended.values().removeIf(t -> t < now);
    early.values().forEach(list -> list.removeIf(v -> v.expiresAtMs < now));
    early.values().removeIf(List::isEmpty);
    if (e == null || e.signal == null || e.expiresAtMs <= now) return;
    if (seen.put(e.eventId, e.expiresAtMs) != null) {
      ack(e);
      return;
    }
    CallSignal s = e.signal;
    CallAccount account =
        Arrays.stream(accounts).filter(a -> a.id.equals(e.accountId)).findFirst().orElse(null);
    if (account == null) {
      seen.remove(e.eventId);
      refresh();
      return;
    }
    String key = e.accountId + ":" + s.callId;
    if (ended.containsKey(key)) {
      ack(e);
      return;
    }
    if ("INVITE".equals(s.kind)) {
      if (BlockedNumberContract.isBlocked(context, e.phone)) {
        ack(e);
        return;
      }
      if (active != null
          && active.id.equals(s.callId)
          && active.account.id.equals(e.accountId)
          && Objects.equals(active.room, e.conversationId)) {
        ack(e);
        return;
      }
      boolean replacingOutgoing = false;
      if (active != null
          && !active.incoming
          && active.phone.equals(e.phone)
          && active.account.id.equals(e.accountId)) {
        if (active.id.compareTo(s.callId) < 0) {
          ack(e);
          return;
        }
        end(active, "user_hangup", true);
        replacingOutgoing = true;
      }
      if (active != null
          || (!replacingOutgoing && context.getSystemService(TelecomManager.class).isInCall())) {
        Active busy = new Active();
        busy.account = account;
        busy.room = e.conversationId;
        busy.id = s.callId;
        CallSignal reject = signal(busy, "HANGUP");
        reject.reason = "user_busy";
        send(busy, reject, false);
        ack(e);
        return;
      }
      Active a = new Active();
      a.account = account;
      a.phone = e.phone;
      a.id = s.callId;
      a.room = e.conversationId;
      a.remoteParty = s.partyId;
      a.incoming = true;
      a.offer = s;
      active = a;
      try {
        Bundle extras = new Bundle();
        extras.putString(CALL_ID, a.id);
        extras.putParcelable(
            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", a.phone, null));
        context
            .getSystemService(TelecomManager.class)
            .addNewIncomingCall(handle(account.id), extras);
        timeout(a, Math.max(1, e.expiresAtMs - now), "invite_timeout");
        List<CallEvent> queued = early.remove(key);
        if (queued != null) for (CallEvent q : queued) process(a, q);
      } catch (RuntimeException ex) {
        end(a, "connection_failed", true);
      }
    } else if (active != null
        && active.account.id.equals(e.accountId)
        && active.id.equals(s.callId)
        && java.util.Objects.equals(active.room, e.conversationId)
        && active.phone.equals(e.phone)) process(active, e);
    else if ("HANGUP".equals(s.kind) || "REJECT".equals(s.kind)) ended.put(key, now + 90000);
    else if ("CANDIDATES".equals(s.kind) && early.size() < 16) {
      List<CallEvent> list = early.computeIfAbsent(key, k -> new ArrayList<>());
      if (list.size() < 64) list.add(e);
    }
    ack(e);
  }

  private void process(Active a, CallEvent e) {
    CallSignal s = e.signal;
    if (a.remoteParty != null && !a.remoteParty.equals(s.partyId)) return;
    switch (s.kind) {
      case "ANSWER":
        if (a.incoming || a.media == null || a.remoteParty != null) break;
        a.remoteParty = s.partyId;
        a.media.remote("answer", s.sdp, false);
        CallSignal selected = signal(a, "SELECT_ANSWER");
        selected.selectedPartyId = s.partyId;
        send(a, selected, true);
        timeout(a, 20000, "connection_timeout");
        break;
      case "SELECT_ANSWER":
        if (a.incoming && !a.account.partyId.equals(s.selectedPartyId))
          end(a, "answered_elsewhere", false);
        break;
      case "CANDIDATES":
        if (s.candidates != null
            && s.mids != null
            && s.lines != null
            && s.candidates.length == s.mids.length
            && s.mids.length == s.lines.length)
          for (int i = 0; i < s.candidates.length; i++) {
            IceCandidate c = new IceCandidate(s.mids[i], s.lines[i], s.candidates[i]);
            if (a.media != null) a.media.candidate(c);
            else if (a.candidates.size() < 128) a.candidates.add(c);
          }
        break;
      case "NEGOTIATE":
        if (a.media != null && a.connected) {
          a.restarting = true;
          timeout(a, 15000, "connection_failed");
          a.media.remote(s.descriptionType, s.sdp, "offer".equals(s.descriptionType));
        }
        break;
      case "REJECT":
        end(a, "rejected", false);
        break;
      case "HANGUP":
        end(a, s.reason == null ? "user_hangup" : s.reason, false);
        break;
      default:
        break;
    }
  }

  private void ack(CallEvent e) {
    io.execute(
        () -> {
          try {
            broker.acknowledge(e.accountId, e.eventId);
          } catch (Exception ignored) {
          }
        });
  }

  private void timeout(Active a, long duration, String reason) {
    main.removeCallbacksAndMessages(a);
    main.postAtTime(
        () -> {
          if (active == a) end(a, reason, true);
        },
        a,
        SystemClock.uptimeMillis() + duration);
  }

  private void end(Active a, String reason, boolean signal) {
    if (a.ended) return;
    a.ended = true;
    ended.put(a.account.id + ":" + a.id, System.currentTimeMillis() + 90000);
    main.removeCallbacksAndMessages(a);
    main.removeCallbacksAndMessages(a.iceRefreshToken);
    if (signal && a.room != null) {
      CallSignal s = signal(a, "rejected".equals(reason) ? "REJECT" : "HANGUP");
      s.reason = reason;
      send(a, s, false);
    }
    if (a.media != null) a.media.close();
    context.stopService(new Intent(context, CallAudioService.class));
    if (a.connection != null) {
      a.connection.setRingbackRequested(false);
      int cause;
      if ("rejected".equals(reason)) cause = DisconnectCause.REJECTED;
      else if ("answered_elsewhere".equals(reason)) cause = DisconnectCause.ANSWERED_ELSEWHERE;
      else if ("user_busy".equals(reason)) cause = DisconnectCause.BUSY;
      else if (a.incoming && !a.accepted) cause = DisconnectCause.MISSED;
      else if (reason.startsWith("connection_")) cause = DisconnectCause.ERROR;
      else cause = signal ? DisconnectCause.LOCAL : DisconnectCause.REMOTE;
      a.connection.setDisconnected(new DisconnectCause(cause));
      a.connection.destroy();
    }
    if (active == a) active = null;
    if (a.onReady != null) {
      java.util.function.Consumer<Boolean> callback = a.onReady;
      a.onReady = null;
      if (reason.startsWith("connection_")) callback.accept(false);
      return;
    }
    if (!a.incoming
        && a.cellular != null
        && (reason.startsWith("connection_") || "invite_timeout".equals(reason))) {
      Intent fallback =
          new Intent(context, CallRouteActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              .putExtra("original", a.cellular)
              .putExtra("failed", true);
      context.startActivity(fallback);
    }
  }

  void cancelOutgoing() {
    if (active != null && !active.incoming) end(active, "user_hangup", true);
  }

  void failedIncoming() {
    if (active != null && active.incoming) end(active, "user_busy", true);
  }

  void failedOutgoing() {
    if (active != null && !active.incoming) end(active, "connection_failed", true);
  }
}
