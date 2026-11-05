package com.android.dialer.dumber;

import android.content.Context;
import android.content.Intent;
import chat.dumber.calls.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Test APK only: no account service, signaling, Telecom or real outgoing call. */
final class CallManager {
  static final String BYPASS = "chat.dumber.calls.CELLULAR";
  static final CallManager instance = new CallManager();
  final ExecutorService io = Executors.newSingleThreadExecutor();
  volatile ICallBroker broker;
  volatile int started;
  static CallManager get(Context unused) { return instance; }
  void connect() {}
  void cancelOutgoing() {}
  void start(CallAccount account, String phone, Intent original, Consumer<Boolean> ready) {
    started++;
    ready.accept(true);
  }
}
