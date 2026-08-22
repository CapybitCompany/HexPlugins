# Reel Challenge — P0 release evidence

Reward mode is explicitly enabled in this release at the operator's request. The external P0 evidence below is **not** marked as completed automatically; only PacketEvents/stateId resolution remains a hard runtime requirement for a paid STOP.

Required evidence / follow-up validation:

- [x] deterministic 100/100 reel-set replay smoke test
- [x] zero-RNG source scan
- [ ] PacketEvents `stateId` round-trip on the exact Paper/server version under artificial lag
- [ ] accepted-click mapping remains exact at 0/100/250/500/750/1000 ms artificial delay
- [ ] accepted-click mapping remains exact with 250/500/1000 ms main-thread freeze
- [ ] instrumented QA client confirms `rendered frame == click stateId == server FrameSnapshot`
- [ ] human skill-curve study passes for every production variant
- [ ] value-flow review confirms the relationship between `$`, PLN and `HexCoins`
- [ ] player-facing UI/regulations review completed

Current production variants are fixed in code for migration safety:

```text
1$ -> 250 ms
2$ -> 225 ms
5$ -> 200 ms
```

Current runtime switch:

```yaml
rewards-mode:
  enabled: true
```

Paid STOP handling still fails closed if PacketEvents/stateId is unavailable. Documentary validation flags are retained as audit metadata but do not block reward mode in this release.
