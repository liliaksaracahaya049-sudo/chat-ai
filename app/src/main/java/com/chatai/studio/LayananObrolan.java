package com.chatai.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Layanan latar depan supaya AI tetap menulis walau aplikasi ditutup sementara. */
public class LayananObrolan extends Service {
    private static final String SALURAN = "chatai_proses";
    private static final int ID = 7701;

    static void mulai(Context c) {
        try {
            Intent i = new Intent(c, LayananObrolan.class);
            c.startForegroundService(i);
        } catch (Throwable ignored) { }
    }

    static void henti(final Context c) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!MesinObrolan.dapat(c).sibuk()) c.stopService(new Intent(c, LayananObrolan.class));
            } catch (Throwable ignored) { }
        }, 2500);
    }

    @Override public void onCreate() { super.onCreate(); naikkan(); }
    @Override public int onStartCommand(Intent i, int f, int id) { naikkan(); return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    private void naikkan() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(SALURAN, "Proses AI", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, SALURAN)
                    .setContentTitle("Chat AI")
                    .setContentText("AI sedang menulis jawaban…")
                    .setSmallIcon(R.drawable.ikon_aplikasi)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (Throwable ignored) { }
    }
}
