package org.lsposed.lspatch.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * How often this app started without the manager answering, kept until the manager can be told.
 *
 * <p>A fallback launch is invisible from both ends: the app runs its modules from the snapshot and
 * says nothing, and the manager was not running to notice. Neither side can reach the other at the
 * moment it happens -- that is the whole problem -- so the host counts the misses in its own storage
 * and hands the count over the next time it does reach the manager, as extras on the bind that
 * reached it. That is a channel both sides already have, and it costs no change to the framework
 * interface the two speak over.</p>
 */
class ModuleDeliveryLog {

    private static final String TAG = "LSPatch";

    static final String EXTRA_FALLBACKS = "fallbackLaunches";
    static final String EXTRA_LAST_FALLBACK_AT = "lastFallbackAt";

    private static final String PREFS = "lspatch-loader";
    private static final String KEY_FALLBACKS = "fallbackLaunches";
    private static final String KEY_LAST_FALLBACK_AT = "lastFallbackAt";

    private final SharedPreferences prefs;

    /** What the last bind attempt carried, so only that much is cleared when it lands. */
    private volatile int reported;

    ModuleDeliveryLog(Context context) {
        SharedPreferences opened;
        try {
            opened = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            // A host whose storage is not ready yet still has to boot; the count is diagnostics.
            Log.w(TAG, "Cannot open the loader's own preferences", t);
            opened = null;
        }
        this.prefs = opened;
    }

    /** Puts what has gone unreported onto the bind that is about to be attempted. */
    void describeTo(Intent intent) {
        if (prefs == null) return;
        int fallbacks = prefs.getInt(KEY_FALLBACKS, 0);
        reported = fallbacks;
        if (fallbacks <= 0) return;
        intent.putExtra(EXTRA_FALLBACKS, fallbacks);
        intent.putExtra(EXTRA_LAST_FALLBACK_AT, prefs.getLong(KEY_LAST_FALLBACK_AT, 0L));
    }

    void recordFallback() {
        if (prefs == null) return;
        prefs.edit()
                .putInt(KEY_FALLBACKS, prefs.getInt(KEY_FALLBACKS, 0) + 1)
                .putLong(KEY_LAST_FALLBACK_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * Called once the manager has answered -- which is also when it has been handed the count, since
     * the extras rode in on that very bind.
     *
     * <p>Only what that bind carried is forgotten. This launch may have counted a miss of its own
     * after the bind went out and before the manager finally came up, and a count nobody has been
     * told is not one to drop.</p>
     */
    void recordDelivered() {
        if (prefs == null) return;
        int handedOver = reported;
        if (handedOver <= 0) return;
        reported = 0;
        int remaining = Math.max(0, prefs.getInt(KEY_FALLBACKS, 0) - handedOver);
        var edit = prefs.edit().putInt(KEY_FALLBACKS, remaining);
        if (remaining == 0) edit.remove(KEY_FALLBACKS).remove(KEY_LAST_FALLBACK_AT);
        edit.apply();
    }
}
