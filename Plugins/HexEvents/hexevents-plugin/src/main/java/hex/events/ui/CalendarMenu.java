package hex.events.ui;

import hex.events.api.EventAvailability;
import hex.events.api.EventState;
import hex.events.lifecycle.EventLifecycleService;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.registration.AdmissionStatus;
import hex.events.registration.RegistrationService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class CalendarMenu implements Listener {
    private static final int[] DAY_SLOTS = {19,20,21,22,23,24,25};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final EventLifecycleService lifecycle;
    private final RegistrationService registrations;
    private final Plugin plugin;

    public CalendarMenu(EventLifecycleService lifecycle, RegistrationService registrations, Plugin plugin) {
        this.lifecycle = lifecycle;
        this.registrations = registrations;
        this.plugin = plugin;
    }

    public void open(Player player) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.Type.CALENDAR, null, null);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&0&lEVENTY"));
        holder.bind(inv);
        fill(inv);
        LocalDate today = LocalDate.now(lifecycle.engineConfig().displayZone());
        List<EventInstance> upcoming = lifecycle.upcomingDays(7, lifecycle.engineConfig().displayZone()).stream()
                .filter(i -> !lifecycle.engineConfig().hideUnavailableEvents() || lifecycle.availability(i) == EventAvailability.AVAILABLE)
                .toList();
        for (int d = 0; d < 7; d++) {
            LocalDate date = today.plusDays(d);
            List<EventInstance> events = upcoming.stream()
                    .filter(i -> i.occurrenceAt().atZone(lifecycle.engineConfig().displayZone()).toLocalDate().equals(date))
                    .sorted(Comparator.comparing(EventInstance::startAt))
                    .toList();
            boolean registered = events.stream().anyMatch(i -> i.registeredPlayers().contains(player.getUniqueId()));
            Material material = events.isEmpty() ? Material.RED_STAINED_GLASS_PANE : (registered ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE);
            List<String> lore = new ArrayList<>();
            lore.add("&7Data: &f" + DATE.format(date));
            lore.add("");
            if (events.isEmpty()) {
                lore.add("&cBrak wydarzeń.");
            } else {
                lore.add("&7Wydarzenia: &f" + events.size());
                lore.add("");
                for (EventInstance event : events) {
                    boolean playerRegistered = event.registeredPlayers().contains(player.getUniqueId());
                    lore.add(event.definition().displayName() + " &8- " + (playerRegistered ? "&aZAPISANO" : "&cNIE ZAPISANO"));
                }
                lore.add("");
                lore.add("&eKliknij, aby zobaczyć.");
            }
            inv.setItem(DAY_SLOTS[d], item(material, "&f&l" + (d == 0 ? "DZISIAJ" : polishDay(date)), lore));
            if (!events.isEmpty()) holder.actions().put(DAY_SLOTS[d], date);
        }

        List<String> info = new ArrayList<>();
        info.add("&cCzerwony &7- brak eventu");
        info.add("&eŻółty &7- event jest zaplanowany");
        info.add("&aZielony &7- jesteś zapisany");
        info.add("");
        info.add("&7Priorytet kolejki:");
        info.add("&f" + rankLabel("media", "\uE004") + " &7- priorytet 1");
        info.add("&f" + rankLabel("elita", "\uE003") + " &7- priorytet 2");
        info.add("&f" + rankLabel("svip", "\uE006") + " &7- priorytet 3");
        info.add("&f" + rankLabel("vip", "\uE007") + " &7- priorytet 4");
        info.add("&f" + rankLabel("default", "\uE002") + " &7- priorytet 5");
        info.add("");
        info.add("&7W obrębie tej samej rangi");
        info.add("&7decyduje czas zapisu.");
        inv.setItem(4, item(Material.PLAYER_HEAD, "&f&lInformacje", info));
        player.openInventory(inv);
    }

    public void openDay(Player player, LocalDate date) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.Type.DAY, date, null);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&0Eventy: " + polishDayTitle(date) + " - " + DATE.format(date)));
        holder.bind(inv);
        fill(inv);
        List<EventInstance> events = lifecycle.allInstances().stream().filter(i -> !i.state().terminal())
                .filter(i -> i.occurrenceAt().atZone(lifecycle.engineConfig().displayZone()).toLocalDate().equals(date))
                .filter(i -> !lifecycle.engineConfig().hideUnavailableEvents() || lifecycle.availability(i) == EventAvailability.AVAILABLE)
                .sorted(Comparator.comparing(EventInstance::startAt)).toList();
        int slot = 10;
        for (EventInstance i : events) {
            while (slot % 9 == 8 || slot % 9 == 0) slot++;
            if (slot >= 44) break;
            EventDefinition d = i.definition();
            EventAvailability a = lifecycle.availability(i);
            Material mat = a == EventAvailability.AVAILABLE ? material(d.iconMaterial(), Material.CLOCK) : Material.BARRIER;
            List<String> lore = new ArrayList<>();
            lore.add("&7Start: &f" + i.startAt().atZone(lifecycle.engineConfig().displayZone()).toLocalTime().withSecond(0).withNano(0));
            lore.add("&7Czas: &f" + formatDuration(d.duration()));
            lore.add("&7Zapisy: &f" + i.registeredPlayers().size());
            if (hasCapacityLimit(d)) lore.add("&7Miejsca zajęte: &f" + i.participants().size() + "/" + d.capacity().maxPlayers());
            int queueSize = lifecycle.queueSize(i);
            if (queueSize > 0) lore.add("&7Kolejka: &f" + queueSize);
            if (d.capacity().minPlayers() > 0) lore.add("&7Minimum: &f" + d.capacity().minPlayers());
            if (!d.requirements().isEmpty()) {
                lore.add(""); lore.add("&7Wymagania:");
                for (var r : d.requirements()) lore.add("&8- &f" + r.type());
            }
            if (!d.costs().isEmpty()) {
                lore.add(""); lore.add("&7Koszty:");
                for (var c : d.costs()) lore.add("&8- &f" + c.type() + " " + c.settings().asMap());
            }
            if (!d.rewardDescriptions().isEmpty()) {
                lore.add(""); lore.add("&7Nagrody:");
                for (String r : d.rewardDescriptions()) lore.add("&8- &f" + r);
            }
            lore.add("");
            appendPlayerStatus(lore, player, i);
            if (a != EventAvailability.AVAILABLE) {
                lore.add("&cNiedostępny: " + a);
            } else if (canJoinNow(i)) {
                lore.add("");
                lore.add("&aEvent już trwa i przyjmuje graczy.");
                lore.add("&eKliknij, aby dołączyć i przenieść się na event.");
            } else {
                lore.add("&eKliknij, aby przejść dalej.");
            }
            inv.setItem(slot, item(mat, d.displayName(), lore));
            holder.actions().put(slot, i.id());
            slot++;
        }
        inv.setItem(49, item(Material.ARROW, "&cWróć", List.of("&7Powrót do kalendarza.")));
        holder.actions().put(49, "back");
        player.openInventory(inv);
    }

    private void appendPlayerStatus(List<String> lore, Player player, EventInstance i) {
        UUID playerId = player.getUniqueId();
        AdmissionStatus status = lifecycle.admissionStatus(i, playerId);
        if (status == AdmissionStatus.QUEUED) {
            lore.add("&7Status: &eKOLEJKA");
            lore.add("&7Twoja pozycja: &f" + lifecycle.queuePosition(i, playerId));
            lore.add("&7Priorytet: &f" + lifecycle.queuePriority(i, playerId));
            appendPlayersLine(lore, i);
            lore.add("");
            lore.add("&eJeśli zwolni się miejsce,");
            lore.add("&ezostaniesz automatycznie dołączony.");
            return;
        }
        if (status == AdmissionStatus.ADMITTED || status == AdmissionStatus.PARTICIPATING || i.participants().contains(playerId)) {
            lore.add("&7Status: &aZAKWALIFIKOWANO");
            appendPlayersLine(lore, i);
            return;
        }
        if (status == AdmissionStatus.QUEUE_REFUNDED) {
            lore.add("&7Status: &cBRAK MIEJSCA");
            lore.add("&aOpłata została zwrócona.");
            return;
        }
        if (status == AdmissionStatus.QUEUE_REFUND_PENDING) {
            lore.add("&7Status: &eZWROT OCZEKUJE");
            lore.add("&7Nie udało się uzyskać miejsca.");
            return;
        }
        if (status == AdmissionStatus.LEFT_FORFEITED) {
            lore.add("&7Status: &cOPUSZCZONO — BRAK POWROTU");
            lore.add("&7Miejsce i opłata zostały utracone.");
            return;
        }
        if (status == AdmissionStatus.NO_SHOW) {
            lore.add("&7Status: &cNIEOBECNY");
            lore.add("&7Brak refundu za niewykorzystane miejsce.");
            return;
        }
        if (i.registeredPlayers().contains(playerId)) {
            lore.add("&7Status: &aZAPISANO");
            int pos = lifecycle.registrationPosition(i, playerId);
            if (hasCapacityLimit(i.definition()) && pos > 0) lore.add("&7Wstępna pozycja zapisu: &f" + pos);
            if (hasCapacityLimit(i.definition())) lore.add("&7Maks. miejsc: &f" + i.definition().capacity().maxPlayers());
            return;
        }
        lore.add("&7Status: &cNIE ZAPISANO");
    }

    private void openConfirm(Player player, EventInstance i, boolean cancel) {
        EventMenuHolder.Type type = cancel ? EventMenuHolder.Type.CONFIRM_CANCEL : EventMenuHolder.Type.CONFIRM_REGISTER;
        EventMenuHolder holder = new EventMenuHolder(type, i.occurrenceAt().atZone(lifecycle.engineConfig().displayZone()).toLocalDate(), i.id());
        Inventory inv = Bukkit.createInventory(holder, 27, color(cancel ? "&0Anulować zapis?" : "&0Potwierdzić zapis?"));
        holder.bind(inv); fill(inv);
        inv.setItem(11, item(Material.LIME_CONCRETE, cancel ? "&a&lTAK, ANULUJ" : "&a&lPOTWIERDŹ", List.of(
                cancel ? "&7Koszty zostaną zwrócone zgodnie z receiptami." : "&7Koszt zostanie pobrany teraz.",
                cancel ? "" : "&7Zapis nie gwarantuje miejsca — działa kolejka priorytetowa."
        )));
        inv.setItem(15, item(Material.RED_CONCRETE, "&c&lWRÓĆ", List.of("&7Bez zmian.")));
        player.openInventory(inv);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EventMenuHolder) event.setCancelled(true);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EventMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        switch (holder.type()) {
            case CALENDAR -> {
                Object action = holder.actions().get(slot);
                if (action instanceof LocalDate date) openDay(player, date);
            }
            case DAY -> {
                Object action = holder.actions().get(slot);
                if ("back".equals(action)) { open(player); return; }
                if (action instanceof UUID id) lifecycle.instance(id).ifPresent(i -> {
                    EventAvailability availability = lifecycle.availability(i);
                    if (availability != EventAvailability.AVAILABLE) { player.sendMessage(color("&cTen event jest niedostępny: " + availability)); return; }
                    if (canJoinNow(i)) {
                        // Teleport wykonywany przez moduł eventu może zmienić świat w trakcie obsługi kliknięcia.
                        // Zamykamy GUI przed JOIN-em, aby klient nie został ze starym, nieaktywnym widokiem ekwipunku.
                        player.closeInventory();
                        var r = lifecycle.requestJoin(player, i.id(), hex.events.api.JoinSource.GUI);
                        player.sendMessage(color((r.success() ? "&a" : (r.status() == hex.events.api.EventJoinResult.Status.FULL ? "&e" : "&c")) + r.message()));
                        if (!r.success()) {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) openDay(player, holder.date());
                            });
                        }
                        return;
                    }
                    if (i.registeredPlayers().contains(player.getUniqueId())) { openConfirm(player, i, true); return; }
                    if (i.definition().registration().enabled()) { openConfirm(player, i, false); return; }
                    player.sendMessage(color("&7Ten event nie wymaga zapisów. Wejście będzie możliwe w czasie wydarzenia."));
                });
            }
            case CONFIRM_REGISTER -> {
                if (slot == 15) { openDay(player, holder.date()); return; }
                if (slot == 11) lifecycle.instance(holder.instanceId()).ifPresent(i -> {
                    player.closeInventory();
                    player.sendMessage(color("&7Przetwarzanie zapisu..."));
                    registrations.registerAsync(player, i).whenComplete((r, error) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null) player.sendMessage(color("&cNie udało się zapisać: " + error.getMessage()));
                                else player.sendMessage(color((r.success() ? "&a" : "&c") + r.message()));
                                openDay(player, holder.date());
                            }));
                });
            }
            case CONFIRM_CANCEL -> {
                if (slot == 15) { openDay(player, holder.date()); return; }
                if (slot == 11) lifecycle.instance(holder.instanceId()).ifPresent(i -> {
                    player.closeInventory();
                    player.sendMessage(color("&7Przetwarzanie anulowania zapisu..."));
                    registrations.cancelAsync(player, i).whenComplete((r, error) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null) player.sendMessage(color("&cNie udało się anulować zapisu: " + error.getMessage()));
                                else player.sendMessage(color((r.success() ? "&a" : "&c") + r.message()));
                                openDay(player, holder.date());
                            }));
                });
            }
        }
    }

    private String rankLabel(String rank, String fallback) {
        try {
            Object config = plugin.getClass().getMethod("getConfig").invoke(plugin);
            Object value = config.getClass().getMethod("getString", String.class, String.class)
                    .invoke(config, "ui.rank-labels." + rank, fallback);
            if (value instanceof String text && !text.isBlank()) return text;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static boolean canJoinNow(EventInstance instance) {
        if (!instance.definition().join().manualEntry()) return false;
        if (instance.state() == EventState.LOBBY) return true;
        return instance.state() == EventState.RUNNING
                && instance.definition().join().lateJoin()
                && Instant.now().isBefore(instance.lateJoinCloseAt());
    }

    private static boolean hasCapacityLimit(EventDefinition definition) {
        return definition.capacity().maxPlayers() > 0;
    }

    private static void appendPlayersLine(List<String> lore, EventInstance instance) {
        if (hasCapacityLimit(instance.definition())) {
            lore.add("&7Miejsca: &f" + instance.participants().size() + "/" + instance.definition().capacity().maxPlayers());
        } else {
            lore.add("&7Gracze w evencie: &f" + instance.participants().size());
        }
    }

    private static String formatDuration(Duration duration) {
        long totalMinutes = Math.max(0L, duration.toMinutes());
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static void fill(Inventory inv) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setLore(null);
            hideTooltip(meta);
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private static void hideTooltip(ItemMeta meta) {
        // Paper/Purpur 1.21.x udostępnia hide_tooltip bezpośrednio w ItemMeta.
        // Wywołanie przez interfejs jest istotne: refleksja na package-private CraftMetaItem
        // może kończyć się IllegalAccessException i wtedy klient pokazuje nazwę "Czarna szyba".
        meta.setHideTooltip(true);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(CalendarMenu::color).toList());
            item.setItemMeta(meta);
        }
        return item;
    }
    private static Material material(String name, Material fallback) { Material m = Material.matchMaterial(name); return m == null ? fallback : m; }
    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    private static String polishDay(LocalDate date) { return switch (date.getDayOfWeek()) { case MONDAY -> "PONIEDZIAŁEK"; case TUESDAY -> "WTOREK"; case WEDNESDAY -> "ŚRODA"; case THURSDAY -> "CZWARTEK"; case FRIDAY -> "PIĄTEK"; case SATURDAY -> "SOBOTA"; case SUNDAY -> "NIEDZIELA"; }; }
    private static String polishDayTitle(LocalDate date) { return switch (date.getDayOfWeek()) { case MONDAY -> "Poniedziałek"; case TUESDAY -> "Wtorek"; case WEDNESDAY -> "Środa"; case THURSDAY -> "Czwartek"; case FRIDAY -> "Piątek"; case SATURDAY -> "Sobota"; case SUNDAY -> "Niedziela"; }; }
}
