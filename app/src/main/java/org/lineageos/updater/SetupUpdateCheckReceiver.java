/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.misc.Utils;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the setup wizard ask whether an installable update exists. The ordered broadcast ends with
 * {@link Activity#RESULT_OK} if one does, and with {@link Activity#RESULT_CANCELED} otherwise.
 *
 * The cached update list is left alone on purpose: the first regular check must still find no
 * cached list, so the user is notified if they choose not to update during setup.
 */
public class SetupUpdateCheckReceiver extends BroadcastReceiver {

    private static final String TAG = "SetupUpdateCheckReceiver";

    // Must stay below the 10 second limit for foreground broadcasts.
    private static final long TIMEOUT_MILLIS = 8000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Utils.isNetworkAvailable(context)) {
            setResultCode(Activity.RESULT_CANCELED);
            return;
        }

        final PendingResult pendingResult = goAsync();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final File list = new File(context.getCacheDir(), "setup_check_" + UUID.randomUUID());

        final Runnable timeout = () -> {
            Log.w(TAG, "Update check timed out");
            finish(pendingResult, finished, Activity.RESULT_CANCELED);
        };

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                done(Activity.RESULT_CANCELED);
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                int result = Activity.RESULT_CANCELED;
                try {
                    if (Utils.hasInstallableUpdate(list)) {
                        result = Activity.RESULT_OK;
                    }
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "Could not parse updates list", e);
                }
                done(result);
            }

            private void done(int result) {
                handler.removeCallbacks(timeout);
                //noinspection ResultOfMethodCallIgnored
                list.delete();
                finish(pendingResult, finished, result);
            }
        };

        try {
            DownloadClient downloadClient = new DownloadClient.Builder()
                    .setUrl(Utils.getServerURL(context))
                    .setDestination(list)
                    .setDownloadCallback(callback)
                    .build();
            handler.postDelayed(timeout, TIMEOUT_MILLIS);
            downloadClient.start();
        } catch (IOException e) {
            Log.e(TAG, "Could not fetch updates list", e);
            handler.removeCallbacks(timeout);
            finish(pendingResult, finished, Activity.RESULT_CANCELED);
        }
    }

    private static void finish(PendingResult pendingResult, AtomicBoolean finished, int result) {
        if (finished.compareAndSet(false, true)) {
            pendingResult.setResultCode(result);
            pendingResult.finish();
        }
    }
}
