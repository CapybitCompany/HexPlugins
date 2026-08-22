# BusDriver deterministic deduction — local validation

Implemented from PLAN_BUSDRIVER_DETERMINISTIC_DEDUCTION_100_BOARDS_v3.

## Local checks performed

- 100/100 bundled boards exist.
- 100/100 board definitions are unique.
- 400 total stages.
- Every stage has exactly 3 hints.
- Every hint is true for its target.
- Every full hint set resolves to exactly one candidate and that candidate equals the stored target.
- `busdriver_boards.yml` SHA-256 matches `busdriver_boards.sha256`.
- Pure deduction engine compiles without Bukkit dependencies.
- BusDriver runtime classes compile against local Paper/PacketEvents API stubs.
- Static scan finds none of the banned RNG/shuffle APIs in `BusDriver*.java` / `StageFrameSnapshot.java`.

## External P0 checks still required on the real server

- PacketEvents / exact Paper version: visible GUI state -> client stateId -> resolved StageFrameSnapshot.
- artificial 0/100/250/500/750/1000 ms network delay.
- 20/15/10 TPS and 1000 ms main-thread freeze.
- instrumented-client visual-state correlation.
- long-term 3h skill-curve telemetry against the production data source.

`bus-driver.deterministic.paid-mode-enabled` is therefore false in the bundled default config. Enable it only for controlled QA or after the required P0 checks.
