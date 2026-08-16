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
 * <h2>為什麼數字是算出來的</h2>
 *
 * <p>說明裡的每一個數字都從 {@link AttributeCurve} 現算，一個字都不手寫——跟 shops.yml
 * 那條「血量與幾發打得破由程式印上去」是同一條規矩。手寫的數字會跟設定漂移，
 * 而漂掉的那天沒有人會發現。改 materials.yml 的曲線，架上與背包裡的說明會一起跟著動。
 *
 * <h2>順帶得到的一個性質</h2>
 *
 * <p>加了名稱與說明之後，商店買來的火藥**不會跟玩家自己帶進場的火藥疊在一起**
 * （原版的堆疊條件包含元件必須相同）。那正好是我們要的：對戰結束時要收回的是前者。
 */
public final class MaterialItems {

    /** 說明裡示範幾個投入量。挑 1／3／9 是因為 9 正好是一次合成的上限（3×3 每格 1 個）。 */
    private static final int[] SAMPLES = {1, 3, 9};

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
     * 這種材料在做什麼，以及投進去換到多少。
     *
     * <p>直接把曲線攤開給玩家看，而不是寫「大幅提升傷害」那種形容詞——邊際遞減是這套系統
     * 唯一需要玩家理解的規則（九倍的材料換不到三倍的效果），而它用三個數字就講完了。
     * 講不清楚的話玩家會一路把錢押在同一條軸上，然後不知道自己為什麼一直虧。
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

        StringBuilder steps = new StringBuilder();
        if (curve.derivedBase()) {
            // 沒有固定的基準（射速是從這一發的重量推出來的），所以印倍率而不是絕對值
            lore.add(line("基準看這一發有多重，投料把它往下壓：", ChatFormatting.GRAY));
            for (int n : SAMPLES) {
                if (!steps.isEmpty()) steps.append("   ");
                steps.append(n).append(" 個 ×")
                        .append(String.format("%.2f", curve.factorAt(n)));
            }
        } else {
            lore.add(line("不投入 " + number(curve, curve.valueAt(0)), ChatFormatting.GRAY));
            for (int n : SAMPLES) {
                if (!steps.isEmpty()) steps.append("   ");
                steps.append(n).append(" 個 ").append(number(curve, curve.valueAt(n)));
            }
        }
        lore.add(line(steps.toString(), ChatFormatting.GRAY));

        if (!def.note().isEmpty()) {
            lore.add(line(def.note(), ChatFormatting.YELLOW));
        }
        lore.add(line("投九倍換不到三倍——攤開來點比押同一條划算", ChatFormatting.DARK_GRAY));
        lore.add(line("放進工作台，兩格以上就組得出一份彈藥設計", ChatFormatting.DARK_GRAY));
        return lore;
    }

    /** 顆數那條要印成整數，其他的印兩位小數——「3.00 顆」看起來像壞掉。 */
    private static String number(AttributeCurve curve, double value) {
        String text = curve.round()
                ? String.valueOf((int) value)
                : String.format(value >= 1 ? "%.2f" : "%.4f", value);
        return text + curve.suffix();
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
}
