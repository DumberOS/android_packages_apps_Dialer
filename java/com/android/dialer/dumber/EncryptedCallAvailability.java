package com.android.dialer.dumber;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import chat.dumber.calls.CallAccount;
import chat.dumber.calls.ICallBroker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Recipient-specific previews for SIM pickers; never prepares or places a call. */
public final class EncryptedCallAvailability {
  // A slow preview must not delay call setup after the user selects a SIM.
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();

  private EncryptedCallAvailability() {}

  /** Start on the UI thread; returns a cancellation action for the dialog lifecycle. */
  public static Runnable query(
      Context context, Intent call, List<PhoneAccountHandle> handles,
      Consumer<Set<PhoneAccountHandle>> result) {
    Context app = context.getApplicationContext();
    Handler main = new Handler(Looper.getMainLooper());
    AtomicBoolean cancelled = new AtomicBoolean();
    Runnable cancel = () -> {
      cancelled.set(true);
      main.removeCallbacksAndMessages(null);
    };
    try {
      if (!CallRouteActivity.canRoute(app, call)) return cancel;
    } catch (RuntimeException e) {
      return cancel;
    }
    String raw = call.getData().getSchemeSpecificPart();
    List<PhoneAccountHandle> candidates = new ArrayList<>(handles);
    CallManager manager = CallManager.get(app);
    manager.connect();
    long deadline = SystemClock.elapsedRealtime() + 4000;
    main.post(new Runnable() {
      @Override public void run() {
        if (cancelled.get()) return;
        ICallBroker broker = manager.broker;
        if (broker == null) {
          if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 100);
          return;
        }
        IO.execute(() -> {
          Set<PhoneAccountHandle> encrypted = new HashSet<>();
          try {
            if (cancelled.get()) return;
            CallAccount[] accounts = broker.accounts();
            TelephonyManager tm = app.getSystemService(TelephonyManager.class);
            for (PhoneAccountHandle handle : candidates) {
              if (cancelled.get()) return;
              try {
                TelephonyManager selected = tm.createForPhoneAccountHandle(handle);
                if (selected != null && supportsCall(broker, accounts,
                    selected.getSubscriptionId(), raw, selected.getSimCountryIso())) {
                  encrypted.add(handle);
                }
              } catch (RuntimeException e) {
                // An unavailable SIM must not prevent checking the other SIMs.
              }
            }
          } catch (Exception e) {
            // Keep ordinary carrier labels when Messaging is unavailable.
          }
          main.post(() -> {
            if (!cancelled.get()) result.accept(encrypted);
          });
        });
      }
    });
    return cancel;
  }

  static boolean supportsCall(
      ICallBroker broker, CallAccount[] accounts, int subscription, String raw, String country) {
    if (!SubscriptionManager.isValidSubscriptionId(subscription)) return false;
    CallAccount match = null;
    for (CallAccount account : accounts) {
      if (account.subscription == subscription) match = account;
    }
    if (match == null) return false;
    try {
      String phone = normalizeNumber(raw, country);
      return phone != null && broker.supportsCall(match.id, phone);
    } catch (Exception e) {
      return false;
    }
  }

  static String normalizeNumber(String raw, String country) {
    return raw.startsWith("+")
        ? PhoneNumberUtils.normalizeNumber(raw)
        : PhoneNumberUtils.formatNumberToE164(raw, country.toUpperCase(Locale.ROOT));
  }
}
