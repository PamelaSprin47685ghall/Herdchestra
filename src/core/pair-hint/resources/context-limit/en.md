# context-limit

The context window is a number the provider declares. It is a fact, not an instruction. Its use is to ground judgement, not to make you panic early.

- The window size says only how many tokens one request can carry. It does not say when overflow will happen, and it does not say what to compress; do not predict overflow from it and do not compress on your own behalf.
- Compaction loses causality. What matters is not "nearly full" but "which facts must survive": the current goal, settled interfaces, open obligations, unclaimed results. Writing those clearly is worth more than saving a few thousand tokens.
- In a long delegation, context is preserved for you; your job is to leave recoverable facts: conclusions, paths, anchors. Anything you can recover by reading again need not stay resident.
- When you are genuinely near the limit, the right move is to put the conclusion into text, hand the work over or park it, not to shorten history and push on.
- A large window does not license waste, and a small window does not force an immediate close. The number is a reference; the facts are the ground.
