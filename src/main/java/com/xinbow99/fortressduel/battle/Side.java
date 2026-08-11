package com.xinbow99.fortressduel.battle;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * 對戰中的一方：一名玩家、他要守的熊貓、他的血條，以及打完要送他回去的地方。
 *
 * <p>只存 UUID 不存 {@link ServerPlayer}——玩家中途離線再上線是同一個 UUID 但不是同一個物件，
 * 存物件會抓到一個已經失效的殘影。熊貓同理：牠們是真的實體，血量的唯一來源是實體本身，
 * 這裡只記 UUID 再去世界裡撈，不快取一份會過期的血量。
 */
public final class Side {

    private final UUID playerId;
    private final String playerName;
    private final boolean dummy;
    /** 這一方的熊貓圈中心。準備階段結束才圍起來，在那之前是 null。 */
    private BlockPos pen;
    /** 要守的熊貓。準備階段結束才生成，在那之前是空的。 */
    private final List<UUID> guardians = new ArrayList<>();

    private final ResourceKey<Level> returnLevel;
    private final Vec3 returnPos;
    private final float returnYaw;
    private final float returnPitch;

    private final ServerBossEvent bar;
    private final ChatFormatting barColor;
    /** 熊貓全滿時的總血量。生成之前是 0——那時還沒有東西可以被打。 */
    private float maxHp;
    private float hp;
    /** 還活著幾隻。 */
    private int alive;
    /** 熊貓生成過了沒。沒生成之前不能算「全滅」，否則開場第一 tick 就判輸。 */
    private boolean spawned;
    /** 上次因為越界被提示的 tick。用來節流訊息。 */
    private long lastBoundaryWarnTick = Long.MIN_VALUE;
    /** 進場前的遊戲模式，打完要還原。null ＝ 沒改過（靶子，或設定值認不得）。 */
    private GameType returnGameMode;

    public Side(ServerPlayer player, ChatFormatting color, BossEvent.BossBarColor barColor) {
        this(player.getUUID(), player.getGameProfile().name(), false,
                player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
                color, barColor);
    }

    /**
     * 一個沒有真人的靶子，給單人練習模式用。
     *
     * <p>UUID 隨機生成、每場都不一樣，所以它永遠不會撞到任何真實玩家的 UUID，也就不會被
     * {@code duelsByPlayer}、錢包這些以 UUID 為鍵的表當成玩家。{@code returnLevel}／
     * {@code returnPos} 是佔位用的——沒有對應的線上玩家，{@code teleportOut} 會先一步跳過它。
     */
    public static Side dummy(String name, ChatFormatting color, BossEvent.BossBarColor barColor) {
        return new Side(UUID.randomUUID(), name, true,
                Level.OVERWORLD, Vec3.ZERO, 0f, 0f,
                color, barColor);
    }

    private Side(UUID playerId, String playerName, boolean dummy,
                 ResourceKey<Level> returnLevel, Vec3 returnPos, float returnYaw, float returnPitch,
                 ChatFormatting color, BossEvent.BossBarColor barColor) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.dummy = dummy;

        this.returnLevel = returnLevel;
        this.returnPos = returnPos;
        this.returnYaw = returnYaw;
        this.returnPitch = returnPitch;

        this.bar = new ServerBossEvent(
                UUID.randomUUID(),
                barTitle(playerName, 0, 0, 0, 0, color),
                barColor,
                BossEvent.BossBarOverlay.NOTCHED_10);
        this.bar.setProgress(1.0f);
        this.barColor = color;
    }

    private static Component barTitle(String name, int alive, int total,
                                      float hp, float maxHp, ChatFormatting color) {
        String text = total == 0
                ? name + " 的熊貓  尚未生成"
                : name + " 的熊貓  " + alive + "/" + total + " 隻   "
                        + Math.round(hp) + " / " + Math.round(maxHp);
        return Component.literal(text).withStyle(color);
    }

    // ---------- 熊貓 ----------

    /** 準備階段結束、熊貓生成好之後登記進來。血條的分母從這一刻才有意義。 */
    public void setGuardians(List<UUID> ids, float perPandaHp) {
        guardians.clear();
        guardians.addAll(ids);
        maxHp = ids.size() * perPandaHp;
        hp = maxHp;
        alive = ids.size();
        spawned = true;
        refreshBar();
    }

    public List<UUID> guardians() {
        return guardians;
    }

    public boolean owns(UUID entityId) {
        return guardians.contains(entityId);
    }

    /**
     * 重新從世界裡的實體算一次總血量與存活數，並更新血條。
     *
     * <p>血量的唯一來源是實體本身，所以每次熊貓掉血都要重算而不是自己記帳——被引走、
     * 被推下去、窒息掉血都是實體那邊發生的事，記帳一定會對不起來。
     *
     * @param lookup UUID → 實體血量；查不到（死了／被清掉）回傳 0
     */
    public void refresh(ToDoubleFunction<UUID> lookup) {
        if (!spawned) return;

        float total = 0;
        int living = 0;
        for (UUID id : guardians) {
            float health = (float) lookup.applyAsDouble(id);
            if (health > 0) {
                total += health;
                living++;
            }
        }
        hp = total;
        alive = living;
        refreshBar();
    }

    private void refreshBar() {
        bar.setProgress(maxHp <= 0 ? 1f : Math.clamp(hp / maxHp, 0f, 1f));
        bar.setName(barTitle(playerName, alive, guardians.size(), hp, maxHp, barColor));
    }

    /** 熊貓全滅 ＝ 這一方輸了。生成之前永遠是 false。 */
    public boolean isDestroyed() {
        return spawned && alive <= 0;
    }

    public int alive() {
        return alive;
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

    public void setPen(BlockPos pen) {
        this.pen = pen;
    }

    /** 這一方是不是靶子（單人練習模式的對手）。 */
    public boolean isDummy() {
        return dummy;
    }

    /**
     * 這一刻要不要提示玩家「你越界了」。
     *
     * <p>越界是持續狀態不是瞬間事件——貼著邊界走的人每一 tick 都會被夾回來，不節流的話
     * 聊天欄會被同一句話洗滿。
     */
    public boolean shouldWarnBoundary(long now, long intervalTicks) {
        if (now - lastBoundaryWarnTick < intervalTicks) return false;
        lastBoundaryWarnTick = now;
        return true;
    }

    public void setReturnGameMode(GameType mode) {
        this.returnGameMode = mode;
    }

    public GameType returnGameMode() {
        return returnGameMode;
    }

    public BlockPos pen() {
        return pen;
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
