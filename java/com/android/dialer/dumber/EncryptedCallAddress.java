package com.android.dialer.dumber;

import android.net.Uri;
import android.telecom.PhoneAccount;

/** An app-owned URI Telecom accepts for a managed call; never a cellular number. */
final class EncryptedCallAddress {
  static final String SCHEME = PhoneAccount.SCHEME_SIP;

  static Uri outgoing(String callId) {
    // Android's managed-call parser accepts only tel:/sip:. A URI-form address
    // keeps this on our explicitly selected VoIP account, outside SIM dialing.
    // No SIP network is used; EncryptedConnectionService owns the call/media.
    return Uri.fromParts(SCHEME, callId + "@dumber.invalid", null);
  }
}
