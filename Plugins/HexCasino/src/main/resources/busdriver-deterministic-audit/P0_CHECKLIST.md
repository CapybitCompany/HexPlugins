# BusDriver P0 checklist

- [x] 100 deterministic boards bundled
- [x] all board stages statically defined
- [x] runtime outcome has no RNG/shuffle
- [x] every stage has exactly one solver result
- [x] every hint is true for the target
- [x] no contradictory hint sets
- [x] per-player sequential board cycle
- [x] board number shown before start
- [x] deterministic 5000 ms decision window for every paid variant
- [x] stateId/frame snapshot resolver implemented
- [x] stale/replay/future-state fail-closed path implemented
- [x] disconnect/restart checkpoint prevents board reroll
- [x] TECHNICAL_VOID refunds and preserves board
- [x] `/busdriver verify`
- [x] `/busdriver board <id>`
- [ ] real 0-1000 ms packet-delay QA
- [ ] instrumented-client visible-state QA
- [ ] production 3h telemetry validation
