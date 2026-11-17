package com.moayed.youtuberesearch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILE = 41;
    private static final String PREFS = "research";
    private static final String URI_KEY = "research_doc_uri";
    private String pendingUrl;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showHome();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void showHome() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(35, 65, 35, 35);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("YouTube → ChatGPT Research");
        title.setTextSize(22);
        panel.addView(title);
        TextView desc = new TextView(this);
        desc.setText("\nSelect a research text file in Google Drive once. Then choose 'To ChatGPT Research' from YouTube's Share menu. Every link is appended automatically.\n");
        desc.setTextSize(16);
        panel.addView(desc);
        Button choose = new Button(this);
        choose.setText("Choose research file in Google Drive");
        choose.setOnClickListener(v -> pickNewFile());
        panel.addView(choose);
        TextView status = new TextView(this);
        status.setText(prefs().contains(URI_KEY) ? "\nDocument connected. Ready to receive YouTube shares." : "\nSetup: select Google Drive in the file picker.");
        panel.addView(status);
        setContentView(panel);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String url = youtubeUrl(text);
        if (url == null) {
            toast("No YouTube link found");
            return;
        }
        pendingUrl = url;
        String stored = prefs().getString(URI_KEY, null);
        if (stored == null) {
            pickNewFile();
        } else {
            append(Uri.parse(stored), url);
        }
    }

    private static String youtubeUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String raw = m.group();
            while (raw.endsWith(".") || raw.endsWith(",") || raw.endsWith(")")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            try {
                Uri uri = Uri.parse(raw);
                String host = uri.getHost();
                if (host == null) continue;
                host = host.toLowerCase(Locale.ROOT);
                if (host.equals("youtu.be") || host.equals("www.youtu.be") ||
                    host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                    host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com")) {
                    return raw;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void pickNewFile() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("text/plain");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, "YouTube Research.txt");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                   Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                   Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs().edit().putString(URI_KEY, uri.toString()).apply();
            showHome();
            if (pendingUrl != null) append(uri, pendingUrl);
            else toast("Research file connected");
        } catch (Exception ex) {
            toast("File permission failed: try Google Drive again");
        }
    }

    private void append(Uri file, String url) {
        pendingUrl = null;
        new Thread(() -> {
            try {
                ByteArrayOutputStream old = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(file)) {
                    if (in == null) throw new Exception("Cannot read document");
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        old.write(buffer, 0, n);
                        if (old.size() > 10_000_000) throw new Exception("Document is too large");
                    }
                }
                String previous = old.toString("UTF-8");
                String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
                String entry = now + " | " + url + "\n";
                byte[] bytes = (previous + entry).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = getContentResolver().openOutputStream(file, "wt")) {
                    if (out == null) throw new Exception("Cannot write document");
                    out.write(bytes);
                    out.flush();
                }
                runOnUiThread(() -> {
                    toast("Saved to research document");
                    finish();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    showHome();
                    toast("Save failed: " + ex.getMessage());
                });
            }
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
