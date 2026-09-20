package org.fairc.forwardcheck;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Provider accounting only: never stores a message, contact, query or source URL. */
final class PhoneCosts {
    static final double RESERVE = .10, TOTAL_LIMIT = 15, DAILY_LIMIT = 10;
    // Same demo account: legacy research plus completed bilingual training-data generation.
    static final double DEVELOPMENT_CHARGED = .117428629 + .05649036, DEVELOPMENT_UNCONFIRMED = .30;
    private final SharedPreferences prefs;
    PhoneCosts(Context context) { prefs = context.getSharedPreferences("research_costs", Context.MODE_PRIVATE); }
    private JSONObject rows() { try { return new JSONObject(prefs.getString("rows", "{}")); } catch (Exception e) { throw new IllegalStateException("Could not read the cost history."); } }
    private void save(JSONObject rows) { if (!prefs.edit().putString("rows", rows.toString()).commit()) throw new IllegalStateException("Could not save the cost history."); }
    synchronized String reserve(String kind) throws Exception {
        JSONObject rows = rows(); String day = LocalDate.now(ZoneOffset.UTC).toString();
        double total = DEVELOPMENT_CHARGED + DEVELOPMENT_UNCONFIRMED, daily = 0;
        java.util.Iterator<String> keys = rows.keys();
        while (keys.hasNext()) {
            JSONObject row = rows.getJSONObject(keys.next()); double amount = row.optDouble("cost", RESERVE);
            total += amount; if (day.equals(row.optString("day"))) daily += amount;
        }
        if (total + RESERVE > TOTAL_LIMIT || daily + RESERVE > DAILY_LIMIT) throw new IllegalStateException("The demo budget is used up. Open Settings for costs.");
        String id = UUID.randomUUID().toString();
        rows.put(id, new JSONObject().put("id", id).put("day", day).put("time_ms", System.currentTimeMillis())
                .put("model", PhoneResearch.MODEL).put("kind", kind).put("state", "reserved"));
        save(rows); return id;
    }
    synchronized void settle(String id, Double amount, String generation, JSONObject usage, String state) throws Exception {
        JSONObject rows = rows(); JSONObject row = rows.getJSONObject(id);
        row.put("state", state);
        if (amount != null && Double.isFinite(amount) && amount >= 0) row.put("cost", amount);
        if (generation != null && !generation.isEmpty()) row.put("generation_id", generation);
        if (usage != null) {
            row.put("prompt_tokens", usage.optInt("prompt_tokens")); row.put("completion_tokens", usage.optInt("completion_tokens"));
            JSONObject tools = usage.optJSONObject("server_tool_use");
            if (tools == null) tools = usage.optJSONObject("server_tool_use_details");
            if (tools != null) row.put("web_search_requests", tools.optInt("web_search_requests"));
        }
        save(rows);
    }
    synchronized String summary() {
        JSONObject rows = rows(); double charged = 0, reserved = 0; int count = 0;
        java.util.Iterator<String> keys = rows.keys();
        while (keys.hasNext()) { JSONObject row = rows.optJSONObject(keys.next()); if (row == null) continue;
            count++; if (row.has("cost")) charged += row.optDouble("cost"); else reserved += RESERVE;
        }
        return String.format(java.util.Locale.US, "This phone: $%.5f\n%d requests\n\nWaiting for confirmed charges: $%.2f\n\nEarlier development: $%.5f charged (includes $0.05649 for training examples), $%.2f unconfirmed.\n\nLimit: $15 total, $10 per day.", charged, count, reserved, DEVELOPMENT_CHARGED, DEVELOPMENT_UNCONFIRMED);
    }
    synchronized String export() {
        StringBuilder out = new StringBuilder("request_id,time_ms,model,kind,state,cost_usd,reserved_usd,generation_id,prompt_tokens,completion_tokens\n");
        JSONObject rows = rows(); java.util.Iterator<String> keys = rows.keys();
        while (keys.hasNext()) { JSONObject row = rows.optJSONObject(keys.next()); if (row == null) continue;
            for (String key : new String[]{"id", "time_ms", "model", "kind", "state", "cost"}) out.append(row.optString(key, "").replace(",", "").replace("\n", "")).append(',');
            out.append(row.has("cost") ? "0" : "0.10").append(',').append(row.optString("generation_id", "").replace(",", "")).append(',')
                    .append(row.optInt("prompt_tokens")).append(',').append(row.optInt("completion_tokens")).append('\n');
        }
        return out.toString();
    }
}
