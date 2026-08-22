# Current Reel Challenge corrections

This release applies the operator-requested corrections on top of deterministic v3.1:

- reward mode enabled;
- fixed variants: `1$/250ms`, `2$/225ms`, `5$/200ms`;
- training icon and preview training action removed from player-facing GUI;
- compass reel-sequence preview transition fixed so the machine GUI does not immediately reopen over it;
- manually closing the sequence preview returns to the machine GUI;
- STOP arrows are rendered only for the one-line layout; 3x3/5x3 use the active-reel glow only;
- all winning geometry remains unchanged: `1 / 8 / 22` patterns, including horizontal, vertical, normal diagonals and the two long 5x3 diagonals;
- PacketEvents window binding ignores window `0` and negative special/cursor updates and locks to the first positive container id, preventing unrelated SET_SLOT updates from overwriting the active Reel Challenge window and causing false `WINDOW_MISMATCH` rejections.

The strict resolver still rejects unmapped/replayed/stale/future state instead of guessing the current frame.
