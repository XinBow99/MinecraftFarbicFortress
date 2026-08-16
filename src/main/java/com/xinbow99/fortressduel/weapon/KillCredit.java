package com.xinbow99.fortressduel.weapon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「你是被哪一發打死的」。
 *
 * <p>原版的死亡訊息只講得出「A 被 B 殺死了」——因為它認得的是**攻擊者手上那件物品**，
 * 而這個 mod 的彈藥在副手，主手永遠是那把弓。於是十種武器加上無限多種自製設計，
 * 死起來全部長一樣，看的人完全不知道剛剛挨的是狙擊還是高爆。
 *
 * <p>做法是命中時把彈藥名字記下來，死亡訊息生成時（{@code CombatTrackerMixin}）換掉那一行。
 * 不另外註冊傷害型別：型別是要同步給客戶端的資料，而訊息本身是翻譯鍵——沒裝模組的人
 * 只會看到一串鍵名。直接送成品文字才在原版客戶端上讀得出來，跟這個專案其他地方同一個前提。
 *
 * <h2>為什麼要求同一 tick</h2>
 * 只有「這一發就是致命傷」才換訊息。中彈之後被推下去摔死、或四秒後餓死的，仍然照原版
 * 說是摔死／餓死——那才是真的死因。扣血與死亡在同一個 tick 內同步走完，所以這個條件
 * 既精準又不需要另外去比對傷害來源。
 */
public final class KillCredit {

    /**
     * 記到幾筆才順手清一次。
     *
     * <p>紀錄是每個受害者一筆、後來的蓋掉先前的，所以同時在線的人有幾個就是幾筆；
     * 會殘留的只有「中彈之後離線且沒死」的那種。定期清掉就夠了，不值得為它掛一個事件。
     */
    private static final int PURGE_THRESHOLD = 64;

    /** 超過這麼多 tick 的紀錄一定用不到了（換訊息只認同一 tick），清理時就照這條丟。 */
    private static final int STALE_TICKS = 200;

    private record Hit(String ammoName, Component killer, long tick) {
    }

    private static final Map<UUID, Hit> hits = new HashMap<>();

    private KillCredit() {
    }

    /** 記下「這個人剛剛被這發打中」。只記玩家：死亡訊息只有玩家會播。 */
    public static void record(LivingEntity victim, ServerPlayer shooter, String ammoName) {
        if (!(victim instanceof ServerPlayer player)) return;

        if (hits.size() > PURGE_THRESHOLD) {
            long now = victim.level().getGameTime();
            hits.values().removeIf(hit -> now - hit.tick() > STALE_TICKS);
        }
        hits.put(player.getUUID(), new Hit(ammoName, shooter.getDisplayName(), victim.level().getGameTime()));
    }

    /** 那一發其實沒打進去（傷害被規則擋掉），把剛才記的抹掉。 */
    public static void forget(LivingEntity victim) {
        hits.remove(victim.getUUID());
    }

    /**
     * 這個人的死亡訊息；不是被彈藥打死的回傳 null（讓原版自己講）。
     *
     * <p>不在這裡把紀錄刪掉：一次死亡會問兩次（廣播一次、死亡畫面一次），刪掉的話
     * 第二次會拿到原版的版本，兩邊講的就不一樣了。留著等它過期。
     */
    public static Component deathMessage(LivingEntity victim) {
        Hit hit = hits.get(victim.getUUID());
        if (hit == null || victim.level().getGameTime() != hit.tick()) return null;

        return Component.empty()
                .append(victim.getDisplayName())
                .append(Component.literal(" 被 "))
                .append(hit.killer())
                .append(Component.literal(" 的 "))
                .append(Component.literal(hit.ammoName()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 擊倒"));
    }
}
