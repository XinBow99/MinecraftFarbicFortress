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
    /**
     * 停火階段：可以擺放方塊，**也可以開火，但打不出自己的半場**。
     *
     * <p>曾經是完全不能開火的「建造階段」，而那讓一件事很難受：鼠疫、或從中場晃進來的生物
     * 就站在你家裡啃牆，你卻只能看著——處理自己領地的麻煩不該需要等到攻擊階段。
     *
     * <p>所以現在唯一的限制是**射程止於中線**：彈丸一離開射手自己的半場就消失
     * （見 {@code WeaponSystem.step}）。清得掉自己家的怪，碰不到對手的任何東西。
     */
    BUILD,
    /** 攻擊階段：不能擺放方塊，可以攻擊，彈丸打得到全場。 */
    COMBAT,
    /** 已分出勝負，等待收尾（還原地形、傳送回去）。 */
    ENDED;

    public boolean canPlaceBlocks() {
        return this == BUILD;
    }

    /**
     * 能不能扣下扳機。停火階段也可以——只是打不出自己的半場，見 {@link #BUILD}。
     *
     * <p>跟 {@link #canAttack} 是兩個問題：這條問「能不能開槍」，那條問「打不打得到對手」。
     */
    public boolean canFire() {
        return this == BUILD || this == COMBAT;
    }

    /**
     * 能不能對**對手**造成影響。熊貓的傷害、中彈罰款這些只在攻擊階段算。
     *
     * <p>停火階段的彈丸根本到不了對面，所以這條在那個階段本來就該是假的；
     * 留著它是為了讓「越過中線的東西算不算數」有一個明確的問法。
     */
    public boolean canAttack() {
        return this == COMBAT;
    }
}
