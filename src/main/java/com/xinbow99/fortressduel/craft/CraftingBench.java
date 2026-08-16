package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 原版工作台上的彈藥組合。
 *
 * <p>玩家把材料（或已經做好的原型）擺進 3×3，結果格就會出現一份新的設計。沒有固定的擺法：
 * 位置無所謂，**只有「總共放了幾個哪種材料」有意義**，因為設計的身分就是那個向量。
 *
 * <h2>為什麼不註冊自訂配方型別</h2>
 * 這個專案的前提是**客戶端不裝 mod**。註冊新的 recipe type 之後，伺服器同步配方時客戶端
 * 無法反序列化，等於強制所有人裝 mod。改成 mixin 進選單自己算結果，客戶端看到的只是
 * 「結果格裡出現了一個物品」——純物品同步，原版客戶端完全無感。
 *
 * <h2>為什麼一次只產出一個</h2>
 * 產出一疊的話會變成無限複製：9 個材料做出 8 發、每發都記著「9 個材料」，把那 8 發丟回去
 * 就是 72 個材料。而原版的合成**每一格只消耗 1 個**，所以「一次最多 9 個材料 → 一個產物」
 * 天生就是守恆的。要突破 9 格的上限就得先做兩個原型再合起來——那是一個看得見的工程，
 * 不是漏洞。
 *
 * <p>數量交給軍火商量產（用錢買），工作台只負責**設計**。
 */
public final class CraftingBench {

    /** 至少要放幾個材料才算一份設計。1 個的話等於把單一材料變成彈藥，那不是組合。 */
    private static final int MIN_MATERIALS = 2;

    /**
     * 標記「這是工作台做出來的原型」。
     *
     * <p>只有原型可以**當材料丟回工作台**；軍火商量產出來的那一疊不行。
     *
     * <p>這條分界是防套利的：量產的價格是照材料數線性算的，如果量產品也能當材料，
     * 玩家就能買一批便宜的成品拆回去當高階材料用，繞過原本的材料成本。分開之後
     * 「工作台做設計、軍火商做彈藥」不只是流程上的說法，它在經濟上也是兩種東西。
     */
    private static final String PROTOTYPE_TAG = "fortressduel_prototype";

    /**
     * 已經看過操作提示的人。
     *
     * <p>整套流程有兩步是**沒有視覺入口**的：取名要打指令、量產要拿去給軍火商。工作台
     * 只會給你一個東西，不講的話玩家會以為做完就結束了。只講第一次——之後每合成一次
     * 都洗一遍就變成噪音了。
     */
    private static final java.util.Set<java.util.UUID> hinted = new java.util.HashSet<>();

    private static ConfigManager config;

    private CraftingBench() {
    }

    /** Mixin 織進原版類別，沒有建構子可以注入，只能走這道靜態橋（跟 BowHooks 同一個做法）。 */
    public static void install(ConfigManager config) {
        CraftingBench.config = config;
    }

    /**
     * 這個格子裡的東西組得出什麼；組不出來回傳空的（讓原版的配方照常運作）。
     *
     * <p>只要格子裡有**任何一個我們不認得的東西**就直接放行——玩家在對戰之外仍然要能
     * 正常合成，而工作台是共用的。
     */
    public static ItemStack resultFor(Container grid) {
        if (config == null) return ItemStack.EMPTY;

        AmmoVector vector = AmmoVector.EMPTY;
        int slotsUsed = 0;

        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;
            slotsUsed++;

            // 已經做好的**原型**：把它整個向量加進來。「拿成品當材料」就是這一行。
            // 軍火商量產出來的彈藥沒有原型標記，會落到下面的材料查詢並被當成不認得的東西，
            // 於是整個格子交還給原版——那正是我們要的，量產品不能當材料
            AmmoVector existing = AmmoVector.read(stack).orElse(null);
            if (existing != null) {
                if (!isPrototype(stack)) return ItemStack.EMPTY;
                vector = vector.plus(existing);
                continue;
            }

            MaterialRegistry.MaterialDef material =
                    config.materials().byItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (material == null) return ItemStack.EMPTY;   // 不認得 → 交給原版

            vector = vector.plus(material.id(), 1);
        }

        if (slotsUsed < MIN_MATERIALS || vector.total() < MIN_MATERIALS) return ItemStack.EMPTY;

        WeaponDef weapon = config.designs().toWeapon(vector);
        ItemStack design = WeaponItems.createDesignAmmo(vector, weapon, 1);
        markPrototype(design);
        return design;
    }

    /** 把這疊東西標成原型（可以當材料再組）。 */
    public static void markPrototype(ItemStack stack) {
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean(PROTOTYPE_TAG, true));
    }

    public static boolean isPrototype(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
                stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(PROTOTYPE_TAG).orElse(false);
    }

    /**
     * 這一份產物是不是我們算出來的。
     *
     * <p>用「身上有沒有材料向量」判斷而不是比對物品：原版的合成結果永遠不帶這個標記，
     * 所以這個判斷不會誤判到任何一條原版配方。
     */
    public static boolean isOurs(ItemStack result) {
        return AmmoVector.read(result).isPresent();
    }

    /** 第一次做出設計時，把接下來的兩步講一次。 */
    public static void hintOnce(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!hinted.add(sp.getUUID())) return;

        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.good("做出一份設計了。接下來："));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.info(
                "  1. 拿在主手打 /duel name <名稱> 給它取個名字（可略過）"));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.info(
                "  2. 拿著它右鍵軍火商登記，他就會開始量產，架上會多一格"));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.plain(
                "  設計圖交出去就收走了，而量產出來的彈藥不能再當材料回收。",
                net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /**
     * 拿走產物之後把材料吃掉：**每一格各消耗 1 個**，跟原版合成一樣。
     *
     * <p>要自己做是因為原版那條路要走配方物件，而我們沒有註冊任何配方。
     */
    public static void consume(Container grid) {
        for (int i = 0; i < grid.getContainerSize(); i++) {
            if (!grid.getItem(i).isEmpty()) {
                grid.removeItem(i, 1);
            }
        }
    }
}
