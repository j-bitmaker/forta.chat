@AGENTS.md

## Claude Code notes

- Verify before commit: `npm run build` (vue-tsc + vite), `npm run test`; Kotlin tests for `android/` changes: `cd android && ./gradlew :app:testSideloadDebugUnitTest --rerun-tasks` (without `--rerun-tasks` Gradle reports cached success).
- Run `/code-review` on the diff before committing.
- Update AGENTS.md when conventions change, not this file.
