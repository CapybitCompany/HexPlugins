package hex.auctionbazaar;

import hex.auctionbazaar.auction.command.AuctionCommand;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.task.AuctionExpiryTask;
import hex.auctionbazaar.bazaar.command.BazaarCommand;
import hex.auctionbazaar.bazaar.repository.BazaarStockRepository;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.ConfigLoader;
import hex.auctionbazaar.config.PluginConfig;
import hex.auctionbazaar.gui.GuiInventoryListener;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class HexAuctionBazaarPlugin extends JavaPlugin {

    private final AtomicReference<PluginConfig> configRef = new AtomicReference<>();
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    private HexCoreBridge hexCore;
    private EconomyBridge economy;
    private MessageFactory messages;

    private AuctionListingRepository listingRepo;
    private AuctionClaimRepository claimRepo;
    private BazaarStockRepository stockRepo;

    private AuctionService auctionService;
    private BazaarService bazaarService;
    private AuctionExpiryTask expiryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureBundledResource("bazaar-items.yml");
        ensureBundledResource("messages.yml");

        PluginConfig loaded = ConfigLoader.load(getDataFolder(), getConfig(), getLogger());
        configRef.set(loaded);
        this.messages = new MessageFactory(() -> configRef.get().messages(), () -> configRef.get().prefix());

        this.hexCore = new HexCoreBridge(getLogger());
        if (!hexCore.tryBootstrap()) {
            getLogger().severe("HexCore is not available - disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.economy = new EconomyBridge(getLogger());
        boolean economyOk = economy.tryBootstrap();
        if (!economyOk && loaded.economyRequired()) {
            getLogger().severe("HexEconomyApi is not available and economy.required=true - disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.listingRepo = new AuctionListingRepository(hexCore.rawDb());
        this.claimRepo = new AuctionClaimRepository(hexCore.rawDb());
        this.stockRepo = new BazaarStockRepository(hexCore.rawDb());

        this.auctionService = new AuctionService(this, hexCore, economy, listingRepo, claimRepo,
                () -> configRef.get().auction());
        this.bazaarService = new BazaarService(this, hexCore, economy, stockRepo, claimRepo,
                () -> configRef.get().bazaar(),
                () -> configRef.get().bazaar().requirePlainItem());

        hexCore.asyncRun(() -> {
            listingRepo.ensureTable();
            claimRepo.ensureTable();
            stockRepo.ensureTable();
        }).thenCompose(v -> bazaarService.seedItems())
          .whenComplete((v, err) -> {
              if (err != null) {
                  getLogger().log(Level.SEVERE, "DB schema init failed", err);
                  Bukkit.getScheduler().runTask(this,
                          () -> getServer().getPluginManager().disablePlugin(this));
                  return;
              }
              Bukkit.getScheduler().runTask(this, () -> {
                  schemaReady.set(true);
                  getLogger().info("HexAuctionBazaar DB schema ready.");
                  expiryTask = new AuctionExpiryTask(this, auctionService, () -> configRef.get().auction());
                  expiryTask.start();
              });
          });

        getServer().getPluginManager().registerEvents(new GuiInventoryListener(), this);
        registerCommand("hexauction", new AuctionCommand(this));
        registerCommand("hexbazaar", new BazaarCommand(this));

        getLogger().info("HexAuctionBazaar enabled.");
    }

    @Override
    public void onDisable() {
        if (expiryTask != null) {
            expiryTask.stop();
            expiryTask = null;
        }
        if (economy != null) {
            economy.shutdown();
            economy = null;
        }
        schemaReady.set(false);
        getLogger().info("HexAuctionBazaar disabled.");
    }

    public boolean schemaReady() {
        return schemaReady.get();
    }

    public PluginConfig config() {
        return configRef.get();
    }

    public MessageFactory messages() {
        return messages;
    }

    public HexCoreBridge hexCore() {
        return hexCore;
    }

    public EconomyBridge economy() {
        return economy;
    }

    public AuctionService auctionService() {
        return auctionService;
    }

    public BazaarService bazaarService() {
        return bazaarService;
    }

    public void reloadAllConfigs() {
        reloadConfig();
        PluginConfig fresh = ConfigLoader.load(getDataFolder(), getConfig(), getLogger());
        configRef.set(fresh);
        if (expiryTask != null) {
            expiryTask.start();
        }
        if (bazaarService != null) {
            bazaarService.seedItems();
        }
    }

    private void registerCommand(String name, Object executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' not found in plugin.yml.");
            return;
        }
        cmd.setExecutor((org.bukkit.command.CommandExecutor) executor);
        cmd.setTabCompleter((org.bukkit.command.TabCompleter) executor);
    }

    private void ensureBundledResource(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) {
            try {
                saveResource(name, false);
            } catch (Exception ignored) {
            }
        }
    }
}
