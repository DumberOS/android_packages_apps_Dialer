package com.android.dialer.dumber;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.telecom.*;
import android.telephony.*;
import androidx.appcompat.app.AlertDialog;
import chat.dumber.calls.*;

/**
 * Visible, cancellable availability checks, with consent after failed encrypted calls.
 */
public class CallRouteActivity extends Activity {
  private Intent original;
  private CallManager manager;
  private volatile boolean finished;
  private AlertDialog checking;
  private AlertDialog confirmation;
  private AlertDialog accountPicker;
  private boolean fallbackRequested;
  private boolean encryptedAttempted;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String failureReason = "Encrypted call could not connect.";
  private String phone;

  public static boolean intercept(Context c, Intent intent) {
    if (!canRoute(c, intent)) return false;
    Intent route = new Intent(c, CallRouteActivity.class).putExtra("original", intent);
    if (!(c instanceof Activity)) route.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    c.startActivity(route);
    return true;
  }

  static boolean canRoute(Context c, Intent intent) {
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
    return true;
  }

  protected void onCreate(Bundle state) {
    super.onCreate(state);
    manager = CallManager.get(this);
    original = getIntent().getParcelableExtra("original", Intent.class);
    phone = getIntent().getStringExtra("phone");
    if (phone == null && state != null) phone = state.getString("phone");
    encryptedAttempted = getIntent().getBooleanExtra("failed", false)
        || (state != null && state.getBoolean("encrypted_attempted", false));
    if (original == null || original.getData() == null) {
      finish();
      return;
    }
    if (encryptedAttempted || (state != null && state.getBoolean("fallback_requested", false))) {
      confirm();
      return;
    }
    showChecking();
    manager.connect();
    waitForBroker(SystemClock.elapsedRealtime() + 4000);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("encrypted_attempted", encryptedAttempted);
    state.putBoolean("fallback_requested", fallbackRequested);
    state.putString("phone", phone);
    super.onSaveInstanceState(state);
  }

  private void showChecking() {
    if (finished || fallbackRequested || (checking != null && checking.isShowing())) return;
    checking = new AlertDialog.Builder(this)
        .setMessage("Checking encrypted call availability.")
        .setPositiveButton("Make a regular call", (dialog, which) -> cellular())
        .setOnCancelListener(dialog -> onBackPressed())
        .create();
    checking.setOnShowListener(dialog -> {
      android.widget.Button button = checking.getButton(AlertDialog.BUTTON_POSITIVE);
      button.setAllCaps(false);
      button.setFocusableInTouchMode(true);
      button.requestFocus();
    });
    checking.setCanceledOnTouchOutside(false);
    checking.show();
  }

  private void dismissChecking() {
    if (checking != null) checking.dismiss();
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
          if (finished) return;
          try {
            CallAccount[] accounts = manager.broker.accounts();
            int sub = getOutgoingSubscriptionId();
            CallAccount match = null;
            for (CallAccount a : accounts) if (a.subscription == sub) match = a;
            if (match == null && accounts.length == 1 && sub < 0) match = accounts[0];
            if (match == null) {
              runOnUiThread(
                  () -> {
                    if (finished || fallbackRequested) return;
                    if (SubscriptionManager.isValidSubscriptionId(sub)) {
                      // Keep the selected voice SIM's identity even when another SIM
                      // has an encrypted account. Use the normal cellular fallback.
                      unavailable("Encrypted calling is not enabled for the selected SIM.");
                    } else if (accounts.length == 0) {
                      unavailable("No enabled encrypted-call account is available. Check dumber.chat settings in Messaging.");
                    } else {
                      // No voice SIM was selected, so ask which encrypted identity to use.
                      dismissChecking();
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

  protected int getOutgoingSubscriptionId() {
    PhoneAccountHandle handle = original.getParcelableExtra(
        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle.class);
    TelephonyManager tm = getSystemService(TelephonyManager.class);
    TelephonyManager selected = handle == null ? null : tm.createForPhoneAccountHandle(handle);
    return selected == null
        ? SubscriptionManager.getDefaultVoiceSubscriptionId()
        : selected.getSubscriptionId();
  }

  private void checkRecipient(CallAccount account) {
    if (finished || fallbackRequested) return;
    showChecking();
    manager.io.execute(
        () -> {
          if (finished) return;
          try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            String raw = original.getData().getSchemeSpecificPart();
            String country = tm.createForSubscriptionId(account.subscription).getSimCountryIso();
            String normalizedPhone = EncryptedCallAvailability.normalizeNumber(raw, country);
            if (normalizedPhone == null) throw new IllegalArgumentException();
            runOnUiThread(() -> { if (!finished) phone = normalizedPhone; });
            if (finished) return;
            boolean supported = manager.broker.supportsCall(account.id, normalizedPhone);
            runOnUiThread(
                () -> {
                  if (finished || fallbackRequested) return;
                  dismissChecking();
                  if (!supported) {
                    // A definitive negative result completes routing automatically.
                    cellular();
                    return;
                  }
                  encryptedAttempted = true;
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
    failureReason = reason;
    android.util.Log.println(android.util.Log.INFO, "DumberCallRoute", reason);
    confirm();
  }

  private void confirm() {
    if (finished || isFinishing() || fallbackRequested) return;
    fallbackRequested = true;
    dismissChecking();
    if (accountPicker != null) accountPicker.dismiss();
    manager.cancelOutgoing();
    handler.removeCallbacksAndMessages(null);
    // A failed encrypted attempt always needs consent before changing transport,
    // including the first call to this number and repeated failed attempts.
    if (encryptedAttempted) {
      showConfirmation();
      return;
    }
    manager.io.execute(() -> {
      try {
        String number = phone;
        if (number == null) {
          String raw = original.getData().getSchemeSpecificPart();
          TelephonyManager tm = getSystemService(TelephonyManager.class);
          PhoneAccountHandle handle = original.getParcelableExtra(
              TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle.class);
          TelephonyManager selected = handle == null ? null : tm.createForPhoneAccountHandle(handle);
          String country = (selected == null ? tm : selected).getSimCountryIso();
          number = raw.startsWith("+") ? PhoneNumberUtils.normalizeNumber(raw)
              : PhoneNumberUtils.formatNumberToE164(raw, country.toUpperCase(java.util.Locale.ROOT));
        }
        if (number == null) throw new IllegalArgumentException();
        final String normalizedPhone = number;
        boolean warn = CallFallbackHistory.requiresWarning(this, normalizedPhone);
        runOnUiThread(() -> {
          if (finished || isFinishing()) return;
          phone = normalizedPhone;
          if (warn) showConfirmation();
          else cellular();
        });
      } catch (Exception e) {
        // If history cannot be checked, keep the decision with the caller.
        runOnUiThread(() -> showConfirmation());
      }
    });
  }

  private void showConfirmation() {
    if (finished || isFinishing() || (confirmation != null && confirmation.isShowing())) return;
    confirmation =
        new AlertDialog.Builder(this)
            .setMessage(failureReason + "\n\nPlace a regular cellular call? It will not use dumber.chat encryption.")
            .setTitle("Use a regular call?")
            .setPositiveButton("Use regular call", (d, w) -> cellular())
            .setNegativeButton(android.R.string.cancel, (d, w) -> onBackPressed())
            .setOnCancelListener(d -> onBackPressed())
            .show();
  }

  private void cellular() {
    if (finished) return;
    // Do not wait for the broker worker: its late result must not start another call.
    finished = true;
    dismissChecking();
    handler.removeCallbacksAndMessages(null);
    manager.cancelOutgoing();
    original.putExtra(CallManager.BYPASS, true);
    placeCellularCall(original);
    if (phone != null) CallFallbackHistory.cellularPlaced(this, phone);
    finish();
  }

  protected void placeCellularCall(Intent intent) {
    getSystemService(TelecomManager.class).placeCall(intent.getData(), intent.getExtras());
  }

  public void onBackPressed() {
    finished = true;
    dismissChecking();
    handler.removeCallbacksAndMessages(null);
    manager.cancelOutgoing();
    finish();
  }

  protected void onDestroy() {
    if (!finished) {
      finished = true;
      manager.cancelOutgoing();
    }
    handler.removeCallbacksAndMessages(null);
    dismissChecking();
    if (confirmation != null) confirmation.dismiss();
    if (accountPicker != null) accountPicker.dismiss();
    super.onDestroy();
  }
}
