package dev.linjian.peek;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The user owns the conversation: messages are stored on-device.
 * Only a user-initiated send makes an AI request. Limited recent messages are
 * included in the opt-in companion snapshot for retrieval through private MCP.
 */
public final class StudyRoomChat {
    private static final String KEY = "study_room_chat_v1";
    public static final String SYNC_KEY = "study_room_sync_enabled";
    public static final String AI_KEY = "study_room_ai_enabled";
    private static final int HISTORY_LIMIT = 60;
    private StudyRoomChat() { }

    public static synchronized JSONArray history(Context ctx) {
        try {
            String raw = AppPrefs.get(ctx).getString(KEY, "[]");
            return new JSONArray(raw);
        } catch (Exception ignored) { return new JSONArray(); }
    }

    public static synchronized void append(Context ctx, String role, String content) {
        if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) return;
        String text = content == null ? "" : content.trim();
        if (text.length() == 0) return;
        if (text.length() > 1000) text = text.substring(0, 1000);
        try {
            JSONArray old = history(ctx);
            JSONArray next = new JSONArray();
            int from = Math.max(0, old.length() - HISTORY_LIMIT + 1);
            for (int i = from; i < old.length(); i++) {
                JSONObject m = old.optJSONObject(i);
                if (m != null) next.put(m);
            }
            next.put(new JSONObject().put("role", role).put("text", text).put("at_ms", System.currentTimeMillis()));
            AppPrefs.get(ctx).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static synchronized JSONArray recent(Context ctx, int count) {
        JSONArray src = history(ctx);
        JSONArray out = new JSONArray();
        int from = Math.max(0, src.length() - Math.max(0, Math.min(count, 20)));
        for (int i = from; i < src.length(); i++) {
            JSONObject m = src.optJSONObject(i);
            if (m != null) out.put(m);
        }
        return out;
    }

    public static synchronized JSONObject snapshot(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONArray h = history(ctx);
            out.put("version", 1);
            boolean sync = AppPrefs.get(ctx).getBoolean(SYNC_KEY, false);
            out.put("sync_enabled", sync);
            out.put("message_count", sync ? h.length() : 0);
            out.put("recent_messages", sync ? recent(ctx, 12) : new JSONArray());
            out.put("sync_note", "仅限私有掌心窗同步；回复服务需独立配置，与当前ChatGPT会话不同");
            out.put("sampled_at_ms", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return out;
    }

    /** Last 8 messages are sent only when the user taps send; system lines omitted. */
    public static synchronized JSONArray contextForAi(Context ctx) {
        JSONArray arr = history(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            try { out.put(new JSONObject().put("role", role).put("content", row.optString("text", ""))); }
            catch (Exception ignored) { }
        }
        JSONArray last = new JSONArray();
        for (int i = Math.max(0, out.length() - 8); i < out.length(); i++) last.put(out.optJSONObject(i));
        return last;
    }
}
