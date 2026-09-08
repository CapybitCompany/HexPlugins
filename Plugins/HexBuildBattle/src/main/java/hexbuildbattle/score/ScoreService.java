package hexbuildbattle.score;

import hexbuildbattle.config.ConfigService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScoreService {

    private final ConfigService configService;

    public ScoreService(ConfigService configService) {
        this.configService = configService;
    }

    public List<RoundPlacement> rank(List<RoundBuildScore> scores) {
        List<RoundBuildScore> sorted = new ArrayList<>(scores);
        sorted.sort(Comparator
                .comparingInt(RoundBuildScore::totalPoints).reversed()
                .thenComparing((RoundBuildScore score) -> score.countRatingLevel(6), Comparator.reverseOrder())
                .thenComparing((RoundBuildScore score) -> score.countRatingLevel(5), Comparator.reverseOrder())
                .thenComparing((RoundBuildScore score) -> score.countRatingLevel(4), Comparator.reverseOrder())
                .thenComparing(RoundBuildScore::validVoteCount, Comparator.reverseOrder())
                .thenComparing(score -> score.ownerId().toString()));

        List<RoundPlacement> placements = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            RoundBuildScore score = sorted.get(i);
            int place = i + 1;
            placements.add(new RoundPlacement(
                    place,
                    score.ownerId(),
                    score.ownerName(),
                    score.totalPoints(),
                    score.validVoteCount(),
                    configService.config().rankingReward(place)
            ));
        }
        return placements;
    }
}
