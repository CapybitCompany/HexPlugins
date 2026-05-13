package hexcustomitems.config;

import hexcustomitems.model.CustomItemDefinition;

import java.util.Map;
import java.util.Objects;

public record HexCustomItemsConfig(
        String prefix,
        String givePermission,
        String reloadPermission,
        int maxGiveAmount,
        boolean protectOpsFromNegativeEffects,
        Messages messages,
        Sounds sounds,
        WindSettings windSettings,
        Map<String, CustomItemDefinition> items,
        Map<String, String> legacyCommandBindings
) {
    public HexCustomItemsConfig {
        prefix = Objects.requireNonNull(prefix, "prefix");
        givePermission = Objects.requireNonNull(givePermission, "givePermission");
        reloadPermission = Objects.requireNonNull(reloadPermission, "reloadPermission");
        maxGiveAmount = Math.max(1, maxGiveAmount);
        messages = Objects.requireNonNull(messages, "messages");
        sounds = Objects.requireNonNull(sounds, "sounds");
        windSettings = Objects.requireNonNull(windSettings, "windSettings");
        items = Map.copyOf(Objects.requireNonNull(items, "items"));
        legacyCommandBindings = Map.copyOf(Objects.requireNonNull(legacyCommandBindings, "legacyCommandBindings"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
    }

    public record Messages(
            String noPermission,
            String playerNotFound,
            String invalidNumber,
            String itemNotFound,
            String usageMain,
            String usageGive,
            String reloaded,
            String givenSender,
            String givenTarget,
            String listHeader,
            String targetPlayerRequired,
            String targetTooFar,
            String targetOpProtected,
            String dropBlocked
    ) {
        public Messages {
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            playerNotFound = Objects.requireNonNull(playerNotFound, "playerNotFound");
            invalidNumber = Objects.requireNonNull(invalidNumber, "invalidNumber");
            itemNotFound = Objects.requireNonNull(itemNotFound, "itemNotFound");
            usageMain = Objects.requireNonNull(usageMain, "usageMain");
            usageGive = Objects.requireNonNull(usageGive, "usageGive");
            reloaded = Objects.requireNonNull(reloaded, "reloaded");
            givenSender = Objects.requireNonNull(givenSender, "givenSender");
            givenTarget = Objects.requireNonNull(givenTarget, "givenTarget");
            listHeader = Objects.requireNonNull(listHeader, "listHeader");
            targetPlayerRequired = Objects.requireNonNull(targetPlayerRequired, "targetPlayerRequired");
            targetTooFar = Objects.requireNonNull(targetTooFar, "targetTooFar");
            targetOpProtected = Objects.requireNonNull(targetOpProtected, "targetOpProtected");
            dropBlocked = Objects.requireNonNull(dropBlocked, "dropBlocked");
        }
    }

    public record Sounds(
            String consume,
            String drink,
            String dark,
            String fire,
            String ice,
            String throwSound,
            String windLaunch,
            String windHit
    ) {
        public Sounds {
            consume = Objects.requireNonNull(consume, "consume");
            drink = Objects.requireNonNull(drink, "drink");
            dark = Objects.requireNonNull(dark, "dark");
            fire = Objects.requireNonNull(fire, "fire");
            ice = Objects.requireNonNull(ice, "ice");
            throwSound = Objects.requireNonNull(throwSound, "throwSound");
            windLaunch = Objects.requireNonNull(windLaunch, "windLaunch");
            windHit = Objects.requireNonNull(windHit, "windHit");
        }
    }

    public record WindSettings(
            double radius,
            double power,
            double powerOwner,
            double up,
            double upOwner,
            double recoil,
            double recoilUp,
            double projectileSpeed,
            int particleExplosionCount,
            int particleRange
    ) {
        public WindSettings {
            radius = Math.max(0.5D, radius);
            power = Math.max(0.0D, power);
            powerOwner = Math.max(0.0D, powerOwner);
            up = Math.max(0.0D, up);
            upOwner = Math.max(0.0D, upOwner);
            recoil = Math.max(0.0D, recoil);
            recoilUp = Math.max(0.0D, recoilUp);
            projectileSpeed = Math.max(0.1D, projectileSpeed);
            particleExplosionCount = Math.max(0, particleExplosionCount);
            particleRange = Math.max(0, particleRange);
        }
    }
}
