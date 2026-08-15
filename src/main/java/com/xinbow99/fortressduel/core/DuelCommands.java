package com.xinbow99.fortressduel.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.incident.IncidentDef;
import com.xinbow99.fortressduel.incident.IncidentScheduler;
import com.xinbow99.fortressduel.jobs.JobDef;
import com.xinbow99.fortressduel.jobs.JobManager;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponItems;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * {@code /duel} 指令。
 *
 * <pre>
 * /duel challenge &lt;player&gt;   向任意玩家發起挑戰
 * /duel accept   &lt;player&gt;   接受挑戰，雙方傳送進競技場
 * /duel deny     &lt;player&gt;   拒絕
 * /duel solo                單人練習，對手是靶子（需要 OP）
 * /duel ready               建造階段蓋完了，雙方都按了就開戰
 * /duel forfeit             投降，判對手獲勝
 * /duel reload              重讀 YAML 設定（需要 OP）
 * /duel hire     &lt;job&gt;      直接雇一名工人，不用付錢（需要 OP）
 * </pre>
 */
public final class DuelCommands {

    /** {@code /duel give} 一次發幾發。一整疊，反正這個指令只是拿來試手感的。 */
    private static final int GIVE_AMMO_COUNT = 64;

    private final DuelManager duels;
    private final ConfigManager config;
    private final SkillEngine skills;
    /** /duel give 要查武器定義才知道發哪個彈藥物品。 */
    private final WeaponSystem weapons;
    /** /duel incident 要能立刻觸發一個事件。 */
    private final IncidentScheduler incidents;
    /** /duel hire 要能直接雇一名工人，不用先湊錢走到商人面前。 */
    private final JobManager jobs;

    public DuelCommands(DuelManager duels, ConfigManager config, SkillEngine skills,
                        WeaponSystem weapons, IncidentScheduler incidents, JobManager jobs) {
        this.duels = duels;
        this.config = config;
        this.skills = skills;
        this.weapons = weapons;
        this.incidents = incidents;
        this.jobs = jobs;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> build(dispatcher));
    }

    private void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("duel")
                .then(Commands.literal("challenge")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> run(ctx.getSource(),
                                        duels.challenge(ctx.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> run(ctx.getSource(),
                                        duels.accept(ctx.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("deny")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> run(ctx.getSource(),
                                        duels.deny(ctx.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("solo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> run(ctx.getSource(),
                                duels.solo(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("ready")
                        .executes(ctx -> run(ctx.getSource(),
                                duels.ready(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("forfeit")
                        .executes(ctx -> run(ctx.getSource(),
                                duels.forfeit(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(this::reload))
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("weapon", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        config.weapons().all().stream().map(WeaponDef::id), builder))
                                .executes(this::give)))
                .then(Commands.literal("spawn")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("mob", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        config.mobs().all().stream().map(MobDef::id), builder))
                                .executes(this::spawn)))
                .then(Commands.literal("incident")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("incident", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        config.incidents().all().stream().map(IncidentDef::id), builder))
                                .executes(this::incident)))
                .then(Commands.literal("hire")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("job", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        config.jobs().allJobs().stream().map(JobDef::id), builder))
                                .executes(ctx -> run(ctx.getSource(), jobs.hire(
                                        ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "job"))))))
                .then(Commands.literal("cleanup")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> cleanup(ctx, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> cleanup(ctx, IntegerArgumentType.getInteger(ctx, "radius")))));

        dispatcher.register(root);
    }

    /**
     * 清掉附近殘留的競技場框線，以及事件留下來的怪。
     *
     * <p>需要它的原因是快照只活在記憶體裡：伺服器被硬砍（或在 SERVER_STOPPING 的處理加進去
     * 之前關掉）時，{@code Duel.finish} 沒跑到，那圈屏障牆就永遠留在世界上了。而屏障是
     * **看不見的**——玩家只會發現「這裡有一道打不穿的空氣牆」，連要清什麼都不知道。
     *
     * <p>只清設定裡的 {@code arena.border_block}（預設屏障），不碰別的方塊：柵欄與木板平台
     * 至少看得見，玩家自己拆得掉；而屏障在生存模式是拆不掉的，只有這條路。
     *
     * <p>怪的情況完全一樣、而且更糟：牠們被 {@code setPersistenceRequired()} 標記成不會自然
     * 消失，正常結束時由 {@code IncidentScheduler} 收掉，但那條路沒跑到的話牠們就永久留著。
     * 標籤跟著實體寫進 NBT，所以重啟之後仍然認得出來——這正是最需要它的時候。
     */
    private int cleanup(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());

        Block border = BuiltInRegistries.BLOCK
                .getOptional(Identifier.parse(config.settings().borderBlock()))
                .orElse(Blocks.BARRIER);

        // 垂直只掃競技場可能碰得到的那一段，不是整個世界高度。
        //
        // 掃全高的話一次是 (2r+1)² × 384 格：半徑 128 就是兩千五百萬次 getBlockState，
        // 同步跑在主執行緒上會把伺服器凍住好幾秒。框線的範圍是 arena.depth 往下、
        // arena.border_height 往上，多留 16 格緩衝就綽綽有餘
        DuelSettings settings = config.settings();
        int minY = Math.max(level.getMinY(), center.getY() - settings.arenaDepth() - 16);
        int maxY = Math.min(level.getMaxY() - 1, center.getY() + settings.borderHeight() + 16);

        int removed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).is(border)) continue;
                    // 旗標 2 ＝ 只通知客戶端，不觸發鄰居更新：一次清幾萬格時那個更新很貴
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    removed++;
                }
            }
        }

        // 怪用同一個半徑，垂直則放到剛才算出來的整段：飛行的怪停在框線頂端上方時
        // 仍然在這個範圍裡。篩選條件是標籤不是位置，所以掃寬一點不會誤傷玩家自己的動物
        int mobs = MobSpawner.clearIn(level, new AABB(
                center.getX() - radius, minY, center.getZ() - radius,
                center.getX() + radius + 1.0, maxY + 1.0, center.getZ() + radius + 1.0));

        int total = removed;
        source.sendSuccess(() -> Msg.good("清掉了 " + total + " 格殘留的框線與 " + mobs
                + " 隻殘留的怪（半徑 " + radius + "）。"), true);
        return total;
    }

    /**
     * DuelManager 的每個動作都回傳「錯誤訊息或 null」，這裡統一翻成 Brigadier 的成功／失敗。
     * 錯誤訊息走 sendFailure，不會有「指令成功但其實沒發生任何事」的情況。
     */
    private int run(CommandSourceStack source, String error) {
        if (error == null) return 1;
        source.sendFailure(Msg.warn(error));
        return 0;
    }

    /** 測試用：直接發一疊彈藥（外加一把弓），省得自己去查它綁哪個物品。 */
    private int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "weapon");
        WeaponDef weapon = config.weapons().byId(id);
        if (weapon == null) {
            ctx.getSource().sendFailure(Msg.warn("weapons.yml 裡沒有 '" + id + "' 這把武器。"));
            return 0;
        }

        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // 弓也一併發：這個指令的全部意義是「拿來試一下」，只給彈藥的話還要自己去找一把弓
        player.getInventory().placeItemBackInInventory(WeaponItems.createBow());
        player.getInventory().placeItemBackInInventory(WeaponItems.createAmmo(weapon, GIVE_AMMO_COUNT));

        ctx.getSource().sendSuccess(() -> Msg.good("給了你一把弓與 " + GIVE_AMMO_COUNT + " 發「"
                + weapon.displayName() + "」。把彈藥放到**副手**，按住右鍵拉弓、放開發射。"), false);
        return 1;
    }

    /**
     * 測試用：立刻在自己這場觸發指定的突發事件。
     *
     * <p>不加這個的話事件效果幾乎測不動——抽籤每 {@code interval_seconds} 才一次，而單一事件
     * 的權重只佔全部的幾個百分點，想看隕石雨平均要等半小時以上。
     */
    private int incident(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Duel duel = duels.duelOf(player);
        if (duel == null) {
            ctx.getSource().sendFailure(Msg.warn("你不在對戰中。突發事件是對某一場對戰觸發的，"
                    + "先用 /duel solo 開一場。"));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "incident");
        String error = incidents.trigger(duel, id);
        if (error != null) {
            ctx.getSource().sendFailure(Msg.warn(error));
            return 0;
        }
        return 1;
    }

    /** 測試用：在指令來源的位置放一隻怪，技能一併掛上。做內容時不用真的開一場對戰才看得到效果。 */
    private int spawn(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "mob");
        MobDef def = config.mobs().byId(id);
        if (def == null) {
            ctx.getSource().sendFailure(Msg.warn("mobs.yml 裡沒有 '" + id + "' 這種怪。"));
            return 0;
        }

        CommandSourceStack source = ctx.getSource();
        int count = MobSpawner.spawnPack(source.getLevel(), def,
                BlockPos.containing(source.getPosition()), 3, skills).size();

        source.sendSuccess(() -> Msg.good("生成了 " + count + " 隻「" + def.displayName() + "」"
                + (def.skills().isEmpty() ? "" : "，技能：" + String.join("、", def.skills()))), false);
        return count;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        config.reload();
        ctx.getSource().sendSuccess(() -> Msg.good("設定已重新載入（進行中的對戰沿用開場時的設定）。"), true);
        return 1;
    }
}
