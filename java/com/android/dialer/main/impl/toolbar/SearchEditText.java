package com.android.dialer.main.impl.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.appcompat.widget.AppCompatEditText;

import com.android.dialer.widget.DpadNavigation;

/** Search editor whose vertical keys navigate results even with a hardware-keyboard IME. */
public final class SearchEditText extends AppCompatEditText {
  public SearchEditText(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public boolean onKeyPreIme(int keyCode, KeyEvent event) {
    return DpadNavigation.moveVertically(this, keyCode, event)
        || super.onKeyPreIme(keyCode, event);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return DpadNavigation.moveVertically(this, keyCode, event)
        || super.onKeyDown(keyCode, event);
  }
}
