package com.android.dialer.dumber;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.telecom.*;
import android.telephony.*;
import android.widget.*;
import chat.dumber.calls.*;

/**
 * User-visible routing and explicit cellular fallback. Never retries carrier calls automatically.
 */
public class CallRouteActivity extends Activity {
  private Intent original;
  private CallManager manager;
  private boolean finished;
  private AlertDialog confirmation;
  private AlertDialog accountPicker;
  private boolean fallbackRequested;
  private boolean cellularConfirmed;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status;

  public static boolean intercept(Context c, Intent intent) {
    Uri uri = intent.getData();
    if (intent.getBooleanExtra(CallManager.BYPASS, false)
        || uri == null
        || !"tel".equals(uri.getScheme())) return false;
    String number = uri.getSchemeSpecificPart();
    if (number == null
        || number.contains("*")
        || number.contains("#")
        || number.contains(",")
        || number.contains(";")
        || c.getSystemService(TelephonyManager.class).isEmergencyNumber(number)) return false;
    if (intent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 0) != 0) return false;
    Intent route = new Intent(c, CallRouteActivity.class).putExtra("original", intent);
    if (!(c instanceof Activity)) route.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    c.startActivity(route);
    return true;
  }

  protected void onCreate(Bundle state) {
    super.onCreate(state);
    manager = CallManager.get(this);
    original = getIntent().getParcelableExtra("original", Intent.class);
    if (original == null || original.getData() == null) {
      finish();
      return;
    }
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(16, 16, 16, 16);
    status = new TextView(this);
    status.setText("Checking encrypted calling…");
    layout.addView(status);
    Button regular = new Button(this);
    regular.setText("Use regular call");
    regular.setOnClickListener(v -> confirm());
    layout.addView(regular);
    Button cancel = new Button(this);
    cancel.setText(android.R.string.cancel);
    cancel.setOnClickListener(v -> onBackPressed());
    layout.addView(cancel);
    setContentView(layout);
    if (getIntent().getBooleanExtra("failed", false) || state != null) {
      status.setText("Encrypted call could not connect.");
      confirm();
      return;
    }
    manager.connect();
    waitForBroker(SystemClock.elapsedRealtime() + 4000);
  }

  private void waitForBroker(long deadline) {
    if (finished || fallbackRequested) return;
    if (manager.broker == null) {
      if (SystemClock.elapsedRealtime() < deadline) {
        handler.postDelayed(() -> waitForBroker(deadline), 100);
        return;
      }
      unavailable("Encrypted calling could not connect to Messaging.");
      return;
    }
    manager.io.execute(
        () -> {
          try {
            CallAccount[] accounts = manager.broker.accounts();
            PhoneAccountHandle h =
                original.getParcelableExtra(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle.class);
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            TelephonyManager selected = h == null ? null : tm.createForPhoneAccountHandle(h);
            int sub =
                selected == null
                    ? SubscriptionManager.getDefaultVoiceSubscriptionId()
                    : selected.getSubscriptionId();
            CallAccount match = null;
            for (CallAccount a : accounts) if (a.subscription == sub) match = a;
            if (match == null && accounts.length == 1 && sub < 0) match = accounts[0];
            if (match == null) {
              runOnUiThread(
                  () -> {
                    if (finished || fallbackRequested) return;
                    if (accounts.length == 0) {
                      unavailable("No enabled encrypted-call account is available. Check dumber.chat settings in Messaging.");
                    } else {
                      // A retained account can belong to a different SIM. Never silently
                      // substitute an identity or interpret this as a cellular-call choice.
                      status.setText("Choose an account for the encrypted call.");
                      String[] labels = new String[accounts.length];
                      for (int i = 0; i < accounts.length; i++) labels[i] = accounts[i].phone;
                      accountPicker = new AlertDialog.Builder(this)
                          .setTitle("Encrypted call account")
                          .setItems(labels, (dialog, which) -> checkRecipient(accounts[which]))
                          .setNegativeButton(android.R.string.cancel, (dialog, which) -> onBackPressed())
                          .setOnCancelListener(dialog -> onBackPressed())
                          .show();
                    }
                  });
              return;
            }
            final CallAccount account = match;
            runOnUiThread(() -> checkRecipient(account));
          } catch (Exception e) {
            runOnUiThread(() -> unavailable("Encrypted calling could not check your account."));
          }
        });
  }

  private void checkRecipient(CallAccount account) {
    if (finished || fallbackRequested) return;
    status.setText("Checking encrypted calling…");
    manager.io.execute(
        () -> {
          try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            String raw = original.getData().getSchemeSpecificPart();
            String country = tm.createForSubscriptionId(account.subscription).getSimCountryIso();
            String phone =
                raw.startsWith("+")
                    ? PhoneNumberUtils.normalizeNumber(raw)
                    : PhoneNumberUtils.formatNumberToE164(
                        raw, country.toUpperCase(java.util.Locale.ROOT));
            if (phone == null) throw new IllegalArgumentException();
            boolean supported = manager.broker.supportsCall(account.id, phone);
            runOnUiThread(
                () -> {
                  if (finished || fallbackRequested) return;
                  if (!supported) {
                    unavailable("Encrypted calling is not available for this recipient. Check that it is enabled on both phones.");
                    return;
                  }
                  status.setText("Starting encrypted call…");
                  manager.start(
                      account,
                      phone,
                      original,
                      ready -> {
                        if (ready && !finished && !fallbackRequested) {
                          finished = true;
                          finish();
                        } else if (!finished) confirm();
                      });
                });
          } catch (Exception e) {
            runOnUiThread(() -> unavailable("Encrypted calling could not check this recipient. Check your internet connection and try again."));
          }
        });
  }

  private void unavailable(String reason) {
    if (finished || fallbackRequested || isFinishing()) return;
    status.setText(reason);
    android.util.Log.println(android.util.Log.INFO, "DumberCallRoute", reason);
    confirm();
  }

  private void confirm() {
    if (finished || isFinishing() || (confirmation != null && confirmation.isShowing())) return;
    fallbackRequested = true;
    if (accountPicker != null) accountPicker.dismiss();
    manager.cancelOutgoing();
    handler.removeCallbacksAndMessages(null);
    confirmation =
        new AlertDialog.Builder(this)
            .setMessage("Place a regular cellular call? It will not use dumber.chat encryption.")
            .setTitle("Use a regular call?")
            .setPositiveButton("Use regular call", (d, w) -> {
              cellularConfirmed = true;
              cellular();
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> onBackPressed())
            .setOnCancelListener(d -> onBackPressed())
            .show();
  }

  private void cellular() {
    if (finished || !cellularConfirmed) return;
    finished = true;
    manager.cancelOutgoing();
    original.putExtra(CallManager.BYPASS, true);
    placeCellularCall(original);
    finish();
  }

  protected void placeCellularCall(Intent intent) {
    getSystemService(TelecomManager.class).placeCall(intent.getData(), intent.getExtras());
  }

  public void onBackPressed() {
    finished = true;
    manager.cancelOutgoing();
    finish();
  }

  protected void onDestroy() {
    if (!finished) {
      finished = true;
      manager.cancelOutgoing();
    }
    handler.removeCallbacksAndMessages(null);
    if (confirmation != null) confirmation.dismiss();
    if (accountPicker != null) accountPicker.dismiss();
    super.onDestroy();
  }
}
