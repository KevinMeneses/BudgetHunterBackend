# Plan: shared-budget limits per plan (backend)

Status: done (package `sharing/`). The app needs to read `sharing` from `GET /api/billing/status` and handle
`402 SHARING_LIMIT_REACHED` on `POST /api/budgets/{id}/collaborators`.

## Rules (decided)

| | FREE | PREMIUM |
|---|---|---|
| Shared budgets the account **owns** | `SHARING_FREE_SHARED_BUDGETS` (1) | unlimited |
| Members besides the owner, per budget | `SHARING_FREE_COLLABORATORS_PER_BUDGET` (1) | unlimited |

- **The owner's plan decides** (`budgets.owner_email`, plan read with `User.planAt(now)`), not the plan of
  whoever adds. Any member may still add people; a FREE member can invite to a PREMIUM owner's budget, a PREMIUM
  member cannot go past a FREE owner's limit.
- **Shared budget** = one the account owns with at least one member besides the owner. Budgets one is invited to
  never count against one's own limit.
- **Only additions are stopped.** Nothing is taken away: what was shared before the limit existed, while PREMIUM
  (a lapsed PREMIUM is FREE for anything new), or received in a hand-over when an account is deleted stays as it
  is, even past the limit. Removing members and leaving always work, and free up room.
- No migration: counted from `user_budgets` and `budgets.owner_email` on each addition.

## API

`POST /api/budgets/{id}/collaborators`, past a limit (checked after 404 unknown account / 409 already a member):

```json
{
  "status": 402,
  "code": "SHARING_LIMIT_REACHED",
  "message": "This budget cannot have more collaborators on its owner's plan",
  "sharing": { "limitType": "COLLABORATORS_PER_BUDGET", "plan": "FREE", "limit": 1, "used": 1, "callerIsOwner": true }
}
```

`limitType` is `SHARED_BUDGETS` or `COLLABORATORS_PER_BUDGET`; `plan` is the owner's; `callerIsOwner` tells
the app whether to offer PREMIUM (only the owner's upgrade lifts the limit) or to say "the owner's plan allows no
more". Same top-level shape as the receipts' 402 (`status`, `code`, `message`); a separate DTO
(`SharingErrorResponse`) because `ReceiptErrorResponse.usage` is the AI usage, which means nothing here.

`GET /api/billing/status` and `POST /api/billing/sync` add:

```json
"sharing": { "plan": "FREE", "sharedBudgetsLimit": 1, "sharedBudgetsUsed": 0, "collaboratorsPerBudgetLimit": 1 }
```

**A null limit means unlimited** (PREMIUM). `sharedBudgetsUsed` can be above the limit (nothing is taken away).
Every field has a default, and the object itself is new, so older apps ignore it. It lives in billing status
because that is what the app already reads to know the plan, and the limits are a property of the plan.

## Concurrency

`SharingLimitService.lockForAddition` runs inside `addCollaborator`'s transaction and, before anything is counted, locks
the **owner's `users` row** (`PESSIMISTIC_WRITE`). That row is the one thing every addition counted against an
owner has in common: locking the budget would not stop two additions to two *different* budgets of the same
owner from both taking the last shared budget. On PostgreSQL the lock is `FOR NO KEY UPDATE`, so foreign-key
checks of new entries or memberships pointing at the owner (`FOR KEY SHARE`) are not held back; only other
additions for the same owner wait, for a few milliseconds.

A conditional `UPDATE ... WHERE used < limit` (as receipts do) would need a stored counter: a migration, and a
count that can drift from `user_budgets` when members leave, are removed, or budgets are handed over. The lock
needs neither.

Lock order: owner row, then the budget row (shared, `findByIdForMembershipChange`), the same order as account
deletion (user row, then each budget it owns), so the two cannot deadlock. The budget lock keeps the owner from
changing until the addition commits; if it changed between reading and locking, the new owner is locked too.

`addCollaborator` checks whether the person is already a member (409) after that lock, not before: two requests
adding the same person at once then run one after the other, and the second sees the first one's committed
membership and answers 409. Checked before the lock, both passed it and the second, counting that person as a
collaborator, answered 402 `COLLABORATORS_PER_BUDGET` to a FREE owner. `checkCanAddMember` counts last.
The cost: a request for someone already in the budget waits for any addition in progress for the same owner
(a few milliseconds) before its 409.

The order is enforced, not only documented: `lockForAddition` returns a `LockedOwner`, whose constructor is
internal, and `checkCanAddMember` takes one, so the count cannot be called without the locks. "Already a
member" is its own `AlreadyCollaboratorException` (an `IllegalStateException`, so still 409 with the same body).

Tests: `SharingLimitIntegrationTest` (rules, status, 20 parallel additions to one budget and to 20 budgets of one
owner) and `SharingLimitMembersConcurrencyTest` (owner and members adding at once). Without the lock both
parallel-budget tests fail.
