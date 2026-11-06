package br.com.yalog.family;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

public final class SharingService extends Service {
    private static final String CHANNEL = "family_sharing";
    private static final String PAUSE = "br.com.yalog.family.PAUSE";
    private static final int NOTICE = 21;
    private static final long INTERVAL = 15 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences link;
    private CancellationSignal request;
    private boolean running;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && PAUSE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        link = getSharedPreferences("family_link", MODE_PRIVATE);
        NotificationManager notices = getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Compartilhamento com responsável", NotificationManager.IMPORTANCE_LOW));
        if (!link.contains("token") || !notices.areNotificationsEnabled()
                || notices.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTICE, notification("Compartilhamento ativo"));
        } catch (SecurityException | IllegalStateException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        handler.post(this::collect);
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        var pause = PendingIntent.getService(this, 1, new Intent(this, SharingService.class).setAction(PAUSE), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("YALog Family · Compartilhamento")
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Pausar", pause).build())
                .build();
    }

    private void collect() {
        NotificationManager notices = getSystemService(NotificationManager.class);
        if (!running || !link.contains("token") || !notices.areNotificationsEnabled()
                || notices.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            stopSelf();
            return;
        }
        LocationManager location = getSystemService(LocationManager.class);
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            stopSelf();
            return;
        }
        String provider = fine && location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (!location.isProviderEnabled(provider)) {
            send(null);
            return;
        }
        request = new CancellationSignal();
        handler.postDelayed(locationTimeout, 20000);
        try {
            location.getCurrentLocation(provider, request, getMainExecutor(), point -> {
                if (!running || request == null) return;
                request = null;
                handler.removeCallbacks(locationTimeout);
                send(point == null ? null : MainActivity.object("lat", point.getLatitude(), "lon",
                        point.getLongitude(), "accuracy", point.getAccuracy(), "at", point.getTime()));
            });
        } catch (SecurityException | IllegalArgumentException e) {
            locationTimeout.run();
        }
    }

    private final Runnable locationTimeout = () -> {
        if (request == null) return;
        request.cancel();
        request = null;
        send(null);
    };

    private void send(JSONObject point) {
        if (!running) return;
        String base = link.getString("base", "");
        String token = link.getString("token", "");
        worker.execute(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            ReportApi.Reply reply;
            try {
                var payload = MainActivity.object("location", point, "usage", DeviceUsage.usage(this), "automatic", true);
                if (Thread.currentThread().isInterrupted()) return;
                reply = ReportApi.post(base, "report", token, payload);
            } catch (IOException | JSONException | IllegalArgumentException e) {
                reply = new ReportApi.Reply(0, null);
            }
            var response = reply;
            handler.post(() -> {
                if (!running) return;
                if (response.status() == 401) {
                    if (token.equals(link.getString("token", ""))) link.edit().clear().apply();
                    stopSelf();
                    return;
                }
                if (response.status() == 200 && token.equals(link.getString("token", ""))) {
                    AppLimitsService.receive(link, response.data());
                }
                String text = response.status() == 200 ? "Último envio concluído" : "Envio pendente. Nova tentativa no próximo ciclo.";
                getSystemService(NotificationManager.class).notify(NOTICE, notification(text));
                handler.postDelayed(this::collect, INTERVAL);
            });
        });
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (request != null) request.cancel();
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
