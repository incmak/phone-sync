package co.twinotify.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class FixtureStateProvider extends ContentProvider {
  public static final Uri STATE_URI = Uri.parse("content://co.twinotify.fixture.e2e/state");

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    if (!STATE_URI.equals(uri)) throw new IllegalArgumentException("unsupported fixture state URI");
    MatrixCursor cursor = new MatrixCursor(new String[] {"state_json"}, 1);
    cursor.addRow(new Object[] {FixtureState.snapshot(attachedContext()).toString()});
    return cursor;
  }

  private android.content.Context attachedContext() {
    if (getContext() == null) throw new IllegalStateException("fixture provider is detached");
    return getContext();
  }

  @Override public String getType(Uri uri) { return "application/json"; }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
  @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
}
