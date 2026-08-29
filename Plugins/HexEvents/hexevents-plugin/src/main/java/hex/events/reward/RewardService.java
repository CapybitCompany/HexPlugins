package hex.events.reward;

import hex.events.api.*;
import hex.events.model.EventInstance;
import hex.events.persistence.PersistenceExecutor;
import hex.events.persistence.ResultRepository;
import hex.events.persistence.RewardRepository;
import hex.events.registry.RewardProviderRegistry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class RewardService {
    private final Plugin plugin;
    private final RewardEngine engine;
    private final RewardProviderRegistry providers;
    private final ResultRepository results;
    private final RewardRepository rewards;
    private final PersistenceExecutor persistence;

    public RewardService(Plugin plugin, RewardEngine engine, RewardProviderRegistry providers,
                         ResultRepository results, RewardRepository rewards, PersistenceExecutor persistence) {
        this.plugin=plugin;this.engine=engine;this.providers=providers;this.results=results;this.rewards=rewards;this.persistence=persistence;
    }

    public CompletableFuture<Void> planCompleted(EventInstance instance, EventResult raw){
        ProcessedResult processed=engine.enrich(raw);
        List<RewardPlanEntry> plan=engine.plan(instance.definition(),processed);
        Map<UUID,String> names=new HashMap<>();
        for(RewardPlanEntry e:plan){
            if(e.subject().type()==ResultSubjectType.PLAYER){
                OfflinePlayer op=Bukkit.getOfflinePlayer(e.subject().id());
                names.put(e.subject().id(),op.getName()==null?e.subject().id().toString():op.getName());
            }
        }
        return persistence.submit(() -> {
            results.save(instance.id(),processed);
            for(RewardPlanEntry e:plan) rewards.insertPlanned(instance.id(),instance.definition().id(),names.get(e.subject().id()),e);
            return null;
        });
    }


    public CompletableFuture<Void> cancelPending(UUID instanceId, String reason){
        return persistence.write(() -> rewards.cancelPending(instanceId, reason));
    }

    public CompletableFuture<Void> deliverForInstance(UUID instanceId){
        return persistence.read(() -> rewards.loadDeliverableForInstance(instanceId)).thenCompose(this::deliverRows);
    }

    public CompletableFuture<Void> deliverForPlayer(UUID playerId){
        return persistence.read(() -> rewards.loadDeliverableForPlayer(playerId)).thenCompose(this::deliverRows);
    }

    private CompletableFuture<Void> deliverRows(List<RewardRepository.RewardRow> rows){
        CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(RewardRepository.RewardRow row:rows) chain=chain.thenCompose(v->deliverOne(row));
        return chain;
    }

    private CompletableFuture<Void> deliverOne(RewardRepository.RewardRow row){
        return persistence.submit(() -> rewards.claim(row.rewardId())).thenCompose(claimed -> {
            if(!claimed) return CompletableFuture.completedFuture(null);
            RewardProvider provider=providers.find(row.provider()).orElse(null);
            if(provider==null||!provider.available()){
                String reason=provider==null?"PROVIDER_UNAVAILABLE":provider.unavailableReason();
                return persistDeliveryResult(row, RewardDeliveryResult.retry(reason));
            }

            CompletableFuture<RewardDeliveryResult> delivered;
            if(provider.requiresMainThread()){
                delivered=new CompletableFuture<>();
                Runnable work=()->{
                    try{ delivered.complete(provider.deliver(row.context(),row.grant())); }
                    catch(Throwable t){ delivered.complete(RewardDeliveryResult.reconcile("Provider threw after claim: "+rootMessage(t))); }
                };
                if(Bukkit.isPrimaryThread())work.run();else Bukkit.getScheduler().runTask(plugin,work);
            } else {
                delivered=persistence.io(() -> {
                    try { return provider.deliver(row.context(),row.grant()); }
                    catch(Throwable t){ return RewardDeliveryResult.reconcile("Provider threw after claim: "+rootMessage(t)); }
                });
            }
            return delivered.thenCompose(result -> persistDeliveryResult(row,result));
        }).exceptionally(error->{plugin.getLogger().log(Level.SEVERE,"Reward delivery failed for "+row.rewardId(),error);return null;});
    }

    private CompletableFuture<Void> persistDeliveryResult(RewardRepository.RewardRow row, RewardDeliveryResult result){
        return persistence.write(() -> {
            switch(result.status()){
                case DELIVERED -> rewards.delivered(row.rewardId());
                case RETRY_LATER -> rewards.retry(row.rewardId(),result.message());
                case FAILED_PERMANENT -> rewards.failed(row.rewardId(),result.message());
                case RECONCILIATION_REQUIRED -> rewards.reconcile(row.rewardId(),result.message());
            }
        });
    }

    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
