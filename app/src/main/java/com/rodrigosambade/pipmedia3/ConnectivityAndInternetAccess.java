/*
 * SPDX-License-Identifier: MIT
 *
 * Focused API-26+ adaptation of Rodrigo Sambade's current connectivity helper:
 * https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f
 *
 * The original helper also preserves compatibility with much older Android releases.
 * This lab has minSdk 26, so only the modern Network/NetworkCapabilities path that the
 * Picture-in-Picture streaming exercise actually needs is retained here.
 */
package com.rodrigosambade.pipmedia3;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle-friendly connectivity/reachability helper for this streaming lab.
 *
 * <p>A connected network is not assumed to imply that the remote media is reachable.
 * Callers can cheaply inspect {@link #snapshotNetworkState(Context)} and then run an
 * asynchronous active HTTP probe with {@link #checkInternetAsync(Context, InternetCallback)}.
 */
public final class ConnectivityAndInternetAccess {

    private static final int CONNECT_TIMEOUT_MS = 1_000;
    private static final int READ_TIMEOUT_MS = 1_000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "pip-connectivity-check");
        thread.setDaemon(true);
        return thread;
    });

    public interface InternetCallback {
        void onResult(InternetResult result);
    }

    public static final class InternetResult {
        private final boolean reachable;
        private final String reachedHost;
        private final List<String> attemptedHosts;
        private final long elapsedMilliseconds;

        private InternetResult(
                boolean reachable,
                String reachedHost,
                List<String> attemptedHosts,
                long elapsedMilliseconds) {
            this.reachable = reachable;
            this.reachedHost = reachedHost;
            this.attemptedHosts = Collections.unmodifiableList(
                    new ArrayList<>(attemptedHosts));
            this.elapsedMilliseconds = elapsedMilliseconds;
        }

        public boolean isReachable() {
            return reachable;
        }

        public String getReachedHost() {
            return reachedHost;
        }

        public List<String> getAttemptedHosts() {
            return attemptedHosts;
        }

        public long getElapsedMilliseconds() {
            return elapsedMilliseconds;
        }
    }

    public static final class NetworkState {
        private final boolean connected;
        private final boolean internetValidated;
        private final boolean captivePortalDetected;

        private NetworkState(
                boolean connected,
                boolean internetValidated,
                boolean captivePortalDetected) {
            this.connected = connected;
            this.internetValidated = internetValidated;
            this.captivePortalDetected = captivePortalDetected;
        }

        public boolean isConnected() {
            return connected;
        }

        public boolean isInternetValidated() {
            return internetValidated;
        }

        public boolean isCaptivePortalDetected() {
            return captivePortalDetected;
        }
    }

    public static final class Request {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;

        private Request() {
        }

        public void cancel() {
            cancelled.set(true);
            Future<?> running = future;
            if (running != null) {
                running.cancel(true);
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        private void attach(Future<?> future) {
            this.future = future;
            if (cancelled.get()) {
                future.cancel(true);
            }
        }
    }

    public static final class Builder {
        private List<String> hosts = Collections.emptyList();

        public Builder setHosts(List<String> hosts) {
            this.hosts = hosts;
            return this;
        }

        public ConnectivityAndInternetAccess build() {
            return new ConnectivityAndInternetAccess(normalizeHosts(hosts));
        }
    }

    private final List<String> hosts;

    private ConnectivityAndInternetAccess(List<String> hosts) {
        this.hosts = hosts;
    }

    public Request checkInternetAsync(Context context, InternetCallback callback) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }

        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Request request = new Request();

        Future<?> future = EXECUTOR.submit(() -> {
            InternetResult result = checkInternetBlocking(appContext);
            if (!request.isCancelled()) {
                MAIN_HANDLER.post(() -> {
                    if (!request.isCancelled()) {
                        callback.onResult(result);
                    }
                });
            }
        });
        request.attach(future);
        return request;
    }

    public static NetworkState snapshotNetworkState(Context context) {
        ConnectivityManager manager = manager(context);
        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) {
            return new NetworkState(false, false, false);
        }

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
        boolean notSuspended = capabilities != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));
        boolean connected = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && notSuspended;
        boolean validated = connected
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean captivePortal = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        return new NetworkState(connected, validated, captivePortal);
    }

    private InternetResult checkInternetBlocking(Context context) {
        long started = SystemClock.elapsedRealtime();
        List<String> attempted = new ArrayList<>();
        ConnectivityManager manager = manager(context);
        Network network = manager.getActiveNetwork();

        if (network == null || !snapshotNetworkState(context).isConnected()) {
            return new InternetResult(
                    false,
                    null,
                    attempted,
                    SystemClock.elapsedRealtime() - started);
        }

        for (String host : hosts) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            attempted.add(host);
            if (probe(network, host)) {
                return new InternetResult(
                        true,
                        host,
                        attempted,
                        SystemClock.elapsedRealtime() - started);
            }
        }

        return new InternetResult(
                false,
                null,
                attempted,
                SystemClock.elapsedRealtime() - started);
    }

    private static boolean probe(Network network, String address) {
        HttpURLConnection connection = null;
        try {
            URLConnection rawConnection = network.openConnection(new URL(address));
            connection = (HttpURLConnection) rawConnection;
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Range", "bytes=0-0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");

            int response = connection.getResponseCode();
            return response >= 200 && response < 400;
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ConnectivityManager manager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("ConnectivityManager unavailable");
        }
        return manager;
    }

    private static List<String> normalizeHosts(List<String> hosts) {
        if (hosts == null) {
            throw new IllegalArgumentException("hosts == null");
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : hosts) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                URL url = new URL(value);
                if (!("http".equalsIgnoreCase(url.getProtocol())
                        || "https".equalsIgnoreCase(url.getProtocol()))) {
                    throw new IllegalArgumentException("Only HTTP(S) hosts are supported");
                }
                normalized.add(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid HTTP(S) URL: " + value, exception);
            }
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("hosts cannot be empty");
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }
}
