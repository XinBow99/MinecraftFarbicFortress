package com.xinbow99.fortressduel.economy;

import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.util.Msg;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 錢的來源與去處。
 *
 * <p>兩個 faucet，跟網頁版同一組：
 * <ul>
 *   <li><b>怪物賞金</b>——唯一的競爭性收入。誰打死的誰拿，所以「清怪」是一個要跟對手搶的目標。</li>
 *   <li><b>階段收入</b>——每一輪建造階段開始時雙方各拿一份，保證落後方也有基本盤，
 *       不會被賞金滾雪球直接輾死。</li>
 * </ul>
 *
 * <p>錢包跟著對戰走，對戰結束就丟掉——單場經濟，不跨場累積。
 */
public final class EconomyManager {

    private final ConfigManager config;
    private final DuelManager duels;
    private final SkillEngine skills;

    private final Map<UUID, Wallet> wallets = new HashMap<>();

    public EconomyManager(ConfigManager config, DuelManager duels, SkillEngine skills) {
        this.config = config;
        this.duels = duels;
        this.skills = skills;
    }

    public void register() {
        DuelEvents.START.register(this::onDuelStart);
        DuelEvents.END.register((duel, result) -> {
            wallets.remove(duel.north().playerId());
            wallets.remove(duel.south().playerId());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onEntityDeath);
    }

    private void onDuelStart(Duel duel) {
        int starting = config.settings().startingMoney();
        wallets.put(duel.north().playerId(), new Wallet(starting));
        wallets.put(duel.south().playerId(), new Wallet(starting));
    }

    /** 每一輪建造階段開始時發，由 {@link Duel} 呼叫。 */
    public void payRoundIncome(ServerPlayer player) {
        int income = config.settings().roundIncome();
        if (income <= 0) return;

        Wallet wallet = wallets.get(player.getUUID());
        if (wallet == null) return;

        wallet.earn(income);
        player.sendSystemMessage(Msg.good("本輪收入 +$" + income + "（餘額 $" + wallet.balance() + "）"));
    }

    /**
     * 怪物死亡 → 賞金給打死牠的人。
     *
     * <p>只在攻擊階段算數。建造階段對手打不到你，站在中場砍怪是零風險的收入——那會讓
     * 「該蓋牆還是該去搶怪」變成假選擇（當然去砍怪）。把賞金鎖在攻擊階段，搶怪才需要
     * 承擔「這段時間對手正在打你的牆」的代價。
     */
    private void onEntityDeath(LivingEntity entity, DamageSource source) {
        MobDef def = skills.definitionOf(entity);
        if (def == null || def.reward() <= 0) return;

        if (!(source.getEntity() instanceof ServerPlayer killer)) return;

        Wallet wallet = wallets.get(killer.getUUID());
        if (wallet == null) return;

        Duel duel = duels.duelOf(killer);
        if (duel == null) return;
        if (!duel.state().canAttack()) {
            killer.sendSystemMessage(Msg.plain("建造階段不發賞金", ChatFormatting.GRAY), true);
            return;
        }

        wallet.earn(def.reward());
        killer.sendSystemMessage(Msg.plain("+$" + def.reward() + "  " + def.displayName()
                + "  （$" + wallet.balance() + "）", ChatFormatting.GOLD), true);
        killer.level().playSound(null, killer.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.4f);
    }

    // ---------- 查詢 ----------

    public Wallet walletOf(ServerPlayer player) {
        return wallets.get(player.getUUID());
    }

    public int balanceOf(ServerPlayer player) {
        Wallet wallet = wallets.get(player.getUUID());
        return wallet == null ? 0 : wallet.balance();
    }

}
