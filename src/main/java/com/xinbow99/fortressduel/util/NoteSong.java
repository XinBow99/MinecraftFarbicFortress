package com.xinbow99.fortressduel.util;

import com.xinbow99.fortressduel.FortressDuel;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一首用**原版音符盒音色**彈出來的曲子。
 *
 * <p>為什麼不放音檔：自訂音效要嘛把音效註冊進登記表（沒裝模組的人會被登記表同步擋在門外），
 * 要嘛靠資源包（每個人都得裝）。音符盒的音色是**原版就有的音效**，只要調 pitch 就能彈出音高，
 * 所以純原版客戶端一樣聽得到，什麼都不用裝。
 *
 * <p>代價是只能彈單音旋律、音域兩個八度（F#3～F#5，就是原版音符盒的音域）——這裡是拿來
 * 放一段短短的提示音，那個限制不重要。
 *
 * <p>設定寫法是 {@code tick:音名}，用空白隔開，tick 從歌的開頭算起（20 tick ＝ 1 秒）：
 * <pre>{@code
 * instrument: bell
 * notes: "0:D5 5:E4 10:F#5"
 * }</pre>
 * 同一個 tick 寫兩個音就是和弦。超出音域的音名會自動升降八度塞進來，不是丟掉——
 * 抄譜時比較不會因為一個音而卡住。
 */
public record NoteSong(Holder<SoundEvent> instrument, List<Note> notes, int lengthTicks) {

    /** 一個音：第幾 tick、pitch 是多少（音符盒是靠 pitch 調音高的，不是靠不同音檔）。 */
    public record Note(int tick, float pitch) {
    }

    /** 音符盒最低的那個音（F#3）。原版音符盒的 0～24 就是從這裡往上兩個八度。 */
    private static final int LOWEST_MIDI = 54;
    private static final int RANGE = 25;

    private static final Map<String, Holder<SoundEvent>> INSTRUMENTS = Map.ofEntries(
            Map.entry("harp", SoundEvents.NOTE_BLOCK_HARP),
            Map.entry("bass", SoundEvents.NOTE_BLOCK_BASS),
            Map.entry("bell", SoundEvents.NOTE_BLOCK_BELL),
            Map.entry("chime", SoundEvents.NOTE_BLOCK_CHIME),
            Map.entry("flute", SoundEvents.NOTE_BLOCK_FLUTE),
            Map.entry("guitar", SoundEvents.NOTE_BLOCK_GUITAR),
            Map.entry("xylophone", SoundEvents.NOTE_BLOCK_XYLOPHONE),
            Map.entry("iron_xylophone", SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE),
            Map.entry("cow_bell", SoundEvents.NOTE_BLOCK_COW_BELL),
            Map.entry("didgeridoo", SoundEvents.NOTE_BLOCK_DIDGERIDOO),
            Map.entry("bit", SoundEvents.NOTE_BLOCK_BIT),
            Map.entry("banjo", SoundEvents.NOTE_BLOCK_BANJO),
            Map.entry("pling", SoundEvents.NOTE_BLOCK_PLING));

    private static final String[] NOTE_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /**
     * 讀設定裡的一首歌。
     *
     * <p>設定寫錯不讓它變成例外炸掉整次購買：看不懂的音就跳過並留一行 log，
     * 整首歌都看不懂才回 {@code null}。
     */
    public static NoteSong parse(String instrument, String notes) {
        Holder<SoundEvent> sound = INSTRUMENTS.get(instrument.toLowerCase(Locale.ROOT));
        if (sound == null) {
            FortressDuel.LOGGER.warn("Unknown note block instrument '{}', falling back to harp", instrument);
            sound = SoundEvents.NOTE_BLOCK_HARP;
        }

        List<Note> parsed = new ArrayList<>();
        int length = 0;
        for (String token : notes.trim().split("\\s+")) {
            if (token.isEmpty()) continue;

            int colon = token.indexOf(':');
            if (colon <= 0) {
                FortressDuel.LOGGER.warn("Skipping note '{}': expected tick:note", token);
                continue;
            }

            int tick;
            try {
                tick = Integer.parseInt(token.substring(0, colon));
            } catch (NumberFormatException e) {
                FortressDuel.LOGGER.warn("Skipping note '{}': '{}' is not a tick", token, token.substring(0, colon));
                continue;
            }

            int midi = midiOf(token.substring(colon + 1));
            if (midi < 0) {
                FortressDuel.LOGGER.warn("Skipping note '{}': '{}' is not a note name", token, token.substring(colon + 1));
                continue;
            }

            parsed.add(new Note(Math.max(0, tick), pitchOf(midi)));
            length = Math.max(length, tick);
        }

        if (parsed.isEmpty()) return null;

        parsed.sort(Comparator.comparingInt(Note::tick));
        // 最後一個音也要響完才算放完，不然按鈕會在尾音還在的時候就放行
        return new NoteSong(sound, List.copyOf(parsed), length + 20);
    }

    /** {@code F#5} → MIDI 音高；看不懂回 -1。 */
    private static int midiOf(String name) {
        if (name.length() < 2) return -1;

        int step = -1;
        for (int i = 0; i < NOTE_NAMES.length; i++) {
            // 長度要夠：「C#」這種只有音名沒有八度的寫法，charAt 會直接越界
            if (name.length() > NOTE_NAMES[i].length()
                    && NOTE_NAMES[i].equalsIgnoreCase(name.substring(0, NOTE_NAMES[i].length()))
                    && Character.isDigit(name.charAt(NOTE_NAMES[i].length()))) {
                // C 與 C# 都以 C 開頭，長的那個才是對的，所以不 break，讓後面的覆蓋前面的
                step = i;
            }
        }
        if (step < 0) return -1;

        try {
            int octave = Integer.parseInt(name.substring(NOTE_NAMES[step].length()));
            return (octave + 1) * 12 + step;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * MIDI 音高 → 音符盒的 pitch。
     *
     * <p>超出音域就整個八度移進來：抄譜的人不必先把每個音都算進 F#3～F#5，寫錯八度也還聽得出
     * 是同一段旋律（比整個音消失好）。
     */
    private static float pitchOf(int midi) {
        int note = midi - LOWEST_MIDI;
        while (note < 0) note += 12;
        while (note >= RANGE) note -= 12;
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }
}
