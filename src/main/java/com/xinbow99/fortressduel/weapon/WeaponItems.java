package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.util.DuelItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 把武器與彈藥做成玩家手上的物品。
 *
 * <p><b>全場只有一把弓，副手放什麼彈藥就射什麼。</b>這正是原版弓的運作方式——原版找箭時本來
 * 就優先看副手，所以「副手換彈」不是模仿原版直覺，它字面上就是原版的機制，玩家不用學新規則。
 *
 * <p>之前的做法是「每把武器各自是一支帶 {@code CUSTOM_DATA} 標記的弓」，實測有兩個問題：
 * 熱鍵欄裡全是弓、只能靠名字分辨；商店圖示走武器綁的物品（高爆彈顯示 TNT）但實際給的是弓，
 * 櫃子上的圖示跟買到的東西對不起來。改成彈藥實物之後兩個問題一起消失，而且**彈藥數由原版
 * 自己畫在物品格上**，HUD 不用再擠一段 {@code 100/120}。
 */
public final class WeaponItems {

    private WeaponItems() {
    }

    /** 全場通用的那把弓。開場發一把，商店也擺一把 $0 的備品。 */
    public static ItemStack createBow() {
        ItemStack stack = new ItemStack(Items.BOW);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("發射器").withStyle(ChatFormatting.AQUA));
        return DuelItems.issue(stack);
    }

    /**
     * 做一疊某種彈藥。
     *
     * <p>不加任何自訂資料——彈藥就是那個原版物品本身，辨識靠 {@code weapons.yml} 的
     * {@code item:} 反查（見 {@code WeaponSystem.byAmmoStack}）。這樣玩家從別處撿到的
     * 同一種物品也能用，不會出現「長得一樣卻射不出去」。
     */
    public static ItemStack createAmmo(WeaponDef weapon, int count) {
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.getOptional(weapon.item()).orElse(Items.STICK), count);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(weapon.displayName()).withStyle(ChatFormatting.AQUA));
        return DuelItems.issue(stack);
    }
}
