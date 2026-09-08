package hexbuildbattle.arena;

import org.bukkit.Material;
import org.bukkit.WeatherType;

public final class ArenaVisualSettings {

    private Material floorMaterial;
    private WeatherPreset weather;
    private TimePreset time;

    public ArenaVisualSettings(Material floorMaterial, WeatherPreset weather, TimePreset time) {
        this.floorMaterial = floorMaterial;
        this.weather = weather;
        this.time = time;
    }

    public static ArenaVisualSettings defaults(Material floorMaterial) {
        return new ArenaVisualSettings(floorMaterial, WeatherPreset.CLEAR, TimePreset.DAY);
    }

    public Material floorMaterial() {
        return floorMaterial;
    }

    public void floorMaterial(Material floorMaterial) {
        this.floorMaterial = floorMaterial;
    }

    public WeatherPreset weather() {
        return weather;
    }

    public void weather(WeatherPreset weather) {
        this.weather = weather;
    }

    public TimePreset time() {
        return time;
    }

    public void time(TimePreset time) {
        this.time = time;
    }

    public ArenaVisualSettings copy() {
        return new ArenaVisualSettings(floorMaterial, weather, time);
    }

    public enum WeatherPreset {
        CLEAR(WeatherType.CLEAR),
        RAIN(WeatherType.DOWNFALL),
        SNOW(WeatherType.CLEAR);

        private final WeatherType bukkitWeather;

        WeatherPreset(WeatherType bukkitWeather) {
            this.bukkitWeather = bukkitWeather;
        }

        public WeatherType bukkitWeather() {
            return bukkitWeather;
        }
    }

    public enum TimePreset {
        DAY(6000L),
        NIGHT(18000L);

        private final long ticks;

        TimePreset(long ticks) {
            this.ticks = ticks;
        }

        public long ticks() {
            return ticks;
        }
    }
}
