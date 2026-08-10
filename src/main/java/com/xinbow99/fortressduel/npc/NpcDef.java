package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * 一個 NPC 的設定，對應 npcs.yml 裡的一個區段。
 *
 * <p>跟怪物一樣，不註冊新的實體型別——拿一個原版實體當殼，關掉 AI、設成無敵，
 * 右鍵它就開對應的商店。
 */
public record NpcDef(
        String id,
        String displayName,
        Identifier entity,
        /** 右鍵它會開哪一間店（對應 shops.yml）；空字串 ＝ 只是個裝飾。 */
        String shop,
        /** 頭上的名字要不要一直顯示。 */
        boolean nameVisible
) {

    public static NpcDef from(String id, Map<String, Object> section) {
        return new NpcDef(
                id,
                YamlConfig.str(section, "name", id),
                Identifier.parse(YamlConfig.str(section, "entity", "minecraft:villager")),
                YamlConfig.str(section, "shop", ""),
                YamlConfig.bool(section, "name_visible", true));
    }
}
