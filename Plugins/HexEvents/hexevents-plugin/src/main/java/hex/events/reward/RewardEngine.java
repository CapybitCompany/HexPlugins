package hex.events.reward;

import hex.events.api.*;
import hex.events.model.EventDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Pure deterministic result/ranking/reward planner. No Bukkit and no SQL. */
public final class RewardEngine {
    public ProcessedResult enrich(EventResult raw) {
        List<ResultSubject> original = raw == null ? List.of() : raw.subjects();
        List<ResultSubject> players = original.stream().filter(s -> s.type() == ResultSubjectType.PLAYER).toList();
        double totalDamage = players.stream().mapToDouble(s -> metric(s, "damage")).sum();
        List<ResultSubject> ranked = new ArrayList<>(players);
        ranked.sort(Comparator.<ResultSubject>comparingDouble(s -> metric(s,"damage")).reversed()
                .thenComparing(Comparator.<ResultSubject>comparingDouble(s -> metric(s,"hits")).reversed())
                .thenComparing(s -> s.id().toString()));
        Map<UUID,Integer> rank = new HashMap<>();
        for (int x=0;x<ranked.size();x++) rank.put(ranked.get(x).id(), x+1);
        List<ResultSubject> out = new ArrayList<>();
        for (ResultSubject s : original) {
            Map<String,Double> m = new LinkedHashMap<>(s.metrics());
            if (s.type() == ResultSubjectType.PLAYER) {
                double damage = metric(s,"damage");
                int r = rank.getOrDefault(s.id(), ranked.size()+1);
                m.put("damage_share", totalDamage <= 0 ? 0.0 : damage / totalDamage);
                m.put("damage_share_percent", totalDamage <= 0 ? 0.0 : damage * 100.0 / totalDamage);
                m.put("rank", (double) r);
                m.put("percentile", ranked.isEmpty() ? 0.0 : 100.0 * (ranked.size() - r + 1) / ranked.size());
            }
            out.add(new ResultSubject(s.type(), s.id(), m, s.tags()));
        }
        EventOutcome outcome = raw == null ? EventOutcome.FAILED : raw.outcome();
        Map<String,String> meta = raw == null ? Map.of() : raw.metadata();
        return new ProcessedResult(outcome, out, meta);
    }

    public List<RewardPlanEntry> plan(EventDefinition definition, ProcessedResult result) {
        if (definition == null || result == null || result.outcome() != EventOutcome.SUCCESS) return List.of();
        Map<String,Set<UUID>> selectedByRule = new LinkedHashMap<>();
        List<RewardPlanEntry> out = new ArrayList<>();
        for (EventDefinition.RewardRule rule : definition.rewards()) {
            List<ResultSubject> eligible = result.subjects().stream().filter(s -> s.type() == rule.target()).toList();
            List<ResultSubject> selected = select(rule.selector(), eligible, selectedByRule);
            selectedByRule.put(rule.id(), selected.stream().map(ResultSubject::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
            for (ResultSubject subject : selected) {
                for (int gi=0; gi<rule.grants().size(); gi++) {
                    EventDefinition.RewardGrantSpec spec = rule.grants().get(gi);
                    BigDecimal amount = calculateAmount(spec.amount(), subject, selected);
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                    out.add(new RewardPlanEntry(rule.id(), gi, subject, new RewardGrant(spec.type(), amount, spec.settings())));
                }
            }
        }
        return List.copyOf(out);
    }

    private List<ResultSubject> select(EventDefinition.RewardSelector selector, List<ResultSubject> eligible, Map<String,Set<UUID>> selectedByRule) {
        Comparator<ResultSubject> order = comparator(selector.metric());
        List<ResultSubject> sorted = new ArrayList<>(eligible);
        sorted.sort(order);
        return switch (selector.type()) {
            case PARTICIPATION -> sorted;
            case TOP_N -> sorted.stream().limit(Math.max(0, selector.n())).toList();
            case TOP_PERCENT -> {
                int count = (int)Math.ceil(sorted.size() * selector.percent() / 100.0);
                count = Math.max(count, selector.minimumWinners());
                if (selector.maximumWinners() > 0) count = Math.min(count, selector.maximumWinners());
                count = Math.min(count, sorted.size());
                yield sorted.stream().limit(count).toList();
            }
            case WINNER -> {
                List<ResultSubject> tagged = sorted.stream().filter(s -> s.tags().contains("WINNER")).toList();
                yield tagged.isEmpty() ? sorted.stream().limit(1).toList() : tagged;
            }
            case REMAINING_ELIGIBLE -> {
                Set<UUID> excluded = new HashSet<>();
                for (String id : selector.excludeRuleIds()) excluded.addAll(selectedByRule.getOrDefault(id, Set.of()));
                yield sorted.stream().filter(s -> !excluded.contains(s.id())).toList();
            }
        };
    }

    private Comparator<ResultSubject> comparator(String metric) {
        return Comparator.<ResultSubject>comparingDouble(s -> metric(s, metric)).reversed()
                .thenComparing(Comparator.<ResultSubject>comparingDouble(s -> metric(s,"hits")).reversed())
                .thenComparing(s -> s.id().toString());
    }

    private BigDecimal calculateAmount(EventDefinition.RewardAmount amount, ResultSubject subject, List<ResultSubject> selected) {
        BigDecimal value = switch (amount.type()) {
            case FIXED -> amount.fixed();
            case METRIC_SCALE -> amount.base().add(amount.perUnit().multiply(BigDecimal.valueOf(metric(subject, amount.metric()))));
            case POOL_SHARE -> {
                double total = selected.stream().mapToDouble(s -> Math.max(0.0, metric(s, amount.metric()))).sum();
                double own = Math.max(0.0, metric(subject, amount.metric()));
                yield total <= 0 ? BigDecimal.ZERO : amount.pool().multiply(BigDecimal.valueOf(own / total));
            }
        };
        if (amount.min() != null && value.compareTo(amount.min()) < 0) value = amount.min();
        if (amount.max() != null && value.compareTo(amount.max()) > 0) value = amount.max();
        int scale = (value.stripTrailingZeros().scale() <= 0) ? 0 : 2;
        return value.setScale(scale, amount.roundingMode() == null ? RoundingMode.DOWN : amount.roundingMode());
    }

    private static double metric(ResultSubject subject, String key) { return subject.metrics().getOrDefault(key, 0.0); }
}
