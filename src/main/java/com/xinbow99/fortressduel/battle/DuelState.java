package com.xinbow99.fortressduel.battle;

/**
 * 一場對戰的生命週期。
 *
 * <p>{@link #BUILD} 與 {@link #COMBAT} 會一直輪替到分出勝負為止——這是網頁版「建造階段 →
 * 開戰階段」的即時制版本，差別是網頁版等雙方都按下完成，這裡是計時的。
 */
public enum DuelState {
    /** 準備階段：雙方站定位置等熊貓生成。不能蓋、不能打。 */
    PREPARE,
    /** 建造階段：可以擺放方塊，不能攻擊。 */
    BUILD,
    /** 攻擊階段：不能擺放方塊，可以攻擊。 */
    COMBAT,
    /** 已分出勝負，等待收尾（還原地形、傳送回去）。 */
    ENDED;

    public boolean canPlaceBlocks() {
        return this == BUILD;
    }

    public boolean canAttack() {
        return this == COMBAT;
    }
}
