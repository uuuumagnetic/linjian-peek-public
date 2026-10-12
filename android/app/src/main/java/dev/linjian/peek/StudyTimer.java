package dev.linjian.peek;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 手动学习计时。手机本机为唯一可信计时源；MCP只读上传快照。
 * 与全机锁定 FocusMode 分离：计时不自动锁手机，也不判断是否真的学会。
 */
public final class StudyTimer {
    private static final String KEY = "study_timer_state_v1";
    private static final int MAX_HISTORY = 50;
    private StudyTimer() { }

    private static JSONObject initial() {
        JSONObject s = new JSONObject();
        try {
            s.put("version", 1);
            s.put("status", "idle");
            s.put("subject", "");
            s.put("session_id", "");
            s.put("started_at_ms", 0L);
            s.put("segment_started_at_ms", 0L);
            s.put("accumulated_ms", 0L);
            s.put("daily_totals_ms", new JSONObject());
            s.put("history", new JSONArray());
        } catch (Exception ignored) { }
        return s;
    }

    private static JSONObject load(Context ctx) {
        try {
            String raw = AppPrefs.get(ctx).getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                JSONObject s = new JSONObject(raw);
                if (!s.has("daily_totals_ms")) s.put("daily_totals_ms", new JSONObject());
                if (!s.has("history")) s.put("history", new JSONArray());
                return s;
            }
        } catch (Exception ignored) { }
        return initial();
    }

    private static void store(Context ctx, JSONObject s) {
        AppPrefs.get(ctx).edit().putString(KEY, s.toString()).apply();
    }

    private static String day(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(ms));
    }

    // 将跨午夜的区间分给各自的自然日，避免昨夜的计时全部记在今天。
    private static void creditDays(JSONObject totals, long start, long end) throws Exception {
        if (start <= 0L || end <= start) return;
        long cursor = start;
        while (cursor < end) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(cursor);
            String key = day(cursor);
            cal.add(Calendar.DAY_OF_YEAR, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long next = cal.getTimeInMillis();
            long cut = Math.min(end, Math.max(cursor + 1, next));
            totals.put(key, totals.optLong(key, 0L) + cut - cursor);
            cursor = cut;
        }
    }

    private static long duration(long start, long end) {
        return Math.max(0L, end - start);
    }

    // 当前这一段只在暂停或结束时入账；读取快照时动态计算但不重复累积。
    private static void commitRunningSegment(JSONObject s, long now) throws Exception {
        if (!"running".equals(s.optString("status"))) return;
        long start = s.optLong("segment_started_at_ms", 0L);
        if (start <= 0L) return;
        long ms = duration(start, now);
        if (ms > 0L) {
            s.put("accumulated_ms", s.optLong("accumulated_ms", 0L) + ms);
            creditDays(s.optJSONObject("daily_totals_ms"), start, start + ms);
        }
        s.put("segment_started_at_ms", 0L);
    }

    public static synchronized JSONObject snapshot(Context ctx) {
        JSONObject s = load(ctx);
        try {
            long now = System.currentTimeMillis();
            String status = s.optString("status", "idle");
            long runningStart = s.optLong("segment_started_at_ms", 0L);
            long runningMs = "running".equals(status) ? duration(runningStart, now) : 0L;
            JSONObject totals = s.optJSONObject("daily_totals_ms");
            JSONObject currentDay = new JSONObject();
            if ("running".equals(status) && runningMs > 0L)
                creditDays(currentDay, runningStart, runningStart + runningMs);
            String today = day(now);
            JSONObject out = new JSONObject();
            out.put("version", 1);
            out.put("source", "palmwindow_manual_study_timer");
            out.put("status", status);
            out.put("active", "running".equals(status));
            out.put("subject", s.optString("subject", ""));
            out.put("session_id", s.optString("session_id", ""));
            out.put("started_at_ms", s.optLong("started_at_ms", 0L));
            out.put("elapsed_ms", s.optLong("accumulated_ms", 0L) + runningMs);
            out.put("today_date", today);
            out.put("today_ms", (totals == null ? 0L : totals.optLong(today, 0L)) + currentDay.optLong(today, 0L));
            out.put("today_completed_ms", totals == null ? 0L : totals.optLong(today, 0L));
            out.put("today_sessions", todaySessions(s, today));
            out.put("last_session", s.optJSONObject("last_session") == null ? JSONObject.NULL : s.optJSONObject("last_session"));
            out.put("sampled_at_ms", now);
            out.put("note", "手动学习计时，不等于已验证的有效学习；全机专注模式互相独立");
            return out;
        } catch (Exception e) {
            try { s.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) { }
            return s;
        }
    }

    private static int todaySessions(JSONObject s, String dayKey) {
        int count = 0;
        JSONArray hist = s.optJSONArray("history");
        if (hist == null) return 0;
        for (int i = 0; i < hist.length(); i++) {
            JSONObject row = hist.optJSONObject(i);
            if (row != null && dayKey.equals(day(row.optLong("ended_at_ms", 0L)))) count++;
        }
        return count;
    }

    public static boolean isStudyAction(String action) {
        return "get_study_timer_status".equals(action) || "start_study_timer".equals(action)
                || "pause_study_timer".equals(action) || "resume_study_timer".equals(action)
                || "stop_study_timer".equals(action);
    }

    public static synchronized JSONObject handleCommand(Context ctx, JSONObject cmd) {
        String action = cmd == null ? "" : cmd.optString("action", "");
        if ("get_study_timer_status".equals(action)) return result(true, "study_timer_status", snapshot(ctx));
        long now = System.currentTimeMillis();
        JSONObject s = load(ctx);
        String status = s.optString("status", "idle");
        try {
            if ("start_study_timer".equals(action)) {
                if (!"idle".equals(status)) return result(false, "已有一段计时，请先结束", snapshot(ctx));
                String subject = cmd.optString("subject", cmd.optString("title", "刑法")).trim();
                if (subject.isEmpty()) subject = "未分类";
                if (subject.length() > 60) subject = subject.substring(0, 60);
                s.put("subject", subject);
                s.put("session_id", String.valueOf(now));
                s.put("started_at_ms", now);
                s.put("segment_started_at_ms", now);
                s.put("accumulated_ms", 0L);
                s.put("status", "running");
            } else if ("pause_study_timer".equals(action)) {
                if (!"running".equals(status)) return result(false, "没有正在运行的计时", snapshot(ctx));
                commitRunningSegment(s, now);
                s.put("status", "paused");
            } else if ("resume_study_timer".equals(action)) {
                if (!"paused".equals(status)) return result(false, "没有已暂停的计时", snapshot(ctx));
                s.put("segment_started_at_ms", now);
                s.put("status", "running");
            } else if ("stop_study_timer".equals(action)) {
                if ("idle".equals(status)) return result(false, "没有需要结束的计时", snapshot(ctx));
                commitRunningSegment(s, now);
                JSONObject entry = new JSONObject();
                entry.put("id", s.optString("session_id", ""));
                entry.put("subject", s.optString("subject", ""));
                entry.put("started_at_ms", s.optLong("started_at_ms", 0L));
                entry.put("ended_at_ms", now);
                entry.put("duration_ms", s.optLong("accumulated_ms", 0L));
                JSONArray old = s.optJSONArray("history");
                JSONArray next = new JSONArray();
                next.put(entry);
                for (int i = 0; old != null && i < old.length() && i < MAX_HISTORY - 1; i++)
                    next.put(old.get(i));
                s.put("history", next);
                s.put("last_session", entry);
                s.put("status", "idle");
                s.put("segment_started_at_ms", 0L);
                s.put("accumulated_ms", 0L);
            } else return result(false, "unknown_study_timer_action", snapshot(ctx));
            store(ctx, s);
            return result(true, action + "_done", snapshot(ctx));
        } catch (Exception e) { return result(false, e.getClass().getSimpleName(), snapshot(ctx)); }
    }

    private static JSONObject result(boolean ok, String message, JSONObject status) {
        JSONObject o = new JSONObject();
        try { o.put("ok", ok); o.put("result", message); o.put("study_timer", status); }
        catch (Exception ignored) { }
        return o;
    }
}
