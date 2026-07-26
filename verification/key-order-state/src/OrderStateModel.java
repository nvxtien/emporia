/**
 * Dependency-free verification model for the quantity and status transitions in
 * {@code TradingOrder}.
 *
 * <p>Quantities are represented as integral lots. The production service maps these
 * values to {@code BigDecimal} using the listing's size increment.
 */
public final class OrderStateModel {
    public static final int LIVE = 0;
    public static final int PARTIALLY_FILLED = 1;
    public static final int FILLED = 2;
    public static final int CANCELLED = 3;

    private /*@ spec_public @*/ int quantityLots;
    private /*@ spec_public @*/ int tradedLots;
    private /*@ spec_public @*/ int remainingLots;
    private /*@ spec_public @*/ int status;
    private /*@ spec_public @*/ int targetStatus;

    /*@ public instance invariant quantityLots > 0;
      @ public instance invariant quantityLots <= 2147483647;
      @ public instance invariant 0 <= tradedLots && tradedLots <= quantityLots;
      @ public instance invariant remainingLots == quantityLots - tradedLots;
      @ public instance invariant 0 <= remainingLots;
      @ public instance invariant
      @     status == LIVE
      @     || status == PARTIALLY_FILLED
      @     || status == FILLED
      @     || status == CANCELLED;
      @ public instance invariant targetStatus == status;
      @ public instance invariant
      @     status == LIVE
      @     ==> tradedLots == 0 && remainingLots == quantityLots;
      @ public instance invariant
      @     status == PARTIALLY_FILLED
      @     ==> tradedLots > 0 && remainingLots > 0;
      @ public instance invariant
      @     status == FILLED
      @     ==> tradedLots == quantityLots && remainingLots == 0;
      @ public instance invariant
      @     status == CANCELLED
      @     ==> tradedLots < quantityLots && remainingLots > 0;
      @*/

    /*@ public normal_behavior
      @ requires initialQuantityLots > 0;
      @ ensures quantityLots == initialQuantityLots;
      @ ensures tradedLots == 0;
      @ ensures remainingLots == initialQuantityLots;
      @ ensures status == LIVE;
      @ ensures targetStatus == LIVE;
      @*/
    public OrderStateModel(int initialQuantityLots) {
        quantityLots = initialQuantityLots;
        tradedLots = 0;
        remainingLots = initialQuantityLots;
        status = LIVE;
        targetStatus = LIVE;
    }

    /*@ public normal_behavior
      @ requires status == LIVE || status == PARTIALLY_FILLED;
      @ requires fillLots > 0;
      @ requires fillLots <= remainingLots;
      @ assignable tradedLots, remainingLots, status, targetStatus;
      @ ensures quantityLots == \old(quantityLots);
      @ ensures tradedLots == \old(tradedLots) + fillLots;
      @ ensures remainingLots == quantityLots - tradedLots;
      @ ensures remainingLots == 0 ==> status == FILLED;
      @ ensures remainingLots > 0 ==> status == PARTIALLY_FILLED;
      @ ensures targetStatus == status;
      @*/
    public void applyFill(int fillLots) {
        remainingLots = remainingLots - fillLots;
        //@ assert 0 <= quantityLots - remainingLots;
        //@ assert quantityLots - remainingLots <= 2147483647;
        tradedLots = quantityLots - remainingLots;
        if (remainingLots == 0) {
            status = FILLED;
        } else {
            status = PARTIALLY_FILLED;
        }
        targetStatus = status;
    }

    /*@ public normal_behavior
      @ requires status == LIVE || status == PARTIALLY_FILLED;
      @ requires newQuantityLots > tradedLots;
      @ assignable quantityLots, remainingLots;
      @ ensures quantityLots == newQuantityLots;
      @ ensures tradedLots == \old(tradedLots);
      @ ensures remainingLots == newQuantityLots - tradedLots;
      @ ensures status == \old(status);
      @ ensures targetStatus == \old(targetStatus);
      @*/
    public void modify(int newQuantityLots) {
        quantityLots = newQuantityLots;
        remainingLots = newQuantityLots - tradedLots;
    }

    /*@ public normal_behavior
      @ requires status == LIVE || status == PARTIALLY_FILLED;
      @ assignable status, targetStatus;
      @ ensures quantityLots == \old(quantityLots);
      @ ensures tradedLots == \old(tradedLots);
      @ ensures remainingLots == \old(remainingLots);
      @ ensures status == CANCELLED;
      @ ensures targetStatus == CANCELLED;
      @*/
    public void cancel() {
        status = CANCELLED;
        targetStatus = CANCELLED;
    }
}
