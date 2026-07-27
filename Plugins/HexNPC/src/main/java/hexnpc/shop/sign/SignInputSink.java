package hexnpc.shop.sign;

import java.util.UUID;

/**
 * Odbiornik surowego wejścia z wirtualnej tabliczki. Celowo używa wyłącznie
 * typów prymitywnych/JDK, żeby implementacja (SignInputService) nie musiała
 * ładować typów PacketEvents, gdy biblioteka jest nieobecna.
 */
public interface SignInputSink {

    /**
     * Wywoływane z wątku netty przez listener pakietów PacketEvents. Zwraca
     * true, jeśli pakiet dotyczył naszej wirtualnej tabliczki (wtedy listener
     * anuluje pakiet, by serwer nie próbował edytować realnego bloku).
     */
    boolean onSignUpdate(UUID uuid, int x, int y, int z, String[] lines);
}
