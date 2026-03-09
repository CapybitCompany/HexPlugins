package hex.ranking.service;

import hex.core.api.db.DatabaseService;
import hex.ranking.model.PointsTable;
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

    public CompletableFuture<Void> addPoints(UUID uuid, PointsTable pointsTable, int amount) {
        if (pointsTable == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tabela nie moze byc pusta."));
        }
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza niz 0."));
        }
        return databaseService.asyncRun(() -> repository.addPoints(uuid, pointsTable, amount));
    }

    public CompletableFuture<Void> removePoints(UUID uuid, PointsTable pointsTable, int amount) {
        if (pointsTable == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tabela nie moze byc pusta."));
        }
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza niz 0."));
        }
        return databaseService.asyncRun(() -> repository.removePoints(uuid, pointsTable, amount));
    }

    public CompletableFuture<Integer> getGlobalPoints(UUID uuid) {
        return databaseService.async(() -> repository.getGlobalPoints(uuid));
    }

    public CompletableFuture<List<RankingPlayer>> getTopGlobal(int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 50));
        return databaseService.async(() -> repository.getTopGlobal(sanitizedLimit));
    }

    public CompletableFuture<Void> addPointsByName(PointsTable pointsTable, String playerName, int amount) {
        if (pointsTable == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tabela nie moze byc pusta."));
        }
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza od 0."));
        }
        return findPlayerUuid(playerName).thenCompose(uuid -> addPoints(uuid, pointsTable, amount));
    }

    public CompletableFuture<Void> removePointsByName(PointsTable pointsTable, String playerName, int amount) {
        if (pointsTable == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tabela nie moze byc pusta."));
        }
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ilosc musi byc wieksza od 0."));
        }
        return findPlayerUuid(playerName).thenCompose(uuid -> removePoints(uuid, pointsTable, amount));
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
                                new IllegalArgumentException("Nie znaleziono gracza '" + normalizedName + "' w tabeli rankingowej.")
                        )));
    }

    private static String normalizePlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Nazwa gracza nie moze byc pusta.");
        }
        return playerName.trim();
    }
}
