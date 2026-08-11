package com.xinbow99.fortressduel.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 標記「這疊東西是對戰發的」，以及對戰結束時把它們收回來。
 *
 * <p>為什麼需要標記而不是直接清空背包：**這個遊戲不傳送玩家**，競技場是就地框在他們站的
 * 位置上的，所以玩家帶著自己原本的背包進場。打完把背包清掉等於沒收他的家當。
 *
 * <p>標記走 {@link DataComponents#CUSTOM_DATA}。順帶得到一個必要的性質：
 * **帶標記的物品不會跟同種類的無標記物品疊在一起**（原版的堆疊條件包含元件必須相同），
 * 所以玩家自己帶進場的 20 個泥土跟對戰發的 20 個泥土會是兩疊，收回時不會誤拿他的。
 */
public final class DuelItems {

    /** 存在 CUSTOM_DATA 裡的鍵。加前綴避免跟別的 mod 撞名。 */
    private static final String ISSUED_KEY = "fortress_duel_issued";

    private DuelItems() {
    }

    /** 打上「對戰發的」標記，回傳同一個堆疊方便串接。 */
    public static ItemStack issue(ItemStack stack) {
        if (stack.isEmpty()) return stack;

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(ISSUED_KEY, true));
        return stack;
    }

    public static boolean isIssued(ItemStack stack) {
        if (stack.isEmpty()) return false;

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty() && data.copyTag().getBooleanOr(ISSUED_KEY, false);
    }

    /**
     * 把這個人身上所有「對戰發的」東西收回來。
     *
     * <p>逐格掃而不是用 {@code clearOrCountMatchingItems}：要涵蓋副手與盔甲欄，而且這樣
     * 「哪些格子被動到」是一目了然的。
     *
     * @return 收回了幾疊
     */
    public static int stripFrom(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int removed = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isIssued(inventory.getItem(slot))) {
                inventory.setItem(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        if (removed > 0) {
            // 不送這個封包的話，客戶端會繼續畫著已經被清掉的格子，直到下一次背包同步
            player.containerMenu.broadcastChanges();
        }
        return removed;
    }
}
