package hexcasino.machine;

import java.util.List;
import java.util.Objects;

/** Legacy probability helpers retained only for config compatibility. Runtime BusDriver outcome does not use this class. */
public final class BusDriverEngine {
    private static final int MIN_RANK = 2;
    private static final int MAX_RANK = 14;
    private static final int RANK_COUNT = MAX_RANK - MIN_RANK + 1;

    public boolean resolveColorGuess(Card card, boolean black) { Objects.requireNonNull(card); return black ? !card.red() : card.red(); }
    public boolean resolveHigherLower(Card current, Card next, boolean higher) { Objects.requireNonNull(current); Objects.requireNonNull(next); return higher ? next.rank()>current.rank() : next.rank()<current.rank(); }
    public boolean resolveBetweenOutside(Card first, Card second, Card next, boolean outside) {
        Objects.requireNonNull(first); Objects.requireNonNull(second); Objects.requireNonNull(next);
        int low=Math.min(first.rank(),second.rank()), high=Math.max(first.rank(),second.rank());
        boolean between=next.rank()>low&&next.rank()<high, strictlyOutside=next.rank()<low||next.rank()>high;
        return outside?strictlyOutside:between;
    }
    public boolean resolveSuitGuess(Card card,Suit suit){Objects.requireNonNull(card);Objects.requireNonNull(suit);return card.suit()==suit;}

    /** Historical balance validator for the configured four-step payout ladder; not used to resolve a game. */
    public double expectedOptimalRtp(List<Double> payouts) {
        Objects.requireNonNull(payouts);
        if(payouts.size()!=4) throw new IllegalArgumentException("BusDriver payout ladder must contain exactly 4 rounds");
        double total=0;
        for(int first=MIN_RANK;first<=MAX_RANK;first++) total+=valueAfterRoundOne(first,payouts);
        return 0.5D*total/RANK_COUNT;
    }
    private double valueAfterRoundOne(int first,List<Double> p){double cash=p.get(0),hi=0,lo=0;for(int second=MIN_RANK;second<=MAX_RANK;second++){if(second>first)hi+=valueAfterRoundTwo(first,second,p)/RANK_COUNT;if(second<first)lo+=valueAfterRoundTwo(first,second,p)/RANK_COUNT;}return Math.max(cash,Math.max(hi,lo));}
    private double valueAfterRoundTwo(int first,int second,List<Double> p){double cash=p.get(1);int low=Math.min(first,second),high=Math.max(first,second);double between=0,outside=0;for(int third=MIN_RANK;third<=MAX_RANK;third++){if(third>low&&third<high)between+=valueAfterRoundThree(p)/RANK_COUNT;if(third<low||third>high)outside+=valueAfterRoundThree(p)/RANK_COUNT;}return Math.max(cash,Math.max(between,outside));}
    private double valueAfterRoundThree(List<Double> p){return Math.max(p.get(2),0.25D*p.get(3));}

    public record Card(int rank,Suit suit){public Card{if(rank<MIN_RANK||rank>MAX_RANK)throw new IllegalArgumentException("rank must be between 2 and 14");Objects.requireNonNull(suit);}public boolean red(){return suit==Suit.HEARTS||suit==Suit.DIAMONDS;}public String label(){return rankLabel(rank)+" "+suit.label();}}
    public enum Suit{HEARTS("Kier"),DIAMONDS("Karo"),CLUBS("Trefl"),SPADES("Pik");private final String label;Suit(String label){this.label=label;}public String label(){return label;}}
    private static String rankLabel(int rank){return switch(rank){case 11->"J";case 12->"Q";case 13->"K";case 14->"A";default->Integer.toString(rank);};}
}
