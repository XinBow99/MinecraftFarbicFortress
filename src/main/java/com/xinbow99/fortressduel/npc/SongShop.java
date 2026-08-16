package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 音樂家的曲目櫃：把 {@code songs.yml} 變成一間商店。
 *
 * <p>歌不寫在 shops.yml 裡是刻意的——加一首歌應該是**丟一個音檔、寫一個名字**，
 * 不必動那份滿是平衡註解的商品清單。所以曲目自己一個檔案，格式也短到看一眼就會寫：
 *
 * <pre>{@code
 * songs:
 *   wow:
 *     name: Wow
 *     length: 5
 * }</pre>
 *
 * <p>櫃子上那一格賣的是**一張光碟**（{@code type: disc}），不是當場播放：買回去拿在手上
 * 右鍵才放，而且放得完就能再放（見 {@link SongDisc}）。點播與放歌拆開之後，「什麼時候放」
 * 才是玩家的決定，而不是「你人必須站在音樂家面前」。
 *
 * <p>兩個省略規則讓那三行變成真的三行：
 * <ul>
 *   <li>{@code sound} 省略就是 {@code fortress-duel:<id>}——音檔叫 {@code wow.ogg}，id 就是 {@code wow}</li>
 *   <li>{@code item} 省略就照順序發一張原版唱片當圖示，不用每加一首都去挑一個</li>
 * </ul>
 *
 * <p>音檔怎麼到玩家手上見 README 的「伺服器資源包」；{@code sounds.json} 是建置時掃資料夾
 * 自動產生的，不用手寫。
 */
public final class SongShop {

    /** 音樂家開的那間店的 id。npcs.yml 的 {@code shop:} 要填這個。 */
    public static final String SHOP_ID = "musician";

    /**
     * 沒指定圖示時照順序發的唱片。
     *
     * <p>用唱片是因為玩家一看就知道那格是一首歌；照順序輪流是為了讓相鄰的兩首**看起來不一樣**，
     * 全部同一張的話櫃子會變成一整面分不出來的方格。
     */
    private static final List<String> DISCS = List.of(
            "minecraft:music_disc_13", "minecraft:music_disc_cat", "minecraft:music_disc_blocks",
            "minecraft:music_disc_chirp", "minecraft:music_disc_far", "minecraft:music_disc_mall",
            "minecraft:music_disc_mellohi", "minecraft:music_disc_stal", "minecraft:music_disc_strad",
            "minecraft:music_disc_ward", "minecraft:music_disc_11", "minecraft:music_disc_wait",
            "minecraft:music_disc_otherside", "minecraft:music_disc_5", "minecraft:music_disc_pigstep",
            "minecraft:music_disc_relic", "minecraft:music_disc_creator", "minecraft:music_disc_precipice");

    private SongShop() {
    }

    public static ShopDef from(YamlConfig cfg) {
        List<ShopEntry> entries = new ArrayList<>();

        int index = 0;
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("songs").entrySet()) {
            String id = e.getKey();
            Map<String, Object> section = e.getValue();

            String sound = YamlConfig.str(section, "sound", "");
            if (sound.isEmpty()) {
                sound = "fortress-duel:" + id;
            }
            String item = YamlConfig.str(section, "item", "");
            if (item.isEmpty()) {
                item = DISCS.get(index % DISCS.size());
            }

            entries.add(new ShopEntry(
                    id,
                    YamlConfig.str(section, "name", id),
                    "disc",
                    Math.max(0, YamlConfig.i(section, "price", 0)),
                    "",
                    "",
                    item,
                    sound,
                    Math.max(1, YamlConfig.i(section, "length", 10)),
                    1,
                    Map.of(),
                    YamlConfig.str(section, "lore", "")));
            index++;
        }

        return new ShopDef(SHOP_ID, cfg.getString("title", "音樂家"), List.copyOf(entries));
    }
}
