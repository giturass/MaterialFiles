/* Disposable APK coexistence fixture. No production keys or user data. */
package com.example.materialfiles.coexistence.fixture;

public final class BuildConfig {
    public static final String APPLICATION_ID = "com.example.materialfiles.coexistence.fixture";
    public static final String AUTHORITY = "com.example.materialfiles.coexistence.fixture.provider";
    public static final String PERMISSION = "com.example.materialfiles.coexistence.fixture.permission.FIXTURE";
    public static final String SELF_TEST_ACTION = "com.example.materialfiles.coexistence.fixture.action.SELF_TEST";
    public static final String CONTENT_URI = "content://com.example.materialfiles.coexistence.fixture.provider/data";

    private BuildConfig() {}
}
