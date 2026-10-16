/* Disposable APK coexistence fixture. Its exported API accepts only fixed test values. */
package com.example.materialfiles.coexistence.fixture;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

public class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        try {
            if ("selfTest".equals(method)) return reply(selfTest(argument));
            if ("readState".equals(method)) return reply(state());
            throw new IllegalArgumentException("Unknown fixture method");
        } catch (Exception exception) {
            Bundle result = new Bundle();
            result.putString("status", "failed");
            result.putString("error", exception.toString());
            return result;
        }
    }

    private JSONObject selfTest(String argument) throws Exception {
        Context context = getContext();
        require(context != null, "Missing provider context");
        // The resource table and code package stay unchanged when only the Manifest is edited.
        int messageId = context.getResources().getIdentifier(
                "fixture_message", "string", BuildConfig.APPLICATION_ID);
        require(messageId == R.string.fixture_message, "Resource package or ID changed");
        require("单体 APK 资源与组件验证".equals(context.getString(messageId)), "Resource text changed");
        require(context.getResources().getDrawable(R.drawable.ic_fixture, null).getIntrinsicWidth() > 0,
                "Fixture icon is missing");

        PackageManager manager = context.getPackageManager();
        require(manager.getPackageInfo(context.getPackageName(), 0).getLongVersionCode() == 17,
                "Version metadata changed");
        String permissionName = context.getPackageName() + ".permission.FIXTURE";
        PermissionInfo permission = manager.getPermissionInfo(permissionName, 0);
        require(context.getPackageName().equals(permission.packageName), "Permission owner mismatch");
        require(manager.checkPermission(permissionName, context.getPackageName())
                == PackageManager.PERMISSION_GRANTED, "Custom normal permission was not granted");
        ProviderInfo provider = manager.getProviderInfo(new ComponentName(context, FixtureProvider.class), 0);
        String authority = context.getPackageName() + ".provider";
        require(authority.equals(provider.authority), "Provider authority mismatch");
        require(BuildConfig.AUTHORITY.equals(context.getString(R.string.fixture_authority)),
                "Original resource payload changed");
        ResolveInfo activity = manager.resolveActivity(
                new Intent(BuildConfig.SELF_TEST_ACTION).setPackage(context.getPackageName()), 0);
        require(activity != null && activity.activityInfo != null
                && context.getPackageName().equals(activity.activityInfo.packageName),
                "Custom activity action did not resolve");

        String value = fixedValue(argument);
        Uri uri = new Uri.Builder().scheme("content").authority(authority).appendPath("data").build();
        ContentValues values = new ContentValues();
        values.put("value", value);
        require(uri.equals(context.getContentResolver().insert(uri, values)), "Provider insert failed");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            require(cursor != null && cursor.moveToFirst()
                    && value.equals(cursor.getString(cursor.getColumnIndexOrThrow("value")))
                    && !cursor.moveToNext(), "Provider query did not return the written value");
        }
        return state().put("authority", provider.authority).put("permission", permission.name)
                .put("action", BuildConfig.SELF_TEST_ACTION).put("resourceId", messageId);
    }

    private JSONObject state() throws Exception {
        return new JSONObject().put("status", "ok").put("package", getContext().getPackageName())
                .put("value", preferences().getString("value", ""))
                .put("activityStarts", preferences().getInt("activityStarts", 0))
                .put("activityResource", preferences().getString("activityResource", ""));
    }

    private static Bundle reply(JSONObject json) {
        Bundle result = new Bundle();
        result.putString("status", json.optString("status"));
        result.putString("json", json.toString());
        return result;
    }

    private static String fixedValue(String argument) {
        if (argument == null || "activity".equals(argument)) return "activity-value";
        if ("original".equals(argument)) return "original-value";
        if ("clone".equals(argument)) return "clone-value";
        throw new IllegalArgumentException("Only fixed fixture values are supported");
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences("fixture", Context.MODE_PRIVATE);
    }

    private void checkUri(Uri uri) {
        if (!(getContext().getPackageName() + ".provider").equals(uri.getAuthority())
                || !"/data".equals(uri.getPath())) {
            throw new IllegalArgumentException("Unexpected fixture URI");
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkUri(uri);
        String value = values.getAsString("value");
        if (!"activity-value".equals(value) && !"original-value".equals(value) && !"clone-value".equals(value)) {
            throw new IllegalArgumentException("Only fixed fixture data may be written");
        }
        require(preferences().edit().putString("value", value).commit(), "Could not persist fixture value");
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        checkUri(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{"value"});
        cursor.addRow(new Object[]{preferences().getString("value", "")});
        return cursor;
    }

    @Override public String getType(Uri uri) { checkUri(uri); return "vnd.android.cursor.item/fixture"; }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
