package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** materials.yml 讀出來的材料表與各條軸的曲線。 */
public final class MaterialRegistry {

    private volatile Map<String, AttributeCurve> curves = Map.of();
    private volatile double pricePerMaterial = 1.5;
    private volatile int batch = 16;
    private volatile Map<String, MaterialDef> byId = Map.of();
    private volatile Map<Identifier, MaterialDef> byItem = Map.of();

    /** 一種買得到的材料。它只推一條軸——這就是「沒有最強的材料」的來源。 */
    public record MaterialDef(String id, String displayName, Identifier item, String attribute, int price) {
    }

    /** 量產時每一發的價格 ＝ 材料數 × 這個。 */
    public double pricePerMaterial() {
        return pricePerMaterial;
    }

    /** 商店一次賣幾發。 */
    public int batch() {
        return batch;
    }

    /** 這份設計量產時，一批要多少錢。 */
    public int batchPrice(AmmoVector vector) {
        return Math.max(1, (int) Math.round(vector.total() * pricePerMaterial * batch));
    }

    public void load(YamlConfig cfg) {
        this.pricePerMaterial = cfg.getDouble("production.price_per_material", 1.5);
        this.batch = Math.max(1, cfg.getInt("production.batch", 16));

        Map<String, AttributeCurve> loadedCurves = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("attributes").entrySet()) {
            loadedCurves.put(e.getKey(), AttributeCurve.from(e.getValue()));
        }

        Map<String, MaterialDef> ids = new LinkedHashMap<>();
        Map<Identifier, MaterialDef> items = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("materials").entrySet()) {
            Map<String, Object> section = e.getValue();
            String attribute = YamlConfig.str(section, "attribute", "");
            if (!loadedCurves.containsKey(attribute)) {
                FortressDuel.LOGGER.warn("Material {} points at attribute '{}' which is not defined, skipping it",
                        e.getKey(), attribute);
                continue;
            }

            MaterialDef def = new MaterialDef(
                    e.getKey(),
                    YamlConfig.str(section, "name", e.getKey()),
                    Identifier.parse(YamlConfig.str(section, "item", "minecraft:gunpowder")),
                    attribute,
                    YamlConfig.i(section, "price", 10));
            ids.put(def.id(), def);

            // 一個物品只能綁一種材料，不然工作台上分不出玩家擺的是哪一種
            MaterialDef previous = items.put(def.item(), def);
            if (previous != null) {
                FortressDuel.LOGGER.warn("Materials {} and {} share the item {}; only {} will be recognised",
                        previous.id(), def.id(), def.item(), def.id());
            }
        }

        this.curves = Map.copyOf(loadedCurves);
        this.byId = Map.copyOf(ids);
        this.byItem = Map.copyOf(items);
    }

    /** 這條軸的曲線；沒定義的話回傳 null（呼叫端要自己決定預設值）。 */
    public AttributeCurve curve(String attribute) {
        return curves.get(attribute);
    }

    public MaterialDef byId(String id) {
        return byId.get(id);
    }

    public MaterialDef byItem(Identifier item) {
        return byItem.get(item);
    }

    public Collection<MaterialDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
