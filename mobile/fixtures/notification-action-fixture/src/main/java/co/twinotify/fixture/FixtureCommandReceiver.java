package co.twinotify.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Collections;
import java.util.Set;

public final class FixtureCommandReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    Set<String> keys = intent.getExtras() == null
        ? Collections.emptySet()
        : intent.getExtras().keySet();
    String fixture = intent.getStringExtra(FixtureContract.EXTRA_FIXTURE);
    String operation = intent.getStringExtra(FixtureContract.EXTRA_OPERATION);
    try {
      FixtureContract.requireValid(fixture, operation, keys);
      FixtureNotifications.execute(context.getApplicationContext(), fixture, operation);
    } catch (IllegalArgumentException ignored) {
      // Fail closed. The authenticated Twinotify bridge reports invalid commands before dispatch.
    }
  }
}
