# Reel Challenge — local implementation validation

Generated: 2026-08-18

This is a local/static implementation report. It does **not** replace the external P0 evidence required by the plan (instrumented client, artificial network lag, human skill-curve, value-flow/UI/legal review).

## Static reel pool

- version: `v1`
- declared sets: `100`
- loaded sets: `100`
- physical strips: `500`
- strip length: `86`
- exact symbol counts per strip: `22 / 18 / 15 / 11 / 9 / 6 / 3 / 2`
- reel sets SHA-256: `2b23e2e74434d26d48e08c04431306845864c93e93739dfd21c6878f0543bcf0`

## Geometry

- 1-line: `1` winning pattern, 3 physical STOP units
- 3×3: `8` winning patterns
- 5×3: `22` winning patterns

## Deterministic replay smoke

A fixed deterministic STOP sequence was replayed twice for every static reel set and every layout.

- cases: `300`
- mismatches: `0`

Final visible symbols, winning-pattern count and direct payout were identical in every replay pair.

## Zero-RNG static scan

The same banned-token policy as Gradle `verifySkillSlotNoRng` was applied to deterministic Reel Challenge runtime files.

- banned tokens found: `0`
- result: `PASS`

## External P0 evidence still required

- PacketEvents `stateId` round-trip on the exact production Paper build
- 0–1000 ms artificial network-delay test
- 250/500/1000 ms main-thread freeze test
- instrumented client: rendered frame == click stateId == server FrameSnapshot
- human skill-curve for every enabled difficulty
- `$` / HexCoins value-flow review
- player-facing UI/regulations review
- legal review / art. 2(6) decision if pursued

Reward mode is currently **enabled by explicit project configuration**. External P0 evidence above is still pending and this report does not mark those gates as passed.

## Win settlement / presentation hotfix

- restored original win presentation: inventory closes temporarily and the configured green `win-subtitle` is shown
- inventory is reopened after `result-subtitle-ticks` through the guarded transition path (no close/reopen flicker loop)
- final reward is rounded once to the same 2-decimal amount sent to `hexeconomy add`
- the exact paid amount is also used by the daily counter and audit record
- delayed balance-placeholder verification logs a warning if the dispatched reward is not reflected shortly afterwards
- audit replay compares against the same final rounded currency amount

