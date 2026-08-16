package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.weapon.ChargeCurve;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 把一個 {@link AmmoVector} 變成一把武器。
 *
 * <p>產出的是**現成的 {@link WeaponDef}**，這是整個組合系統能接上去的關鍵：射擊、彈道、
 * 方塊傷害、後座力、軌跡粒子那一整套完全不用改，它們只是拿到一份不是從 weapons.yml
 * 讀出來的定義而已。
 *
 * <p>沒有交給玩家調的欄位（冷卻、後座力、壽命、軌跡顏色）由已經決定的那幾軸推導出來，
 * 規則寫在各自的方法上。原則是：**每一個推導都要是一條真實的取捨**，不能是隨便填的常數。
 */
public final class AmmoDesign {

    /**
     * 設計的快取。同一份設計每 tick 會被查好幾次（開火、命中、HUD），而每次重建都要跑
     * 六條曲線的 {@code Math.pow}。鍵是 {@link AmmoVector#key()}，所以「怎麼組出來的」
     * 不影響命中率。
     */
    private final Map<String, WeaponDef> cache = new WeakHashMap<>();

    private final MaterialRegistry materials;

    public AmmoDesign(MaterialRegistry materials) {
        this.materials = materials;
    }

    public void clearCache() {
        cache.clear();
    }

    /** 這份設計對應的武器。同一個向量永遠得到同一把。 */
    public WeaponDef toWeapon(AmmoVector vector) {
        return cache.computeIfAbsent(vector.key(), key -> build(vector));
    }

    private WeaponDef build(AmmoVector vector) {
        double damage = axis(vector, "damage");
        double speed = axis(vector, "speed");
        double gravity = axis(vector, "guidance");
        double spread = axis(vector, "precision");
        int pellets = (int) Math.max(1, axis(vector, "pellets"));
        double splash = axis(vector, "splash");

        return new WeaponDef(
                "design/" + vector.key(),
                displayName(vector),
                // 物品是佔位用的：實際發到玩家手上的那疊會另外覆寫外觀（生物蛋模型），
                // 而彈藥的身分本來就不看物品，看的是 CUSTOM_DATA 裡的向量
                Identifier.parse("minecraft:iron_nugget"),
                damage,
                splash,
                0.0,                       // 穿甲不開放給組合：它是「剋制」不是一條連續的軸
                Set.of(),
                damage * 0.008,            // 擊退跟著傷害走，跟 weapons.yml 的預設同一條公式
                ChargeCurve.parse("linear", "design"),
                0.15,
                true, false, false,        // 蓄力只影響初速：那是拋物線武器最直覺的一條
                pellets, pellets,
                spread,
                recoil(damage, pellets),
                recoilMax(spread),
                recoilRecovery(damage, pellets),
                cooldown(damage, pellets, splash),
                speed,
                gravity,
                600,                       // 壽命只是安全網，射程由重力決定（見 weapons.yml）
                false,                     // 連射不開放：那需要一條「射速」軸，目前射速是推導的
                true,
                List.of(), 1, 1,
                1,
                0,
                Identifier.parse("minecraft:crit"),
                trailColor(vector),
                1.4,
                Identifier.parse("minecraft:entity.arrow.shoot"));
    }

    private double axis(AmmoVector vector, String attribute) {
        AttributeCurve curve = materials.curve(attribute);
        if (curve == null) return 0;

        // 一條軸可能被好幾種材料推（現在是一對一，但沒有理由寫死）
        int n = 0;
        for (MaterialRegistry.MaterialDef material : materials.all()) {
            if (material.attribute().equals(attribute)) {
                n += vector.count(material.id());
            }
        }
        return curve.valueAt(n);
    }

    /**
     * 射速由**一發的總火力**決定：越重的一發裝填越久。
     *
     * <p>沒有這條的話「最大傷害 ＋ 最大射速」不受任何限制，而射速不是玩家可以投資的軸
     * （沒有對應的材料），所以它必須是推導出來的。這也讓「多顆小彈」與「一顆重彈」
     * 在持續輸出上真的可以互相比較，而不是前者全面勝出。
     */
    private static int cooldown(double damage, int pellets, double splash) {
        double burst = damage * pellets + splash * 12;
        return (int) Math.clamp(Math.round(6 + burst * 0.55), 4, 120);
    }

    /** 後座力跟一發的重量走：越重的一發，連著開越快失準。 */
    private static double recoil(double damage, int pellets) {
        return Math.clamp(damage * pellets * 0.02, 0.2, 4.0);
    }

    /** 上限跟著基礎散佈走——本來就不準的槍，失準的天花板也更高。 */
    private static double recoilMax(double spread) {
        return Math.max(3.0, spread * 1.5);
    }

    /**
     * 回復速度照 weapons.yml 那條原則訂：每發淨增約後座力的一半，
     * 也就是 {@code recoil ÷ (2 × 冷卻秒數)}。
     *
     * <p>直接算而不是給常數，是因為那個坑踩過一次——回復比射速快的話後座力完全不存在，
     * 而且不會報錯。推導出來就不可能再踩到。
     */
    private static double recoilRecovery(double damage, int pellets) {
        double seconds = cooldown(damage, pellets, 0) / 20.0;
        return recoil(damage, pellets) / (2 * Math.max(0.05, seconds));
    }

    /** 軌跡顏色由投入最多的那一軸決定，所以看一眼曳光就知道對面帶的是什麼路線的彈。 */
    private static int trailColor(AmmoVector vector) {
        String dominant = vector.counts().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        return switch (dominant) {
            case "powder" -> 0xFF5A2A;      // 火藥：橙紅
            case "propellant" -> 0xFFE9A8;  // 推進：淡金
            case "guidance" -> 0x5AC8FF;    // 導引：天藍
            case "precision" -> 0xFFFFFF;   // 校準：白
            case "shot" -> 0xC8C8C8;        // 霰粒：灰
            case "charge" -> 0xA020F0;      // 炸藥：紫
            default -> 0xC8C8C8;
        };
    }

    private String displayName(AmmoVector vector) {
        String dominant = vector.counts().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        MaterialRegistry.MaterialDef def = dominant == null ? null : materials.byId(dominant);
        String base = def == null ? "自製彈" : def.displayName() + "彈";
        return base + " ×" + vector.total();
    }
}
