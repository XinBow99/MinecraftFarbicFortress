package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.weapon.WeaponSystem;

/**
 * 一場對戰會用到的其他子系統。
 *
 * <p>把它們包成一包而不是逐一當參數傳：{@link Duel} 需要蓋建築、發收入、顯示彈藥，
 * 再加下去建構子的參數列會長到看不出重點。這裡也是唯一一個「battle 認得其他子系統」的地方，
 * 反過來的方向仍然只走 {@link com.xinbow99.fortressduel.core.DuelEvents}。
 */
public record DuelServices(
        BuildingPlacer buildings,
        EconomyManager economy,
        WeaponSystem weapons
) {}
