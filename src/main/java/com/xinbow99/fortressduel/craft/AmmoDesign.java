package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.weapon.ChargeCurve;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
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
     * 每一條軸的 {@code Math.pow}。鍵是 {@link AmmoVector#key()}，所以「怎麼組出來的」
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

        // 連發機構是唯一「有沒有投入」本身就有意義的一條：投了才是連射武器
        int cadence = invested(vector, "cadence");

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
                cooldown(damage, pellets, splash, cadence),
                speed,
                gravity,
                600,                       // 壽命只是安全網，射程由重力決定（見 weapons.yml）
                cadence > 0,               // 投了連發機構就是按住不放的槍
                true,
                List.of(), 1, 1,
                1,
                0,
                Identifier.parse("minecraft:crit"),
                trailColor(vector),
                1.4,
                fireSounds(vector));
    }

    private double axis(AmmoVector vector, String attribute) {
        AttributeCurve curve = materials.curve(attribute);
        return curve == null ? 0 : curve.valueAt(invested(vector, attribute));
    }

    /** 這份設計在某一條軸上總共投了幾個材料。一條軸可能被好幾種材料推（現在是一對一）。 */
    private int invested(AmmoVector vector, String attribute) {
        int n = 0;
        for (MaterialRegistry.MaterialDef material : materials.all()) {
            if (material.attribute().equals(attribute)) {
                n += vector.count(material.id());
            }
        }
        return n;
    }

    /**
     * 裝填間隔。
     *
     * <p>**基準由一發的總火力決定**（越重的一發裝填越久），玩家再用連發機構把它往下壓。
     * 兩段式而不是讓射速自成一條獨立的軸，是因為傷害 × 射速 ＝ DPS 是**相乘**的：
     * 兩條各自 {@code n^0.6} 的軸相乘就是 {@code n^1.2}，而價格是線性的 {@code n}——
     * 那會讓「傷害＋射速」對半下注變成一個報酬超線性的組合，正好推翻整套設計賴以成立的
     * 那條「專精與堆疊在金錢上永遠是虧的」。
     *
     * <p>接在推導值後面就沒有這個問題：冷卻與火力成正比，相除之後火力抵銷掉，DPS 只剩
     * 連發機構那一條的 {@code n^0.6}。傷害買的是**單發的穿透力**（破得了幾格牆、能不能
     * 一發帶走），射速買的才是持續輸出——兩者不再是同一件事。
     */
    private int cooldown(double damage, int pellets, double splash, int cadence) {
        double burst = damage * pellets + splash * 12;
        double base = Math.clamp(6 + burst * 0.55, 4, 120);

        AttributeCurve curve = materials.curve("cadence");
        double ticks = curve == null ? base : curve.valueAt(cadence, base);
        return (int) Math.max(1, Math.round(ticks));
    }

    /**
     * 這份設計開火時放什麼。
     *
     * <p>沒放光碟就是原版的弓聲；放了就**改放那幾首**，而不是弓聲再加上去——三首歌上面
     * 再疊一聲「咻」只是噪音，而歌本身已經是足夠清楚的開火回饋了。
     *
     * <p>同一首放兩張就會在清單裡出現兩次，也就會被送兩次：那正是把兩張塞進去的人想要的。
     */
    private static List<Identifier> fireSounds(AmmoVector vector) {
        List<String> songs = vector.songs();
        if (songs.isEmpty()) return List.of(Identifier.parse("minecraft:entity.arrow.shoot"));

        List<Identifier> sounds = new ArrayList<>(songs.size());
        for (String song : songs) {
            Identifier id = Identifier.tryParse(song);
            if (id != null) sounds.add(id);
        }
        return sounds.isEmpty() ? List.of(Identifier.parse("minecraft:entity.arrow.shoot")) : sounds;
    }

    /**
     * 投入最多的那一種**材料**。
     *
     * <p>要走 {@code materials()} 不能走 {@code counts()}：音效也住在同一個向量裡，
     * 而三張光碟會蓋過兩個火藥變成「主材料」——那會讓曳光顏色與預設名稱跟著音效跑。
     */
    private static String dominant(AmmoVector vector) {
        return vector.materials().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /** 軌跡顏色由投入最多的那一軸決定，所以看一眼曳光就知道對面帶的是什麼路線的彈。 */
    private static int trailColor(AmmoVector vector) {
        return switch (dominant(vector)) {
            case "powder" -> 0xFF5A2A;      // 火藥：橙紅
            case "propellant" -> 0xFFE9A8;  // 推進：淡金
            case "guidance" -> 0x5AC8FF;    // 導引：天藍
            case "precision" -> 0xFFFFFF;   // 校準：白
            case "shot" -> 0xC8C8C8;        // 霰粒：灰
            case "charge" -> 0xA020F0;      // 炸藥：紫
            case "cadence" -> 0x7CFF5A;     // 連發機構：亮綠
            default -> 0xC8C8C8;
        };
    }

    private String displayName(AmmoVector vector) {
        MaterialRegistry.MaterialDef def = materials.byId(dominant(vector));
        String base = def == null ? "自製彈" : def.displayName() + "彈";
        return base + " ×" + vector.total();
    }
}
