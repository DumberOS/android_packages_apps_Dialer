package com.android.dialer.widget;

import android.view.KeyEvent;
import android.view.View;

/** Lets single-line editors hand vertical navigation to the surrounding UI before the IME. */
public final class DpadNavigation {
  private DpadNavigation() {}

  public static boolean moveVertically(View editor, int keyCode, KeyEvent event) {
    int direction;
    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
      direction = View.FOCUS_DOWN;
    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
      direction = View.FOCUS_UP;
    } else {
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN && editor.isInTouchMode()) {
      // Pre-IME dispatch happens before Android normally leaves touch mode. Leave it
      // before searching, otherwise keyboard-only action buttons are excluded.
      editor.requestFocusFromTouch();
    }
    View target = editor.focusSearch(direction);
    if (target == null || target == editor) {
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      return target.requestFocus(direction);
    }
    return event.getAction() == KeyEvent.ACTION_UP;
  }
}
