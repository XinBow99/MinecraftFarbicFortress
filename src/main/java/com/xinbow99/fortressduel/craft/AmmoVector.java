package com.xinbow99.fortressduel.craft;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 一份彈藥設計裡**總共用掉了幾個哪種材料**。
 *
 * <p>整套組合系統就建立在這一個東西上：**組合 ＝ 向量相加**。材料貢獻自己那一軸的 1，
 * 已經做好的原型貢獻它記著的整個向量——所以「把成品丟回去再組」不需要另外一套規則，
 * 它是加法的自然結果。
 *
 * <p>用 {@link TreeMap} 而不是 HashMap：{@link #key()} 要求同一組材料永遠得到同一個字串，
 * 而那個字串同時是設計的身分、外觀（決定挑到哪顆生物蛋）與快取的鍵。順序不穩定的話，
 * 同一個配方會時而被當成兩個不同的東西。
 *
 * <p>存在 {@link DataComponents#CUSTOM_DATA} 裡，跟 {@code DuelItems} 的標記同一個機制。
 * 這也是為什麼彈藥的身分**不能**再靠物品 id：組合是無界的，而物品只有那幾種。
 */
public record AmmoVector(Map<String, Integer> counts) {

    /** 存在 CUSTOM_DATA 裡的鍵。加前綴避免跟別的 mod 撞名。 */
    private static final String TAG = "fortressduel_ammo";

    public static final AmmoVector EMPTY = new AmmoVector(Map.of());

    public AmmoVector {
        counts = Map.copyOf(new TreeMap<>(counts));
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int count(String material) {
        return counts.getOrDefault(material, 0);
    }

    /** 總共用了幾個材料。價格與「這份設計有多貴重」都看這個。 */
    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 相加。這就是「組合」的全部。 */
    public AmmoVector plus(AmmoVector other) {
        Map<String, Integer> sum = new TreeMap<>(counts);
        other.counts.forEach((id, n) -> sum.merge(id, n, Integer::sum));
        return new AmmoVector(sum);
    }

    public AmmoVector plus(String material, int n) {
        if (n <= 0) return this;
        Map<String, Integer> sum = new TreeMap<>(counts);
        sum.merge(material, n, Integer::sum);
        return new AmmoVector(sum);
    }

    /**
     * 這份設計的身分。同一組材料永遠得到同一個字串，不管它是怎麼被組出來的——
     * 一次放九個火藥，跟先做兩個原型再合起來，結果是同一份設計，也就長得一樣。
     */
    public String key() {
        StringBuilder sb = new StringBuilder();
        counts.forEach((id, n) -> sb.append(id).append(':').append(n).append(';'));
        return sb.toString();
    }

    // ---------- 存取物品 ----------

    public static Optional<AmmoVector> read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();

        CompoundTag root = data.copyTag();
        return root.getCompound(TAG).map(tag -> {
            Map<String, Integer> counts = new TreeMap<>();
            for (String id : tag.keySet()) {
                tag.getInt(id).filter(n -> n > 0).ifPresent(n -> counts.put(id, n));
            }
            return new AmmoVector(counts);
        }).filter(v -> !v.isEmpty());
    }

    public void write(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag tag = new CompoundTag();
            counts.forEach(tag::putInt);
            root.put(TAG, tag);
        });
    }

    @Override
    public String toString() {
        return key();
    }
}
