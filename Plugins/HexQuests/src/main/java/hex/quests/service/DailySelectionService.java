package hex.quests.service;

import hex.quests.config.QuestRegistry;
import hex.quests.database.QuestRepository;
import hex.quests.model.QuestDefinition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

public final class DailySelectionService {
    private final QuestRepository repository;
    private final AtomicReference<Selection> current = new AtomicReference<>(new Selection(LocalDate.MIN, List.of()));

    public DailySelectionService(QuestRepository repository) {
        this.repository = repository;
    }

    public List<String> prepare(LocalDate date, QuestRegistry registry, int count, long seed) {
        List<String> proposed = choose(registry.weightedCandidates(), count, seed ^ date.toEpochDay());
        List<String> stored = repository.ensureDailySelection(date, proposed);
        if (stored.size() != count) {
            throw new IllegalStateException("SQL zwrócił " + stored.size() + " zadań dla " + date
                    + ", oczekiwano " + count);
        }
        List<String> unknown = stored.stream().filter(id -> registry.get(id) == null).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Dzisiejszy zestaw SQL zawiera nieznane zadania: "
                    + String.join(", ", unknown));
        }
        current.set(new Selection(date, List.copyOf(stored)));
        return stored;
    }

    public List<String> ids(LocalDate date) {
        Selection selection = current.get();
        if (selection.date.equals(date)) return selection.questIds;
        return repository.loadDailySelection(date);
    }

    public List<String> currentIds() {
        return current.get().questIds;
    }

    public LocalDate currentDate() {
        return current.get().date;
    }

    public void setCurrent(LocalDate date, List<String> ids) {
        current.set(new Selection(date, List.copyOf(ids)));
    }

    private static List<String> choose(List<QuestDefinition> source, int count, long seed) {
        List<QuestDefinition> candidates = new ArrayList<>(source);
        List<String> selected = new ArrayList<>();
        Random random = new Random(seed);
        while (selected.size() < count && !candidates.isEmpty()) {
            int totalWeight = candidates.stream().mapToInt(QuestDefinition::weight).sum();
            int roll = random.nextInt(Math.max(1, totalWeight));
            int cursor = 0;
            for (int i = 0; i < candidates.size(); i++) {
                cursor += Math.max(1, candidates.get(i).weight());
                if (roll < cursor) {
                    selected.add(candidates.remove(i).id());
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private record Selection(LocalDate date, List<String> questIds) {}
}
