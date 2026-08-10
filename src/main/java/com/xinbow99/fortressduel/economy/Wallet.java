package com.xinbow99.fortressduel.economy;

/**
 * 一名玩家在一場對戰裡的錢包。
 *
 * <p>錢是**虛擬餘額**，不是背包裡的物品。理由跟網頁版一樣：這是封閉的單場經濟，錢不跨場累積；
 * 而且做成物品的話會被丟在地上、被對手撿走、佔背包格，那些都不是這個遊戲想處理的問題。
 */
public final class Wallet {

    private int balance;

    public Wallet(int startingBalance) {
        this.balance = Math.max(0, startingBalance);
    }

    public int balance() {
        return balance;
    }

    public void earn(int amount) {
        if (amount > 0) {
            balance += amount;
        }
    }

    public boolean canAfford(int price) {
        return balance >= price;
    }

    /** @return true ＝ 扣款成功；餘額不足時不會扣任何錢 */
    public boolean spend(int price) {
        if (price <= 0 || balance < price) {
            return price <= 0;
        }
        balance -= price;
        return true;
    }
}
