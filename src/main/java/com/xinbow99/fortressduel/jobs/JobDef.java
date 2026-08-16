package com.xinbow99.fortressduel.jobs;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Map;

/**
 * 一種職業，對應 jobs.yml 的 {@code jobs:} 底下一個區段。
 *
 * <p>職業本身沒有任何寫死的行為——它只是「用哪個 NPC 當殼、去採哪一種節點、一次賺多少、
 * 採一次要多久」四個數字。礦工與農夫的差別**全部**在設定裡，Java 這邊兩者走同一條路徑。
 */
public record JobDef(
        String id,
        String displayName,
        /** 用 npcs.yml 的哪一個 NPC 當殼（血量、實體、名字都在那邊）。 */
        String npc,
        /** 去採哪一種節點（對應 jobs.yml 的 {@code nodes:}）。 */
        String node,
        /** 每採收一格進帳多少。 */
        int income,
        /**
         * 走到節點旁邊之後，要站多久才收成一次。
         *
         * <p>這是產出速率的唯一旋鈕：{@code income / work_ticks} 就是每 tick 的收入。
         * 調它比調 {@code income} 直覺——玩家看得到工人在動，慢一點就是慢一點。
         */
        int workTicks
) {

    public static JobDef from(String id, Map<String, Object> section) {
        return new JobDef(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.str(section, "npc", ""),
                YamlConfig.str(section, "node", ""),
                Math.max(0, YamlConfig.i(section, "income", 40)),
                // 0 會變成「每 tick 收成一次」，那是一秒 20 格的印鈔機
                Math.max(1, YamlConfig.i(section, "work_ticks", 40)));
    }
}
