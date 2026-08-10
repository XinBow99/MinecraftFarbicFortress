package com.xinbow99.fortressduel.battle;

import java.util.UUID;

/**
 * 一張還沒被接受的挑戰書。
 *
 * <p>逾時是用伺服器 tick 數算的，不是牆上時間——伺服器卡頓時兩邊看到的倒數才會一致。
 */
public final class Challenge {

    private final UUID challenger;
    private final String challengerName;
    private final UUID target;
    private final long expiresAtTick;

    public Challenge(UUID challenger, String challengerName, UUID target, long expiresAtTick) {
        this.challenger = challenger;
        this.challengerName = challengerName;
        this.target = target;
        this.expiresAtTick = expiresAtTick;
    }

    public UUID challenger() {
        return challenger;
    }

    public String challengerName() {
        return challengerName;
    }

    public UUID target() {
        return target;
    }

    public boolean isExpired(long currentTick) {
        return currentTick >= expiresAtTick;
    }

    public int secondsLeft(long currentTick) {
        return (int) Math.max(0, (expiresAtTick - currentTick) / 20);
    }
}
