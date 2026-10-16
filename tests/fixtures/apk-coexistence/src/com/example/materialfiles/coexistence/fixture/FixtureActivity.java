/* Disposable APK coexistence fixture. No production keys or user data. */
package com.example.materialfiles.coexistence.fixture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

public final class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_fixture);
        TextView status = findViewById(R.id.self_test_status);
        Bundle result = getContentResolver().call(
                new Uri.Builder().scheme("content").authority(getPackageName() + ".provider")
                        .appendPath("data").build(), "selfTest", "activity", null);
        String value = result == null ? "missing result" : result.getString("status");
        status.setText(getString(R.string.status_format, value));
        if (!"ok".equals(value)) throw new IllegalStateException("Fixture self-test failed");
        SharedPreferences preferences = getSharedPreferences("fixture", MODE_PRIVATE);
        if (!preferences.edit()
                .putInt("activityStarts", preferences.getInt("activityStarts", 0) + 1)
                .putString("activityResource", getString(R.string.fixture_message)).commit()) {
            throw new IllegalStateException("Could not record fixture activity startup");
        }
    }
}
