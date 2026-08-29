package hex.events.reward;

import hex.events.api.ResultSubject;
import hex.events.api.RewardGrant;

public record RewardPlanEntry(String ruleId, int grantIndex, ResultSubject subject, RewardGrant grant) { }
