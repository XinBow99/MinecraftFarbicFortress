package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.building.BuildingDef;
import com.xinbow99.fortressduel.building.BuildingPlacer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 建築師的圖紙櫃：把 buildings.yml 裡標了 {@code sold: true} 的藍圖變成一間商店。
 *
 * <h2>價格是算出來的</h2>
 * <p>照藍圖裡**每一格的建材單價**加總，再打 {@value #DISCOUNT} 折。單價從軍火商架上的建材
 * 商品回推（{@code price ÷ amount}），所以石頭漲價的那天圖紙會自己跟著漲。
 *
 * <p>手寫價格的話，改一層樓高度就要記得回頭改那個數字——而忘記改不會有任何徵兆，
 * 只會有一張莫名其妙划算或莫名其妙貴的圖紙。這跟 shops.yml 那條「血量與幾發打得破由程式
 * 印上去」是同一條規矩。
 *
 * <p>折扣的意義是**你在買時間，而不是在買便宜**：同樣的錢自己一格一格擺也蓋得出來，
 * 圖紙省掉的是那三分鐘。八折剛好讓它在「懶得蓋」之外還有一點正面的理由，
 * 又不至於變成「不買圖紙就是虧」。
 *
 * <p>藍圖裡用到的方塊如果不在架上（梯子、空氣），單價算 0——那些東西玩家本來就買不到，
 * 而它們是附屬的，不該憑空生出一個價格。
 */
public final class BlueprintShop {

    /** 建築師開的那間店的 id。npcs.yml 的 {@code shop:} 要填這個。 */
    public static final String SHOP_ID = "architect";

    /** 圖紙的折扣。你買的是時間不是便宜，所以只打一點點。 */
    private static final double DISCOUNT = 0.8;

    private BlueprintShop() {
    }

    /**
     * @param blockPrices 方塊 id → 一格多少錢，從建材商品回推（見 {@code NpcManager.blockPrices}）
     */
    public static ShopDef from(BuildingPlacer buildings, Map<String, Double> blockPrices, String title) {
        List<ShopEntry> entries = new ArrayList<>();

        for (BuildingDef def : buildings.all()) {
            if (!def.sold()) continue;
            entries.add(entry(def, priceOf(def, blockPrices)));
        }
        return new ShopDef(SHOP_ID, title, List.copyOf(entries));
    }

    private static int priceOf(BuildingDef def, Map<String, Double> blockPrices) {
        double total = 0;
        for (Map.Entry<String, Integer> e : def.blockCounts().entrySet()) {
            // 方塊 id 可能帶狀態（minecraft:ladder[facing=south]），查價要先切掉
            String block = e.getKey().split("\\[", 2)[0];
            total += blockPrices.getOrDefault(block, 0.0) * e.getValue();
        }
        return Math.max(1, (int) Math.round(total * DISCOUNT));
    }

    /**
     * {@code weapon} 欄位借來放藍圖 id——跟玩家設計借它放材料向量的 key 是同一個做法。
     * 那個欄位的意思一直都是「這筆商品指向哪一個定義」。
     */
    private static ShopEntry entry(BuildingDef def, int price) {
        return new ShopEntry(
                "blueprint_" + def.id(),
                def.displayName(),
                "blueprint",
                price,
                def.id(),
                "",
                "minecraft:paper",
                "",
                1,
                1,
                Map.of(),
                "買一張，右鍵地面就整棟蓋起來");
    }
}
