package br.com.yalog.family;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZoneId;

final class DeviceUsage {
    private DeviceUsage() {}

    static JSONObject usage(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        long start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        try {
            var stats = manager.queryAndAggregateUsageStats(start, System.currentTimeMillis());
            JSONArray apps = new JSONArray();
            stats.values().stream()
                    .filter(item -> item.getTotalTimeInForeground() > 0)
                    .sorted(java.util.Comparator.comparingLong(UsageStats::getTotalTimeInForeground).reversed())
                    .limit(150)
                    .forEach(item -> {
                        long seconds = item.getTotalTimeInForeground() / 1000;
                        String label = appLabel(context, item.getPackageName());
                        if (label.trim().isEmpty()) label = item.getPackageName();
                        label = label.substring(0, Math.min(160, label.length()));
                        apps.put(MainActivity.object("label", label, "seconds", seconds, "package", item.getPackageName()));
                    });
            return MainActivity.object("day", LocalDate.now(zone).toString(), "apps", apps);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static String appLabel(Context context, String packageName) {
        PackageManager packages = context.getPackageManager();
        try {
            return packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

}
