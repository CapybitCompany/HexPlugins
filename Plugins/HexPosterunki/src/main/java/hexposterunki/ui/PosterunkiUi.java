package hexposterunki.ui;

import hex.core.api.HexApi;
import hex.core.api.ui.TemplateDefinition;
import hex.core.api.ui.UiTokens;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every player-visible string of HexPosterunki, in Polish, registered as HexCore UI defaults.
 *
 * <p>The map keys are the <b>fully qualified</b> {@link UiKeys} constants. HexCore's registry only
 * prepends the namespace to keys without a dot, so a short key like {@code "announce.activated"}
 * would register under a name nobody renders. Registering and rendering through the same constants
 * removes that whole class of bug, and {@link #defaults()} lets a test verify it against the real
 * HexCore UI service.
 *
 * <p>Admins can still override any line in HexCore's {@code ui.yml} under {@code overrides} using
 * exactly these keys.
 */
public final class PosterunkiUi {

    private final HexApi api;

    public PosterunkiUi(HexApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /** The Polish defaults, keyed by fully qualified template key. */
    public static Map<String, TemplateDefinition> defaults() {
        Map<String, TemplateDefinition> templates = new LinkedHashMap<>();

        templates.put(UiKeys.ANNOUNCE_ACTIVATED, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold> <gray>»</gray> <white>Posterunek</white> "
                        + "<yellow>„<name>”</yellow> <white>został aktywowany! Odległość:</white> "
                        + "<aqua><distance></aqua> <white>bloków.</white>",
                List.of("name", "distance")));

        templates.put(UiKeys.ANNOUNCE_EXPIRED, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold> <gray>»</gray> <white>Nikt nie przejął posterunku</white> "
                        + "<yellow>„<name>”</yellow><white>. Twierdza wraca do spoczynku.</white>",
                List.of("name")));

        templates.put(UiKeys.ANNOUNCE_COMPLETED, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold> <gray>»</gray> <white>Drużyna</white> "
                        + "<green><town></green> <white>zdobyła posterunek</white> <yellow>„<name>”</yellow><white>!</white>",
                List.of("town", "name")));

        templates.put(UiKeys.ANNOUNCE_WIPED, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold> <gray>»</gray> <white>Teren posterunku</white> "
                        + "<yellow>„<name>”</yellow> <white>opustoszał — obrona wróciła do pierwszej fali.</white>",
                List.of("name")));

        templates.put(UiKeys.ANNOUNCE_FAILED, TemplateDefinition.of(
                "<dark_red><bold>POSTERUNEK</bold></dark_red> <gray>»</gray> "
                        + "<white>Wydarzenie zostało wstrzymane z powodu błędu technicznego. "
                        + "Administracja została powiadomiona.</white>",
                List.of()));

        templates.put(UiKeys.CLAIM_BROADCAST, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold> <gray>»</gray> <white>Drużyna</white> "
                        + "<green><town></green> <white>przejęła kontrolę nad posterunkiem</white> "
                        + "<yellow>„<name>”</yellow><white>.</white>",
                List.of("town", "name")));

        templates.put(UiKeys.CLAIM_TITLE, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold>", List.of()));

        templates.put(UiKeys.CLAIM_SUBTITLE, TemplateDefinition.of(
                "<white>Twoja drużyna przejęła</white> <yellow>„<name>”</yellow>", List.of("name")));

        templates.put(UiKeys.TAKEOVER_BROADCAST, TemplateDefinition.of(
                "<red><bold>PRZEJĘCIE</bold></red> <gray>»</gray> <white>Miasto</white> "
                        + "<green><town></green> <white>przejęło posterunek!</white>",
                List.of("town")));

        templates.put(UiKeys.TAKEOVER_TITLE, TemplateDefinition.of(
                "<red><bold>PRZEJĘCIE</bold></red>", List.of()));

        templates.put(UiKeys.TAKEOVER_SUBTITLE, TemplateDefinition.of(
                "<white>Posterunek kontroluje teraz</white> <green><town></green>", List.of("town")));

        templates.put(UiKeys.WAVE_STARTED, TemplateDefinition.of(
                "<gold>Fala</gold> <yellow><wave></yellow><gray>/</gray><yellow><total></yellow> "
                        + "<white>rusza na posterunek!</white>",
                List.of("wave", "total")));

        templates.put(UiKeys.WAVE_CLEARED, TemplateDefinition.of(
                "<green>Fala <wave> została odparta!</green>", List.of("wave")));

        templates.put(UiKeys.BOSS_TITLE, TemplateDefinition.of(
                "<dark_red><bold>BOSS</bold></dark_red>", List.of()));

        templates.put(UiKeys.BOSS_SUBTITLE, TemplateDefinition.of(
                "<white>Na posterunku pojawił się</white> <red><boss></red>", List.of("boss")));

        templates.put(UiKeys.BOSS_BROADCAST, TemplateDefinition.of(
                "<dark_red><bold>BOSS</bold></dark_red> <gray>»</gray> <white>Na posterunku</white> "
                        + "<yellow>„<name>”</yellow> <white>pojawił się</white> <red><boss></red><white>!</white>",
                List.of("name", "boss")));

        templates.put(UiKeys.BOSS_DEFEATED, TemplateDefinition.of(
                "<green>Boss <boss> został pokonany!</green>", List.of("boss")));

        templates.put(UiKeys.VICTORY_TITLE, TemplateDefinition.of(
                "<gold><bold>ZWYCIĘSTWO</bold></gold>", List.of()));

        templates.put(UiKeys.VICTORY_SUBTITLE, TemplateDefinition.of(
                "<white>Posterunek</white> <yellow>„<name>”</yellow> <white>należy do drużyny</white> "
                        + "<green><town></green>",
                List.of("name", "town")));

        templates.put(UiKeys.LOOTING_BROADCAST, TemplateDefinition.of(
                "<gold><bold>ŁUPY</bold></gold> <gray>»</gray> <white>Drużyna</white> <green><town></green> "
                        + "<white>ma</white> <yellow><seconds></yellow> <white>sekund na zebranie łupów "
                        + "z posterunku</white> <yellow>„<name>”</yellow><white>.</white>",
                List.of("town", "seconds", "name")));

        templates.put(UiKeys.LOOTING_ACTIONBAR, TemplateDefinition.of(
                "<gold>Zbieranie łupów</gold> <dark_gray>•</dark_gray> <white>pozostało</white> "
                        + "<yellow><seconds></yellow> <white>s</white> <dark_gray>•</dark_gray> "
                        + "<white>Zwycięzca:</white> <green><town></green>",
                List.of("seconds", "town")));

        templates.put(UiKeys.LOOTING_ENDED, TemplateDefinition.of(
                "<gray>Czas na zbieranie łupów dobiegł końca — posterunek jest przywracany.</gray>",
                List.of()));

        templates.put(UiKeys.PROGRESS_LOST_LEAVE, TemplateDefinition.of(
                "<red>Opuściłeś posterunek — twój postęp został wyzerowany.</red>", List.of()));

        templates.put(UiKeys.PROGRESS_LOST_DEATH, TemplateDefinition.of(
                "<red>Zginąłeś na posterunku — twój postęp został wyzerowany.</red>", List.of()));

        templates.put(UiKeys.PROGRESS_LOST_WORLD, TemplateDefinition.of(
                "<red>Zmieniłeś świat — twój postęp na posterunku został wyzerowany.</red>", List.of()));

        templates.put(UiKeys.PROGRESS_LOST_TELEPORT, TemplateDefinition.of(
                "<red>Teleportowałeś się poza posterunek — twój postęp został wyzerowany.</red>", List.of()));

        templates.put(UiKeys.TELEPORT_BLOCKED, TemplateDefinition.of(
                "<red>Nie możesz teleportować się na teren aktywnego posterunku.</red>", List.of()));

        templates.put(UiKeys.NO_TOWN, TemplateDefinition.of(
                "<red>Nie należysz do żadnej drużyny — nie możesz walczyć o posterunek ani otrzymać nagród.</red>",
                List.of()));

        templates.put(UiKeys.NOT_CONTROLLING, TemplateDefinition.of(
                "<red>Posterunek kontroluje drużyna</red> <yellow><town></yellow> "
                        + "<red>— najpierw wyprzyj ją z terenu.</red>",
                List.of("town")));

        templates.put(UiKeys.NOT_PARTICIPATING, TemplateDefinition.of(
                "<red>Musisz stać na terenie posterunku, aby walczyć z jego obrońcami.</red>", List.of()));

        templates.put(UiKeys.SAME_TOWN_PVP, TemplateDefinition.of(
                "<red>Nie możesz atakować członków własnej drużyny na posterunku.</red>", List.of()));

        templates.put(UiKeys.REGION_PROTECTED, TemplateDefinition.of(
                "<red>Teren posterunku jest chroniony.</red>", List.of()));

        templates.put(UiKeys.CONTAINER_LOCKED, TemplateDefinition.of(
                "<red>Ta skrzynia otworzy się dopiero po oczyszczeniu posterunku.</red>", List.of()));

        templates.put(UiKeys.CONTAINER_NOT_YOURS, TemplateDefinition.of(
                "<red>Łupy należą do drużyny</red> <yellow><town></yellow><red>.</red>", List.of("town")));

        templates.put(UiKeys.EVENT_PAUSED, TemplateDefinition.of(
                "<red>Walka na posterunku jest chwilowo wstrzymana — obrażenia i zabójstwa nie są teraz liczone.</red>",
                List.of()));

        templates.put(UiKeys.ACTIONBAR_STATUS, TemplateDefinition.of(
                "<gray><phase></gray> <dark_gray>|</dark_gray> <white>Twoje zabójstwa:</white> "
                        + "<yellow><kills></yellow><gray>/</gray><yellow><required></yellow> "
                        + "<dark_gray>•</dark_gray> <white>Pozycja:</white> <aqua><rank></aqua> "
                        + "<dark_gray>•</dark_gray> <white>Kontrola:</white> <green><town></green>",
                List.of("phase", "kills", "required", "rank", "town")));

        templates.put(UiKeys.BOSSBAR_TEXT, TemplateDefinition.of(
                "<gold>Posterunek</gold> <yellow>„<name>”</yellow> <dark_gray>•</dark_gray> "
                        + "<white><status></white> <dark_gray>•</dark_gray> <aqua><distance></aqua> <white>bloków</white>",
                List.of("name", "status", "distance")));

        templates.put(UiKeys.HOLOGRAM_TEXT, TemplateDefinition.of(
                "<gold><bold>POSTERUNEK</bold></gold>\n<yellow>„<name>”</yellow>\n"
                        + "<white><status></white>\n<gray>Kontrola:</gray> <green><town></green>",
                List.of("name", "status", "town")));

        templates.put(UiKeys.REWARD_GRANTED, TemplateDefinition.of(
                "<green>Otrzymałeś nagrodę za</green> <yellow><place></yellow><green>. miejsce na posterunku!</green>",
                List.of("place")));

        templates.put(UiKeys.REWARD_PENDING_REVIEW, TemplateDefinition.of(
                "<gold>Twoja nagroda wymaga sprawdzenia przez administrację — nie przepadła.</gold>",
                List.of()));

        templates.put(UiKeys.REWARD_NOT_ELIGIBLE, TemplateDefinition.of(
                "<gray>Nie osiągnąłeś wymaganych</gray> <yellow><required></yellow> "
                        + "<gray>zabójstw — nie otrzymujesz nagrody.</gray>",
                List.of("required")));

        templates.put(UiKeys.ADMIN_STATUS_HEADER, TemplateDefinition.of(
                "<gold><bold>HexPosterunki</bold></gold> <gray>» status</gray>", List.of()));

        templates.put(UiKeys.ADMIN_STATUS_LINE, TemplateDefinition.of(
                "<gray><label>:</gray> <white><value></white>", List.of("label", "value")));

        templates.put(UiKeys.ADMIN_VALIDATE_OK, TemplateDefinition.of(
                "<green>Walidacja zakończona poprawnie: <count> posterunków gotowych.</green>",
                List.of("count")));

        templates.put(UiKeys.ADMIN_VALIDATE_PROBLEM, TemplateDefinition.of(
                "<red>✖</red> <white><what></white> <gray>—</gray> <red><reason></red>",
                List.of("what", "reason")));

        templates.put(UiKeys.ADMIN_STARTED, TemplateDefinition.of(
                "<green>Uruchomiono posterunek</green> <yellow>„<name>”</yellow><green>.</green>",
                List.of("name")));

        templates.put(UiKeys.ADMIN_STOPPED, TemplateDefinition.of(
                "<yellow>Posterunek został zatrzymany i przeszedł w przerwę.</yellow>", List.of()));

        templates.put(UiKeys.ADMIN_RESET, TemplateDefinition.of(
                "<yellow>Posterunek został zresetowany.</yellow>", List.of()));

        templates.put(UiKeys.ADMIN_RELOADED, TemplateDefinition.of(
                "<green>Konfiguracja HexPosterunki została przeładowana.</green>", List.of()));

        templates.put(UiKeys.ADMIN_ERROR, TemplateDefinition.of(
                "<red>Błąd:</red> <white><reason></white>", List.of("reason")));

        templates.put(UiKeys.ADMIN_USAGE, TemplateDefinition.of(
                "<gray>Użycie: /posterunki <white><usage></white></gray>", List.of("usage")));

        templates.put(UiKeys.ADMIN_NO_PERMISSION, TemplateDefinition.of(
                "<red>Nie masz uprawnień do tej komendy.</red>", List.of()));

        templates.put(UiKeys.ADMIN_REWARD_HEADER, TemplateDefinition.of(
                "<gold><bold>Nagrody do wyjaśnienia</bold></gold>", List.of()));

        templates.put(UiKeys.ADMIN_REWARD_LINE, TemplateDefinition.of(
                "<gray><id></gray> <white>miejsce <place></white> <gray>—</gray> <yellow><status></yellow> "
                        + "<gray>(prób: <attempts>, wydano części: <progress>)</gray> <red><problem></red>",
                List.of("id", "place", "status", "attempts", "progress", "problem")));

        templates.put(UiKeys.ADMIN_REWARD_EMPTY, TemplateDefinition.of(
                "<green>Brak nagród wymagających decyzji administratora.</green>", List.of()));

        templates.put(UiKeys.ADMIN_REWARD_RESOLVED, TemplateDefinition.of(
                "<green>Nagroda <id> została oznaczona jako rozliczona.</green>", List.of("id")));

        templates.put(UiKeys.ADMIN_REWARD_RETRY, TemplateDefinition.of(
                "<yellow>Nagroda <id> została skierowana do ponownego wydania.</yellow>", List.of("id")));

        templates.put(UiKeys.ADMIN_REWARD_UNKNOWN, TemplateDefinition.of(
                "<red>Nie znaleziono nagrody o identyfikatorze <id>.</red>", List.of("id")));

        return Map.copyOf(templates);
    }

    /** Registers the Polish defaults under their fully qualified keys. */
    public void registerDefaults() {
        api.ui().registerDefaultsWithArgs(UiKeys.NAMESPACE, defaults());
    }

    // ---------------------------------------------------------------- helpers

    public Component render(String key, UiTokens tokens) {
        return api.ui().render(key, tokens);
    }

    public void send(CommandSender sender, String key, UiTokens tokens) {
        api.ui().send(sender, key, tokens);
    }

    public void send(CommandSender sender, String key) {
        api.ui().send(sender, key, new UiTokens());
    }

    public void broadcast(String key, UiTokens tokens) {
        api.ui().broadcast(key, tokens);
    }

    public void actionBar(Player player, String key, UiTokens tokens) {
        api.ui().sendActionBar(player, key, tokens);
    }

    public void title(Player player, String titleKey, String subtitleKey, UiTokens tokens) {
        api.ui().sendTitle(player, titleKey, subtitleKey, tokens);
    }

    public void sound(Player player, Sound sound) {
        api.ui().playSound(player, sound);
    }

    public void broadcastSound(Sound sound) {
        api.ui().broadcastSound(sound);
    }
}
