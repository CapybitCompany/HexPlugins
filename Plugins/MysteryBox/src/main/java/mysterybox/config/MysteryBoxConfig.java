package mysterybox.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

public record MysteryBoxConfig(
        String prefix,
        CommandSettings commands,
        BoxSettings box,
        VoucherSettings voucher,
        AuditSettings audit,
        DropProtectionSettings dropProtection,
        OpeningSettings opening,
        MessageSettings messages,
        List<RewardSettings> rewards
) {
    public MysteryBoxConfig {
        prefix = Objects.requireNonNull(prefix, "prefix");
        commands = Objects.requireNonNull(commands, "commands");
        box = Objects.requireNonNull(box, "box");
        voucher = Objects.requireNonNull(voucher, "voucher");
        audit = Objects.requireNonNull(audit, "audit");
        dropProtection = Objects.requireNonNull(dropProtection, "dropProtection");
        opening = Objects.requireNonNull(opening, "opening");
        messages = Objects.requireNonNull(messages, "messages");
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("rewards cannot be empty");
        }
    }

    public record CommandSettings(
            String givePermission,
            int maxGiveAmount
    ) {
        public CommandSettings {
            givePermission = Objects.requireNonNull(givePermission, "givePermission");
            if (givePermission.isBlank()) {
                throw new IllegalArgumentException("givePermission cannot be blank");
            }
            maxGiveAmount = Math.max(1, maxGiveAmount);
        }
    }

    public record BoxSettings(
            Material material,
            String name,
            List<String> lore
    ) {
        public BoxSettings {
            material = Objects.requireNonNull(material, "material");
            name = Objects.requireNonNull(name, "name");
            lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
        }
    }

    public record VoucherSettings(
            Material material,
            String name,
            List<String> lore,
            List<String> alreadyVipCheckPermissions,
            boolean treatOpAsAlreadyVip,
            VoucherAction alreadyVip,
            VoucherAction activate
    ) {
        public VoucherSettings {
            material = Objects.requireNonNull(material, "material");
            name = Objects.requireNonNull(name, "name");
            lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
            alreadyVipCheckPermissions = List.copyOf(
                    Objects.requireNonNull(alreadyVipCheckPermissions, "alreadyVipCheckPermissions")
            );
            alreadyVip = Objects.requireNonNull(alreadyVip, "alreadyVip");
            activate = Objects.requireNonNull(activate, "activate");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
        }
    }

    public record VoucherAction(
            String message,
            String actionbar,
            List<String> commands
    ) {
        public VoucherAction {
            message = message == null ? "" : message;
            actionbar = actionbar == null ? "" : actionbar;
            commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        }
    }

    public record DropProtectionSettings(
            boolean blockMysteryBoxDrop,
            boolean blockVoucherDrop
    ) {
    }

    public record AuditSettings(
            boolean enabled,
            String fileName
    ) {
        public AuditSettings {
            fileName = Objects.requireNonNull(fileName, "fileName");
            if (fileName.isBlank()) {
                throw new IllegalArgumentException("fileName cannot be blank");
            }
        }
    }

    public record OpeningSettings(
            String guiTitle,
            int guiSize,
            List<Integer> rowSlots,
            int spinSteps,
            int updatePeriodTicks,
            int rewardDelayTicks,
            SoundSettings tickSound,
            SoundSettings finalSound
    ) {
        public OpeningSettings {
            guiTitle = Objects.requireNonNull(guiTitle, "guiTitle");
            guiSize = normalizeGuiSize(guiSize);
            rowSlots = List.copyOf(Objects.requireNonNull(rowSlots, "rowSlots"));
            spinSteps = Math.max(10, spinSteps);
            updatePeriodTicks = Math.max(1, updatePeriodTicks);
            rewardDelayTicks = Math.max(0, rewardDelayTicks);
            tickSound = Objects.requireNonNull(tickSound, "tickSound");
            finalSound = Objects.requireNonNull(finalSound, "finalSound");

            if (rowSlots.size() < 3 || rowSlots.size() % 2 == 0) {
                throw new IllegalArgumentException("rowSlots must have odd size >= 3");
            }
            for (Integer slot : rowSlots) {
                if (slot == null || slot < 0 || slot >= guiSize) {
                    throw new IllegalArgumentException("rowSlots contains invalid slot: " + slot);
                }
            }
        }

        public int centerSlot() {
            return rowSlots.get(rowSlots.size() / 2);
        }

        private static int normalizeGuiSize(int guiSize) {
            int normalized = Math.max(9, guiSize);
            int remainder = normalized % 9;
            if (remainder != 0) {
                normalized += 9 - remainder;
            }
            return Math.min(54, normalized);
        }
    }

    public record SoundSettings(
            String name,
            float volume,
            float pitch
    ) {
        public SoundSettings {
            name = Objects.requireNonNull(name, "name");
            volume = Math.max(0.0F, volume);
            pitch = Math.max(0.0F, pitch);
        }
    }

    public record MessageSettings(
            String noPermission,
            String reloaded,
            String playerNotFound,
            String usageMysteryBox,
            String onlyPlayer,
            String giveSuccessSender,
            String giveSuccessTarget,
            String voucherGiveSuccess,
            String alreadyOpening,
            String openStarted,
            String openWon,
            String inventoryFull
    ) {
        public MessageSettings {
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            reloaded = Objects.requireNonNull(reloaded, "reloaded");
            playerNotFound = Objects.requireNonNull(playerNotFound, "playerNotFound");
            usageMysteryBox = Objects.requireNonNull(usageMysteryBox, "usageMysteryBox");
            onlyPlayer = Objects.requireNonNull(onlyPlayer, "onlyPlayer");
            giveSuccessSender = Objects.requireNonNull(giveSuccessSender, "giveSuccessSender");
            giveSuccessTarget = Objects.requireNonNull(giveSuccessTarget, "giveSuccessTarget");
            voucherGiveSuccess = Objects.requireNonNull(voucherGiveSuccess, "voucherGiveSuccess");
            alreadyOpening = Objects.requireNonNull(alreadyOpening, "alreadyOpening");
            openStarted = Objects.requireNonNull(openStarted, "openStarted");
            openWon = Objects.requireNonNull(openWon, "openWon");
            inventoryFull = Objects.requireNonNull(inventoryFull, "inventoryFull");
        }
    }

    public record RewardSettings(
            String id,
            int chance,
            String winMessage,
            RewardPreviewSettings preview,
            RewardGrantSettings grant
    ) {
        public RewardSettings {
            id = Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id cannot be blank");
            }
            chance = Math.max(0, chance);
            winMessage = Objects.requireNonNull(winMessage, "winMessage");
            preview = Objects.requireNonNull(preview, "preview");
            grant = Objects.requireNonNull(grant, "grant");
        }
    }

    public record RewardPreviewSettings(
            Material material,
            String name,
            List<String> lore
    ) {
        public RewardPreviewSettings {
            material = Objects.requireNonNull(material, "material");
            name = Objects.requireNonNull(name, "name");
            lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
        }
    }

    public record RewardGrantSettings(
            RewardGrantItemSettings item,
            List<String> commands
    ) {
        public RewardGrantSettings {
            item = Objects.requireNonNull(item, "item");
            commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        }
    }

    public record RewardGrantItemSettings(
            boolean enabled,
            RewardItemPreset preset,
            Material material,
            int amount,
            String name,
            List<String> lore
    ) {
        public RewardGrantItemSettings {
            preset = Objects.requireNonNull(preset, "preset");
            amount = Math.max(1, amount);
            lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
            if (preset == RewardItemPreset.CUSTOM) {
                material = Objects.requireNonNull(material, "material");
                name = Objects.requireNonNull(name, "name");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("name cannot be blank for custom item");
                }
            } else {
                material = material == null ? Material.PAPER : material;
                name = name == null ? "" : name;
            }
        }
    }

    public enum RewardItemPreset {
        CUSTOM,
        MYSTERY_BOX,
        VIP_VOUCHER
    }
}
