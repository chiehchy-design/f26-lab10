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

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.
