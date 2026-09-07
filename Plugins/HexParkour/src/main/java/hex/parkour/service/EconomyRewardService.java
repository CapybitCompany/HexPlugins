package hex.parkour.service;

import hex.parkour.config.ParkourConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.math.BigDecimal;

public final class EconomyRewardService {
    public boolean deposit(Player player, BigDecimal amount, String pointId, ParkourConfig config) {
        if (tryApi(player, amount, pointId, config)) return true;
        return fallbackCommand(player, amount, config);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean tryApi(Player player, BigDecimal amount, String pointId, ParkourConfig config) {
        try {
            Class apiClass = Class.forName("hex.economy.api.HexEconomyApi");
            RegisteredServiceProvider registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) return false;
            Object api = registration.getProvider();
            String reason = config.economyReason().replace("{point}", pointId).replace("{amount}", amount.toPlainString());
            Method simpleDeposit = method(api.getClass(), "deposit", java.util.UUID.class, String.class, BigDecimal.class, String.class);
            if (simpleDeposit != null) {
                Object result = simpleDeposit.invoke(api, player.getUniqueId(), player.getName(), amount, reason);
                return success(result);
            }
            Class currencyType = Class.forName("hex.economy.api.CurrencyType");
            Object currency = Enum.valueOf(currencyType, config.economyCurrency());
            Method currencyDeposit = method(api.getClass(), "deposit", java.util.UUID.class, String.class, currencyType, BigDecimal.class, String.class);
            if (currencyDeposit == null) return false;
            Object result = currencyDeposit.invoke(api, player.getUniqueId(), player.getName(), currency, amount, reason);
            return success(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private boolean success(Object result) throws Exception {
        if (result == null) return false;
        Method method = result.getClass().getMethod("success");
        Object value = method.invoke(result);
        return value instanceof Boolean ok && ok;
    }

    private boolean fallbackCommand(Player player, BigDecimal amount, ParkourConfig config) {
        String playerName = player.getName().replaceAll("[^A-Za-z0-9_]", "");
        if (playerName.isBlank()) return false;
        String amountText = amount.toPlainString();
        if (!amountText.matches("[0-9]+(\\.[0-9]+)?")) return false;
        String command = config.economyFallbackCommand()
                .replace("{player}", playerName)
                .replace("{amount}", amountText);
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
}
