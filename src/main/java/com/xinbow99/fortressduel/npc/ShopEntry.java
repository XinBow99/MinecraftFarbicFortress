package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Map;

/**
 * 商店裡的一件商品。
 *
 * <p>三種 {@code type}：
 * <ul>
 *   <li>{@code weapon}——賣一把武器（把對應物品放進背包，並補到 {@code ammo} 指定的彈藥）</li>
 *   <li>{@code ammo}——只賣子彈，補 {@code amount} 發到指定武器上（買滿了就不收錢）</li>
 *   <li>{@code item}——賣一般物品（建材之類）</li>
 * </ul>
 */
public record ShopEntry(
        String id,
        String displayName,
        String type,
        int price,
        /** type = weapon/ammo 時指向 weapons.yml 的武器 id。 */
        String weapon,
        /** type = item 時要給的物品，type = weapon 時當作展示圖示（省略就用武器綁的物品）。 */
        String item,
        /** 買一次給幾發／幾個。 */
        int amount,
        String lore
) {

    public static ShopEntry from(String id, Map<String, Object> section) {
        return new ShopEntry(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.str(section, "type", "item"),
                Math.max(0, YamlConfig.i(section, "price", 0)),
                YamlConfig.str(section, "weapon", ""),
                YamlConfig.str(section, "item", ""),
                Math.max(1, YamlConfig.i(section, "amount", 1)),
                YamlConfig.str(section, "lore", ""));
    }
}
