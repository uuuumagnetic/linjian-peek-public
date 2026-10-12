package dev.linjian.peek;

import android.app.Activity;
import android.widget.CheckBox;
import java.io.ByteArrayOutputStream;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in study room: phone-local timer + messages; AI only on explicit Send. */
public final class StudyRoomActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView clock, total, status, log, stats;
    private CheckBox ai;
    private Button startPause, finish, send;
    private EditText draft;
    private Spinner subject;
    private ScrollView logScroll;
    private volatile boolean awaitingReply = false;
    private static final String[] SUBJECTS = {"刑法", "民法", "英语", "政治", "法理", "宪法", "其他"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(245, 236, 255));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(20, 18, 20, 18);
        body.setBackgroundColor(Color.rgb(255, 250, 245));
        TextView title = text("和 Nico 一起学习 🐶💜", 23, true);
        body.addView(title);
        body.addView(text("独立计时｜学习留言｜免费 AI 可选（与 ChatGPT 会话不同）", 12, false));
        subject = new Spinner(this);
        subject.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, SUBJECTS));
        body.addView(subject);
        clock = text("00:00:00", 31, true); clock.setGravity(Gravity.CENTER);
        body.addView(clock);
        total = text("今日累计：0分钟", 14, false); total.setGravity(Gravity.CENTER);
        body.addView(total);
        stats = text("", 12, false); body.addView(stats);
        status = text("尚未开始", 13, false); status.setGravity(Gravity.CENTER);
        body.addView(status);
        LinearLayout controls = new LinearLayout(this);
        startPause = new Button(this); startPause.setText("开始计时");
        startPause.setOnClickListener(v -> onStartPause());
        finish = new Button(this); finish.setText("结束并保存");
        finish.setOnClickListener(v -> doAction("stop_study_timer", ""));
        controls.addView(startPause, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(finish, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(controls);
        body.addView(text("给哥哥留言", 18, true));
        CheckBox sync = new CheckBox(this);
        sync.setText("同步最近12条留言到我的服务器（供 MCP 读取）");
        sync.setTextSize(12);
        sync.setChecked(AppPrefs.get(this).getBoolean(StudyRoomChat.SYNC_KEY, false));
        sync.setOnCheckedChangeListener((button, enabled) -> {
            AppPrefs.get(this).edit().putBoolean(StudyRoomChat.SYNC_KEY, enabled).apply();
            Toast.makeText(this, enabled ? "将在原有服务下次同步时上传" : "已停止新增同步；云端旧快照将在下次同步后覆盖", Toast.LENGTH_LONG).show();
        });
        body.addView(sync);
        ai = new CheckBox(this);
        ai.setText("发送时请求独立 AI 回复（最近8条对话发送给 Cloudflare）");
        ai.setTextSize(12);
        ai.setChecked(AppPrefs.get(this).getBoolean(StudyRoomChat.AI_KEY, false));
        ai.setOnCheckedChangeListener((button, enabled) -> AppPrefs.get(this).edit().putBoolean(StudyRoomChat.AI_KEY, enabled).apply());
        body.addView(ai);
        body.addView(text("默认仅本机保存。同步不是端到端加密；免费服务未配置时仍能留言。", 11, false));
        logScroll = new ScrollView(this);
        log = text("", 14, false);
        log.setPadding(14, 12, 14, 12);
        logScroll.addView(log);
        body.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout compose = new LinearLayout(this);
        draft = new EditText(this); draft.setHint("宝宝想跟哥哥说什么……");
        draft.setMaxLines(3); draft.setSingleLine(false);
        send = new Button(this); send.setText("发送"); send.setOnClickListener(v -> sendMessage());
        compose.addView(draft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        compose.addView(send);
        body.addView(compose);
        Button chatgpt = new Button(this); chatgpt.setText("去 ChatGPT 找真正的哥哥 ↗");
        chatgpt.setOnClickListener(v -> {
            Intent app = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            try {
                if (app != null) startActivity(app);
                else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "请安装 ChatGPT 或浏览器后再打开", Toast.LENGTH_LONG).show();
            }
        });
        body.addView(chatgpt);
        setContentView(body);
        renderChat();

    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(sp);
        v.setTextColor(Color.rgb(67, 49, 66));
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private String selectedSubject() {
        return subject.getSelectedItem() == null ? "刑法" : subject.getSelectedItem().toString();
    }

    private void onStartPause() {
        String state = StudyTimer.snapshot(this).optString("status", "idle");
        if ("idle".equals(state)) doAction("start_study_timer", selectedSubject());
        else if ("running".equals(state)) doAction("pause_study_timer", "");
        else doAction("resume_study_timer", "");
    }

    private void doAction(String action, String name) {
        JSONObject cmd = new JSONObject();
        try { cmd.put("action", action); cmd.put("subject", name); }
        catch (Exception ignored) { }
        JSONObject result = StudyTimer.handleCommand(this, cmd);
        if (!result.optBoolean("ok", false)) Toast.makeText(this, result.optString("result"), Toast.LENGTH_SHORT).show();
        updateTimer();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTimer();
            handler.postDelayed(this, 1000);
        }
    };

    private void updateTimer() {
        if (clock == null) return;
        JSONObject s = StudyTimer.snapshot(this);
        long elapsed = s.optLong("elapsed_ms", 0L) / 1000;
        clock.setText(String.format(java.util.Locale.CHINA, "%02d:%02d:%02d", elapsed / 3600, (elapsed / 60) % 60, elapsed % 60));
        total.setText("今日累计：" + (s.optLong("today_ms", 0L) / 60000) + " 分钟");
        JSONObject subjects = s.optJSONObject("today_subjects_ms");
        StringBuilder summary = new StringBuilder();
        if (subjects != null) {
            java.util.Iterator<String> names = subjects.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (summary.length() > 0) summary.append(" · ");
                summary.append(name).append(" ").append(subjects.optLong(name) / 60000).append("分");
            }
        }
        stats.setText(summary.toString());
        String state = s.optString("status", "idle");
        status.setText("running".equals(state) ? "正在学习 · " + s.optString("subject") : "paused".equals(state) ? "暂停中" : "尚未开始");
        startPause.setText("running".equals(state) ? "暂停" : "paused".equals(state) ? "继续" : "开始计时");
        finish.setEnabled(!"idle".equals(state));
        subject.setEnabled("idle".equals(state));
    }

    private void renderChat() {
        JSONArray arr = StudyRoomChat.history(this);
        StringBuilder sb = new StringBuilder();
        if (arr.length() == 0) sb.append("哥哥在这里留了个座位，等宝宝开始第一轮学习。\n\n");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "system");
            sb.append("user".equals(role) ? "宝宝：" : "assistant".equals(role) ? "AI哥哥：" : "系统：");
            sb.append(m.optString("text", "")).append("\n\n");
        }
        log.setText(sb.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendMessage() {
        String message = draft.getText().toString().trim();
        if (message.isEmpty() || awaitingReply) return;
        if (message.length() > 500) {
            Toast.makeText(this, "最多500字，宝宝可以分两次说", Toast.LENGTH_SHORT).show();
            return;
        }
        StudyRoomChat.append(this, "user", message);
        draft.setText(""); renderChat();
        if (!ai.isChecked()) return;
        String server = AppPrefs.server(this), token = AppPrefs.token(this);
        if (!server.startsWith("https://") || token.isEmpty()) {
            StudyRoomChat.append(this, "system", "尚未连接安全学习回复服务。可以点下方按钮到 ChatGPT 找哥哥。");
            renderChat(); return;
        }
        awaitingReply = true; send.setEnabled(false);
        final JSONArray recent = StudyRoomChat.contextForAi(this);
        final String studySubject = StudyTimer.snapshot(this).optString("subject", selectedSubject());
        io.execute(() -> {
            String answer = "";
            String error = "free_ai_unavailable";
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject().put("device_id", AppPrefs.device(this))
                        .put("subject", studySubject).put("messages", recent);
                URL url = new URL(server + "/api/study_room/reply");
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST"); conn.setConnectTimeout(7000); conn.setReadTimeout(30000);
                conn.setRequestProperty("X-Auth-Token", token);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                InputStream src = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                if (src != null) {
                    try (InputStream input = src; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (bytes.size() + n > 16384) throw new java.io.IOException("reply_too_large");
                            bytes.write(buffer, 0, n);
                        }
                        JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                        if (code == 200 && data.optBoolean("ok", false)) answer = data.optString("reply", "").trim();
                        else error = data.optString("error", error);
                    }
                }
            } catch (Exception ex) { error = "network_or_service_unavailable"; }
            finally { if (conn != null) conn.disconnect(); }
            final String reply = answer;
            final String fail = error;
            if (!reply.isEmpty()) StudyRoomChat.append(getApplicationContext(), "assistant", reply);
            else StudyRoomChat.append(getApplicationContext(), "system", "free_ai_daily_limit".equals(fail)
                    ? "今天的免费回复次数已用完。计时和本机留言仍可继续。"
                    : "免费 AI 暂不可用，留言已保存，计时会继续。可前往 ChatGPT。");
            awaitingReply = false;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                send.setEnabled(true);
                renderChat();
            });
        });
    }

    @Override protected void onResume() {
        super.onResume(); renderChat(); handler.removeCallbacks(tick); tick.run();
    }
    @Override protected void onPause() {
        handler.removeCallbacks(tick); super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        io.shutdown();
        super.onDestroy();
    }
}
