package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * 對戰期間把時間與天氣釘住。
 *
 * <p>為什麼要釘：這是一個靠**看**打的遊戲——彈道軌跡、對方的牆、熊貓藏在哪裡。入夜之後
 * 什麼都看不見，下雨會讓遠處的粒子糊掉，而那兩件事是隨機發生的，跟雙方的操作完全無關。
 * 一場對戰的勝負不該取決於它剛好開在幾點。
 *
 * <p><b>作用範圍是整個世界，不是單一場對戰</b>——時間與天氣本來就是全域的。所以這裡用
 * 「有沒有任何對戰進行中」來開關：第一場開始時鎖上並記下原本的狀態，最後一場結束時還原。
 * 同時開好幾場不會互相打架，因為它們要的是同一件事。
 *
 * <p>時間走 26.2 的 {@code ServerClockManager.setPaused}——那是原版自己的「暫停時鐘」開關，
 * 比每 tick 硬寫回去乾淨，也不會跟 {@code /time} 打架（玩家真的想改的話解鎖就好）。
 */
public final class WorldLock {

    /** 已經鎖上了嗎。避免第二場對戰開始時把「原本的狀態」覆蓋成鎖住之後的狀態。 */
    private boolean locked;
    /** 鎖上之前時鐘是不是本來就停著。還原時要放回原樣，不能一律恢復流動。 */
    private boolean clockWasPaused;
    /** 鎖上之前的晴天倒數。 */
    private int previousClearWeatherTime;

    /** 鎖住之後晴天還能維持多久（tick）。給一個大得不可能用完的值就是「一直晴」。 */
    private static final int CLEAR_FOREVER = Integer.MAX_VALUE / 2;

    /**
     * 鎖上。已經鎖著就什麼都不做——第二場對戰開始時不能再記一次「原本的狀態」。
     *
     * @param timeMarker 要停在哪個時刻（noon／day／night／midnight）；null ＝ 不鎖時間
     * @param lockWeather 要不要強制晴天
     */
    public void apply(MinecraftServer server, ServerLevel level,
                      ResourceKey<ClockTimeMarker> timeMarker, boolean lockWeather) {
        if (locked) return;
        locked = true;

        if (timeMarker != null) {
            Holder<WorldClock> clock = overworldClock(server);
            if (clock != null) {
                ServerClockManager clocks = server.clockManager();
                // 先移到目標時刻再暫停：反過來的話會停在原本的時間上
                clocks.moveToTimeMarker(clock, timeMarker);
                clockWasPaused = false;
                clocks.setPaused(clock, true);
            }
        }

        if (lockWeather) {
            WeatherData weather = level.getWeatherData();
            previousClearWeatherTime = weather.getClearWeatherTime();
            // 兩件事都要做：resetWeatherCycle 把現在的雨停掉，clearWeatherTime 讓它不會再開始
            level.resetWeatherCycle();
            weather.setClearWeatherTime(CLEAR_FOREVER);
        }
    }

    /** 解鎖，把時鐘與天氣放回原本的狀態。沒鎖過就什麼都不做。 */
    public void release(MinecraftServer server, ServerLevel level) {
        if (!locked) return;
        locked = false;

        Holder<WorldClock> clock = overworldClock(server);
        if (clock != null) {
            server.clockManager().setPaused(clock, clockWasPaused);
        }
        level.getWeatherData().setClearWeatherTime(previousClearWeatherTime);
    }

    private static Holder<WorldClock> overworldClock(MinecraftServer server) {
        try {
            return server.registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(WorldClocks.OVERWORLD);
        } catch (RuntimeException e) {
            // 資料包把 overworld 時鐘拿掉了之類。鎖不了時間不該讓整場對戰開不起來
            FortressDuel.LOGGER.warn("Could not resolve the overworld clock, leaving time unlocked", e);
            return null;
        }
    }

    /** 設定檔寫的時刻名對到原版的時間標記；空字串或認不得回 null（＝不鎖時間）。 */
    public static ResourceKey<ClockTimeMarker> markerByName(String name) {
        return switch (name.trim().toLowerCase()) {
            case "noon" -> ClockTimeMarkers.NOON;
            case "day" -> ClockTimeMarkers.DAY;
            case "night" -> ClockTimeMarkers.NIGHT;
            case "midnight" -> ClockTimeMarkers.MIDNIGHT;
            case "" -> null;
            default -> {
                FortressDuel.LOGGER.warn("battle.lock_time '{}' is not a known time marker "
                        + "(noon/day/night/midnight), leaving time unlocked", name);
                yield null;
            }
        };
    }
}
