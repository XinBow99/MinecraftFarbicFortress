package com.xinbow99.fortressduel.weapon;

import java.util.HashMap;
import java.util.Map;

/**
 * 一名玩家身上各武器的彈藥。
 *
 * <p>顯示成 FPS 常見的 {@code 現有/上限}（例如 {@code 100/120}）：{@code 上限} 是這把武器
 * 最多帶幾發，買彈藥就是往上補到上限。沒有做「彈匣 + 換彈」那一層——那需要一個換彈中的狀態機
 * 與動畫，等基礎跑順了再加。
 *
 * <p>彈藥是虛擬數字而不是背包物品，理由同 {@link com.xinbow99.fortressduel.economy.Wallet}：
 * 不會被丟掉、不佔背包、對手撿不走。
 */
public final class AmmoPouch {

    private final Map<String, Integer> ammo = new HashMap<>();

    public int get(String weaponId) {
        return ammo.getOrDefault(weaponId, 0);
    }

    public boolean has(String weaponId, int amount) {
        return get(weaponId) >= amount;
    }

    /** @return true ＝ 扣掉了；不夠就一發都不扣 */
    public boolean consume(String weaponId, int amount) {
        int current = get(weaponId);
        if (current < amount) return false;

        ammo.put(weaponId, current - amount);
        return true;
    }

    /**
     * 補彈到上限。
     *
     * @return 實際補進去的數量（已經滿了就是 0，買之前要用這個判斷值不值得花錢）
     */
    public int refill(String weaponId, int amount, int capacity) {
        int current = get(weaponId);
        int added = Math.min(amount, Math.max(0, capacity - current));
        if (added > 0) {
            ammo.put(weaponId, current + added);
        }
        return added;
    }

    /** 開場配給用：直接設定，不看上限以外的東西。 */
    public void set(String weaponId, int amount) {
        ammo.put(weaponId, Math.max(0, amount));
    }
}
