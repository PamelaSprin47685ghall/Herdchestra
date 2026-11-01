# token-economy

The price of a request has three parts: input tokens that miss the cache, input tokens that hit it, and output tokens. The first two differ by an order of magnitude, so saving tokens starts with keeping the cached prefix intact, not with writing less.

- The prefix cache matches on the byte prefix of a request. **Keep stable facts first**: system prompt, tool definitions, session-bound facts. **Keep what changes last**: this turn's question, the newest observation. The same text in a different position is a cache miss.
- Do not reorder existing content for tidiness. One harmless edit can force a whole context to be recomputed.
- Read a file once per program and write it once. A repeated `cat` is repeated input tokens; a repeated `write` is a repeated conflict.
- Put large text in the conversation only when it is needed. Conclusions, anchors and line numbers usually suffice; before pasting a whole file, ask whether it will actually be used.
- Give output on demand: full for a delivery, terse for a process. Cut filler, never evidence.
- To diagnose a cache miss, check in this order: did the system prompt change, did the tool list change, was an earlier message rewritten, did an injected hint move? The first one usually answers.
