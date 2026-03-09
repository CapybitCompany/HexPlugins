package hex.ranking;

import hex.core.api.HexApi;
import hex.ranking.command.AddPointsCommand;
import hex.ranking.command.PointsCommand;
import hex.ranking.command.RemovePointsCommand;
import hex.ranking.command.TopCommand;
import hex.ranking.repository.MySqlPlayerIdentityRepository;
import hex.ranking.repository.MySqlRankingRepository;
import hex.ranking.repository.PlayerIdentityRepository;
import hex.ranking.repository.RankingRepository;
import hex.ranking.service.RankingService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class HexRanking extends JavaPlugin {

    private RankingService rankingService;

    @Override
    public void onEnable() {
        var registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            getLogger().severe("HexCore not found. Disabling HexRanking.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HexApi api = registration.getProvider();
        saveDefaultConfig();

        try {
            String rankingTable = getConfig().getString("db.ranking-table", "ranking_points");
            String rankingNameColumn = getConfig().getString("db.ranking-name-column");
            if (rankingNameColumn == null || rankingNameColumn.isBlank()) {
                rankingNameColumn = getConfig().getString("db.identity-name-column", "player");
            }
            String rankingUuidColumn = getConfig().getString("db.ranking-uuid-column");
            if (rankingUuidColumn == null || rankingUuidColumn.isBlank()) {
                rankingUuidColumn = getConfig().getString("db.identity-uuid-column", "uuid");
            }

            RankingRepository repository = new MySqlRankingRepository(api.db().db(), rankingTable);
            PlayerIdentityRepository playerIdentityRepository = new MySqlPlayerIdentityRepository(
                    api.db().db(),
                    rankingTable,
                    rankingNameColumn,
                    rankingUuidColumn
            );
            this.rankingService = new RankingService(api.db(), repository, playerIdentityRepository);
        } catch (IllegalArgumentException ex) {
            getLogger().severe("Invalid HexRanking config: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerCommands();
        getLogger().info("HexRanking enabled.");
    }

    private void registerCommands() {
        PluginCommand add = Objects.requireNonNull(getCommand("dajpunkt"), "Command dajpunkt missing in plugin.yml");
        add.setExecutor(new AddPointsCommand(this, rankingService));

        PluginCommand remove = Objects.requireNonNull(getCommand("odejmijpunkt"), "Command odejmijpunkt missing in plugin.yml");
        remove.setExecutor(new RemovePointsCommand(this, rankingService));

        PluginCommand points = Objects.requireNonNull(getCommand("punkty"), "Command punkty missing in plugin.yml");
        points.setExecutor(new PointsCommand(this, rankingService));

        PluginCommand ranking = Objects.requireNonNull(getCommand("ranking"), "Command ranking missing in plugin.yml");
        TopCommand topCommand = new TopCommand(this, rankingService);
        ranking.setExecutor(topCommand);
        ranking.setTabCompleter(topCommand);
    }
}
