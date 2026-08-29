package co.twinotify.fixture;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

public final class FixtureState {
  private static final String PREFS = "notification-action-fixture";
  private static final String REPLY_COUNT = "reply_dispatch_count";
  private static final String MARK_READ_COUNT = "mark_read_dispatch_count";
  private static final String GENERATION = "last_fixture_generation";
  private static final String STATUS = "last_terminal_status";

  private FixtureState() {}

  static synchronized int nextGeneration(Context context, String status) {
    SharedPreferences prefs = prefs(context);
    int generation = boundedIncrement(prefs.getInt(GENERATION, 0));
    if (!prefs.edit().putInt(GENERATION, generation).putString(STATUS, status).commit()) {
      throw new IllegalStateException("fixture state commit failed");
    }
    return generation;
  }

  static synchronized void recordReply(Context context) {
    increment(context, REPLY_COUNT, "reply_dispatched");
  }

  static synchronized void recordMarkRead(Context context) {
    increment(context, MARK_READ_COUNT, "mark_read_dispatched");
  }

  static synchronized void setStatus(Context context, String status) {
    if (!prefs(context).edit().putString(STATUS, status).commit()) {
      throw new IllegalStateException("fixture state commit failed");
    }
  }

  public static synchronized void reset(Context context) {
    if (!prefs(context).edit().clear().putString(STATUS, "none").commit()) {
      throw new IllegalStateException("fixture state reset failed");
    }
  }

  public static synchronized JSONObject snapshot(Context context) {
    SharedPreferences prefs = prefs(context);
    try {
      return new JSONObject()
          .put(REPLY_COUNT, bounded(prefs.getInt(REPLY_COUNT, 0)))
          .put(MARK_READ_COUNT, bounded(prefs.getInt(MARK_READ_COUNT, 0)))
          .put(GENERATION, bounded(prefs.getInt(GENERATION, 0)))
          .put(STATUS, prefs.getString(STATUS, "none"));
    } catch (JSONException impossible) {
      throw new IllegalStateException("fixture state encoding failed", impossible);
    }
  }

  private static void increment(Context context, String key, String status) {
    SharedPreferences prefs = prefs(context);
    int next = boundedIncrement(prefs.getInt(key, 0));
    if (!prefs.edit().putInt(key, next).putString(STATUS, status).commit()) {
      throw new IllegalStateException("fixture state commit failed");
    }
  }

  private static int boundedIncrement(int value) {
    return value >= 1_000_000_000 ? 1_000_000_000 : value + 1;
  }

  private static int bounded(int value) {
    return Math.max(0, Math.min(1_000_000_000, value));
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
