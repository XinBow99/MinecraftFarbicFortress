package com.xinbow99.fortressduel.util;

import com.xinbow99.fortressduel.FortressDuel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一份讀進記憶體的 YAML 設定。
 *
 * <p>取值一律走 {@code "a.b.c"} 這種路徑字串並帶預設值——設定檔是玩家會手改的東西，
 * 少一個鍵、型別寫錯都不該讓伺服器炸掉，所以這裡沒有任何會丟例外的取值方法，
 * 取不到就回預設值並在 log 留一行。
 */
public final class YamlConfig {

    private final String name;
    private final Map<String, Object> root;

    private YamlConfig(String name, Map<String, Object> root) {
        this.name = name;
        this.root = root == null ? Map.of() : root;
    }

    public static YamlConfig empty(String name) {
        return new YamlConfig(name, Map.of());
    }

    /**
     * 從設定目錄讀取；檔案不存在時先把 jar 內建的預設檔複製出去，讓玩家有一份可以改的範本。
     *
     * @param configDir 設定目錄（config/fortress-duel）
     * @param fileName  檔名，例如 mobs.yml
     */
    @SuppressWarnings("unchecked")
    public static YamlConfig load(Path configDir, String fileName) {
        Path file = configDir.resolve(fileName);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(configDir);
                copyDefault(fileName, file);
            }
            try (InputStream in = Files.newInputStream(file)) {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object loaded = new Yaml(options).load(in);
                if (loaded == null) {
                    return new YamlConfig(fileName, Map.of());
                }
                if (!(loaded instanceof Map)) {
                    FortressDuel.LOGGER.error("The root of {} must be a mapping, but got {}; falling back to an empty config",
                            fileName, loaded.getClass().getSimpleName());
                    return new YamlConfig(fileName, Map.of());
                }
                return new YamlConfig(fileName, (Map<String, Object>) loaded);
            }
        } catch (Exception e) {
            // 包含 YAML 語法錯誤：留完整訊息讓玩家知道改壞了哪一行，然後退回空設定
            FortressDuel.LOGGER.error("Failed to read {}, falling back to an empty config", fileName, e);
            return new YamlConfig(fileName, Map.of());
        }
    }

    private static void copyDefault(String fileName, Path target) throws IOException {
        String resource = "/fortressduel/defaults/" + fileName;
        try (InputStream in = YamlConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                FortressDuel.LOGGER.warn("No bundled default at {}, so {} will be empty", resource, fileName);
                Files.createFile(target);
                return;
            }
            Files.copy(in, target);
            FortressDuel.LOGGER.info("Wrote default config {}", target);
        }
    }

    public String name() {
        return name;
    }

    public Map<String, Object> raw() {
        return root;
    }

    // ---------- 取值 ----------

    public String getString(String path, String def) {
        Object v = resolve(path);
        return v == null ? def : String.valueOf(v);
    }

    public int getInt(String path, int def) {
        Object v = resolve(path);
        if (v instanceof Number n) return n.intValue();
        return warnType(path, v, def);
    }

    public double getDouble(String path, double def) {
        Object v = resolve(path);
        if (v instanceof Number n) return n.doubleValue();
        return warnType(path, v, def);
    }

    public boolean getBoolean(String path, boolean def) {
        Object v = resolve(path);
        if (v instanceof Boolean b) return b;
        return warnType(path, v, def);
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object v = resolve(path);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 取一包「鍵 → 數字」的對照表，例如逐方塊的血量覆寫。
     *
     * <p>值不是數字的項目會被跳過並留一行警告——整包丟掉的話，一個打錯的數值會靜默地讓
     * 其他每一項都失效。
     */
    public Map<String, Double> getDoubleMap(String path) {
        Object v = resolve(path);
        if (!(v instanceof Map<?, ?> map)) return Map.of();

        java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() instanceof Number n) {
                out.put(String.valueOf(e.getKey()), n.doubleValue());
            } else {
                FortressDuel.LOGGER.warn("{}: {}.{} is not a number, skipping it", name, path, e.getKey());
            }
        }
        return out;
    }

    /**
     * 取一個「子區段」，也就是 {@code key: {…}} 底下那一整包。
     * 怪物、武器、事件的設定都是「一個 id 對一組欄位」，載入器會用這個把它們一個個拆出來。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getSections(String path) {
        Object v = resolve(path);
        if (!(v instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        java.util.LinkedHashMap<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> child) {
                out.put(String.valueOf(e.getKey()), (Map<String, Object>) child);
            } else {
                FortressDuel.LOGGER.warn("{}: {}.{} is not a mapping, skipping it", name, path, e.getKey());
            }
        }
        return out;
    }

    private Object resolve(String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private <T> T warnType(String path, Object actual, T def) {
        if (actual != null) {
            FortressDuel.LOGGER.warn("{}: {} has the wrong type (value '{}'), using default {}", name, path, actual, def);
        }
        return def;
    }

    // ---------- 給載入器用的 Map 取值（子區段裡的欄位） ----------

    public static String str(Map<String, Object> section, String key, String def) {
        Object v = section.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int i(Map<String, Object> section, String key, int def) {
        Object v = section.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public static double d(Map<String, Object> section, String key, double def) {
        Object v = section.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static boolean bool(Map<String, Object> section, String key, boolean def) {
        Object v = section.get(key);
        return v instanceof Boolean b ? b : def;
    }
}
