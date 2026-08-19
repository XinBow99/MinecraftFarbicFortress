package com.xinbow99.fortressduel.craft;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * 音效那幾個 key 的前綴。
     *
     * <p>光碟不是材料，但它**住在同一個向量裡**——因為「組合 ＝ 向量相加」那條性質已經把
     * 疊加、遞迴（原型丟回工作台）、量產、設計的身分與快取全部處理掉了。另外開一份清單
     * 的話，那四件事每一件都要再寫一次，而且每一次都有寫錯的機會。
     *
     * <p>代價是「key 就是材料 id」這個假設不再成立，所以凡是要走 {@code counts} 的地方
     * 都得先過濾（見 {@link #materials()}）。那個代價收在這個檔案裡，不會外溢。
     */
    private static final String SONG_PREFIX = "song:";

    public static String songKey(String sound) {
        return SONG_PREFIX + sound;
    }

    public static boolean isSong(String key) {
        return key.startsWith(SONG_PREFIX);
    }

    public int count(String material) {
        return counts.getOrDefault(material, 0);
    }

    /** 只有材料的那一部分。數值、價格、名稱、外觀全部只看這個。 */
    public Map<String, Integer> materials() {
        Map<String, Integer> out = new TreeMap<>();
        counts.forEach((id, n) -> {
            if (!isSong(id)) out.put(id, n);
        });
        return out;
    }

    /**
     * 這份設計帶的音效 id，**同一首放幾張就出現幾次**。
     *
     * <p>重複不是多餘的：開火時每一份都會各播一次，疊起來就是玩家把三張同樣的光碟塞進去
     * 想要的那個效果（更大聲、更厚）。
     */
    public List<String> songs() {
        List<String> out = new ArrayList<>();
        counts.forEach((id, n) -> {
            if (!isSong(id)) return;
            for (int i = 0; i < n; i++) out.add(id.substring(SONG_PREFIX.length()));
        });
        return out;
    }

    /** 帶了幾份音效（含重複）。 */
    public int songCount() {
        return counts.entrySet().stream()
                .filter(e -> isSong(e.getKey()))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    /**
     * 總共用了幾個**材料**。價格與「這份設計有多貴重」都看這個。
     *
     * <p>音效不算：它是裝飾，不是戰力。算進去的話一發彈藥會因為好聽而變貴，而那條沒有
     * 任何道理——量產的價格是在買一發子彈的威力。
     */
    public int total() {
        return counts.entrySet().stream()
                .filter(e -> !isSong(e.getKey()))
                .mapToInt(Map.Entry::getValue)
                .sum();
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
     * 把另一份設計加進來 {@code times} 次。
     *
     * <p>給「一格疊了好幾個原型」用：那一格會被整疊吃掉，所以它貢獻的就是整疊的份量。
     */
    public AmmoVector plus(AmmoVector other, int times) {
        if (times <= 0) return this;
        Map<String, Integer> sum = new TreeMap<>(counts);
        other.counts.forEach((id, n) -> sum.merge(id, n * times, Integer::sum));
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
