package co.twinotify.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class FixtureActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    TextView text = new TextView(this);
    text.setGravity(Gravity.CENTER);
    text.setText(R.string.fixture_opened);
    text.setTextSize(20);
    text.setPadding(48, 48, 48, 48);
    setContentView(text);
  }
}
