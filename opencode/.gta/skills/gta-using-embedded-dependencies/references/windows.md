# Windows notes

- Run `.\gtaw.cmd` (PowerShell or cmd); the agents use the same command.
- Keep the repository on a short path (e.g. `C:\dev\order-service`) to stay under the 260-character limit, or enable long paths in git (`git config core.longpaths true`).
- Embedded brokers and databases write to directories under `target/`; never reuse a directory between runs (file locks survive crashed JVMs).
- Line endings: tests that compare files must normalise `\r\n`.
- Paths in fixtures and assertions use `/` and `Path.of(...)`, never hard-coded `\`.
- Time zone and locale differ between developer machines: gtaw also runs new tests under `Asia/Kolkata` and `de-DE`.
