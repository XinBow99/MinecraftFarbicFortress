package com.xinbow99.fortressduel.incident;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;

/**
 * 突發事件的排程。
 *
 * <p>網頁版是「每回合開始抽一次」，MC 版沒有回合，所以改成每隔 {@code interval_seconds} 抽一次。
 * 抽中什麼由 incidents.yml 的權重決定，跟網頁版同一套（權重比例照抄）。
 *
 * <p>骨架階段只實作兩種 action：{@code message}（純公告，用來驗證排程有在跑）與
 * {@code spawn_mobs}（在場中央放一群怪）。要加新效果就在 {@link #execute} 加一個分支。
 */
public final class IncidentScheduler {

    private final ConfigManager config;
    private final SkillEngine skills;

    /** 每一場對戰各自的倒數，key 用 Duel 物件本身（一場對戰的生命週期內都是同一個實例）。 */
    private final Map<Duel, Integer> countdowns = new HashMap<>();

    public IncidentScheduler(ConfigManager config, SkillEngine skills) {
        this.config = config;
        this.skills = skills;
    }

    public void register() {
        DuelEvents.START.register(duel -> countdowns.put(duel, intervalTicks()));
        DuelEvents.END.register((duel, result) -> countdowns.remove(duel));
        DuelEvents.TICK.register(this::onDuelTick);
    }

    /** 抽籤間隔。放在 duel.yml 而不是 incidents.yml：這是對戰節奏，不是某個事件自己的設定。 */
    private int intervalTicks() {
        return Math.max(20, config.settings().incidentIntervalSeconds() * 20);
    }

    private void onDuelTick(Duel duel) {
        Integer remaining = countdowns.get(duel);
        if (remaining == null) return;

        if (remaining > 0) {
            countdowns.put(duel, remaining - 1);
            return;
        }
        countdowns.put(duel, intervalTicks());

        IncidentDef incident = config.incidents().randomWeighted(duel.arena().level().getRandom());
        if (incident == null) return;

        announce(duel, incident);
        execute(duel, incident);
    }

    private void announce(Duel duel, IncidentDef incident) {
        Component title = Component.literal("突發事件：" + incident.displayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        SoundEvent music = music(incident);

        for (ServerPlayer player : playersOf(duel)) {
            player.sendSystemMessage(title);
            if (!incident.description().isEmpty()) {
                player.sendSystemMessage(Msg.info(incident.description()));
            }
            if (music != null) {
                // 在每個人自己的位置放一次，而不是在場中央放一次——不然離得遠的那一方
                // 會因為距離衰減而聽不到自己這場的事件音樂。
                // 走 MUSIC 頻道，玩家調音樂音量就管得到它
                player.level().playSound(null, player.blockPosition(), music, SoundSource.MUSIC,
                        (float) incident.musicVolume(), (float) incident.musicPitch());
            }
        }
    }

    private SoundEvent music(IncidentDef incident) {
        if (incident.music() == null) return null;

        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(incident.music());
        if (sound == null) {
            FortressDuel.LOGGER.warn("Incident {} references sound '{}' which does not exist", incident.id(), incident.music());
        }
        return sound;
    }

    private void execute(Duel duel, IncidentDef incident) {
        switch (incident.action()) {
            case "message" -> {
                // 公告已經在 announce 做完了，沒有額外效果
            }
            case "spawn_mobs" -> spawnMobs(duel, incident);
            default -> FortressDuel.LOGGER.warn("Incident {} uses action '{}' which is not implemented yet",
                    incident.id(), incident.action());
        }
    }

    private void spawnMobs(Duel duel, IncidentDef incident) {
        ServerLevel level = duel.arena().level();
        BlockPos center = duel.arena().region().center();
        // 中場放怪，散佈半徑取半場寬度的一半——不要生到任何一方的核心腳邊
        int spread = duel.arena().region().sizeZ() / 4;

        for (String mobId : incident.mobs()) {
            MobDef def = config.mobs().byId(mobId);
            if (def == null) {
                FortressDuel.LOGGER.warn("Incident {} references mob '{}' which is not defined in mobs.yml", incident.id(), mobId);
                continue;
            }
            MobSpawner.spawnPack(level, def, center, spread, skills);
        }
    }

    private ServerPlayer[] playersOf(Duel duel) {
        var server = duel.arena().level().getServer();
        ServerPlayer north = server.getPlayerList().getPlayer(duel.north().playerId());
        ServerPlayer south = server.getPlayerList().getPlayer(duel.south().playerId());
        if (north != null && south != null) return new ServerPlayer[]{north, south};
        if (north != null) return new ServerPlayer[]{north};
        if (south != null) return new ServerPlayer[]{south};
        return new ServerPlayer[0];
    }
}
