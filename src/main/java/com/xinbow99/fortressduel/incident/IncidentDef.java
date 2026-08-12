package com.xinbow99.fortressduel.incident;

import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * 一個突發事件的設定，對應 incidents.yml 裡的一個區段。
 *
 * <p>網頁版的事件是「每回合抽一次」，MC 版是即時制，所以改成每隔一段時間抽一次
 * （見 {@link IncidentScheduler}）。{@code action} 決定這個事件實際做什麼，
 * 由 {@code IncidentScheduler} 分派——骨架階段只實作 {@code spawn_mobs} 與 {@code message}。
 */
public record IncidentDef(
        String id,
        String displayName,
        String description,
        /** 抽中這個事件的相對權重。 */
        double weight,
        /** 效果種類：spawn_mobs / raid / meteor / modifier / message。 */
        String action,
        /** action ＝ spawn_mobs（生在中場）或 raid（生在雙方玩家區塊）時要生成的怪物 id。 */
        List<String> mobs,

        // ---- action ＝ modifier 專用 ----
        /**
         * 要改的東西：{@code gravity}／{@code weapon_damage}／{@code block_damage}，
         * 對應 {@link com.xinbow99.fortressduel.battle.Duel} 的 MOD_* 常數。
         */
        String modifier,
        /** 倍率。0.5 ＝ 減半，1.5 ＝ 增加五成。 */
        double factor,
        /** 持續幾秒。 */
        int durationSeconds,

        // ---- action ＝ meteor 專用 ----
        /** 每一方的頭上各落幾顆。 */
        int meteorCount,
        /** 從熊貓圈上方幾格落下。 */
        int meteorHeight,
        /** 以熊貓圈為中心，落點散佈幾格。 */
        int meteorSpread,
        /** 引信長度（tick）。要夠長才能讓它在半空中就開始掉、落地附近才爆。 */
        int meteorFuseTicks,
        /** 引信的隨機浮動（tick）。讓爆炸錯開成一陣雨，而不是同一瞬間全炸。 */
        int meteorFuseJitter,
        /** 發生時播放的音樂／音效；null ＝ 不播。 */
        Identifier music,
        double musicVolume,
        double musicPitch
) {

    public static IncidentDef from(String id, Map<String, Object> section) {
        Object rawMobs = section.get("mobs");
        List<String> mobs = rawMobs instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        String music = YamlConfig.str(section, "music", "");

        return new IncidentDef(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.str(section, "description", ""),
                YamlConfig.d(section, "weight", 1.0),
                YamlConfig.str(section, "action", "message"),
                mobs,
                YamlConfig.str(section, "modifier", ""),
                YamlConfig.d(section, "factor", 1.0),
                Math.max(1, YamlConfig.i(section, "duration_seconds", 60)),
                Math.max(1, YamlConfig.i(section, "meteor_count", 10)),
                Math.max(1, YamlConfig.i(section, "meteor_height", 26)),
                Math.max(0, YamlConfig.i(section, "meteor_spread", 8)),
                Math.max(1, YamlConfig.i(section, "meteor_fuse_ticks", 45)),
                Math.max(0, YamlConfig.i(section, "meteor_fuse_jitter", 25)),
                music.isBlank() ? null : Identifier.parse(music),
                YamlConfig.d(section, "music_volume", 1.0),
                YamlConfig.d(section, "music_pitch", 1.0));
    }
}
