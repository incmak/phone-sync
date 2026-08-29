package co.twinotify.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.List;

public final class FixtureActionReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    Uri data = intent.getData();
    if (data == null || !"twinotify-fixture".equals(data.getScheme()) || !"action".equals(data.getHost())) {
      return;
    }
    List<String> segments = data.getPathSegments();
    if (segments.size() != 2 || !segments.get(1).matches("[1-9][0-9]{0,9}")) return;
    if ("reply".equals(segments.get(0))) {
      FixtureNotifications.onReplyDispatched(context.getApplicationContext());
    } else if ("mark_read".equals(segments.get(0))) {
      FixtureNotifications.onMarkReadDispatched(context.getApplicationContext());
    }
  }
}
