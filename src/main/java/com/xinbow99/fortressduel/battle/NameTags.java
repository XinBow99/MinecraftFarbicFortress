package com.xinbow99.fortressduel.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * 對戰中把玩家頭上的名牌藏起來。
 *
 * <p>名牌**穿牆、而且很遠就看得到**，所以它等於一個免費的雷達：對手還沒露臉，你已經知道他
 * 躲在哪面牆後面。這個遊戲的攻防建立在「你得先找到目標」上，而名牌把那一步整個跳過去了。
 *
 * <h2>為什麼是計分板隊伍</h2>
 * <p>玩家的名牌**不是實體的自訂名字**，關不掉——原版唯一的開關是隊伍的
 * {@code nametagVisibility}，而那是伺服器端就能設、客戶端純原版也吃得到的。
 *
 * <h2>為什麼一人一隊</h2>
 * <p>兩個人放同一隊比較省事，但那會讓他們在原版眼裡變成**隊友**：近戰傷害會被
 * {@code allowFriendlyFire} 擋掉、名牌會穿牆給隊友看、之後任何一條「對隊友特別處理」的
 * 原版邏輯都會自動套用到這一場對戰上。
 *
 * <p>這個模組的武器是自己算傷害的（不經過那條檢查），所以今天不會出事——但那是一個
 * **沒有寫下來的巧合**，而不是一條規則。一人一隊之後就沒有隊友這回事，也就沒有那一整類
 * 問題。多幾行程式碼換掉一個未來會咬人的耦合。
 */
public final class NameTags {

    /** 隊伍名稱的前綴。加前綴才不會跟伺服器本來就有的隊伍撞名。 */
    private static final String PREFIX = "fd_";

    private NameTags() {
    }

    /** 藏起這個人的名牌。 */
    public static void hide(ServerPlayer player) {
        Scoreboard scoreboard = player.level().getScoreboard();
        String name = teamName(player);

        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setNameTagVisibility(Team.Visibility.NEVER);
            // 明確寫出來：一人一隊本來就不可能有隊友，但如果哪天有人把這裡改成共用一隊，
            // 這一行是「他們仍然要能互相傷害」唯一的保險
            team.setAllowFriendlyFire(true);
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /**
     * 還回去。
     *
     * <p>連隊伍一起刪掉而不是只把人移出來：這個隊伍是這一場對戰造出來的，留著會在伺服器上
     * 累積出一堆空隊伍，而它們會出現在 {@code /team list} 裡讓人以為是誰手動建的。
     */
    public static void show(ServerPlayer player) {
        Scoreboard scoreboard = player.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(teamName(player));
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
    }

    /**
     * 這個人專屬的隊伍名。
     *
     * <p>用 UUID 而不是名字：隊伍名有長度上限，而且玩家可以改名——改名之後舊的隊伍會變成
     * 一個沒有人清得掉的孤兒。
     */
    private static String teamName(ServerPlayer player) {
        return PREFIX + player.getUUID().toString().substring(0, 8);
    }
}
