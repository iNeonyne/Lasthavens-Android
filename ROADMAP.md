# LastHavenPlus roadmap

**Last updated:** 2026-11-28  
**Latest stable release:** [v2.4.0](https://github.com/iNeonyne/LastHavenPlus/releases/tag/v2.4.0)

This is the single strategic roadmap for the project. It records the current priorities and intended sequence, not individual agent assignments. GitHub Issues, their current labels/comments, pull requests and Actions remain authoritative for implementation scope and live task state. The repository owner controls priority changes and release decisions.

## Current priorities

### 1. Complete the Addon Manager software integration

Finish the remaining code-level integration while preserving the existing game session and app-private addon library.

- [#147 — Consolidate launcher navigation and back-stack state](https://github.com/iNeonyne/LastHavenPlus/issues/147). Completed and integrated into beta by [PR #204](https://github.com/iNeonyne/LastHavenPlus/pull/204) (merge commit `ba1edf6a3919e8942ad8796326e2d67f40667a09`).
- [#183 — Disconnect stale addon bridge ports when GeckoSession changes](https://github.com/iNeonyne/LastHavenPlus/issues/183). Assigned to Vibe (`agent:vibe`) as the next isolated code-level fix; disconnect old-session ports, reject late callbacks and cover replacement/teardown with automated regression tests.
- [#170 — Integrate Addon Manager navigation and same-session behavior](https://github.com/iNeonyne/LastHavenPlus/issues/170). Remains sequenced after #183 is integrated; then complete the overlay entry/return flow and automated lifecycle/navigation tests.

The former physical-device runtime acceptance task [#167](https://github.com/iNeonyne/LastHavenPlus/issues/167) is out of scope and closed. This project does not perform physical-device testing. Acceptance is based on source review, coding/debugging, automated tests and CI; do not make unsupported claims about actual in-game addon execution.

### 2. Resume updater reliability work

After the Addon Manager software integration is safely handed off, resume the coordinated updater reliability work:

- [#4 — Build reproducibility and updater test coverage](https://github.com/iNeonyne/LastHavenPlus/issues/4).
- [#15 — Updater recovery](https://github.com/iNeonyne/LastHavenPlus/issues/15).
- [#111 — Update progress and retry feedback](https://github.com/iNeonyne/LastHavenPlus/issues/111), sequenced after the recovery work.

Keep overlapping updater implementation under one owner. Preserve verified full-APK fallback, bounded downloads, digest/signature validation and explicit user-controlled installation. The live issue assignments determine when this work resumes.

### 3. Improve settings and diagnostics

Once higher-priority reliability work is complete, revisit the scoped settings and diagnostics backlog. Relevant work includes [storage accounting (#28)](https://github.com/iNeonyne/LastHavenPlus/issues/28), [settings (#6)](https://github.com/iNeonyne/LastHavenPlus/issues/6), [orientation and keep-awake (#16)](https://github.com/iNeonyne/LastHavenPlus/issues/16), [load-error details (#12)](https://github.com/iNeonyne/LastHavenPlus/issues/12), [connection diagnostics (#14)](https://github.com/iNeonyne/LastHavenPlus/issues/14), [network totals (#10)](https://github.com/iNeonyne/LastHavenPlus/issues/10) and [settings backup (#17)](https://github.com/iNeonyne/LastHavenPlus/issues/17).

These are strategic areas, not simultaneous assignments. Chaty should sequence them from the live dependencies and owner priorities before work starts.

## Ongoing engineering constraints

- ARM64 only (`arm64-v8a`); preserve the application ID and established signing identity.
- Use the GeckoView ARM64 Stable artifact pinned in the Gradle version catalog.
- Keep the existing game and browser profile data safe; do not modify game-hosted files.
- Keep update checks, downloads and installation user-initiated.
- Prefer coding, debugging and automated regression tests. Physical-device testing is not part of project acceptance.
- Require exact-head CI for changes and avoid speculative dependency/toolchain changes.

## Roadmap maintenance

Update this file when strategic priorities, sequence or release direction changes. Keep detailed acceptance criteria, ownership, blockers and progress in the linked GitHub Issues and pull requests; do not duplicate them here. Release versions and publication require explicit repository-owner approval.

<!-- ROADMAP-LIVE-START -->
## Live issue and PR inventory

Generated: 2026-11-28T17:56:08+00:00
Source of truth: live GitHub Issues, pull requests and labels. This section is generated automatically; do not edit it manually.

Every open issue is listed below, grouped by its current priority. Opening a newer issue or PR does not supersede an older one. Duplicates and supersession must be explicitly reviewed and linked in GitHub.

### Active issues

#### P0.1

- [#4 — P0.1 — Improve build reproducibility and updater test coverage](https://github.com/iNeonyne/LastHavenPlus/issues/4) — bug, P0.1

#### P1

- [#28 — P1 — Measure game-origin storage accurately for Network & Storage](https://github.com/iNeonyne/LastHavenPlus/issues/28) — enhancement, P1, coderabbit-fix
- [#248 — P1 — Native landscape UX, POCO X6 Pro layout and Android UI smoke coverage](https://github.com/iNeonyne/LastHavenPlus/issues/248) — enhancement, P1, coderabbit-fix

#### P2

- [#14 — P2 — User-triggered connection diagnostics](https://github.com/iNeonyne/LastHavenPlus/issues/14) — enhancement, P2
- [#15 — P2 — Make updater recovery safer](https://github.com/iNeonyne/LastHavenPlus/issues/15) — bug, P2
- [#16 — P2 — Keep-awake preference for landscape-only gameplay](https://github.com/iNeonyne/LastHavenPlus/issues/16) — enhancement, P2
- [#17 — P2 — Back up and restore launcher settings](https://github.com/iNeonyne/LastHavenPlus/issues/17) — enhancement, P2
- [#221 — P2 — Audit localization completeness and RTL layout behavior](https://github.com/iNeonyne/LastHavenPlus/issues/221) — enhancement, P2
- [#223 — P2 — Confirm destructive clearing of local diagnostic logs](https://github.com/iNeonyne/LastHavenPlus/issues/223) — bug, P2
- [#230 — P2 — Handle malformed updater installer handoff state without crashing](https://github.com/iNeonyne/LastHavenPlus/issues/230) — bug, P2
- [#231 — P2 — Make addon runtime origin restrictions visible to users](https://github.com/iNeonyne/LastHavenPlus/issues/231) — enhancement, P2
- [#232 — P2 — Verify and constrain GeckoView popup and new-session requests](https://github.com/iNeonyne/LastHavenPlus/issues/232) — bug, P2, security
- [#233 — P2 — Document the security trust model for installed addons](https://github.com/iNeonyne/LastHavenPlus/issues/233) — documentation, P2, security
- [#234 — P2 — Apply small updater, bridge protocol and archive safety cleanups](https://github.com/iNeonyne/LastHavenPlus/issues/234) — P2, maintenance
- [#235 — P2 — Preserve explicit return destinations when opening Diagnostics](https://github.com/iNeonyne/LastHavenPlus/issues/235) — bug, P2
- [#243 — P2 — Include version and channel in published APK asset names](https://github.com/iNeonyne/LastHavenPlus/issues/243) — enhancement, P2

#### P3

- [#187 — P3 — Verify direct AndroidX dependencies are necessary](https://github.com/iNeonyne/LastHavenPlus/issues/187) — dependencies, P3
- [#222 — P3 — Make the Android Back menu discoverable without persistent HUD](https://github.com/iNeonyne/LastHavenPlus/issues/222) — enhancement, P3

#### Unprioritized

- [#6 — P2 — Essential launcher settings and safe storage controls](https://github.com/iNeonyne/LastHavenPlus/issues/6) — enhancement
- [#7 — P3 — Measure runtime performance and evaluate APK size improvements](https://github.com/iNeonyne/LastHavenPlus/issues/7) — enhancement
- [#11 — P2 — Privacy-conscious app and updater log tests](https://github.com/iNeonyne/LastHavenPlus/issues/11) — enhancement
- [#12 — P2 — Actionable GeckoView load-error details](https://github.com/iNeonyne/LastHavenPlus/issues/12) — bug
- [#19 — P1 — Add an opt-in beta release channel to the updater](https://github.com/iNeonyne/LastHavenPlus/issues/19) — enhancement
- [#23 — P0 — Complete Addon Manager ZIP 0.5.1 Phase B for GeckoView](https://github.com/iNeonyne/LastHavenPlus/issues/23) — enhancement
- [#31 — P0 — Fix draft release asset verification and verify differential fallback](https://github.com/iNeonyne/LastHavenPlus/issues/31) — bug
- [#67 — Repository: reserve original project rights and complete third-party notices](https://github.com/iNeonyne/LastHavenPlus/issues/67)

### Open pull requests

- [#250 — ui: start landscape launcher redesign and POCO smoke coverage (#248)](https://github.com/iNeonyne/LastHavenPlus/pull/250) (beta <- chaty/native-landscape-ui-248)
- [#255 — docs(addons): document the addon security trust model (#233)](https://github.com/iNeonyne/LastHavenPlus/pull/255) (beta <- vibe/addon-trust-docs-233)
- [#257 — Show unavailable game storage when no origin estimate is accessible](https://github.com/iNeonyne/LastHavenPlus/pull/257) (beta <- coderabbit/game-origin-storage-28)

### Recently completed (last 30 days)

- [#245 — P0 — Diagnose and implement actual enabled-addon script injection](https://github.com/iNeonyne/LastHavenPlus/issues/245)
- [#244 — P1 — Make the game reload action reliably navigate and confirm completion](https://github.com/iNeonyne/LastHavenPlus/issues/244)
- [#229 — P1 — Cache page-side addon bundle clones by revision](https://github.com/iNeonyne/LastHavenPlus/issues/229)
- [#228 — P1 — Reconcile addon import and runtime payload size limits](https://github.com/iNeonyne/LastHavenPlus/issues/228)
- [#227 — P1 — Serialize addon library access and transaction recovery](https://github.com/iNeonyne/LastHavenPlus/issues/227)
- [#219 — P2 — Redact URL credentials and standalone bearer tokens from diagnostics](https://github.com/iNeonyne/LastHavenPlus/issues/219)
- [#218 — P2 — Ignore callbacks from replaced GeckoSessions](https://github.com/iNeonyne/LastHavenPlus/issues/218)
- [#208 — Make GitHub the only live task queue and retire stale status snapshots](https://github.com/iNeonyne/LastHavenPlus/issues/208)
- [#205 — Consolidate agent documentation and remove workflow duplication](https://github.com/iNeonyne/LastHavenPlus/issues/205)
- [#188 — P2 — Audit and update stable Android dependencies](https://github.com/iNeonyne/LastHavenPlus/issues/188)
- [#186 — P2 — Confirm GeckoView top-level navigation policy for the launcher](https://github.com/iNeonyne/LastHavenPlus/issues/186)
- [#185 — P2 — Reconcile ownership-label automation with the project ownership contract](https://github.com/iNeonyne/LastHavenPlus/issues/185)
- [#184 — P2 — Move diagnostics log I/O off the Compose main thread](https://github.com/iNeonyne/LastHavenPlus/issues/184)
- [#183 — P2 — Disconnect stale addon bridge ports when GeckoSession changes](https://github.com/iNeonyne/LastHavenPlus/issues/183)
- [#182 — P2 — Close BSDIFF section streams when later initialization fails](https://github.com/iNeonyne/LastHavenPlus/issues/182)
- [#181 — P2 — Remove obsolete verified updater APKs safely](https://github.com/iNeonyne/LastHavenPlus/issues/181)
- [#178 — Documentation maintenance — reconcile project records and add a recurring checklist](https://github.com/iNeonyne/LastHavenPlus/issues/178)
- [#170 — P1 — Integrate addon manager navigation and verify same-session runtime behavior](https://github.com/iNeonyne/LastHavenPlus/issues/170)
- [#169 — P0 — Harden Worker interception and blocks.idx read masking](https://github.com/iNeonyne/LastHavenPlus/issues/169)
- [#168 — P0 — Correct and bound the addon XHR resource overlay](https://github.com/iNeonyne/LastHavenPlus/issues/168)
- [#167 — P0 — Verify ZIP 0.5.1 bridge runtime on pinned GeckoView Stable](https://github.com/iNeonyne/LastHavenPlus/issues/167)
- [#150 — P2 — Document GeckoView content and Android permission policy](https://github.com/iNeonyne/LastHavenPlus/issues/150)
- [#149 — P2 — Move launcher-owned user-facing strings into Android resources](https://github.com/iNeonyne/LastHavenPlus/issues/149)
- [#148 — P2 — Centralize app-wide landscape and immersive window state](https://github.com/iNeonyne/LastHavenPlus/issues/148)
- [#147 — P1 — Consolidate launcher navigation and back-stack state](https://github.com/iNeonyne/LastHavenPlus/issues/147)
- [#146 — P1 — Extract GeckoView game-session ownership from MainActivity](https://github.com/iNeonyne/LastHavenPlus/issues/146)
- [#142 — Chaty plan — continuous Vibe work queue and v2.5.0 integration hold](https://github.com/iNeonyne/LastHavenPlus/issues/142)
- [#133 — v2.4.0 — Redesign the adaptive launcher app icon](https://github.com/iNeonyne/LastHavenPlus/issues/133)
- [#129 — CI optimization: consolidate beta post-merge quality checks](https://github.com/iNeonyne/LastHavenPlus/issues/129)
- [#128 — Post-v2.3.0 repository housekeeping and documentation reconciliation](https://github.com/iNeonyne/LastHavenPlus/issues/128)
- [#111 — P2 — Improve updater download progress and retry feedback](https://github.com/iNeonyne/LastHavenPlus/issues/111)
- [#107 — P3 — Establish consistent visual design and interaction polish](https://github.com/iNeonyne/LastHavenPlus/issues/107)
- [#106 — P2 — Improve gameplay loading, reconnect and load-failure UX](https://github.com/iNeonyne/LastHavenPlus/issues/106)
- [#86 — v2.4.0 — Deferred settings, diagnostics, audit and supporting-feature backlog](https://github.com/iNeonyne/LastHavenPlus/issues/86)
- [#84 — [COMPLETE] Use owner-approved recreated LastHavens-inspired launcher artwork](https://github.com/iNeonyne/LastHavenPlus/issues/84)
- [#81 — Dependencies: refresh compatible AndroidX and compression libraries](https://github.com/iNeonyne/LastHavenPlus/issues/81)
- [#70 — Docs: reconcile contributor/security guidance with beta workflow](https://github.com/iNeonyne/LastHavenPlus/issues/70)
- [#66 — Release engineering: verify independent clean APK rebuilds](https://github.com/iNeonyne/LastHavenPlus/issues/66)
- [#65 — Testing: add a minimal ARM64 Android emulator smoke test](https://github.com/iNeonyne/LastHavenPlus/issues/65)
- [#64 — Build: centralize dependency and plugin versions in a Gradle version catalog](https://github.com/iNeonyne/LastHavenPlus/issues/64)
- [#63 — CI security: pin third-party Actions and minimize workflow permissions](https://github.com/iNeonyne/LastHavenPlus/issues/63)
- [#62 — Repository: establish a low-noise branch lifecycle and audit stale branches](https://github.com/iNeonyne/LastHavenPlus/issues/62)
- [#61 — CI: split and simplify the Android release workflow](https://github.com/iNeonyne/LastHavenPlus/issues/61)
- [#60 — Done — Android lint and Kotlin formatting quality gate](https://github.com/iNeonyne/LastHavenPlus/issues/60)
- [#56 — [COMPLETE] P1 — Direct launch and responsive in-game overlay](https://github.com/iNeonyne/LastHavenPlus/issues/56)
- [#42 — Superseded — v2.1.0 Home-screen UI verification](https://github.com/iNeonyne/LastHavenPlus/issues/42)
- [#29 — P1 — Reconcile beta documentation with current source and CI evidence](https://github.com/iNeonyne/LastHavenPlus/issues/29)
- [#22 — P1 — Add the LastHavenPlus home screen with Play and Settings](https://github.com/iNeonyne/LastHavenPlus/issues/22)
- [#21 — Owner feedback — GeckoView runtime and profile behavior after release](https://github.com/iNeonyne/LastHavenPlus/issues/21)
- [#20 — P1 — Complete the GeckoView 160 artifact and dependency audit](https://github.com/iNeonyne/LastHavenPlus/issues/20)
- [#18 — Roadmap — v2.4.0–v2.6.0 UI/UX, updater and settings](https://github.com/iNeonyne/LastHavenPlus/issues/18)
- [#13 — P2 — Add accessible names to launcher actions](https://github.com/iNeonyne/LastHavenPlus/issues/13)
- [#10 — P2 — Persistent app-wide network usage totals](https://github.com/iNeonyne/LastHavenPlus/issues/10)
- [#9 — Verify accessibility and navigation for the direct-launch overlay](https://github.com/iNeonyne/LastHavenPlus/issues/9)
- [#8 — Superseded — Legacy addon manager removed; future scope waits for owner spec (#23)](https://github.com/iNeonyne/LastHavenPlus/issues/8)
- [#5 — Owner feedback — LastHaven gameplay and update behavior after release](https://github.com/iNeonyne/LastHavenPlus/issues/5)

<!-- ROADMAP-LIVE-END -->
