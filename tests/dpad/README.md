# Dialer d-pad device tests

Build with `m Dialer DialerDpadTests`. Install both APKs using the signing
certificate of the Dialer already installed on the test phone. The phone must
be unlocked, Dialer must be the default phone app, and Recents must contain a
call. Tests use the current contacts permission and keyboard configuration.

Run the explicit class to avoid the legacy runner scanning all of Dialer's
classes during startup:

```sh
adb -s SERIAL shell am instrument -w \
  -e class com.android.dialer.dpad.DpadTest \
  com.android.dialer.dpad.tests/android.test.InstrumentationTestRunner
```

The tests cover navigation between recents, search, results and the dialer actions;
skipping the touch-only digit grid; physical number keys; long-press zero; and single-tap digit entry, deletion,
search and clear after d-pad navigation. They do not place calls or write contacts.

Top-tab checks cover Up from the first recent row through search to the selected
tab, Left/Right and Center between tabs, Down back to the list, and first-tap tab
activation. Layout checks cover search/dialpad transitions and activity recreation,
including recreation while search is open.
The top-panel dialpad check verifies that its button fits within the panel,
participates in horizontal d-pad navigation, and opens on Center or the first
touch without changing the selected tab.
Favorites checks navigate up from each visible contact through Add and search to
the selected tab, then back into the grid. They first scroll the header behind
search and assert that focused Add is fully visible below the toolbar. On a phone without favorites or
suggestions they check the upward path from the empty-state action instead.

The SIM chooser check uses the real dialog with a test-only selection callback.
It verifies d-pad selection and first-tap selection of a different row without
placing a call or changing the default account.

Also check on two phones: scroll beyond the visible recent rows, expand a row
and open contact creation, call the other phone from its recent-call button,
choose the encrypted account, answer, enter an in-call keypad digit once, and
hang up. Repeat in the opposite direction and mix touch with key input.
