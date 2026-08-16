package com.xinbow99.fortressduel.jobs;

import java.util.UUID;

/**
 * 一名雇來的工人。
 *
 * <p>只記 UUID 不記實體，理由跟 {@link com.xinbow99.fortressduel.battle.Side} 一樣：
 * 實體會被打死、會被卸載，存物件會抓到一個已經失效的殘影。
 *
 * <p>實體本身（血量、AI、不能離開半場、對戰結束時收掉）完全交給
 * {@link com.xinbow99.fortressduel.npc.NpcManager}——工人就是一個沒有商店的 NPC，
 * 這個類別只補上它額外要記的三件事：替誰工作、做哪一行、現在採到哪裡了。
 */
final class Worker {

    final UUID entityId;
    final UUID ownerId;
    final JobDef job;

    /** 目前指派的節點；null ＝ 還沒找到（場上沒有活著的同類節點）。 */
    WorkNode target;
    /** 站在節點旁邊累積了幾 tick，滿 {@code job.workTicks()} 收成一次。 */
    int progress;

    Worker(UUID entityId, UUID ownerId, JobDef job) {
        this.entityId = entityId;
        this.ownerId = ownerId;
        this.job = job;
    }
}
