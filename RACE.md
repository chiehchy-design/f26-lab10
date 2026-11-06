# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line
verdict for each. Add or remove rows as needed.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book()`: `store.hasOverlap(...)` then `store.insert(...)` | **The bug.** Each call is synchronized on its own, but nothing holds the lock between the check and the insert (check-then-act). |
| 2 | `InMemoryBookingStore`: every method `synchronized` on the store | Fine on its own. Each single call is atomic, but that does not make two calls in a row atomic. |
| 3 | `cancelAndPromote()`: cancel + promote in one store call | Fine. Both changes happen under one lock, and promotion re-checks for confirmed overlaps. (A `book()` sitting in the gap from #1 can still race it, but that is the same bug as #1.) |
| 4 | `BookingStats`: plain `long` counters, `count++` with no lock | Real race (lost updates), but documented as approximate and nothing reads it. Not the ticket. |
| 5 | `BookingService.sequence` (`AtomicLong`) for booking ids | Fine. Atomic, so ids are unique. That matches the ticket (different ids). |
| 6 | `BookingService.catalog` (`HashMap`) | Fine. Built in the constructor, stored in a `final` field, and only read after that. |
| 7 | `joinWaitlist()`: `insert` with no check | Fine. A WAITLISTED entry blocks nothing, so it cannot break the invariant. |

**Which one explains the ticket, and how you know.** #1. The invariant is "a room has at most
one CONFIRMED booking covering any minute". Only `book()` creates CONFIRMED bookings from
scratch, and it decides "the slot is free" in one locked call and acts on it in another. Two
requests that both finish `hasOverlap` before either one calls `insert` both see "free" and
both insert as CONFIRMED. That matches the ticket: two confirmations 1 ms apart, different ids,
no errors, and only when a slot has just opened and people rush for it. Clicking on a count of
three is far too slow to land inside a gap that small, which is why ops could not reproduce it.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each
thread does at that step and leave the other column empty. Add or remove rows
as your prediction needs.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | `book(WEH-5409, 540, 600)` calls `hasOverlap` and gets **false** (room empty) | |
| t2 | *(paused right after the check, before `insert`)* | |
| t3 | | `book(WEH-5409, 540, 600)` calls `hasOverlap` and gets **false** (A has not inserted yet) |
| t4 | | `insert(bk-2, CONFIRMED)`, logs `booking.confirmed` |
| t5 | `insert(bk-1, CONFIRMED)`, logs `booking.confirmed` | |
| | **End state:** two CONFIRMED bookings for WEH-5409 540..600. The invariant is broken. | |

If the run turned out different from your prediction, add one line on what
you missed. Leave the table as you first wrote it.

> **What I missed:** the end state matched. Only the ids were swapped: `book()` calls `nextId()`
> *after* `hasOverlap`, so B (which got past the check first) got `bk-1` and A got `bk-2`.

### Your race test

**How you aimed `PausingStore`, and the result.** Method name and call index, why
that pair pins the schedule you predicted, and the output of two separate
runs (assertion failure and log lines).

Test: `RaceTest.twoBookingsForTheSameSlotConfirmAtMostOnce`.

**Aim: `hasOverlap`, call 1.** Thread A makes the first `hasOverlap` call. It gets back "free"
and then parks *before* it can `insert`. That is exactly the gap in suspect #1. Only once A is
parked (`awaitPaused()`) does the test start B. B's `hasOverlap` is call 2, so it passes
straight through. It also sees "free", inserts, and confirms. Then `release()` lets A insert
too. No sleeps and no luck involved: the latches force this order on every run.

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

This matches the ticket's log: two `booking.confirmed` lines for the same room and range, a few
ms apart, with nothing logged in between.

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**Green**, but only because the gap moved somewhere the test does not look:
```
2026-11-06T16:40:24.420Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T16:40:24.424Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```
A parks after its *first* check. While it is parked, B books. Then A's new re-check sees B's
booking and rejects.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

Test: `RaceTest.doubleCheckStillLetsTwoBookingsConfirm`. **Aim: `hasOverlap`, call 2**, which
is A's re-check, the one the patch added.
```
2026-11-06T16:40:33.651Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T16:40:33.653Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
[ERROR] RaceTest.doubleCheckStillLetsTwoBookingsConfirm:68 a room may hold at most one CONFIRMED booking for any minute ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```
Why this aim tests the patch: the patch's claim is "nothing can sneak in after the re-check".
Parking right after the re-check targets exactly that claim. There is still a gap between the
last `hasOverlap` and `insert`, and B gets through it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

**Reject.** The re-check is still a separate locked call from the insert, so it is the same
check-then-act bug with the gap moved a few lines down. All it does is make the gap smaller,
which is why the suite and the stress runner looked clean. The green milestone-1 run only
proves the patch closes *that one* schedule. It says nothing about the other schedules. A green
race test means "this interleaving is handled", not "the race is gone".

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Option 2: an atomic check-and-insert in the store.** I added `BookingStore.insertIfFree(booking)`.
In `InMemoryBookingStore` it is `synchronized` on the store, so **`hasOverlap` and the insert
now run under one lock, in one call**. `book()` calls it instead of `hasOverlap` + `insert`.
`PausingStore` passes it through (as the handout says, the build broke until I added it).

Why option 2 and not `synchronized` on the service: the constructor says the store can be
*shared by several service instances*. A lock on the service only stops callers of that one
instance. The store's lock covers everyone, and it is the same lock `cancelAndPromote` already
uses, so book and cancel/promote can't interleave either.

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
index 5e6b639..1118dd1 100644
--- a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
@@ -111,2 +111,7 @@ public final class PausingStore implements BookingStore {
 
+    @Override
+    public boolean insertIfFree(Booking booking) {
+        return gate("insertIfFree", () -> delegate.insertIfFree(booking));
+    }
+
     @Override
diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
index 514c3e1..70028f7 100644
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ -69,7 +69,7 @@ public final class BookingService {
         }
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+        // One store call: the check and the insert happen under the store's lock.
+        if (!store.insertIfFree(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
         stats.recordConfirmed();
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
index 3ae3544..6d81401 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
@@ -27,2 +27,11 @@ public interface BookingStore {
 
+    /**
+     * Stores a CONFIRMED booking only if no CONFIRMED booking for the same room
+     * overlaps its range, as one indivisible step. No other thread can change
+     * the room between the check and the insert.
+     *
+     * @return true when the booking was stored, false when the slot was held
+     */
+    boolean insertIfFree(Booking booking);
+
     /** Stores the booking. Ids are assumed unique; the store does not check. */
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
index f7fd85f..6762c31 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
@@ -33,2 +33,12 @@ public final class InMemoryBookingStore implements BookingStore {
 
+    @Override
+    public synchronized boolean insertIfFree(Booking booking) {
+        // Check and insert under the same lock, so nobody can book in between.
+        if (hasOverlap(booking.roomId(), booking.startMinute(), booking.endMinute())) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }
+
     @Override
```

Milestone-1 test (re-aimed, see below):
```
2026-11-06T16:41:22.841Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
2026-11-06T16:41:22.844Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
Full suite:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**I adjusted it.** After the fix, `book()` never calls `hasOverlap`, so the old aim
(`hasOverlap`, 1) would never fire. The test would go green for the wrong reason: nothing was
paused, so it would be testing nothing. I re-aimed it at **`insertIfFree`, call 1** and added
`assertTrue(gated.awaitPaused())`, so the test fails if the pause doesn't happen. Now A
check-and-inserts, parks, and B books in that gap. The log shows B **rejected** while A is
parked, then A's confirm. So yes, it paused, and the invariant held anyway.

(The milestone-2 test, aimed at `hasOverlap` 2, no longer fires on the fixed code either. It
stays as the evidence against the patch, and it is green here after its ~2 s timeout.)

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

Every `book()` call on the store, for **any room**, now waits for the store's single lock while
it scans the list and inserts. Before, a booker only held the lock for the scan, let go, and
held it again for the insert, so other callers could slip in between. Now the whole
check-and-insert is one turn, so these all queue behind it: other `book()` calls (even for a
different room), `cancelBooking` (`cancelAndPromote`), `joinWaitlist` (`insert`), and
`listBookings` (`bookingsForRoom`). **Cost:** one lock for the whole store, held a bit longer per
booking. That's fine for an in-memory list. A busy system would want a lock per room.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.

- **`BookingStats` lost updates** (suspect #4): documented as approximate, nothing reads it, and
  locking every counter adds contention for a dashboard tile.
- **`hasOverlap` / `insert` are still public on the store:** some future caller could rebuild
  the check-then-act. I left them because `joinWaitlist`, the tests, and `PausingStore` use
  them. The javadoc on `insertIfFree` says to use it for confirmed bookings.
- **One store-wide lock, not one per room:** simpler and obviously correct. I'd only split it if
  contention showed up.
- **Ids can now skip a number:** `nextId()` runs before the atomic check, so a rejected booking
  uses up an id. Ids only need to be unique, so this is harmless.
