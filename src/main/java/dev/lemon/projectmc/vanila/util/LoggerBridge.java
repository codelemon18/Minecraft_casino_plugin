package dev.lemon.projectmc.vanila.util;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class LoggerBridge {
    private static Class<?> lemonLoggerClass;
    private static Method infoMethod3;
    private static Method warnMethod2;
    private static Method errorMethod2;
    private static boolean debugEnabled;
    private static boolean includeThread;
    private static String baseFormat;
    private static DateTimeFormatter timeFormatter;
    private static final Map<String, String> eventTemplates = new HashMap<>();
    private static String infoDefault;
    private static final Map<String, String> infoBySummary = new HashMap<>();
    private static String warnDefault;
    private static String errorDefault;
    private static String kvDefault;
    private static final Map<String, String> kvBySummary = new HashMap<>();
    private static String eventDefault;
    private static final Map<String, String> eventByKey = new HashMap<>();

    private LoggerBridge() {}

    // Lightweight configure (used by configureFull)
    public static void configure(boolean debug, boolean thread, String format, String tsPattern) {
        debugEnabled = debug;
        includeThread = thread;
        if (format != null && !format.isBlank()) baseFormat = format;
        if (tsPattern != null && !tsPattern.isBlank()) {
            try { timeFormatter = DateTimeFormatter.ofPattern(tsPattern).withZone(ZoneId.systemDefault()); }
            catch (Exception ignored) { timeFormatter = DateTimeFormatter.ISO_INSTANT; }
        }
    }

    public static void configureFull(boolean debug, boolean thread, String format, String tsPattern, Map<String, Object> templatesRoot) {
        configure(debug, thread, format, tsPattern);
        infoBySummary.clear(); kvBySummary.clear(); eventByKey.clear();
        infoDefault = "{message}"; kvDefault = "{all}"; eventDefault = "{all}"; warnDefault = "{message}"; errorDefault = "{message}";
        if (templatesRoot != null) {
            // info templates
            Object infoObj = templatesRoot.get("info");
            if (infoObj instanceof Map<?, ?> infoMap) {
                Object def = infoMap.get("default"); if (def != null) infoDefault = String.valueOf(def);
                Object bySummaryObj = infoMap.get("by-summary");
                if (bySummaryObj instanceof Map<?, ?> m) m.forEach((k,v)-> infoBySummary.put(String.valueOf(k), String.valueOf(v)));
            }
            // warn template
            Object warnObj = templatesRoot.get("warn");
            if (warnObj instanceof Map<?, ?> warnMap) {
                Object def = warnMap.get("default"); if (def != null) warnDefault = String.valueOf(def);
            }
            // error template
            Object errObj = templatesRoot.get("error");
            if (errObj instanceof Map<?, ?> errMap) {
                Object def = errMap.get("default"); if (def != null) errorDefault = String.valueOf(def);
            }
            // kv templates
            Object kvObj = templatesRoot.get("kv");
            if (kvObj instanceof Map<?, ?> kvMap) {
                Object def = kvMap.get("default"); if (def != null) kvDefault = String.valueOf(def);
                Object bySummaryObj = kvMap.get("by-summary");
                if (bySummaryObj instanceof Map<?, ?> m) m.forEach((k,v)-> kvBySummary.put(String.valueOf(k), String.valueOf(v)));
            }
            // event templates
            Object evObj = templatesRoot.get("event");
            if (evObj instanceof Map<?, ?> evMap) {
                Object def = evMap.get("default"); if (def != null) eventDefault = String.valueOf(def);
                Object byKeyObj = evMap.get("by-key");
                if (byKeyObj instanceof Map<?, ?> m) m.forEach((k,v)-> eventByKey.put(String.valueOf(k), String.valueOf(v)));
            }
        }
        // legacy merge: promote simple eventTemplates into eventByKey
        if (!eventTemplates.isEmpty()) eventByKey.putAll(eventTemplates);
    }

    private static String now() { return timeFormatter.format(Instant.now()); }
    private static String threadPart() { return Thread.currentThread().getName(); }

    private static String formatLine(String level, Plugin plugin, String summary, String message) {
        return baseFormat
                .replace("{ts}", now())
                .replace("{level}", level)
                .replace("{plugin}", plugin == null ? "plugin" : plugin.getName())
                .replace("{summary}", summary == null ? "-" : summary)
                .replace("{message}", message == null ? "" : message)
                .replace("{thread}", includeThread ? threadPart() : "");
    }

    private static String applyTemplate(String raw, Map<String, ?> data, String allFallback) {
        if (raw == null) return allFallback;
        String out = raw;
        if (data != null) {
            for (Map.Entry<String, ?> e : data.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return out.replace("{all}", allFallback);
    }

    public static void info(Plugin plugin, String summary, String message) {
        String chosen = infoBySummary.getOrDefault(summary, infoDefault);
        String formattedMsg = applyTemplate(chosen, Map.of("message", message, "summary", summary), message);
        if (lemonLoggerClass != null) {
            try { infoMethod3.invoke(null, plugin.getName(), summary, formattedMsg); return; }
            catch (IllegalAccessException | InvocationTargetException ignored) {}
        }
        plugin.getLogger().info(formatLine("INFO", plugin, summary, formattedMsg));
    }

    public static void warn(Plugin plugin, String message) {
        String formattedMsg = applyTemplate(warnDefault, Map.of("message", message), message);
        if (lemonLoggerClass != null) {
            try { warnMethod2.invoke(null, plugin.getName(), formattedMsg); return; }
            catch (IllegalAccessException | InvocationTargetException ignored) {}
        }
        plugin.getLogger().warning(formatLine("WARN", plugin, "-", formattedMsg));
    }

    public static void error(Plugin plugin, String message) {
        String formattedMsg = applyTemplate(errorDefault, Map.of("message", message), message);
        if (lemonLoggerClass != null) {
            try { errorMethod2.invoke(null, plugin.getName(), formattedMsg); return; }
            catch (IllegalAccessException | InvocationTargetException ignored) {}
        }
        plugin.getLogger().severe(formatLine("ERROR", plugin, "-", formattedMsg));
    }

    public static void debug(Plugin plugin, String summary, String message) {
        if (!debugEnabled) return;
        info(plugin, "DEBUG:" + summary, message);
    }

    public static void infoKV(Plugin plugin, String summary, Map<String, ?> kv) {
        StringJoiner sj = new StringJoiner(" ");
        kv.forEach((k,v)-> {
            String val = String.valueOf(v);
            if (val.contains(" ")) val = '"' + val + '"';
            sj.add(k + "=" + val);
        });
        String all = sj.toString();
        String chosen = kvBySummary.getOrDefault(summary, kvDefault);
        String msg = applyTemplate(chosen, kv, all);
        info(plugin, summary, msg);
    }

    public static void event(Plugin plugin, String game, String action, Map<String, ?> extra) {
        String key = game + '.' + action;
        StringJoiner sj = new StringJoiner(" ");
        sj.add("game=" + game).add("action=" + action);
        extra.forEach((k,v)-> {
            String val = String.valueOf(v);
            if (val.contains(" ")) val = '"' + val + '"';
            sj.add(k + "=" + val);
        });
        String fallback = sj.toString();
        String tpl = eventByKey.getOrDefault(key, eventDefault);
        String msg = applyTemplate(tpl, extra, fallback);
        info(plugin, "event", msg);
    }

    static {
        debugEnabled = false; includeThread = false;
        baseFormat = "{ts} [{level}] {plugin} {summary} | {message}";
        timeFormatter = DateTimeFormatter.ISO_INSTANT;
        infoDefault = "{message}"; warnDefault = "{message}"; errorDefault = "{message}"; kvDefault = "{all}"; eventDefault = "{all}";
        try {
            lemonLoggerClass = Class.forName("dev.lemon.projectmc.lemon.lemonlib.api.logger");
            infoMethod3 = lemonLoggerClass.getMethod("info", String.class, String.class, String.class);
            warnMethod2 = lemonLoggerClass.getMethod("warn", String.class, String.class);
            errorMethod2 = lemonLoggerClass.getMethod("error", String.class, String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            lemonLoggerClass = null;
        }
    }
}
