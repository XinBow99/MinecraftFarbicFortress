package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Map;

/**
 * 一條軸：從「投入了幾個材料」換算成實際數值。
 *
 * <p>核心是**邊際遞減**（{@code exponent}，預設 0.6）配上**線性的價格**：九倍的投入只換到
 * 不到三倍的效果，所以把錢全押在一條軸上永遠是虧的。攤開來點才划算，而「我就是要一發
 * 超重的」變成奢侈品而不是最優解。
 *
 * <p>這個性質是結構性的，不靠數值維持——無論之後怎麼調 base 與 unit，{@code n^0.6} 對上
 * {@code n} 的差距都在。組合遞迴（把做好的彈藥丟回去再組）也因此安全：套幾層都追不上價格。
 *
 * <p>{@code direction: down} 是給「越低越好」的兩條軸用的（重力、散佈）。它們走的是
 * 逼近下限的倒數曲線，永遠到不了 {@code floor}——所以再有錢也買不到絕對的零散佈。
 */
public record AttributeCurve(
        /** 這條軸叫什麼（給玩家看的）。材料的說明文字靠它，沒有的話架上只會出現 id。 */
        String label,
        /**
         * 數值的單位後綴（度、格、顆…）。空字串 ＝ 沒有單位。
         *
         * <p>叫 suffix 不叫 unit 是因為 {@link #unit} 已經被曲線的係數用掉了——那兩個東西
         * 名字撞在一起，而它們一個是給玩家看的字、一個是數學。
         */
        String suffix,
        /** 投入 0 個時的值。刻意是「很差但能用」而不是 0，見 materials.yml。 */
        double base,
        double unit,
        double exponent,
        /** true ＝ 越多越小（重力、散佈）。 */
        boolean down,
        /** {@code down} 時的下限，逼近但到不了。 */
        double floor,
        /** 上限；{@link Double#MAX_VALUE} ＝ 不限。顆數與濺射的上限是效能不是平衡。 */
        double max,
        /** 取整數（顆數用）。 */
        boolean round,
        /**
         * true ＝ 這條軸的 {@code base} 是**執行時算出來的**，不是設定值（目前只有射速）。
         *
         * <p>只影響說明文字：沒有固定的基準就印不出「投 3 個變成多少」，所以那種軸改成印
         * **倍率**。不標的話玩家會看到一串從 base 0 推出來的負數。
         */
        boolean derivedBase
) {

    public static AttributeCurve from(String id, Map<String, Object> section) {
        return new AttributeCurve(
                YamlConfig.str(section, "label", id),
                YamlConfig.str(section, "suffix", ""),
                YamlConfig.d(section, "base", 0.0),
                YamlConfig.d(section, "unit", 1.0),
                YamlConfig.d(section, "exponent", 0.6),
                "down".equalsIgnoreCase(YamlConfig.str(section, "direction", "up")),
                YamlConfig.d(section, "floor", 0.0),
                YamlConfig.d(section, "max", Double.MAX_VALUE),
                YamlConfig.bool(section, "round", false),
                YamlConfig.bool(section, "derived_base", false));
    }

    /**
     * 投入 {@code n} 個之後，值變成原本的幾倍（只對 {@code down} 的軸有意義）。
     *
     * <p>給 {@link #derivedBase()} 那種軸的說明文字用：它沒有固定的基準，印不出絕對值，
     * 但「打了對折」是講得清楚的。忽略 {@code floor}——基準遠高於下限時那條不影響，
     * 而說明本來就只是給人抓量級的。
     */
    public double factorAt(int n) {
        return n <= 0 ? 1.0 : 1.0 / (1 + unit * Math.pow(n, exponent));
    }

    /** 投入 {@code n} 個材料之後這條軸的值。 */
    public double valueAt(int n) {
        return valueAt(n, base);
    }

    /**
     * 同上，但基準由呼叫端給。
     *
     * <p>給射速那條軸用：它的「投入 0 個」不是一個常數，而是**從這一發有多重推出來的**
     * 冷卻（見 {@code AmmoDesign.cooldown}）。把基準留在設定檔裡的話那個耦合就斷了，
     * 而斷掉的後果是傷害與射速變成兩條互相獨立、可以同時買滿的軸。
     */
    public double valueAt(int n, double base) {
        double curved = n <= 0 ? 0 : unit * Math.pow(n, exponent);
        double value = down
                ? floor + (base - floor) / (1 + curved)
                : base + curved;

        value = Math.min(value, max);
        return round ? Math.floor(value) : value;
    }
}
