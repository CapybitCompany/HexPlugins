package hex.minigames.game.drones;

import hex.minigames.game.*;
import hex.minigames.game.common.GameSettings;
import hex.minigames.model.LocationSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class DronesFixtures {
    static YamlConfiguration yaml() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(DronesFixtures.class.getResourceAsStream("/games/drones.yml")),StandardCharsets.UTF_8));
    }
    static MinigameDefinition definition() { return definition(yaml()); }
    static MinigameDefinition definition(YamlConfiguration yaml) {
        Map<String,Object> settings=new LinkedHashMap<>(yaml.getConfigurationSection("settings").getValues(false));
        settings.put("world",yaml.getString("world"));
        return new MinigameDefinition("drones","Drony",true,true,false,1,14,1,
                Optional.of(GameSettings.region(yaml.getConfigurationSection("region"),yaml.getString("world"))),
                List.of(new LocationSpec(-73.5,9,81.5,90,0,true)),Optional.of(new LocationSpec(-144,20,70,0,0,true)),yaml.getInt("round-time-seconds"),settings,"games/drones.yml");
    }
    static long solve(DronesPuzzles.Puzzle puzzle,long tick) {
        if(puzzle instanceof DronesPuzzles.Cables cables) {
            for(int i=0;i<4;i++) cables.drag(cables.targets().get(i),Set.of(i),tick++);
        } else if(puzzle instanceof DronesPuzzles.Cores cores) {
            for(int i=0;i<5;i++) {
                int source=5; while(cores.at(source)!=cores.order().get(i)) source++;
                cores.exchange(i,cores.exchange(source,null));
            }
        } else if(puzzle instanceof DronesPuzzles.Calibration calibration) {
            for(int i=0;i<3;i++) while(calibration.level(i)<calibration.target()) calibration.increment(i,tick++);
        } else if(puzzle instanceof DronesPuzzles.Sequence sequence) {
            while(!sequence.done()) {
                while(sequence.showing(tick)||sequence.locked(tick)) tick++;
                int length=sequence.length(); for(int i=0;i<length;i++) sequence.click(sequence.base().get(i),tick++);
            }
        } else if(puzzle instanceof DronesPuzzles.Generator generator) {
            while(!generator.done()) {
                while(generator.locked(tick)||generator.cursor(tick)<generator.target()||generator.cursor(tick)>=generator.target()+generator.width()) tick++;
                generator.stop(tick++);
            }
        }
        return tick;
    }
}
