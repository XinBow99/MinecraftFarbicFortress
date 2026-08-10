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
    /** 這一方的核心。準備階段結束才長出來，在那之前是 null。 */
    private BlockPos core;

    private final ResourceKey<Level> returnLevel;
    private final Vec3 returnPos;
    private final float returnYaw;
    private final float returnPitch;

    private final ServerBossEvent bar;
    private final ChatFormatting barColor;
    private final float maxHp;
    private float hp;

    public Side(ServerPlayer player, float maxHp, ChatFormatting color, BossEvent.BossBarColor barColor) {
        this.playerId = player.getUUID();
        this.playerName = player.getGameProfile().name();


        this.returnLevel = player.level().dimension();
        this.returnPos = player.position();
        this.returnYaw = player.getYRot();
        this.returnPitch = player.getXRot();

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

    public void setCore(BlockPos core) {
        this.core = core;
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
