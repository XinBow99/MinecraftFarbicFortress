package com.xinbow99.fortressduel.weapon;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * 把一把武器做成玩家手上的物品。
 *
 * <p>蓄力武器全部是 {@code minecraft:bow}——這是唯一能拿到拉弓動畫與力道回饋、而且客戶端
 * 不用裝任何東西的做法。但這樣一來「手上這支弓是哪把武器」就不能再靠物品型別分辨了，
 * 所以武器 id 存進 {@link DataComponents#CUSTOM_DATA}。
 *
 * <p>用自訂資料而不是副手放彈種，有兩個好處：9 把武器仍然是 9 個獨立的物品堆疊，熱鍵切換
 * 照舊（副手方案要切副手，在 60 秒的攻擊階段裡慢得有感）；而且商店與 {@code /duel give}
 * 幾乎不用改。代價是熱鍵欄裡的蓄力武器全是弓的外觀，只能靠名字分辨。
 *
 * <p>右鍵即發的武器仍然綁各自的原版物品（鐵粒、燧石、TNT…），走 {@code byItem} 那條舊路。
 */
public final class WeaponItems {

    /** 存在 CUSTOM_DATA 裡的鍵。加前綴避免跟別的 mod 撞名。 */
    public static final String WEAPON_KEY = "fortress_duel_weapon";

    private WeaponItems() {
    }

    /** 做一份這把武器的物品。 */
    public static ItemStack create(WeaponDef weapon) {
        ItemStack stack = new ItemStack(weapon.bowLaunched()
                ? Items.BOW
                : BuiltInRegistries.ITEM.getOptional(weapon.item()).orElse(Items.STICK));

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(weapon.displayName()).withStyle(ChatFormatting.AQUA));

        // 即發武器也一併標記：那條路目前靠 byItem 就夠了，但兩把武器綁同一個物品時
        // byItem 只會回傳其中一把，有標記就不會有這個歧義
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString(WEAPON_KEY, weapon.id()));
        return stack;
    }

    /**
     * 這個物品堆疊代表哪把武器的 id；沒有標記回傳 null。
     *
     * @return 只是 id，還要拿去 {@code weapons.yml} 查——設定重載後 id 可能已經不存在了
     */
    public static String weaponIdOf(ItemStack stack) {
        if (stack.isEmpty()) return null;

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;

        String id = data.copyTag().getStringOr(WEAPON_KEY, "");
        return id.isEmpty() ? null : id;
    }
}
