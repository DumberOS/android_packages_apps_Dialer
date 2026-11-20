package com.android.dialer.dpad;

import android.app.Instrumentation.ActivityMonitor;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.android.contacts.common.widget.SelectPhoneAccountDialogFragment;
import com.android.contacts.common.widget.SelectPhoneAccountDialogOptionsUtil;
import com.android.dialer.R;
import com.android.dialer.main.impl.MainActivity;
import com.android.dialer.speeddial.FavoritesViewHolder;
import com.android.dialer.speeddial.SuggestionViewHolder;

import java.util.ArrayList;
import java.util.List;

/** Device regressions for mixed touch/key input. Does not place calls or modify contacts. */
public final class DpadTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public DpadTest() {
    super(MainActivity.class);
  }

  private MainActivity openDialpad() {
    setActivityIntent(new Intent(Intent.ACTION_DIAL));
    MainActivity activity = getActivity();
    ready(activity, R.id.digits);
    return activity;
  }

  private void ready(MainActivity activity, int targetId) {
    long deadline = SystemClock.uptimeMillis() + 10000;
    boolean[] ready = new boolean[1];
    do {
      getInstrumentation().runOnMainSync(() -> {
        View view = activity.findViewById(targetId);
        ready[0] = activity.hasWindowFocus() && view != null && view.isShown()
            && view.getWidth() > 0 && view.getHeight() > 0;
      });
      if (ready[0]) {
        settle();
        return;
      }
      SystemClock.sleep(100);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("Dialer did not show its test UI; unlock the phone before running tests");
  }

  private void settle() {
    getInstrumentation().waitForIdleSync();
    SystemClock.sleep(500); // Finish fragment and focus animations before injecting input.
    getInstrumentation().waitForIdleSync();
  }

  private void key(int code) {
    getInstrumentation().sendKeyDownUpSync(code);
    settle();
  }

  private void focus(View view) {
    assertNotNull(view);
    boolean[] focused = new boolean[1];
    getInstrumentation().runOnMainSync(() -> focused[0] = view.requestFocus());
    assertTrue(focused[0]);
    settle();
  }

  private void tap(View view) {
    int[] location = new int[2];
    getInstrumentation().runOnMainSync(() -> view.getLocationOnScreen(location));
    float x = location[0] + view.getWidth() / 2f;
    float y = location[1] + view.getHeight() / 2f;
    long now = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
    getInstrumentation().sendPointerSync(down);
    getInstrumentation().sendPointerSync(up);
    down.recycle();
    up.recycle();
    settle();
  }

  private String digits(MainActivity activity) {
    return ((TextView) activity.findViewById(R.id.digits)).getText().toString();
  }

  private int screenTop(View view) {
    int[] location = new int[2];
    getInstrumentation().runOnMainSync(() -> view.getLocationOnScreen(location));
    return location[1];
  }

  private void assertTopTabsLayout(MainActivity activity) {
    View tabs = activity.findViewById(R.id.bottom_nav_bar);
    View toolbar = activity.findViewById(R.id.toolbar);
    View content = activity.findViewById(R.id.coordinator_layout);
    View root = activity.findViewById(R.id.root_layout);
    assertTrue(tabs.isShown());
    assertEquals(screenTop(root), screenTop(tabs));
    assertEquals(screenTop(tabs) + tabs.getHeight(), screenTop(toolbar));
    assertEquals(screenTop(toolbar) + toolbar.getHeight(), screenTop(content));
    assertEquals(screenTop(root) + root.getHeight(), screenTop(content) + content.getHeight());
  }

  private MainActivity recreate(MainActivity activity, int readyId) {
    ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
    getInstrumentation().runOnMainSync(activity::recreate);
    MainActivity recreated = (MainActivity) monitor.waitForActivityWithTimeout(10000);
    getInstrumentation().removeMonitor(monitor);
    assertNotNull("Activity did not recreate", recreated);
    setActivity(recreated);
    ready(recreated, readyId);
    return recreated;
  }

  public void testTopTabsNavigationAndFirstTouch() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 1));
    MainActivity activity = getActivity();
    ready(activity, R.id.primary_action_view);
    assertTopTabsLayout(activity);
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(activity.findViewById(R.id.primary_action_view));
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.call_log_tab, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_LEFT);
    assertEquals(R.id.speed_dial_tab, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_LEFT);
    assertEquals(R.id.speed_dial_tab, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_CENTER);
    assertTrue(activity.findViewById(R.id.speed_dial_tab).isSelected());
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    assertEquals(R.id.contacts_tab, activity.getCurrentFocus().getId());
    if (!activity.findViewById(R.id.voicemail_tab).isShown()) {
      key(KeyEvent.KEYCODE_DPAD_RIGHT);
      assertEquals(R.id.fab, activity.getCurrentFocus().getId());
      key(KeyEvent.KEYCODE_DPAD_RIGHT);
      assertEquals(R.id.fab, activity.getCurrentFocus().getId());
      key(KeyEvent.KEYCODE_DPAD_LEFT);
      assertEquals(R.id.contacts_tab, activity.getCurrentFocus().getId());
    }
    key(KeyEvent.KEYCODE_DPAD_CENTER);
    assertTrue(activity.findViewById(R.id.contacts_tab).isSelected());
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.contacts_tab, activity.getCurrentFocus().getId());
    tap(activity.findViewById(R.id.call_log_tab));
    assertTrue("A tab must activate on the first touch", activity.findViewById(R.id.call_log_tab).isSelected());
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(activity.findViewById(R.id.call_log_tab));
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.primary_action_view, activity.getCurrentFocus().getId());
  }

  public void testFavoritesUpReachesSearchAndSelectedTab() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 0));
    MainActivity activity = getActivity();
    ready(activity, R.id.speed_dial_tab);
    RecyclerView recycler = activity.findViewById(R.id.speed_dial_recycler_view);
    View add = activity.findViewById(R.id.speed_dial_add_button);
    key(KeyEvent.KEYCODE_DPAD_UP);
    if (add != null && add.isShown()) {
      List<View> contacts = new ArrayList<>();
      getInstrumentation().runOnMainSync(() -> {
        for (int i = 0; i < recycler.getChildCount(); i++) {
          View child = recycler.getChildAt(i);
          RecyclerView.ViewHolder holder = recycler.getChildViewHolder(child);
          if (holder instanceof FavoritesViewHolder || holder instanceof SuggestionViewHolder) {
            contacts.add(child);
          }
        }
      });
      assertFalse("Populated Favorites must expose contact rows", contacts.isEmpty());
      for (View contact : contacts) {
        focus(contact);
        // Reproduce a list scrolled under the toolbar before returning to its header.
        View toolbar = activity.findViewById(R.id.toolbar);
        getInstrumentation().runOnMainSync(() -> recycler.scrollBy(0, toolbar.getHeight() * 2));
        settle();
        int limit = recycler.getAdapter().getItemCount() + 2;
        for (int step = 0; step < limit
            && activity.getCurrentFocus() != add; step++) {
          key(KeyEvent.KEYCODE_DPAD_UP);
        }
        assertSame("Up from a contact must reach Add", add, activity.getCurrentFocus());
        assertTrue("Focused Add must stay below the search toolbar",
            screenTop(add) >= screenTop(toolbar) + toolbar.getHeight());
        Rect visible = new Rect();
        assertTrue("Focused Add must be visible", add.getGlobalVisibleRect(visible));
        assertEquals("The whole Add button and focus frame must be visible",
            add.getHeight(), visible.height());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("Up from Add must leave the grid", R.id.search_box_start_search,
            activity.getCurrentFocus().getId());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(R.id.speed_dial_tab, activity.getCurrentFocus().getId());
      }
      // Down can restore the last contact or enter via Add; either path must reach the grid.
      key(KeyEvent.KEYCODE_DPAD_DOWN);
      assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
      key(KeyEvent.KEYCODE_DPAD_DOWN);
      if (activity.getCurrentFocus() == add) {
        key(KeyEvent.KEYCODE_DPAD_DOWN);
      }
      assertNotNull(recycler.findContainingItemView(activity.getCurrentFocus()));
      assertNotSame(add, activity.getCurrentFocus());
      // Enter a scrolled list using keys as well as requesting contact focus directly.
      focus(activity.findViewById(R.id.search_box_start_search));
      getInstrumentation().runOnMainSync(() -> recycler.scrollBy(0, recycler.getHeight()));
      settle();
      key(KeyEvent.KEYCODE_DPAD_DOWN);
      assertNotNull(recycler.findContainingItemView(activity.getCurrentFocus()));
      key(KeyEvent.KEYCODE_DPAD_UP);
      assertSame(add, activity.getCurrentFocus());
      View toolbar = activity.findViewById(R.id.toolbar);
      assertTrue("Add must be uncovered after returning from a scrolled contact",
          screenTop(add) >= screenTop(toolbar) + toolbar.getHeight());
      Rect visible = new Rect();
      assertTrue(add.getGlobalVisibleRect(visible));
      assertEquals(add.getHeight(), visible.height());
    } else {
      View empty = activity.findViewById(R.id.speed_dial_empty_content_view);
      assertTrue("Favorites must have loaded its empty state", empty.isShown());
      focus(empty.findViewById(R.id.empty_list_view_action));
      key(KeyEvent.KEYCODE_DPAD_UP);
      assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
      key(KeyEvent.KEYCODE_DPAD_UP);
      assertEquals(R.id.speed_dial_tab, activity.getCurrentFocus().getId());
    }
  }

  public void testTopTabsHideAndRestoreAroundSearchAndDialpad() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 1));
    MainActivity activity = getActivity();
    ready(activity, R.id.primary_action_view);
    tap(activity.findViewById(R.id.search_box_start_search));
    assertEquals(View.GONE, activity.findViewById(R.id.bottom_nav_bar).getVisibility());
    assertEquals(screenTop(activity.findViewById(R.id.root_layout)),
        screenTop(activity.findViewById(R.id.toolbar)));
    tap(activity.findViewById(R.id.search_back_button));
    assertTopTabsLayout(activity);
    tap(activity.findViewById(R.id.fab));
    assertEquals(View.GONE, activity.findViewById(R.id.bottom_nav_bar).getVisibility());
    assertEquals(screenTop(activity.findViewById(R.id.root_layout)),
        screenTop(activity.findViewById(R.id.coordinator_layout)));
    key(KeyEvent.KEYCODE_BACK);
    assertTopTabsLayout(activity);
  }

  public void testTopPanelDialpadKeyboardAndFirstTouch() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 1));
    MainActivity activity = getActivity();
    ready(activity, R.id.fab);
    View panel = activity.findViewById(R.id.bottom_nav_bar);
    View dialpad = activity.findViewById(R.id.fab);
    assertSame(dialpad, panel.findViewById(R.id.fab));
    Rect panelBounds = new Rect();
    Rect buttonBounds = new Rect();
    assertTrue(panel.getGlobalVisibleRect(panelBounds));
    assertTrue(dialpad.getGlobalVisibleRect(buttonBounds));
    assertTrue("Dialpad button must fit inside the top panel", panelBounds.contains(buttonBounds));
    assertEquals(dialpad.getWidth(), buttonBounds.width());
    assertEquals(dialpad.getHeight(), buttonBounds.height());
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(activity.findViewById(R.id.contacts_tab));
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    if (activity.findViewById(R.id.voicemail_tab).isShown()) {
      assertEquals(R.id.voicemail_tab, activity.getCurrentFocus().getId());
      key(KeyEvent.KEYCODE_DPAD_RIGHT);
    }
    assertSame(dialpad, activity.getCurrentFocus());
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.call_log_tab, activity.getCurrentFocus().getId());
    focus(dialpad);
    key(KeyEvent.KEYCODE_DPAD_CENTER);
    assertTrue(activity.findViewById(R.id.digits).isShown());
    assertFalse(panel.isShown());
    key(KeyEvent.KEYCODE_BACK);
    assertTopTabsLayout(activity);
    assertTrue(dialpad.isShown());
    assertTrue(activity.findViewById(R.id.call_log_tab).isSelected());
    focus(activity.findViewById(R.id.contacts_tab));
    tap(dialpad);
    assertTrue("First tap must open the dialpad", activity.findViewById(R.id.digits).isShown());
    key(KeyEvent.KEYCODE_BACK);
    assertTopTabsLayout(activity);
  }

  public void testTopTabsRestoreAfterRecreation() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 2));
    MainActivity activity = getActivity();
    ready(activity, R.id.contacts_tab);
    activity = recreate(activity, R.id.contacts_tab);
    assertTopTabsLayout(activity);
    assertTrue(activity.findViewById(R.id.contacts_tab).isSelected());
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(activity.findViewById(R.id.search_box_start_search));
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.contacts_tab, activity.getCurrentFocus().getId());
    tap(activity.findViewById(R.id.search_box_start_search));
    activity = recreate(activity, R.id.search_view);
    assertEquals(View.GONE, activity.findViewById(R.id.bottom_nav_bar).getVisibility());
    assertEquals(screenTop(activity.findViewById(R.id.root_layout)),
        screenTop(activity.findViewById(R.id.toolbar)));
    tap(activity.findViewById(R.id.search_back_button));
    assertTopTabsLayout(activity);
  }

  public void testFirstTouchAfterDpadEntersOneDigitAndDeletesOnce() {
    MainActivity activity = openDialpad();
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    focus(activity.findViewById(R.id.dialpad_call));
    key(KeyEvent.KEYCODE_2);
    assertEquals("2", digits(activity));
    View nine = activity.findViewById(R.id.nine);
    assertFalse(nine.isFocusableInTouchMode());
    tap(nine);
    assertEquals("29", digits(activity));
    tap(activity.findViewById(R.id.deleteButton));
    assertEquals("2", digits(activity));
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.dialpad_call, activity.getCurrentFocus().getId());
  }

  public void testPhysicalDigitsKeepActionFocusAndLongZeroProducesPlus() {
    MainActivity activity = openDialpad();
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    View call = activity.findViewById(R.id.dialpad_call);
    focus(call);
    key(KeyEvent.KEYCODE_6);
    assertEquals("6", digits(activity));
    assertSame(call, activity.getCurrentFocus());
    key(KeyEvent.KEYCODE_DEL);
    assertEquals("", digits(activity));
    focus(call);
    long now = SystemClock.uptimeMillis();
    getInstrumentation().sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
        KeyEvent.KEYCODE_0, 0));
    SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 200);
    getInstrumentation().sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
        KeyEvent.KEYCODE_0, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS));
    getInstrumentation().sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
        KeyEvent.KEYCODE_0, 0));
    settle();
    assertEquals("+", digits(activity));
  }

  public void testDpadSkipsTouchDigitGrid() {
    MainActivity activity = openDialpad();
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertEquals(R.id.dialpad_call, activity.getCurrentFocus().getId());
    assertFalse(activity.findViewById(R.id.two).isFocusable());
    key(KeyEvent.KEYCODE_2);
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    assertEquals(R.id.deleteButton, activity.getCurrentFocus().getId());
  }

  public void testRecentsToSearchAndResults() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 1));
    MainActivity activity = getActivity();
    ready(activity, R.id.primary_action_view);
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(activity.findViewById(R.id.primary_action_view));
    key(KeyEvent.KEYCODE_DPAD_UP);
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    key(KeyEvent.KEYCODE_DPAD_CENTER);
    assertEquals(R.id.search_view, activity.getCurrentFocus().getId());
    getInstrumentation().sendStringSync("123456789");
    settle();
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertFalse("Down must leave the search editor", activity.getCurrentFocus().getId() == R.id.search_view);
  }

  public void testContactListSearchAndSingleTapClear() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 2));
    MainActivity activity = getActivity();
    ready(activity, R.id.contacts_tab);
    View addContact = activity.findViewById(R.id.click_target);
    if (addContact == null || !addContact.isShown()) {
      addContact = activity.findViewById(R.id.empty_list_view_action);
    }
    ready(activity, addContact.getId());
    key(KeyEvent.KEYCODE_DPAD_UP);
    focus(addContact);
    for (int step = 0; step < 4
        && activity.getCurrentFocus().getId() != R.id.search_box_start_search; step++) {
      key(KeyEvent.KEYCODE_DPAD_UP);
    }
    assertEquals(R.id.search_box_start_search, activity.getCurrentFocus().getId());
    // One tap must open search even when keyboard focus was on another control.
    focus(activity.findViewById(R.id.main_options_menu_button));
    tap(activity.findViewById(R.id.search_box_start_search));
    assertEquals(R.id.search_view, activity.getCurrentFocus().getId());
    getInstrumentation().sendStringSync("123456789");
    settle();
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    assertFalse(activity.getCurrentFocus().getId() == R.id.search_view);
    tap(activity.findViewById(R.id.search_clear_button));
    assertEquals("", ((TextView) activity.findViewById(R.id.search_view)).getText().toString());
    tap(activity.findViewById(R.id.search_back_button));
    assertTrue(activity.findViewById(R.id.contacts_tab).isShown());
  }

  public void testRecentCallHorizontalNavigationStaysInItsRow() {
    setActivityIntent(new Intent("ACTION_SHOW_TAB").putExtra("EXTRA_SHOW_TAB", 1));
    MainActivity activity = getActivity();
    ready(activity, R.id.primary_action_view);
    View row = activity.findViewById(R.id.primary_action_view);
    assertNotNull("Test phone needs a recent call", row);
    View call = row.findViewById(R.id.primary_action_button);
    View contact = row.findViewById(R.id.quick_contact_photo);
    key(KeyEvent.KEYCODE_DPAD_DOWN);
    focus(row);
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    assertSame(call, activity.getCurrentFocus());
    key(KeyEvent.KEYCODE_DPAD_LEFT);
    assertSame(row, activity.getCurrentFocus());
    key(KeyEvent.KEYCODE_DPAD_LEFT);
    assertSame(contact, activity.getCurrentFocus());
    key(KeyEvent.KEYCODE_DPAD_RIGHT);
    assertSame(row, activity.getCurrentFocus());
    assertFalse(call.isFocusableInTouchMode());
  }

  public void testSimChooserKeyboardAndFirstTouch() {
    MainActivity activity = getActivity();
    ready(activity, R.id.search_box_start_search);
    List<PhoneAccountHandle> accounts =
        activity.getSystemService(TelecomManager.class).getCallCapablePhoneAccounts();
    assertFalse("SIM chooser test needs a phone account", accounts.isEmpty());
    List<PhoneAccountHandle> choices = new ArrayList<>();
    choices.add(accounts.get(0));
    choices.add(accounts.get(accounts.size() - 1));
    for (boolean useTouch : new boolean[] {false, true}) {
      PhoneAccountHandle[] selected = new PhoneAccountHandle[1];
      int[] selectionCount = new int[1];
      SelectPhoneAccountDialogFragment dialogFragment =
          SelectPhoneAccountDialogFragment.newInstance(
              SelectPhoneAccountDialogOptionsUtil.builderWithAccounts(choices)
                  .setTitle(R.string.pre_call_select_phone_account)
                  .build(),
              new SelectPhoneAccountDialogFragment.SelectPhoneAccountListener() {
                @Override
                public void onPhoneAccountSelected(
                    PhoneAccountHandle account, boolean setDefault, String callId) {
                  selected[0] = account;
                  selectionCount[0]++;
                  // Deliberately do not place a call or persist an account preference.
                }
              });
      getInstrumentation().runOnMainSync(() ->
          dialogFragment.showNow(activity.getSupportFragmentManager(), "sim-chooser-test"));
      settle();
      AlertDialog dialog = (AlertDialog) dialogFragment.getDialog();
      assertNotNull(dialog);
      ListView list = dialog.getListView();
      key(KeyEvent.KEYCODE_DPAD_UP);
      focus(list);
      getInstrumentation().runOnMainSync(() -> list.setSelection(0));
      settle();
      key(KeyEvent.KEYCODE_DPAD_DOWN);
      assertEquals(1, list.getSelectedItemPosition());
      assertFalse(list.getChildAt(0).isFocusableInTouchMode());
      if (useTouch) {
        // Touch a different option from the one highlighted by the d-pad.
        tap(list.getChildAt(0));
      } else {
        key(KeyEvent.KEYCODE_DPAD_CENTER);
      }
      assertEquals(choices.get(useTouch ? 0 : 1), selected[0]);
      assertEquals("Selection must activate exactly once", 1, selectionCount[0]);
      assertFalse(dialog.isShowing());
    }
  }
}
