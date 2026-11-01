# wait-cost

Waiting is not free, but waiting long is not the same as being urgent. Calibrate in one direction only: make the wait match how urgent the work at hand really is.

- When a command has run for two minutes, first look at what it is doing; do not kill it first. Builds, tests and dependency installs take time, and restarting only starts them over.
- Get the conclusion first when you need one. Do not idle inside a wait when other work can advance: read the next place, write the next piece, dispatch the next worker.
- Do not poll often just to look busy. Polling costs turns and context, and a result does not arrive sooner because you asked again.
- A short command that is slow points at the environment first (network, locks, cold start), at the code second. A long job that is slow: first confirm it is not waiting for input that will never come.
- A park is not a failure. After a program parks, the world keeps running and it resumes where it stopped; treating the wait as a rerun you must arrange is the real waste.
- A timeout is information, not a verdict. It says this path costs more than expected; shorten it or take another path rather than simply retrying.
