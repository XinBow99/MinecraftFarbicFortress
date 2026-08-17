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
        /** 血量。NPC 打得死，玩家必須保護他——他死了那一側就買不到東西。 */
        double health,
        /** 頭上的名字要不要一直顯示。 */
        boolean nameVisible,
        /**
         * 活多久（秒）就自己收攤；0 ＝ 留到對戰結束。
         *
         * <p>給**事件放出來的**商人用。長駐的商人（軍火商）是這一側的命脈，臨時出現的則是
         * 這一輪的機會——而機會要有期限才是機會，不然它只是一個晚一點才拿到的常駐設施，
         * 「現在要不要放下手邊的事去買」那個決定就不存在了。
         */
        int lifespanSeconds
) {

    public static NpcDef from(String id, Map<String, Object> section) {
        return new NpcDef(
                id,
                YamlConfig.str(section, "name", id),
                Identifier.parse(YamlConfig.str(section, "entity", "minecraft:villager")),
                YamlConfig.str(section, "shop", ""),
                Math.max(1.0, YamlConfig.d(section, "health", 200.0)),
                YamlConfig.bool(section, "name_visible", true),
                Math.max(0, YamlConfig.i(section, "lifespan_seconds", 0)));
    }
}
