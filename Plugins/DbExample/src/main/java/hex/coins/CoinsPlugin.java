package hex.coins;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoinsPlugin extends JavaPlugin {

    private HexApi api;
    private CoinsRepository repo;

    @Override
    public void onEnable() {

        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg == null) {
            getLogger().severe("HexCore not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = reg.getProvider();
        this.repo = new CoinsRepository(api.db().db());

        // create table async (nie blokuj startu)
        api.db().asyncRun(() -> repo.ensureTable())
                .exceptionally(ex -> {
                    getLogger().severe("DB init failed: " + ex.getMessage());
                    return null;
                });

        CoinsCommand cmd = new CoinsCommand(this, api, repo);
        getCommand("coins").setExecutor(cmd);
        getCommand("coins").setTabCompleter(cmd);

        getLogger().info("CoinsPlugin enabled ✅");
    }
}
