package com.umeng.pagesdk;

import android.app.Activity;

/**
 * apm-plugin 2.0.0 injects calls to the newer pagesdk, while apm 1.5.2 does not
 * ship it. This no-op compatibility class keeps release builds and runtime
 * safe; page monitoring is not enabled in the 1.5.2 APM configuration.
 */
public final class PageManger {
    private PageManger() {
    }

    public static void onTracePageBegin(Activity activity, String pageName) {
    }

    public static void onTracePageBegin(Activity activity, String pageName, boolean trace) {
    }

    public static void onTracePageEnd(Activity activity, String pageName) {
    }

    public static void onTracePageEnd(Activity activity, String pageName, boolean trace) {
    }
}
