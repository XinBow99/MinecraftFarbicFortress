package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.FortressDuel;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉弓的力道曲線：把「拉了多久」（0~1）換算成「力道」（0~1）。
 *
 * <p>力道值**不交給原版算**。我們攔的是放開右鍵的那一刻，拿到的是原始的拉弓 tick 數，
 * 要怎麼換算完全自己決定——原版的 {@code (f² + 2f) / 3} 只是其中一種可選的形狀。
 * 所以「逐武器不同曲線」是純資料，加一種手感不用寫 Java。
 *
 * <p><b>滿弓固定是 20 tick，這點不能改。</b> 拉弓動畫由客戶端自己數 tick 播放，伺服器改不到，
 * 而客戶端是純原版。把某把武器設成 40 tick 才滿力道的話，玩家看到的弓在第 20 tick 就已經拉滿、
 * 之後畫面毫無變化但實際力道還在漲——視覺會說謊，那比曲線難調糟糕得多。所以這裡只負責
 * 0→1 之間的形狀，兩端永遠對齊動畫。
 */
public final class ChargeCurve {

    /** 原版弓的曲線：前段漲得快、後段趨緩。 */
    public static final ChargeCurve VANILLA = new ChargeCurve(null, "vanilla");
    /** 線性：好抓落點，適合當測距工具的拋物線武器。 */
    public static final ChargeCurve LINEAR = new ChargeCurve(null, "linear");

    /** 分段折線的節點，依 x 遞增；null ＝ 用具名公式。 */
    private final List<double[]> points;
    private final String named;

    private ChargeCurve(List<double[]> points, String named) {
        this.points = points;
        this.named = named;
    }

    /**
     * 從 YAML 讀一條曲線。
     *
     * <p>支援兩種寫法：具名（{@code curve: vanilla}）或分段折線
     * （{@code curve: [[0, 0.2], [0.7, 0.45], [1.0, 1.0]]}）。折線的寫法跟 buildings.yml
     * 的逐層字元圖是同一個路數——形狀直接畫出來，不必先在腦中解一條公式。
     */
    public static ChargeCurve parse(Object raw, String weaponId) {
        if (raw == null) return VANILLA;

        if (raw instanceof String name) {
            return switch (name.toLowerCase()) {
                case "linear" -> LINEAR;
                case "vanilla" -> VANILLA;
                default -> {
                    FortressDuel.LOGGER.warn("Weapon {} uses unknown charge curve '{}', falling back to vanilla",
                            weaponId, name);
                    yield VANILLA;
                }
            };
        }

        if (raw instanceof List<?> list) {
            List<double[]> parsed = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof List<?> pair) || pair.size() < 2) continue;
                parsed.add(new double[]{
                        ((Number) pair.get(0)).doubleValue(),
                        ((Number) pair.get(1)).doubleValue()});
            }
            // 少於兩點畫不出折線
            if (parsed.size() >= 2) {
                parsed.sort((a, b) -> Double.compare(a[0], b[0]));
                return new ChargeCurve(parsed, null);
            }
            FortressDuel.LOGGER.warn("Weapon {} has a charge curve with fewer than two points, falling back to vanilla",
                    weaponId);
        }

        return VANILLA;
    }

    /**
     * @param drawn 拉了多久，0 ＝ 剛按下、1 ＝ 滿弓
     * @return 力道，夾在 0~1
     */
    public double power(double drawn) {
        double t = Math.clamp(drawn, 0.0, 1.0);

        if (points == null) {
            return Math.clamp(switch (named) {
                case "linear" -> t;
                // 原版 BowItem.getPowerForTime 的形狀
                default -> (t * t + t * 2.0) / 3.0;
            }, 0.0, 1.0);
        }

        // 折線：找到 t 落在哪一段，段內線性內插
        if (t <= points.getFirst()[0]) return Math.clamp(points.getFirst()[1], 0.0, 1.0);
        if (t >= points.getLast()[0]) return Math.clamp(points.getLast()[1], 0.0, 1.0);

        for (int i = 1; i < points.size(); i++) {
            double[] lo = points.get(i - 1);
            double[] hi = points.get(i);
            if (t > hi[0]) continue;

            double span = hi[0] - lo[0];
            // 同一個 x 疊了兩個點（做垂直跳變用）：直接取後面那個
            double ratio = span <= 1.0E-9 ? 1.0 : (t - lo[0]) / span;
            return Math.clamp(lo[1] + (hi[1] - lo[1]) * ratio, 0.0, 1.0);
        }
        return Math.clamp(points.getLast()[1], 0.0, 1.0);
    }
}
