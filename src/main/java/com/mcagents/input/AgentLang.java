package com.mcagents.input;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.MCAgentsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从模组 JAR 内 {@code assets/mc-agents/lang/*.json} 加载文案，供服务端按玩家语言解析键。
 */
public final class AgentLang {
    private static final Map<String, Map<String, String>> BY_LOCALE = new HashMap<>();
    private static volatile boolean loaded;

    private AgentLang() {
    }

    public static void init() {
        if (loaded) {
            return;
        }
        synchronized (AgentLang.class) {
            if (loaded) {
                return;
            }
            loadFile("en_us");
            loadFile("zh_cn");
            loaded = true;
        }
    }

    /**
     * 按 {@code player} 客户端语言选表；无玩家时依次尝试 en_us、zh_cn；均无则返回 {@code fallback}。
     */
    static String translate(ServerPlayer player, String key, String fallback) {
        init();
        if (player != null) {
            try {
                String raw = resolveRawLocale(player);
                if (raw != null) {
                    String loc = normalizeLocale(raw);
                    String v = get(loc, key);
                    if (v != null) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String v = get("en_us", key);
        if (v != null) {
            return v;
        }
        v = get("zh_cn", key);
        if (v != null) {
            return v;
        }
        return fallback;
    }

    static String translate(CommandSourceStack source, String key, String fallback) {
        if (source != null) {
            ServerPlayer p = source.getPlayer();
            if (p != null) {
                return translate(p, key, fallback);
            }
        }
        return translate((ServerPlayer) null, key, fallback);
    }

    /**
     * 各 MC 版本差异：1.19.4–1.20.1 无 {@code clientInformation()}；1.20.2+ 多为 ClientInformation / language 字段。
     * 用反射避免对旧版编译时依赖不存在的方法；无法解析时返回 {@code null}，由调用方走默认语言链。
     */
    private static String resolveRawLocale(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Object options = invokeNoArg(player, "clientInformation");
        if (options == null) {
            options = invokeNoArg(player, "getClientOptions");
        }
        if (options != null) {
            String fromOptions = invokeLanguage(options);
            if (fromOptions != null) {
                return fromOptions;
            }
        }
        try {
            Field f = ServerPlayer.class.getDeclaredField("language");
            f.setAccessible(true);
            Object v = f.get(player);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeLanguage(Object clientOptions) {
        if (clientOptions == null) {
            return null;
        }
        try {
            Method m = clientOptions.getClass().getMethod("language");
            Object v = m.invoke(clientOptions);
            return v instanceof String s && !s.isBlank() ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en_us";
        }
        String t = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        if (BY_LOCALE.containsKey(t)) {
            return t;
        }
        if (t.startsWith("zh")) {
            return "zh_cn";
        }
        return "en_us";
    }

    private static String get(String locale, String key) {
        Map<String, String> m = BY_LOCALE.get(locale);
        return m == null ? null : m.get(key);
    }

    private static void loadFile(String locale) {
        String path = "/assets/mc-agents/lang/" + locale + ".json";
        try (InputStream in = MCAgentsMod.class.getResourceAsStream(path)) {
            if (in == null) {
                MCAgentsMod.LOGGER.warn("MC-Agents: missing language file {}", path);
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            BY_LOCALE.put(locale, out);
        } catch (Exception e) {
            MCAgentsMod.LOGGER.warn("MC-Agents: failed to load language {}: {}", locale, e.getMessage());
        }
    }
}
