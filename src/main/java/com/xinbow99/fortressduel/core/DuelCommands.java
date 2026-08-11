package com.xinbow99.fortressduel.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponItems;
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

/**
 * {@code /duel} 指令。
 *
 * <pre>
 * /duel challenge &lt;player&gt;   向任意玩家發起挑戰
 * /duel accept   &lt;player&gt;   接受挑戰，雙方傳送進競技場
 * /duel deny     &lt;player&gt;   拒絕
 * /duel solo                單人練習，對手是靶子（需要 OP）
 * /duel forfeit             投降，判對手獲勝
 * /duel reload              重讀 YAML 設定（需要 OP）
 * </pre>
 */
public final class DuelCommands {

    private final DuelManager duels;
    private final ConfigManager config;
    private final SkillEngine skills;

    public DuelCommands(DuelManager duels, ConfigManager config, SkillEngine skills) {
        this.duels = duels;
        this.config = config;
        this.skills = skills;
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
                                .executes(this::spawn)));

        dispatcher.register(root);
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

    /** 測試用：直接發一把武器，省得自己去查它綁哪個物品。 */
    private int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "weapon");
        WeaponDef weapon = config.weapons().byId(id);
        if (weapon == null) {
            ctx.getSource().sendFailure(Msg.warn("weapons.yml 裡沒有 '" + id + "' 這把武器。"));
            return 0;
        }

        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.getInventory().placeItemBackInInventory(WeaponItems.create(weapon));

        ctx.getSource().sendSuccess(() -> Msg.good("給了你「" + weapon.displayName() + "」"
                + (weapon.bowLaunched() ? "，按住右鍵拉弓、放開發射。" : "，右鍵開火。")), false);
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
