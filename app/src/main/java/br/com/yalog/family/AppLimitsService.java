package br.com.yalog.family;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AppLimitsService extends AccessibilityService {
    private static final long RULE_VALIDITY = 24 * 60 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recheck = this::applyRules;
    private String foreground;

    static void receive(SharedPreferences link, JSONObject response) {
        JSONArray rules = response.optJSONArray("rules");
        if (rules != null) {
            link.edit().putString("rules", rules.toString())
                    .putLong("rules_received", System.currentTimeMillis()).apply();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) return;
        foreground = event.getPackageName().toString();
        handler.removeCallbacks(recheck);
        applyRules();
    }

    private void applyRules() {
        if (foreground == null || !eligible(foreground)) return;
        SharedPreferences link = getSharedPreferences("family_link", MODE_PRIVATE);
        if (!link.contains("token") || System.currentTimeMillis() - link.getLong("rules_received", 0) > RULE_VALIDITY) return;
        try {
            JSONArray rules = new JSONArray(link.getString("rules", "[]"));
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                if (!foreground.equals(rule.optString("app"))) continue;
                if (blockedNow(rule) || dailyLimitReached(rule.optInt("minutes"))) {
                    if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                        Toast.makeText(this, "Aplicativo fora do limite familiar.", Toast.LENGTH_SHORT).show();
                    }
                    foreground = null;
                } else {
                    handler.postDelayed(recheck, 30000);
                }
                return;
            }
        } catch (JSONException | java.time.DateTimeException ignored) {
            // Discard unreadable rules and keep the device accessible.
        }
    }

    private boolean eligible(String name) {
        if (getPackageName().equals(name)) return false;
        PackageManager packages = getPackageManager();
        try {
            int flags = packages.getApplicationInfo(name, 0).flags;
            if ((flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return false;
            var home = packages.resolveActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return home != null && home.activityInfo != null && !name.equals(home.activityInfo.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean blockedNow(JSONObject rule) {
        String fromValue = rule.optString("from");
        if (fromValue.isEmpty()) return false;
        LocalTime from = LocalTime.parse(fromValue);
        LocalTime to = LocalTime.parse(rule.optString("to"));
        LocalTime now = LocalTime.now();
        if (from.isBefore(to)) return !now.isBefore(from) && now.isBefore(to);
        return !now.isBefore(from) || now.isBefore(to);
    }

    private boolean dailyLimitReached(int minutes) {
        if (minutes <= 0) return false;
        long start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            UsageStats stats = getSystemService(UsageStatsManager.class)
                    .queryAndAggregateUsageStats(start, System.currentTimeMillis()).get(foreground);
            return stats != null && stats.getTotalTimeInForeground() >= minutes * 60000L;
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override public void onInterrupt() {
        foreground = null;
        handler.removeCallbacks(recheck);
    }

    @Override public void onDestroy() {
        onInterrupt();
        super.onDestroy();
    }
}
