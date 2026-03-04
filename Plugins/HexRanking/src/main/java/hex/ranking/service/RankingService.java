package hex.ranking.service;

import hex.core.api.db.DatabaseService;
import hex.ranking.model.RankingPlayer;
import hex.ranking.repository.PlayerIdentityRepository;
import hex.ranking.repository.RankingRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RankingService {

    private final DatabaseService databaseService;
    private final RankingRepository repository;
    private final PlayerIdentityRepository playerIdentityRepository;

    public RankingService(
            DatabaseService databaseService,
            RankingRepository repository,
            PlayerIdentityRepository playerIdentityRepository
    ) {
        this.databaseService = databaseService;
        this.repository = repository;
        this.playerIdentityRepository = playerIdentityRepository;
    }

    public CompletableFuture<Void> addPoints(UUID uuid, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza niz 0."));
        }
        return databaseService.asyncRun(() -> repository.addGlobalPoints(uuid, amount));
    }

    public CompletableFuture<Void> removePoints(UUID uuid, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza niz 0."));
        }
        return databaseService.asyncRun(() -> repository.removeGlobalPoints(uuid, amount));
    }

    public CompletableFuture<Integer> getGlobalPoints(UUID uuid) {
        return databaseService.async(() -> repository.getGlobalPoints(uuid));
    }

    public CompletableFuture<List<RankingPlayer>> getTopGlobal(int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 50));
        return databaseService.async(() -> repository.getTopGlobal(sanitizedLimit));
    }

    public CompletableFuture<Void> addPointsByName(String playerName, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza od 0."));
        }
        return findPlayerUuid(playerName).thenCompose(uuid -> addPoints(uuid, amount));
    }

    public CompletableFuture<Void> removePointsByName(String playerName, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza od 0."));
        }
        return findPlayerUuid(playerName).thenCompose(uuid -> removePoints(uuid, amount));
    }

    public CompletableFuture<Integer> getGlobalPointsByName(String playerName) {
        return findPlayerUuid(playerName).thenCompose(this::getGlobalPoints);
    }

    public CompletableFuture<UUID> findPlayerUuid(String playerName) {
        String normalizedName;
        try {
            normalizedName = normalizePlayerName(playerName);
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        return databaseService.async(() -> playerIdentityRepository.findUuidByName(normalizedName))
                .thenCompose(optionalUuid -> optionalUuid
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalArgumentException("Nie znaleziono gracza '" + normalizedName + "' w xconomy.")
                        )));
    }

    private static String normalizePlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Nazwa gracza nie moze byc pusta.");
        }
        return playerName.trim();
    }
}
