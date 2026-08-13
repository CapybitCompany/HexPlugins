package hexcustomitems.model;

/**
 * Eine einzelne, konfigurierbare Aktion eines Custom-Items.
 * Aktionen werden pro Item in Reihenfolge ausgeführt.
 *
 * <p>{@link #offensive()} markiert Aktionen, die die Region-/PvP-Guard-Schicht
 * durchlaufen müssen (z.B. später zurückkehrende Angriffs-Items).
 */
public sealed interface ItemAction
        permits CommandAction, SelfPotionAction, MessageAction, SoundAction, SpecialAction {

    boolean offensive();
}
