package com.xinbow99.fortressduel.core;

import com.xinbow99.fortressduel.battle.Duel;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

/**
 * 對戰本身的事件匯流排。
 *
 * <p>weapon / mobs / incident 三個子系統都需要知道「一場對戰開始了」「核心被打了」，
 * 但它們彼此不該互相認識——全部掛在這裡，battle 只負責把事件丟出來。
 * 寫法照 Fabric API 的 {@code Event<T>}，跟 mod 其他地方的事件註冊長得一樣。
 */
public final class DuelEvents {

    private DuelEvents() {}

    /** 競技場蓋好、雙方已傳送進場的那一刻。 */
    public static final Event<Start> START = EventFactory.createArrayBacked(Start.class,
            listeners -> duel -> {
                for (Start l : listeners) l.onDuelStart(duel);
            });

    /** 對戰結束（分出勝負、投降、或有人離線）。此時競技場還沒還原。 */
    public static final Event<End> END = EventFactory.createArrayBacked(End.class,
            listeners -> (duel, result) -> {
                for (End l : listeners) l.onDuelEnd(duel, result);
            });

    /** 每個伺服器 tick 對每一場進行中的對戰各呼叫一次。突發事件的計時器掛在這裡。 */
    public static final Event<Tick> TICK = EventFactory.createArrayBacked(Tick.class,
            listeners -> duel -> {
                for (Tick l : listeners) l.onDuelTick(duel);
            });

    /** 要守的熊貓掉血。damage 是實際扣掉的量，不是原始傷害。 */
    public static final Event<CoreDamaged> CORE_DAMAGED = EventFactory.createArrayBacked(CoreDamaged.class,
            listeners -> (duel, owner, attacker, damage) -> {
                for (CoreDamaged l : listeners) l.onCoreDamaged(duel, owner, attacker, damage);
            });

    @FunctionalInterface
    public interface Start {
        void onDuelStart(Duel duel);
    }

    @FunctionalInterface
    public interface End {
        void onDuelEnd(Duel duel, Duel.Result result);
    }

    @FunctionalInterface
    public interface Tick {
        void onDuelTick(Duel duel);
    }

    @FunctionalInterface
    public interface CoreDamaged {
        /**
         * @param owner    核心的主人（被打的那一方）
         * @param attacker 打的人，可能是 null（突發事件、怪物造成的傷害）
         */
        void onCoreDamaged(Duel duel, ServerPlayer owner, ServerPlayer attacker, float damage);
    }
}
