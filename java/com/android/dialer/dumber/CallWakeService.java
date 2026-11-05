package com.android.dialer.dumber;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import chat.dumber.calls.ICallWake;

public final class CallWakeService extends Service {
  private final ICallWake.Stub binder =
      new ICallWake.Stub() {
        public void wake() {
          try {
            if (Binder.getCallingUid()
                != getPackageManager().getPackageUid("com.android.messaging", 0))
              throw new SecurityException("Messaging only");
          } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new SecurityException("Messaging missing");
          }
          CallManager.get(CallWakeService.this).connect();
        }
      };

  public IBinder onBind(Intent intent) {
    return binder;
  }
}
