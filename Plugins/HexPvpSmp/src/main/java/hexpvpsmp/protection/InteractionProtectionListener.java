package hexpvpsmp.protection;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.combat.PermissionGate;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.protection.InteractionRules.ItemCategory;
import hexpvpsmp.region.RegionType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Data-driven right-click / item / projectile protection for spawn and no-build
 * regions. All the "what is blocked" knowledge lives in {@link InteractionRules}
 * (Material sets / Bukkit tags); this listener only maps events onto those
 * rules and applies the region + bypass policy.
 *
 * <p>Region policy:
 * <ul>
 *   <li>Interactables (gates, doors, levers, signs, ...) and non-whitelisted
 *       containers are blocked in every protected region.</li>
 *   <li>Crafting tables and configured public chests are always allowed.</li>
 *   <li>Held-item / projectile use: {@code TERRAIN} items are blocked in every
 *       protected region; {@code COMBAT} items in spawn always, in no-build only
 *       when {@code protection.items.block-pvp-in-no-build} is on.</li>
 * </ul>
 *
 * <p>Protection is enforced both at the <b>source</b> (launch / interact) and at
 * the <b>target</b>: projectiles, fireworks and splash/lingering potions cannot
 * affect a player standing inside a protected region even when the shooter is
 * outside it. A spawn safezone is therefore fully safe from ranged attacks.
 *
 * <p>Only item frames are touched among entities (filtered by type) so HexNPC
 * entity interaction is never affected.
 */
public final class InteractionProtectionListener implements Listener {

    private final HexPvpSmpPlugin plugin;

    public InteractionProtectionListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ---- Block right-click + held item -----------------------------------

    /**
     * Registered with {@code ignoreCancelled = false} on purpose: the held-item
     * block (step 2, e.g. eye of ender / goat horn) must still fire even when the
     * block-interaction result is already denied, so a hard-blocked item can
     * never slip through. This listener only ever <em>cancels</em>; it never
     * un-cancels, so events another plugin deliberately cancelled stay cancelled
     * (native spawn protection is handled via the server spawn radius, not here).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        boolean rightClickBlock = event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null;

        // 0) Always allow opening a public chest or using a crafting table inside
        //    a protected region — even when the player happens to hold a blocked
        //    item. Returning here also prevents the held-item block (2) from
        //    stopping the chest/table from opening (explicit-use priority).
        if (rightClickBlock && (isPublicChest(block) || InteractionRules.isAlwaysAllowed(block.getType()))) {
            if (isChest(block.getType())) {
                plugin.debugLog(chestDebug(block, true));
            }
            return;
        }

        if (rightClickBlock && HexChestsCompatibility.isHandledRewardShulker(
                plugin.getServer().getPluginManager(), block)) {
            plugin.debugLog("HexChests reward shulker interact allowed without HexPvpSmp denial at "
                    + block.getLocation());
            return;
        }

        if (rightClickBlock && HexAuctionBazaarCompatibility.isPricePromptSign(block)) {
            plugin.debugLog("HexAuctionBazaar sign prompt interact allowed without HexPvpSmp denial at "
                    + block.getLocation());
            return;
        }

        // 1) Right-clicking a protected block (container / interactable).
        if (rightClickBlock && !bypassesInteract(player) && isBuildProtected(block.getLocation())) {
            Material type = block.getType();
            boolean container = InteractionRules.isBlockedContainer(type) || isInventoryHolder(block);
            if (container || InteractionRules.isProtectedInteractable(type, config.protection().blockButtons())) {
                event.setCancelled(true);
                denyInteract(player);
                if (isChest(type)) {
                    plugin.debugLog(chestDebug(block, false));
                }
                return;
            }
        }

        // 2) Using a restricted held item (throw pearl, place boat, bone meal,
        //    eye of ender, goat horn, ...). {@code event.getItem()} is the item in
        //    the hand that fired THIS event, so both main hand and off hand are
        //    covered (Bukkit fires the event once per hand). The decision looks at
        //    BOTH the player's position and the clicked block's position so a
        //    player at a region edge cannot use a terrain item outward or inward.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            ItemStack item = event.getItem();
            if (item == null) {
                return;
            }
            ItemCategory category = InteractionRules.itemCategory(item.getType());
            if (blockHeldItemUse(category, player, block)) {
                event.setCancelled(true);
                denyItem(player);
            }
        }
    }

    /**
     * Region policy for USING a held item, considering BOTH the player's own
     * position and — for a block right-click — the clicked block's position. The
     * use is blocked when the item's effect is forbidden at EITHER location.
     *
     * <p>This closes the region-edge bypass for hard-blocked terrain items (eye of
     * ender, goat horn, pearls, boats, ...): standing inside a protected region
     * they cannot be aimed at an unprotected block outside, and standing just
     * outside they cannot be aimed at a block inside. Spawn and TERRAIN never get
     * a bypass carve-out (see {@link #blockItemEffect}), so OP / bypass players
     * are blocked too.
     */
    private boolean blockHeldItemUse(ItemCategory category, Player player, Block block) {
        if (category == ItemCategory.NONE) {
            return false;
        }
        if (blockItemEffect(category, player.getLocation(), player)) {
            return true;
        }
        return block != null && blockItemEffect(category, block.getLocation(), player);
    }

    // ---- Projectiles (defense-in-depth for thrown/shot items) ------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        Projectile projectile = event.getEntity();
        ProjectileSource source = projectile.getShooter();
        if (!(source instanceof Player player)) {
            return;
        }
        ItemCategory category = categorize(projectile);
        if (blockItemEffect(category, player.getLocation(), player)) {
            event.setCancelled(true);
            denyItem(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (blockItemEffect(ItemCategory.COMBAT, player.getLocation(), player)) {
            event.setCancelled(true);
            denyItem(player);
        }
    }

    // ---- Potions landing / lingering inside protected regions ------------

    /**
     * Splash potions must not affect players standing inside a protected region.
     * The spawn safezone is always protected (bypass never applies there); a
     * no-build zone follows {@code block-pvp-in-no-build} + the shooter's item
     * bypass. Each blocked entity has its intensity zeroed.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player source = playerSource(event.getPotion().getShooter());
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (blockItemEffect(ItemCategory.COMBAT, affected.getLocation(), source)) {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    /**
     * Lingering potions must not create an effect cloud inside a protected
     * region. Spawn is always protected; no-build follows the config + bypass.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLingeringSplash(LingeringPotionSplashEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player source = playerSource(event.getEntity().getShooter());
        Location where = event.getAreaEffectCloud() != null
                ? event.getAreaEffectCloud().getLocation()
                : event.getEntity().getLocation();
        if (blockItemEffect(ItemCategory.COMBAT, where, source)) {
            event.setCancelled(true);
        }
    }

    /**
     * Defense-in-depth: an effect cloud that drifts into a protected region must
     * not apply to players standing there (spawn always, no-build per config).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAreaCloudApply(AreaEffectCloudApplyEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player source = playerSource(event.getEntity().getSource());
        try {
            event.getAffectedEntities().removeIf(
                    e -> blockItemEffect(ItemCategory.COMBAT, e.getLocation(), source));
        } catch (UnsupportedOperationException ignored) {
            // Some implementations expose an immutable list; nothing to do then.
        }
    }

    // ---- Projectile / firework damage reaching a protected region --------

    /**
     * Blocks projectile (and firework) damage whose victim stands inside a
     * region where the projectile's item category is blocked — so a player
     * outside cannot shoot arrows/snowballs/etc. into spawn, and item frames are
     * protected from being shot. Complements {@code CombatListener}, which only
     * covers player-vs-player PvP inside spawn safezones.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!isEnabled()) {
            return;
        }
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();

        // Item frames: protected from players AND projectiles inside any region.
        if (isItemFrame(victim)) {
            Player owner = playerBehind(damager);
            if (owner != null && bypassesInteract(owner)) {
                return;
            }
            if (isBuildProtected(victim.getLocation())) {
                event.setCancelled(true);
                if (owner != null) {
                    denyInteract(owner);
                }
            }
            return;
        }

        // Any other victim: block projectile/firework damage landing in a region
        // where that item category is not allowed. Spawn is always protected;
        // bypass may only relax no-build.
        ItemCategory category = categorizeDamager(damager);
        if (category == ItemCategory.NONE) {
            return;
        }
        if (blockItemEffect(category, victim.getLocation(), playerBehind(damager))) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks non-damage projectile hit effects (wind charge knockback, ender
     * pearl teleport, egg/snowball impact, ...) whose impact point lies inside a
     * protected region — closing the gap for projectiles that do not raise a
     * damage event. Spawn is always protected regardless of bypass; the
     * projectile is also removed so nothing lingers. Categories come from
     * {@link #categorize(Projectile)}.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!isEnabled()) {
            return;
        }
        Projectile projectile = event.getEntity();
        ItemCategory category = categorize(projectile);
        if (category == ItemCategory.NONE) {
            return;
        }
        Location where = hitLocation(event, projectile);
        Player shooter = playerBehind(projectile);
        if (blockItemEffect(category, where, shooter)) {
            event.setCancelled(true);
            projectile.remove();
        }
    }

    // ---- Item frames (entity, but type-filtered so NPCs are untouched) ---

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemFrameInteract(PlayerInteractEntityEvent event) {
        if (!isItemFrame(event.getRightClicked())) {
            return;
        }
        Player player = event.getPlayer();
        if (!isEnabled() || bypassesInteract(player)) {
            return;
        }
        if (isBuildProtected(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            denyInteract(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemFrameBreak(HangingBreakByEntityEvent event) {
        if (!isItemFrame(event.getEntity())) {
            return;
        }
        Player remover = playerBehind(event.getRemover());
        if (!isEnabled() || (remover != null && bypassesInteract(remover))) {
            return;
        }
        if (isBuildProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
            if (remover != null) {
                denyInteract(remover);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!isItemFrame(event.getEntity())) {
            return;
        }
        Player player = event.getPlayer();
        if (!isEnabled() || (player != null && bypassesInteract(player))) {
            return;
        }
        if (isBuildProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
            if (player != null) {
                denyInteract(player);
            }
        }
    }

    // ---- Signs (direct edit/open events beyond the right-click path) -----

    /**
     * Blocks writing/editing a sign inside a protected region. This is the
     * authoritative path (the client can open the sign editor without a fresh
     * right-click), complementing the interactable right-click block.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!isEnabled() || bypassesInteract(player)) {
            return;
        }
        if (HexAuctionBazaarCompatibility.isPricePromptSign(event.getBlock())) {
            return;
        }
        if (isBuildProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
            denyInteract(player);
        }
    }

    /**
     * Blocks opening the sign editor inside a protected region (Paper event,
     * covers reopening an existing sign). Bypass follows the interact policy.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignOpen(PlayerOpenSignEvent event) {
        Player player = event.getPlayer();
        if (!isEnabled() || bypassesInteract(player)) {
            return;
        }
        if (HexAuctionBazaarCompatibility.isPricePromptSign(event.getSign().getBlock())) {
            return;
        }
        if (isBuildProtected(event.getSign().getLocation())) {
            event.setCancelled(true);
            denyInteract(player);
        }
    }

    // ---- Helpers ---------------------------------------------------------

    /** The player ultimately responsible for an entity (direct, or a projectile's shooter). */
    private static Player playerBehind(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        // Fireworks carry no accessible shooter; treated as unattributed so their
        // damage inside a protected region is always blocked (no bypass path).
        return null;
    }

    /** Category of a damaging entity (projectile or firework); NONE for anything else. */
    private ItemCategory categorizeDamager(Entity damager) {
        if (damager instanceof Projectile projectile) {
            return categorize(projectile);
        }
        if (damager instanceof Firework) {
            return ItemCategory.COMBAT;
        }
        return ItemCategory.NONE;
    }

    private static Player playerSource(ProjectileSource source) {
        return source instanceof Player player ? player : null;
    }

    /** Impact location: the hit block, else the hit entity, else the projectile itself. */
    private static Location hitLocation(ProjectileHitEvent event, Projectile projectile) {
        if (event.getHitBlock() != null) {
            return event.getHitBlock().getLocation();
        }
        if (event.getHitEntity() != null) {
            return event.getHitEntity().getLocation();
        }
        return projectile.getLocation();
    }

    /**
     * Region + bypass policy for an item/projectile EFFECT reaching {@code location}.
     * Builds on {@link #isItemBlockedAt(ItemCategory, Location)} (the pure region
     * policy) and adds the single, narrow bypass carve-out:
     *
     * <ul>
     *   <li>SPAWN_SAFEZONE — always blocked; bypass never applies there.</li>
     *   <li>NO_BUILD, TERRAIN — always blocked; bypass never applies (terrain
     *       items are forbidden in every protected region).</li>
     *   <li>NO_BUILD, COMBAT — blocked per {@code block-pvp-in-no-build}, but a
     *       source holding {@code bypass.items} is exempt.</li>
     * </ul>
     *
     * @param source the player responsible, or {@code null} if unattributed
     */
    private boolean blockItemEffect(ItemCategory category, Location location, Player source) {
        if (!isItemBlockedAt(category, location)) {
            return false;
        }
        // The region policy blocks it. Bypass may only relax a COMBAT item inside
        // a NO_BUILD zone — never a TERRAIN item, and never inside spawn.
        if (category == ItemCategory.COMBAT
                && source != null
                && bypassesItems(source)
                && !plugin.protectionService().isSpawnSafezone(location)) {
            return false;
        }
        return true;
    }

    private ItemCategory categorize(Projectile projectile) {
        if (projectile instanceof EnderPearl || projectile instanceof Egg) {
            return ItemCategory.TERRAIN;
        }
        if (projectile instanceof Snowball
                || projectile instanceof ThrownPotion
                || projectile instanceof AbstractWindCharge
                || projectile instanceof AbstractArrow
                || projectile instanceof Firework) {
            return ItemCategory.COMBAT;
        }
        return ItemCategory.NONE;
    }

    /**
     * Applies the region policy to a held/launched item category:
     * TERRAIN is blocked in any protected region; COMBAT in spawn always and in
     * no-build only when configured.
     */
    private boolean isItemBlockedAt(ItemCategory category, Location location) {
        if (category == ItemCategory.NONE || location == null) {
            return false;
        }
        Optional<RegionType> type = plugin.protectionService().effectiveType(location);
        if (type.isEmpty()) {
            return false;
        }
        if (type.get() == RegionType.SPAWN_SAFEZONE) {
            return true; // spawn blocks everything
        }
        if (category == ItemCategory.TERRAIN) {
            return true;
        }
        HexPvpConfig config = plugin.config();
        return config != null && config.protection().blockPvpItemsInNoBuild();
    }

    private static boolean isItemFrame(Entity entity) {
        return entity instanceof ItemFrame || entity instanceof GlowItemFrame;
    }

    private static boolean isChest(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    /**
     * Debug line describing a chest interaction decision: world, X/Y/Z, block
     * type, effective region type and the public-chest result (requirement B).
     * Reads only already-loaded state; never loads a chunk.
     */
    private String chestDebug(Block block, boolean allowed) {
        ProtectionService protection = plugin.protectionService();
        String region = protection != null
                ? protection.effectiveType(block.getLocation()).map(Enum::name).orElse("NONE")
                : "?";
        return String.format("Chest interact %s at world=%s x=%d y=%d z=%d type=%s region=%s publicChest=%b",
                allowed ? "ALLOWED" : "BLOCKED",
                block.getWorld() != null ? block.getWorld().getName() : "?",
                block.getX(), block.getY(), block.getZ(),
                block.getType(), region, isPublicChest(block));
    }

    private static boolean isInventoryHolder(Block block) {
        BlockState state = block.getState();
        return state instanceof InventoryHolder;
    }

    private boolean isEnabled() {
        HexPvpConfig config = plugin.config();
        return config != null && config.enabled();
    }

    private boolean isBuildProtected(Location location) {
        ProtectionService protection = plugin.protectionService();
        return protection != null && protection.isBuildProtected(location);
    }

    private boolean isPublicChest(Block block) {
        PublicChestRegistry registry = plugin.publicChestRegistry();
        return registry != null && registry.isPublicChest(block);
    }

    private boolean bypassesInteract(Player player) {
        HexPvpConfig config = plugin.config();
        return PermissionGate.bypasses(player)
                && (config == null || config.protection().bypassInteract());
    }

    private boolean bypassesItems(Player player) {
        HexPvpConfig config = plugin.config();
        return PermissionGate.bypasses(player)
                && (config == null || config.protection().bypassItems());
    }

    private void denyInteract(Player player) {
        HexPvpConfig config = plugin.config();
        if (config != null) {
            plugin.messageService().sendChat(player, config.messages().interactDenied());
        }
        plugin.debugLog("Interact denied for " + player.getName());
    }

    private void denyItem(Player player) {
        HexPvpConfig config = plugin.config();
        if (config != null) {
            plugin.messageService().sendChat(player, config.messages().itemDenied());
        }
        plugin.debugLog("Item use denied for " + player.getName());
    }
}
