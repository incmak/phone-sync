package co.twinotify.fixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Set;
import java.util.HashSet;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NotificationActionFixtureTest {
  private Context context;
  private NotificationManager notifications;

  @Before
  public void reset() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    notifications = context.getSystemService(NotificationManager.class);
    try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .executeShellCommand("pm grant co.twinotify.fixture android.permission.POST_NOTIFICATIONS")) {
      try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        while (input.read() != -1) {
          // Drain the shell command so the grant completes before posting.
        }
      }
    }
    FixtureState.reset(context);
    notifications.cancelAll();
  }

  @Test
  public void contractAcceptsOnlyFixedFixturesOperationsAndExactKeys() {
    for (String fixture : Set.of("reply", "mark_read", "auto_cancel", "persistent")) {
      for (String operation : Set.of("post", "update", "cancel", "reset_counters")) {
        FixtureContract.requireValid(fixture, operation, Set.of("fixture", "operation"));
      }
    }

    assertRejects("arbitrary", "post", Set.of("fixture", "operation"));
    assertRejects("reply", "arbitrary", Set.of("fixture", "operation"));
    assertRejects("reply", "post", Set.of("fixture", "operation", "title"));
    assertRejects("reply", "post", Set.of("fixture", "operation", "reply_text"));
  }

  @Test
  public void replyFixtureOwnsMutableFreeFormRemoteInputAndCountsDispatch() throws Exception {
    FixtureNotifications.execute(context, "reply", "post");
    Notification notification = onlyActiveNotification();
    assertEquals(1, notification.actions.length);
    Notification.Action action = notification.actions[0];
    assertNotNull(action.getRemoteInputs());
    assertEquals(1, action.getRemoteInputs().length);
    assertTrue(action.getRemoteInputs()[0].getAllowFreeFormInput());

    Intent fillIn = new Intent();
    Bundle results = new Bundle();
    results.putCharSequence("fixture_reply", "not retained");
    RemoteInput.addResultsToIntent(action.getRemoteInputs(), fillIn, results);
    action.actionIntent.send(context, 0, fillIn);

    awaitCounter("reply_dispatch_count", 1);
    awaitStatus("reply_dispatched");
    assertEquals("reply_dispatched", FixtureState.snapshot(context).getString("last_terminal_status"));
  }

  @Test
  public void markReadFixtureCountsOnceAndRemovesItsNotification() throws Exception {
    FixtureNotifications.execute(context, "mark_read", "post");
    Notification.Action action = onlyActiveNotification().actions[0];

    action.actionIntent.send();

    awaitCounter("mark_read_dispatch_count", 1);
    long removalDeadline = System.currentTimeMillis() + 2_000;
    while (notifications.getActiveNotifications().length != 0 && System.currentTimeMillis() < removalDeadline) {
      Thread.sleep(20);
    }
    assertEquals(0, notifications.getActiveNotifications().length);
  }

  @Test
  public void updateRotatesGenerationAndAutoCancelVariantsAreTruthful() throws Exception {
    FixtureNotifications.execute(context, "auto_cancel", "post");
    int initial = FixtureState.snapshot(context).getInt("last_fixture_generation");
    assertTrue((onlyActiveNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0);

    FixtureNotifications.execute(context, "auto_cancel", "update");
    assertEquals(initial + 1, FixtureState.snapshot(context).getInt("last_fixture_generation"));

    FixtureNotifications.execute(context, "persistent", "post");
    assertFalse((onlyActiveNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0);
  }

  @Test
  public void stateProviderReturnsOnlySanitizedCounters() throws Exception {
    FixtureNotifications.execute(context, "reply", "post");
    try (Cursor cursor = context.getContentResolver().query(
        FixtureStateProvider.STATE_URI, null, null, null, null)) {
      assertNotNull(cursor);
      assertTrue(cursor.moveToFirst());
      JSONObject state = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("state_json")));
      assertEquals(
          Set.of("reply_dispatch_count", "mark_read_dispatch_count", "last_fixture_generation", "last_terminal_status"),
          jsonKeys(state));
      String serialized = state.toString();
      assertFalse(serialized.contains("title"));
      assertFalse(serialized.contains("text"));
      assertFalse(serialized.contains("reply_text"));
      assertFalse(serialized.contains("payload"));
    }
  }

  private Notification onlyActiveNotification() {
    assertEquals(1, notifications.getActiveNotifications().length);
    return notifications.getActiveNotifications()[0].getNotification();
  }

  private void awaitCounter(String key, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 2_000;
    while (FixtureState.snapshot(context).getInt(key) != expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(expected, FixtureState.snapshot(context).getInt(key));
  }

  private void awaitStatus(String expected) throws Exception {
    long deadline = System.currentTimeMillis() + 2_000;
    while (!expected.equals(FixtureState.snapshot(context).getString("last_terminal_status"))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(expected, FixtureState.snapshot(context).getString("last_terminal_status"));
  }

  private static void assertRejects(String fixture, String operation, Set<String> keys) {
    IllegalArgumentException error = null;
    try {
      FixtureContract.requireValid(fixture, operation, keys);
    } catch (IllegalArgumentException caught) {
      error = caught;
    }
    assertNotNull(error);
  }

  private static Set<String> jsonKeys(JSONObject value) {
    Set<String> keys = new HashSet<>();
    value.keys().forEachRemaining(keys::add);
    return keys;
  }
}
