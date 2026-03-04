package uiexample;

import hex.core.api.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class UiExamplePlugin extends JavaPlugin {

    private HexApi api;

    @Override
    public void onEnable() {

        var provider = Bukkit.getServicesManager()
                .getRegistration(HexApi.class);

        if (provider == null) {
            getLogger().severe("HexCore not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = provider.getProvider();

        UiExampleCommand command = new UiExampleCommand(api);

        reg("uichatall", command);
        reg("uichat", command);
        reg("uiactionall", command);
        reg("uiaction", command);
        reg("uisubtitleall", command);
        reg("uisubtitle", command);

        getLogger().info("UiExample enabled");
    }

    private void reg(String name, CommandExecutor exec) {
        var c = getCommand(name);
        if (c == null) {
            getLogger().severe("Command not found in plugin.yml: " + name);
            return;
        }
        c.setExecutor(exec);
    }
}
