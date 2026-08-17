package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.util.DuelItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * 材料在玩家眼裡的樣子。
 *
 * <p>材料是**原版物品**（火藥就是火藥、糖就是糖），所以少了說明的話，玩家背包裡那疊東西
 * 跟一般的火藥沒有任何差別——他不會知道它是幹嘛的，更不會知道它推的是哪一條軸。
 * 而這套系統的全部樂趣就建立在「知道每種材料換到什麼」上面。
 *
 * <h2>只講屬性，不列數字</h2>
 *
 * <p>玩家在架前要決定的是「這次買哪一種」，而那只需要知道每一種管什麼。曾經在這裡印過
 * 「投 1／3／9 個各換到多少」想把邊際遞減攤開來，結果是四行小字換一個他當下用不到的答案。
 * 實際的數值在做出彈藥之後就寫在那疊彈藥上了（見 {@link AmmoLook#apply}）——那才是它
 * 有意義的時候，而且那一份是從曲線現算的。
 *
 * <h2>順帶得到的一個性質</h2>
 *
 * <p>加了名稱與說明之後，商店買來的火藥**不會跟玩家自己帶進場的火藥疊在一起**
 * （原版的堆疊條件包含元件必須相同）。那正好是我們要的：對戰結束時要收回的是前者。
 */
public final class MaterialItems {

    private MaterialItems() {
    }

    /** 一疊看得懂的材料。 */
    public static ItemStack create(MaterialRegistry.MaterialDef def, MaterialRegistry materials, int count) {
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.getOptional(def.item()).orElse(Items.STICK), count);

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(def.displayName()).withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(describe(def, materials)));
        return DuelItems.issue(stack);
    }

    /**
     * 這種材料推的是哪一條軸。
     *
     * <p>**只講屬性，不列數字。** 曾經在這裡印過「投 1／3／9 個各換到多少」，想把邊際遞減
     * 攤給玩家看，但那是四行小字換一個他當下不需要的答案——他要決定的是「這次買哪一種」，
     * 而那只需要知道每一種管什麼。真正的數值在做出彈藥之後就寫在那疊彈藥上了
     * （見 {@link AmmoLook#apply}），那才是它有意義的時候。
     */
    public static List<Component> describe(MaterialRegistry.MaterialDef def, MaterialRegistry materials) {
        List<Component> lore = new ArrayList<>();
        AttributeCurve curve = materials.curve(def.attribute());
        if (curve == null) {
            lore.add(line("這種材料的屬性沒有定義（materials.yml）", ChatFormatting.RED));
            return lore;
        }

        lore.add(line("屬性：" + curve.label() + (curve.down() ? "（越低越好）" : ""),
                ChatFormatting.GREEN));

        // 唯一留下的例外：連發機構會改變**操作方式**，那件事屬性名稱講不出來
        if (!def.note().isEmpty()) {
            lore.add(line(def.note(), ChatFormatting.YELLOW));
        }
        return lore;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
}
