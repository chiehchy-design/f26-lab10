package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Concurrency tests that pin one interleaving with PausingStore. */
class RaceTest {

    private static final String WEAN = "WEH-5409";
    private static final List<Room> ROOMS = List.of(
            new Room(WEAN, "Wean 5409", 12),
            new Room("GHC-4301", "Gates 4301", 30));

    /**
     * Milestone 1: two customers book the same free slot. Thread A is parked
     * right after its overlap check says "free", and thread B books the same
     * slot in that gap. At most one booking may end up CONFIRMED.
     */
    @Test
    void twoBookingsForTheSameSlotConfirmAtMostOnce() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
        BookingService service = new BookingService(gated, ROOMS);

        Thread a = new Thread(() -> service.book(WEAN, 540, 600), "customer-A");
        a.start();
        gated.awaitPaused(); // A has checked "free" and is parked before insert

        Thread b = new Thread(() -> service.book(WEAN, 540, 600), "customer-B");
        b.start();
        b.join(PausingStore.TIMEOUT_MILLIS);

        gated.release(); // A goes on to insert
        a.join(PausingStore.TIMEOUT_MILLIS);

        assertEquals(1, confirmedCount(real, 540, 600),
                "a room may hold at most one CONFIRMED booking for any minute");
    }

    /**
     * Milestone 2: the teammate's patch checks twice. Park A after its SECOND
     * hasOverlap (the re-check right before insert) and let B book in that gap.
     */
    @Test
    void doubleCheckStillLetsTwoBookingsConfirm() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 2);
        BookingService service = new BookingService(gated, ROOMS);

        Thread a = new Thread(() -> service.book(WEAN, 540, 600), "customer-A");
        a.start();
        gated.awaitPaused(); // A passed both checks and is parked before insert

        Thread b = new Thread(() -> service.book(WEAN, 540, 600), "customer-B");
        b.start();
        b.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        a.join(PausingStore.TIMEOUT_MILLIS);

        assertEquals(1, confirmedCount(real, 540, 600),
                "a room may hold at most one CONFIRMED booking for any minute");
    }

    private static int confirmedCount(InMemoryBookingStore store, int start, int end) {
        int count = 0;
        for (Booking booking : store.bookingsForRoom(WEAN)) {
            if (booking.status() == BookingStatus.CONFIRMED && booking.overlaps(start, end)) {
                count++;
            }
        }
        return count;
    }
}
