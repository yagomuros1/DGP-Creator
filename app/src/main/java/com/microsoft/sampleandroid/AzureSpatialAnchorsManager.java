// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.microsoft.sampleandroid;

import android.util.Log;

import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.microsoft.azure.spatialanchors.AnchorLocateCriteria;
import com.microsoft.azure.spatialanchors.AnchorLocatedListener;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchor;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchorSession;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchorWatcher;
import com.microsoft.azure.spatialanchors.LocateAnchorsCompletedListener;
import com.microsoft.azure.spatialanchors.OnLogDebugEvent;
import com.microsoft.azure.spatialanchors.SessionErrorEvent;
import com.microsoft.azure.spatialanchors.SessionLogLevel;
import com.microsoft.azure.spatialanchors.SessionUpdatedListener;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class AzureSpatialAnchorsManager {
    // Set this string to the account ID provided for the Azure Spatial Service resource.
    private static final String SpatialAnchorsAccountId = <ACCOUNT_ID>;

    // Set this string to the account key provided for the Azure Spatial Service resource.
    private static final String SpatialAnchorsAccountKey = <ACCOUNT_KEY>;

    // Log message tag
    private static final String TAG = "ASACloud";

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private final CloudSpatialAnchorSession spatialAnchorsSession;

    AzureSpatialAnchorsManager(Session arCoreSession) {
        if (arCoreSession == null) {
            throw new IllegalArgumentException("The arCoreSession may not be null.");
        }

        spatialAnchorsSession = new CloudSpatialAnchorSession();
        spatialAnchorsSession.getConfiguration().setAccountId(SpatialAnchorsAccountId);
        spatialAnchorsSession.getConfiguration().setAccountKey(SpatialAnchorsAccountKey);
        spatialAnchorsSession.setSession(arCoreSession);
        spatialAnchorsSession.setLogLevel(SessionLogLevel.All);

        spatialAnchorsSession.addOnLogDebugListener(this::onLogDebugListener);
        spatialAnchorsSession.addErrorListener(this::onErrorListener);
    }

    //region Listener Handling

    void addSessionUpdatedListener(SessionUpdatedListener listener) {
        this.spatialAnchorsSession.addSessionUpdatedListener(listener);
    }

    void addAnchorLocatedListener(AnchorLocatedListener listener) {
        this.spatialAnchorsSession.addAnchorLocatedListener(listener);
    }

    void addLocateAnchorsCompletedListener(LocateAnchorsCompletedListener listener) {
        this.spatialAnchorsSession.addLocateAnchorsCompletedListener(listener);
    }


    //endregion

    CompletableFuture<CloudSpatialAnchor> createAnchorAsync(CloudSpatialAnchor anchor) {
        //noinspection unchecked,unchecked
        return this.toEmptyCompletableFuture(spatialAnchorsSession.createAnchorAsync(anchor))
                .thenApply((ignore) -> anchor);
    }

    void start() {
        spatialAnchorsSession.start();
    }

    void startLocating(AnchorLocateCriteria criteria) {
        // Only 1 active watcher at a time is permitted.
        stopLocating();
        spatialAnchorsSession.createWatcher(criteria);
    }

    void stopLocating() {
        List<CloudSpatialAnchorWatcher> watchers = spatialAnchorsSession.getActiveWatchers();

        if (watchers.isEmpty()) {
            return;
        }

        // Only 1 watcher is at a time is currently permitted.
        CloudSpatialAnchorWatcher watcher = watchers.get(0);

        watcher.stop();
    }

    void stop() {
        spatialAnchorsSession.stop();
        stopLocating();
    }

    void update(Frame frame) {
        spatialAnchorsSession.processFrame(frame);
    }

    private CompletableFuture toEmptyCompletableFuture(Future future) {
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    private void onErrorListener(SessionErrorEvent event) {
        Log.e(TAG, event.getErrorMessage());
    }

    private void onLogDebugListener(OnLogDebugEvent args) {
        Log.d(TAG, args.getMessage());
    }
}
