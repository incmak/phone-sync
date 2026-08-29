package co.twinotify.fixture;

import java.util.Set;

public final class FixtureContract {
  static final String EXTRA_FIXTURE = "fixture";
  static final String EXTRA_OPERATION = "operation";
  static final Set<String> FIXTURES = Set.of("reply", "mark_read", "auto_cancel", "persistent");
  static final Set<String> OPERATIONS = Set.of("post", "update", "cancel", "reset_counters");
  private static final Set<String> KEYS = Set.of(EXTRA_FIXTURE, EXTRA_OPERATION);

  private FixtureContract() {}

  public static void requireValid(String fixture, String operation, Set<String> keys) {
    if (!FIXTURES.contains(fixture) || !OPERATIONS.contains(operation) || !KEYS.equals(keys)) {
      throw new IllegalArgumentException("fixture command is not in the closed contract");
    }
  }
}
