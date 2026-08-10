package com.xinbow99.fortressduel.battle;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 對戰中的一方：一名玩家、他的核心血量、他的血條，以及打完要送他回去的地方。
 *
 * <p>只存 UUID 不存 {@link ServerPlayer}——玩家中途離線再上線是同一個 UUID 但不是同一個物件，
 * 存物件會抓到一個已經失效的殘影。
 */
public final class Side {

    private final UUID playerId;
    private final String playerName;
    private final boolean dummy;
    private final BlockPos core;

    private final ResourceKey<Level> returnLevel;
    private final Vec3 returnPos;
    private final float returnYaw;
    private final float returnPitch;

    private final ServerBossEvent bar;
    private final ChatFormatting barColor;
    private final float maxHp;
    private float hp;

    public Side(ServerPlayer player, BlockPos core, float maxHp, ChatFormatting color, BossEvent.BossBarColor barColor) {
        this(player.getUUID(), player.getGameProfile().name(), false, core,
                player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
                maxHp, color, barColor);
    }

    /**
     * 一個沒有真人的靶子，給單人練習模式用。
     *
     * <p>UUID 隨機生成、每場都不一樣，所以它永遠不會撞到任何真實玩家的 UUID，也就不會被
     * {@code duelsByPlayer}、錢包、彈藥袋這些以 UUID 為鍵的表當成玩家。{@code returnLevel}／
     * {@code returnPos} 是佔位用的——沒有對應的線上玩家，{@code teleportOut} 會先一步跳過它。
     */
    public static Side dummy(String name, BlockPos core, float maxHp,
                             ChatFormatting color, BossEvent.BossBarColor barColor) {
        return new Side(UUID.randomUUID(), name, true, core,
                Level.OVERWORLD, Vec3.ZERO, 0f, 0f,
                maxHp, color, barColor);
    }

    private Side(UUID playerId, String playerName, boolean dummy, BlockPos core,
                 ResourceKey<Level> returnLevel, Vec3 returnPos, float returnYaw, float returnPitch,
                 float maxHp, ChatFormatting color, BossEvent.BossBarColor barColor) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.dummy = dummy;
        this.core = core;

        this.returnLevel = returnLevel;
        this.returnPos = returnPos;
        this.returnYaw = returnYaw;
        this.returnPitch = returnPitch;

        this.maxHp = maxHp;
        this.hp = maxHp;

        this.bar = new ServerBossEvent(
                UUID.randomUUID(),
                barTitle(playerName, maxHp, maxHp, color),
                barColor,
                BossEvent.BossBarOverlay.NOTCHED_10);
        this.bar.setProgress(1.0f);
        this.barColor = color;
    }

    private static Component barTitle(String name, float hp, float maxHp, ChatFormatting color) {
        return Component.literal(name + " 的核心  " + Math.round(hp) + " / " + Math.round(maxHp))
                .withStyle(color);
    }

    // ---------- 血量 ----------

    /** @return 實際扣掉的量（血量不會扣成負的，所以最後一下可能小於傳進來的值） */
    public float damage(float amount) {
        float applied = Math.min(amount, hp);
        hp -= applied;
        bar.setProgress(maxHp <= 0 ? 0f : hp / maxHp);
        bar.setName(barTitle(playerName, hp, maxHp, barColor));
        return applied;
    }

    public boolean isDestroyed() {
        return hp <= 0;
    }

    public float hp() {
        return hp;
    }

    public float maxHp() {
        return maxHp;
    }

    // ---------- 血條 ----------

    /** 兩邊的血條雙方都要看得到，所以每一方的 bar 都會加入兩名玩家。 */
    public void showTo(ServerPlayer player) {
        bar.addPlayer(player);
    }

    public void hideFrom(ServerPlayer player) {
        bar.removePlayer(player);
    }

    public void hideFromAll() {
        bar.removeAllPlayers();
    }

    // ---------- 查詢 ----------

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    /** 這一方是不是靶子（單人練習模式的對手）。 */
    public boolean isDummy() {
        return dummy;
    }

    public BlockPos core() {
        return core;
    }

    public ResourceKey<Level> returnLevel() {
        return returnLevel;
    }

    public Vec3 returnPos() {
        return returnPos;
    }

    public float returnYaw() {
        return returnYaw;
    }

    public float returnPitch() {
        return returnPitch;
    }
}
