package com.android.dialer.dumber;

import android.content.Intent;

/** The real routing Activity, with the final cellular side effect replaced. */
public final class RouteTestActivity extends CallRouteActivity {
  static volatile int cellularCalls;
  static Integer outgoingSubscription;
  static Intent cellularIntent;
  @Override protected int getOutgoingSubscriptionId() {
    return outgoingSubscription == null ? super.getOutgoingSubscriptionId() : outgoingSubscription;
  }
  @Override protected void placeCellularCall(Intent intent) {
    cellularIntent = new Intent(intent);
    cellularCalls++;
  }
}
