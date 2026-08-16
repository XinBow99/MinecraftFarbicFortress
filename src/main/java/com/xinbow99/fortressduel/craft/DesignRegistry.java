package com.xinbow99.fortressduel.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每個玩家已經在軍火商那裡登記過的設計。
 *
 * <p>**逐人**而不是全服共用：配方探索本身是競爭的一部分，對手要嘛自己試出來、要嘛就是沒有。
 * 名字也因此自然是逐人的，兩個人各自發現同一組材料、各自取名，不會打架。
 *
 * <p>不持久化。設計只在這一場有意義——經濟、材料、彈藥全部都是打完就收的，配方沒有理由例外。
 * 對戰結束時整份清掉（見 {@code NpcManager} 的 END 事件）。
 */
public final class DesignRegistry {

    /** 一份登記過的設計：材料向量 ＋ 玩家給它的名字。 */
    public record Design(AmmoVector vector, String name) {
    }

    /** 玩家 → 他登記過的設計。用 {@link LinkedHashMap} 保留登記順序，架上的位置才不會跳來跳去。 */
    private final Map<UUID, Map<String, Design>> byPlayer = new LinkedHashMap<>();

    /**
     * 登記一份設計。
     *
     * @return true ＝ 這是新的；false ＝ 早就登記過了（同樣的材料就是同一份設計，名字不算）
     */
    public boolean register(UUID player, AmmoVector vector, String name) {
        Map<String, Design> designs = byPlayer.computeIfAbsent(player, k -> new LinkedHashMap<>());
        return designs.put(vector.key(), new Design(vector, name)) == null;
    }

    public List<Design> designsOf(UUID player) {
        Map<String, Design> designs = byPlayer.get(player);
        return designs == null ? List.of() : new ArrayList<>(designs.values());
    }

    public Design byKey(UUID player, String key) {
        Map<String, Design> designs = byPlayer.get(player);
        return designs == null ? null : designs.get(key);
    }

    public void forget(UUID player) {
        byPlayer.remove(player);
    }
}
