package hex.events.provider;

import hex.economy.api.CurrencyType;
import hex.economy.api.HexEconomyApi;
import hex.events.api.*;

public final class EconomyRewardProvider implements RewardProvider {
    private final String type;
    private final CurrencyType currency;
    private final HexEconomyApi economy;
    public EconomyRewardProvider(String type, CurrencyType currency, HexEconomyApi economy){ this.type=type;this.currency=currency;this.economy=economy; }
    @Override public String type(){ return type; }
    @Override public boolean available(){ return economy != null && economy.isCurrencyAvailable(currency); }
    @Override public String unavailableReason(){ return available()?"":"HexEconomy currency unavailable: "+currency; }
    @Override public boolean requiresMainThread(){ return currency == CurrencyType.HEX_COINS; }
    @Override public RewardDeliveryResult deliver(RewardContext context, RewardGrant grant){
        if(context.subjectType()!=ResultSubjectType.PLAYER) return RewardDeliveryResult.failed("Economy reward requires PLAYER subject");
        if(grant.amount().signum()<=0) return RewardDeliveryResult.failed("Amount must be > 0");
        if(currency==CurrencyType.HEX_COINS && grant.amount().stripTrailingZeros().scale()>0) return RewardDeliveryResult.failed("HEX_COINS amount must be integer");
        String name=context.subjectName()==null||context.subjectName().isBlank()?context.subjectId().toString():context.subjectName();
        var result=economy.deposit(context.subjectId(),name,currency,grant.amount(),"HexEvents reward "+context.instanceId());
        return result.success()?RewardDeliveryResult.delivered():RewardDeliveryResult.retry(result.reason());
    }
}
