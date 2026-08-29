package hex.events.provider;

import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;
import hex.events.api.RequirementCheck;
import hex.events.api.RequirementProvider;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;

public final class TownMemberRequirementProvider implements RequirementProvider {
    @Override public String type() { return "town_member"; }
    private TownsApi api() {
        var registration = Bukkit.getServicesManager().getRegistration(TownsApi.class);
        return registration == null ? null : registration.getProvider();
    }
    @Override public RequirementCheck check(PlayerContext player, EventModuleSettings settings) {
        TownsApi api = api();
        if (api == null) return RequirementCheck.fail("HexTowns jest niedostępny.");
        return api.townIdOf(player.playerId()).isPresent() ? RequirementCheck.ok() : RequirementCheck.fail("Musisz należeć do miasta.");
    }
    @Override public boolean available() { return api() != null; }
    @Override public String unavailableReason() { return available() ? "" : "HexTowns API unavailable"; }
}
