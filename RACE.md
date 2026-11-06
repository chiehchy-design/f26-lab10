# RACE.md

**The rule (from the ticket):** a room can have at most **one CONFIRMED booking** for any minute.

---

## Milestone 1: find the bug and make it fail every time

### Suspect list

| # | What touches shared state | Verdict |
|---|---|---|
| 1 | `BookingService.book()`: calls `hasOverlap`, then `insert` | **The bug.** Two separate locked calls. Another thread can book in between. |
| 2 | `InMemoryBookingStore`: every method is `synchronized` | Fine. But it only protects *one* call, not two calls in a row. |
| 3 | `cancelAndPromote()` | Fine. Cancel and promote happen inside one locked call. |
| 4 | `BookingStats` counters (`count++`, no lock) | A real race, but on purpose: the counts are approximate and nothing depends on them. |
| 5 | `sequence` (`AtomicLong`) for booking ids | Fine. Ids are always unique, which matches the ticket (different ids). |
| 6 | `catalog` (`HashMap`) | Fine. Filled once in the constructor, only read after that. |
| 7 | `joinWaitlist()`: inserts without checking | Fine. A waitlisted booking doesn't hold the room. |

**Why #1 is the bug:** `book()` is the only place that creates CONFIRMED bookings. It asks "is the
slot free?" and then, in a separate call, saves the booking. If two people both ask before either
one saves, both hear "free" and both get confirmed. That fits the ticket exactly: two
confirmations 1 ms apart, no errors, right when a slot opens. Humans clicking together are too
slow to hit a gap that small, so ops couldn't reproduce it.

### Predicted interleaving (written and committed before running the test)

| Step | Thread A | Thread B |
|---|---|---|
| t1 | `book(WEH-5409, 540, 600)` calls `hasOverlap` and gets **false** (room empty) | |
| t2 | *(paused right after the check, before `insert`)* | |
| t3 | | `book(WEH-5409, 540, 600)` calls `hasOverlap` and gets **false** (A has not inserted yet) |
| t4 | | `insert(bk-2, CONFIRMED)`, logs `booking.confirmed` |
| t5 | `insert(bk-1, CONFIRMED)`, logs `booking.confirmed` | |
| | **End state:** two CONFIRMED bookings for WEH-5409 540..600. The invariant is broken. | |

**What I missed:** only the ids. The id is created *after* the check, so B got `bk-1` and A got
`bk-2`. The end state was exactly as predicted.

### The race test

Test: `RaceTest.twoBookingsForTheSameSlotConfirmAtMostOnce`

**Aim: `hasOverlap`, call 1.**
- A makes the first check, hears "free", and gets paused before it can save.
- B runs its check (call 2, not paused), hears "free", and saves.
- A is released and saves too. Now there are 2 confirmed bookings.

The latches force this order every time. No sleeps, no luck.

Run 1:
```
2026-11-06T16:40:06.188Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T16:40:06.191Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
[ERROR] RaceTest.twoBookingsForTheSameSlotConfirmAtMostOnce:43 a room may hold at most one CONFIRMED booking for any minute ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

Run 2:
```
2026-11-06T16:40:07.222Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T16:40:07.224Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
[ERROR] RaceTest.twoBookingsForTheSameSlotConfirmAtMostOnce:43 a room may hold at most one CONFIRMED booking for any minute ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

Same pattern as the ticket's log: two confirmations for one slot, milliseconds apart.

---

## Milestone 2: the teammate's patch

**What the patch does:** checks `hasOverlap` a second time, right before `insert`.

**My milestone 1 test on the patch: green.** But that's because the test pauses A at the *first*
check. B books, then A's second check catches it:
```
2026-11-06T16:40:24.420Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T16:40:24.424Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

**Re-aimed test: red.** `RaceTest.doubleCheckStillLetsTwoBookingsConfirm`, aimed at
**`hasOverlap`, call 2**: A's *second* check, the one the patch added. A passes both checks and
gets paused right before saving. B books. A saves. Two confirmed again:
```
2026-11-06T16:40:33.651Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T16:40:33.653Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
[ERROR] RaceTest.doubleCheckStillLetsTwoBookingsConfirm:68 a room may hold at most one CONFIRMED booking for any minute ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```
This aim tests the patch directly: it pauses in the exact spot the patch claims is now safe.

**Verdict: reject.** The check and the save are still two separate calls, so the gap is still
there. It's just smaller, which is why the suite and the stress runner looked clean.
A green race test only means *that one order* is handled, not that the race is gone.

---

## Milestone 3: my fix

**Option 2: an atomic check-and-insert in the store.**

I added `insertIfFree(booking)` to `BookingStore`. It does the **check (`hasOverlap`) and the
save (`insert`) inside one `synchronized` call**, so no one can get in between. `book()` now
uses it. `PausingStore` passes it through.

**Why not just `synchronized` on the service?** The store can be shared by several service
objects. A service lock only blocks callers of that one service. The store lock blocks everyone.

```diff
// BookingService.book()
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+        if (!store.insertIfFree(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);

// BookingStore (interface)
+    boolean insertIfFree(Booking booking);

// InMemoryBookingStore
+    @Override
+    public synchronized boolean insertIfFree(Booking booking) {
+        if (hasOverlap(booking.roomId(), booking.startMinute(), booking.endMinute())) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }

// PausingStore
+    @Override
+    public boolean insertIfFree(Booking booking) {
+        return gate("insertIfFree", () -> delegate.insertIfFree(booking));
+    }
```

Milestone 1 test (re-aimed, see below): **green**
```
2026-11-06T16:41:22.841Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
2026-11-06T16:41:22.844Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Full suite: **green**
```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Is the milestone 1 test still testing anything?

Yes, but only because I changed it. After the fix, `book()` never calls `hasOverlap`, so the old
aim would never pause anything. The test would pass without testing anything.

- I moved the aim to **`insertIfFree`, call 1**.
- I added `assertTrue(gated.awaitPaused())`, so the test **fails if nothing pauses**.

Now A books and is paused, then B tries the same slot. The log shows B **rejected** while A is
paused. So the pause really happened, and the rule still held.

(The milestone 2 test no longer pauses on the fixed code either. I kept it as the evidence
against the patch.)

### What runs one at a time now

Every `book()` now holds the store's lock for the whole check + save. While it does, these
**wait in line**: other `book()` calls (**even for a different room**), `cancelBooking`,
`joinWaitlist`, and `listBookings`.

**Cost:** one lock for the whole store, held a little longer per booking. That's fine here; a busy
system might want one lock per room.

### What I left unfixed

- **`BookingStats` counters:** approximate on purpose, and nothing depends on them.
- **`hasOverlap` and `insert` are still public:** future code could misuse them, but
  `joinWaitlist` and the tests still need them.
- **One lock for all rooms, not one per room:** simpler, and correct. I'd split it only if it got slow.
- **Ids may skip a number:** a rejected booking now uses up an id. Ids only need to be unique.
