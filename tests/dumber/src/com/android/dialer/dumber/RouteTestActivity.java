package com.android.dialer.dumber;

import android.content.Intent;

/** The real routing Activity, with the final cellular side effect replaced. */
public final class RouteTestActivity extends CallRouteActivity {
  static volatile int cellularCalls;
  @Override protected void placeCellularCall(Intent intent) { cellularCalls++; }
}
