package com.akitaattribute.biomepockets.world;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/**
 * Derived level data that keeps the normal secondary-dimension read behavior while
 * forwarding the state mutations used by vanilla sleep completion to the primary
 * Overworld level data.
 *
 * <p>Vanilla ServerLevel sleep processing already handles SleepStatus, wake-up,
 * player rest/insomnia state, the Forge sleep-finished hook, weather clearing and the
 * morning time jump. Plain DerivedLevelData intentionally discards these setters,
 * which makes those normal writes disappear in dynamically-created pocket levels.
 * Forwarding only the shared clock/weather mutations lets the unmodified vanilla
 * sleep path remain authoritative.</p>
 */
public final class PocketServerLevelData extends DerivedLevelData {
    private final ServerLevelData primary;

    public PocketServerLevelData(WorldData worldData, ServerLevelData primary) {
        super(worldData, primary);
        this.primary = primary;
    }

    @Override
    public void setDayTime(long time) {
        primary.setDayTime(time);
    }

    @Override
    public void setClearWeatherTime(int time) {
        primary.setClearWeatherTime(time);
    }

    @Override
    public void setRainTime(int time) {
        primary.setRainTime(time);
    }

    @Override
    public void setThunderTime(int time) {
        primary.setThunderTime(time);
    }

    @Override
    public void setRaining(boolean raining) {
        primary.setRaining(raining);
    }

    @Override
    public void setThundering(boolean thundering) {
        primary.setThundering(thundering);
    }
}
