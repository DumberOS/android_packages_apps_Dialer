package com.android.dialer.dumber;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.test.InstrumentationTestCase;
import chat.dumber.calls.CallAccount;
import chat.dumber.calls.CallEvent;
import chat.dumber.calls.CallSignal;
import chat.dumber.calls.CallSetup;
import chat.dumber.calls.ICallBroker;
import chat.dumber.calls.ICallListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.webrtc.IceCandidate;

/** Actual CallManager lifecycle, isolated from Messaging, media and real Telecom calls. */
public final class CallManagerTest extends InstrumentationTestCase {
  private CallManager manager;
  private CallManager.Active call;
  private Context context;
  private int bindAttempts;
  private final CountDownLatch releaseSend = new CountDownLatch(1);

  private abstract static class Broker extends ICallBroker.Stub {
    public int version() { return 1; }
    public CallAccount[] accounts() { return new CallAccount[0]; }
    public void subscribe(ICallListener listener) {}
    public boolean supportsCall(String account, String phone) { return true; }
    public CallSetup prepare(String account, String phone, String room) { return null; }
    public void acknowledge(String account, String event) {}
  }

  @Override protected void setUp() throws Exception {
    super.setUp();
    context = new ContextWrapper(getInstrumentation().getTargetContext()) {
      @Override public Context getApplicationContext() { return this; }
      @Override public boolean stopService(Intent intent) { return true; }
      @Override public boolean bindService(
          Intent intent, android.content.ServiceConnection connection, int flags) {
        bindAttempts++;
        return false;
      }
      @Override public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
        // Simulate being in an auxiliary process; connecting must be a no-op.
        info.processName = "another.process";
        return info;
      }
    };
    Constructor<CallManager> constructor = CallManager.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    manager = constructor.newInstance(context);
    call = new CallManager.Active();
    call.account = new CallAccount();
    call.account.id = "test-account";
    call.account.partyId = "local-party";
    call.id = "test-call";
    call.phone = "+12025550123";
    call.room = "test-room";
    call.remoteParty = "remote-party";
    call.incoming = true;
    manager.accounts = new CallAccount[] {call.account};
    field("active").set(manager, call);
  }

  private Field field(String name) throws Exception {
    Field result = CallManager.class.getDeclaredField(name);
    result.setAccessible(true);
    return result;
  }

  private ConnectionRequest request() {
    Bundle extras = new Bundle();
    extras.putString(CallManager.CALL_ID, call.id);
    return new ConnectionRequest(manager.handle(call.account.id),
        Uri.fromParts("tel", call.phone, null), extras);
  }

  private void receive(String kind, String id) throws Exception {
    CallEvent event = new CallEvent();
    event.eventId = id;
    event.accountId = call.account.id;
    event.conversationId = call.room;
    event.phone = call.phone;
    event.expiresAtMs = System.currentTimeMillis() + 60000;
    event.signal = new CallSignal();
    event.signal.kind = kind;
    event.signal.callId = call.id;
    event.signal.partyId = call.remoteParty;
    Method method = CallManager.class.getDeclaredMethod("receive", CallEvent.class);
    method.setAccessible(true);
    getInstrumentation().runOnMainSync(() -> {
      try { method.invoke(manager, event); }
      catch (Exception e) { throw new AssertionError(e); }
    });
  }

  public void testAuxiliaryProcessDoesNotSubscribe() throws Exception {
    manager.connect();
    getInstrumentation().waitForIdleSync();
    manager.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    assertEquals("Auxiliary processes must never bind to Messaging", 0, bindAttempts);
    assertFalse(field("bound").getBoolean(manager));
    assertNull(manager.broker);
  }

  public void testDuplicateAttachCannotOrphanOriginalConnection() throws Exception {
    getInstrumentation().runOnMainSync(() -> {
      Connection original = manager.attach(request(), true);
      Connection duplicate = manager.attach(request(), true);
      assertEquals(Connection.STATE_RINGING, original.getState());
      assertEquals(Connection.STATE_DISCONNECTED, duplicate.getState());
      assertSame(original, call.connection);
      // Rejecting the duplicate must not terminate the original call.
      duplicate.onReject();
      assertFalse(call.ended);
      original.onDisconnect();
      assertEquals(Connection.STATE_DISCONNECTED, original.getState());
      assertTrue(call.ended);
    });
    assertNull(field("active").get(manager));
  }

  public void testRemoteCancelClosesRingingConnection() throws Exception {
    getInstrumentation().runOnMainSync(() -> manager.attach(request(), true));
    receive("HANGUP", "cancel");
    assertTrue(call.ended);
    assertEquals(Connection.STATE_DISCONNECTED, call.connection.getState());
    assertNull(field("active").get(manager));
    // Replayed offers after cancellation cannot ring again.
    receive("INVITE", "late-offer");
    assertNull(field("active").get(manager));
  }

  public void testRemoteCancelBeforeAttachRejectsLateConnection() throws Exception {
    receive("HANGUP", "early-cancel");
    getInstrumentation().runOnMainSync(() ->
        assertEquals(Connection.STATE_DISCONNECTED, manager.attach(request(), true).getState()));
    assertNull(call.connection);
    assertTrue(call.ended);
  }

  public void testLocalRejectClosesRingingConnection() throws Exception {
    getInstrumentation().runOnMainSync(() -> {
      Connection connection = manager.attach(request(), true);
      connection.onReject();
      assertEquals(Connection.STATE_DISCONNECTED, connection.getState());
    });
    assertTrue(call.ended);
    assertNull(field("active").get(manager));
  }

  private void send(String kind) throws Exception {
    CallSignal signal = new CallSignal();
    signal.kind = kind;
    Method method = CallManager.class.getDeclaredMethod(
        "send", CallManager.Active.class, CallSignal.class, boolean.class);
    method.setAccessible(true);
    getInstrumentation().runOnMainSync(() -> {
      try { method.invoke(manager, call, signal, false); }
      catch (Exception e) { throw new AssertionError(e); }
    });
  }

  public void testCancelBypassesBlockedSendAndDiscardsObsoleteSignaling() throws Exception {
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    List<String> sent = Collections.synchronizedList(new ArrayList<>());
    List<String> cancellations = Collections.synchronizedList(new ArrayList<>());
    manager.broker = new Broker() {
      public void send(String account, String room, CallSignal signal, String transaction) {
        sent.add(signal.kind);
        if ("CANDIDATES".equals(signal.kind)) {
          sending.countDown();
          try { releaseSend.await(10, TimeUnit.SECONDS); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          // A failed in-flight request must not retry after cancellation.
          throw new IllegalStateException("Blocked candidate request failed");
        }
        if ("HANGUP".equals(signal.kind)) {
          cancellations.add(transaction);
          if (cancellations.size() == 1) throw new IllegalStateException("Retry cancellation");
          cancelled.countDown();
        }
      }
    };
    getInstrumentation().runOnMainSync(() -> manager.attach(request(), true));
    send("CANDIDATES");
    assertTrue(sending.await(2, TimeUnit.SECONDS));
    send("CANDIDATES");
    send("INVITE");
    getInstrumentation().runOnMainSync(() -> call.connection.onDisconnect());
    assertEquals(Connection.STATE_DISCONNECTED, call.connection.getState());
    assertTrue("Hangup must reach the broker while the candidate request is blocked",
        cancelled.await(2, TimeUnit.SECONDS));
    assertEquals("Cancellation retries must be idempotent", cancellations.get(0), cancellations.get(1));
    releaseSend.countDown();
    manager.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
    assertEquals(java.util.Arrays.asList("CANDIDATES", "HANGUP", "HANGUP"), sent);
  }

  public void testCandidatesAreBatchedWithinBrokerLimit() throws Exception {
    List<CallSignal> sent = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch batches = new CountDownLatch(2);
    manager.broker = new Broker() {
      public void send(String account, String room, CallSignal signal, String transaction) {
        sent.add(signal);
        batches.countDown();
      }
    };
    Method candidate = CallManager.class.getDeclaredMethod(
        "queueCandidate", CallManager.Active.class, IceCandidate.class);
    candidate.setAccessible(true);
    getInstrumentation().runOnMainSync(() -> {
      try {
        for (int i = 0; i < 33; i++)
          candidate.invoke(manager, call, new IceCandidate("audio", 0, "candidate-" + i));
      } catch (Exception e) { throw new AssertionError(e); }
    });
    assertTrue(batches.await(2, TimeUnit.SECONDS));
    assertEquals(2, sent.size());
    assertEquals(32, sent.get(0).candidates.length);
    assertEquals(1, sent.get(1).candidates.length);
    assertEquals("candidate-0", sent.get(0).candidates[0]);
    assertEquals("candidate-31", sent.get(0).candidates[31]);
    assertEquals("candidate-32", sent.get(1).candidates[0]);
    assertEquals("audio", sent.get(1).mids[0]);
    assertEquals(0, sent.get(1).lines[0]);
  }

  @Override protected void tearDown() throws Exception {
    releaseSend.countDown();
    getInstrumentation().runOnMainSync(() -> {
      manager.main.removeCallbacksAndMessages(null);
      if (call.connection != null) call.connection.destroy();
    });
    manager.io.shutdown();
    manager.io.awaitTermination(5, TimeUnit.SECONDS);
    manager.endings.shutdown();
    manager.endings.awaitTermination(5, TimeUnit.SECONDS);
    super.tearDown();
  }
}
