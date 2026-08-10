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
        /** 效果種類：spawn_mobs / message。 */
        String action,
        /** action ＝ spawn_mobs 時要生成的怪物 id（對應 mobs.yml）。 */
        List<String> mobs,
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
                music.isBlank() ? null : Identifier.parse(music),
                YamlConfig.d(section, "music_volume", 1.0),
                YamlConfig.d(section, "music_pitch", 1.0));
    }
}
