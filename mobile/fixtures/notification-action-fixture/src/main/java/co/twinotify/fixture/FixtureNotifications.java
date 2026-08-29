package co.twinotify.fixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.Set;

public final class FixtureNotifications {
  private static final String CHANNEL_ID = "twinotify_action_fixture";
  private static final String REPLY_INPUT_KEY = "fixture_reply";

  private FixtureNotifications() {}

  public static void execute(Context context, String fixture, String operation) {
    FixtureContract.requireValid(fixture, operation, Set.of("fixture", "operation"));
    switch (operation) {
      case "post":
        cancelAllExcept(context, fixture);
        post(context, fixture, "posted");
        return;
      case "update":
        post(context, fixture, "updated");
        return;
      case "cancel":
        manager(context).cancel(tag(fixture), id(fixture));
        FixtureState.setStatus(context, "cancelled");
        return;
      case "reset_counters":
        cancelAll(context);
        FixtureState.reset(context);
        FixtureState.setStatus(context, "counters_reset");
        return;
      default:
        throw new IllegalArgumentException("operation is not allowlisted");
    }
  }

  static void onReplyDispatched(Context context) {
    FixtureState.recordReply(context);
    post(context, "reply", "updated");
    FixtureState.setStatus(context, "reply_dispatched");
  }

  static void onMarkReadDispatched(Context context) {
    FixtureState.recordMarkRead(context);
    manager(context).cancel(tag("mark_read"), id("mark_read"));
  }

  private static void post(Context context, String fixture, String status) {
    createChannel(context);
    int generation = FixtureState.nextGeneration(context, status);
    Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title(fixture))
        .setContentText("Twinotify verification generation " + generation)
        .setOnlyAlertOnce(true)
        .setShowWhen(true)
        .setContentIntent(contentIntent(context, fixture, generation))
        .setAutoCancel(fixture.equals("auto_cancel"));

    if (fixture.equals("reply")) {
      builder.setCategory(Notification.CATEGORY_MESSAGE).addAction(replyAction(context, generation));
    } else if (fixture.equals("mark_read")) {
      builder.setCategory(Notification.CATEGORY_MESSAGE).addAction(markReadAction(context, generation));
    }

    manager(context).notify(tag(fixture), id(fixture), builder.build());
  }

  private static Notification.Action replyAction(Context context, int generation) {
    Intent action = new Intent(context, FixtureActionReceiver.class)
        .setData(Uri.parse("twinotify-fixture://action/reply/" + generation));
    PendingIntent pending = PendingIntent.getBroadcast(
        context,
        1001,
        action,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    RemoteInput input = new RemoteInput.Builder(REPLY_INPUT_KEY)
        .setLabel("Message")
        .setAllowFreeFormInput(true)
        .build();
    return new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pending)
        .addRemoteInput(input)
        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
        .build();
  }

  private static Notification.Action markReadAction(Context context, int generation) {
    Intent action = new Intent(context, FixtureActionReceiver.class)
        .setData(Uri.parse("twinotify-fixture://action/mark_read/" + generation));
    PendingIntent pending = PendingIntent.getBroadcast(
        context,
        1002,
        action,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Mark as read", pending)
        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .build();
  }

  private static PendingIntent contentIntent(Context context, String fixture, int generation) {
    Intent intent = new Intent(Intent.ACTION_VIEW)
        .setComponent(new ComponentName(context, FixtureActivity.class))
        .setData(Uri.parse("twinotify-fixture://open/" + fixture + "/" + generation));
    return PendingIntent.getActivity(
        context,
        2000 + id(fixture),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static void createChannel(Context context) {
    manager(context).createNotificationChannel(new NotificationChannel(
        CHANNEL_ID,
        "Twinotify action verification",
        NotificationManager.IMPORTANCE_DEFAULT));
  }

  private static void cancelAll(Context context) {
    for (String fixture : FixtureContract.FIXTURES) {
      manager(context).cancel(tag(fixture), id(fixture));
    }
  }

  private static void cancelAllExcept(Context context, String retainedFixture) {
    for (String fixture : FixtureContract.FIXTURES) {
      if (!fixture.equals(retainedFixture)) {
        manager(context).cancel(tag(fixture), id(fixture));
      }
    }
  }

  private static NotificationManager manager(Context context) {
    return context.getSystemService(NotificationManager.class);
  }

  private static String tag(String fixture) {
    return "twinotify-fixture-" + fixture;
  }

  private static int id(String fixture) {
    switch (fixture) {
      case "reply": return 7001;
      case "mark_read": return 7002;
      case "auto_cancel": return 7003;
      case "persistent": return 7004;
      default: throw new IllegalArgumentException("fixture is not allowlisted");
    }
  }

  private static String title(String fixture) {
    switch (fixture) {
      case "reply": return "Reply verification";
      case "mark_read": return "Mark-read verification";
      case "auto_cancel": return "Auto-cancel verification";
      case "persistent": return "Persistent verification";
      default: throw new IllegalArgumentException("fixture is not allowlisted");
    }
  }
}
