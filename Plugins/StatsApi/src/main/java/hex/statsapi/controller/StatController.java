package hex.statsapi.controller;

import hex.statsapi.dto.StatResponse;
import hex.statsapi.service.StatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stats")
@CrossOrigin(origins = "*")
public class StatController {

    private final StatService statService;

    public StatController(StatService statService) {
        this.statService = statService;
    }

    @GetMapping
    public List<Map<String, String>> availableStats() {
        return statService.availableDefinitions().values().stream()
                .map(def -> Map.of(
                        "id", def.getId(),
                        "displayName", def.getDisplayName()
                ))
                .toList();
    }

    @GetMapping("/{statId}")
    public ResponseEntity<StatResponse> leaderboard(
            @PathVariable String statId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(statService.leaderboard(statId, limit));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{statId}/player/{uuid}")
    public ResponseEntity<StatResponse.PlayerStatEntry> playerStat(
            @PathVariable String statId,
            @PathVariable UUID uuid) {
        try {
            return ResponseEntity.ok(statService.playerStat(statId, uuid));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}

