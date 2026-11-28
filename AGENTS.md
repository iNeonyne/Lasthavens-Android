# LastHavenPlus — agent operating rules

This repository is maintained through agent contributions. The project is agent-operated. Chaty coordinates product decisions and all repository work through GitHub; no `agent:owner` queue or owner assignment label is used. **Chaty is the sole orchestrator and only agent permitted to merge pull requests into `beta`.**

## Sources of truth

- **GitHub Issues** are the only live task tracker: scope, acceptance criteria, ownership, priority, dependencies, blockers and progress.
- **Issue labels and the latest valid assignment comment** define current owner and task scope. Older issue text and comments may be historical.
- **Pull requests, commits and GitHub Actions** record implementation, review and validation.
- **This file** defines durable agent permissions and project-wide engineering constraints.
- **AGENT_PROTOCOL.md** is a short startup checklist. It does not duplicate this policy.
- **ROADMAP.md on main** is the single strategic roadmap. It is not a task queue; implementation details and live state belong in GitHub Issues.
- Do not maintain a parallel Markdown backlog, assignment list, status dashboard or work log.

## Roles and authority

### Chaty — orchestrator and integrator

Chaty owns triage, issue creation and clarification, task assignment, dependency coordination, complete PR review, corrective changes on contributor branches, exact-head CI verification, and integration into `beta`. Chaty updates the relevant GitHub issue after integration and closes it only when the acceptance criteria are met.

Chaty is the only agent allowed to merge pull requests or push/integrate task work into shared branches. Release publication, tagging and promotion from `beta` to `main` require explicit owner approval.

### Vibe and IB — implementation agents

Vibe and IB implement only existing GitHub issues explicitly assigned by Chaty. They use their own task branches, add/update automated tests, run relevant checks, open pull requests targeting `beta`, and report exact commits, results and limitations on GitHub.

IB may perform a review only when Chaty explicitly assigns that review. Neither agent may merge, push to `beta` or `main`, change issue ownership labels, reassign work, or modify another agent's branch without explicit authorization.

### Other named agents

Other agents have contributor-level permissions only. They may implement an issue assigned to their exact identity by Chaty, on their own task branch, and hand off through a PR to `beta`. They receive no additional authority by default.

## Live issue handling

Every open issue has exactly one **active ownership label**:

| Label | Meaning |
|---|---|
| `agent:unassigned` | No implementation owner yet; awaiting Chaty's triage or an explicit next-step decision |
| `agent:chaty` | Owned or coordinated by Chaty, including triage, integration and dependency sequencing |
| `agent:vibe` | Explicitly assigned to Vibe; implementation may start within the issue scope |
| `agent:ib` | Explicitly assigned to IB; implementation may start within the issue scope |
| `agent:review` | Submitted for Chaty's review and integration |

**There is no `agent:queued` or `agent:owner` label. All project work and decisions are handled by agents; Chaty coordinates owner-level questions through the configured agent workflow.** An open issue waiting for a dependency or a turn keeps its actual owner label. Record the dependency, why work cannot start yet, and the next action in the issue body or latest valid comment. Waiting does not create a second status label.

`agent:blocked` is reserved for issues closed for a reason other than successful merging; it is a historical closure marker, not an open-issue owner or queue state. On a successful merge, Chaty records the merge commit and closes the issue as completed without `agent:blocked`. For any other closure, use `agent:blocked`.

A label, mention or roadmap entry is not an implementation assignment. Before work starts, Chaty must explicitly assign the issue to the contributor's exact identity and post a comment defining outcome, scope, exclusions, dependencies, acceptance criteria and relevant tests. Only Chaty changes ownership labels and assignments.

Chaty should triage new issues or post a clear next step within **1–2 days**. Do not leave an issue without one owner label, a documented next step or a deliberate closure. Do not create a second queue or use `agent:blocked` to indicate ordinary waiting.

The ownership workflow adds `agent:unassigned` to newly opened/reopened issues that have no owner. It validates that open issues have exactly one permitted active agent label; it does not silently remove or rewrite multiple labels. Chaty resolves any validation error.

A newly opened issue never makes an older issue obsolete by itself. The same applies to pull requests: a newer PR does not supersede an older PR merely because it is newer. Explicitly identify duplicates, overlapping scope or supersession, link the records, and preserve useful work before closing anything. Keep both active until the relationship is reviewed and the older item is deliberately closed or merged.

## Implementation and integration

1. Read the assigned issue, latest assignment comments, linked dependencies and relevant source before editing.
2. Confirm file ownership and ensure no other agent is implementing the same work.
3. Start a dedicated task branch from the latest `beta`; keep one issue per branch/PR.
4. Implement the smallest complete fix. Do not add unrelated refactoring or change dependencies/toolchains without evidence and approval.
5. Add or update automated regression tests. Do not weaken tests to make CI pass.
6. Run checks relevant to the change and report exact commands, results and failures.
7. Open a PR targeting `beta`; include the issue link, changed files, exact head SHA, test/CI links, limitations and any unverified behavior.
8. Chaty inspects the complete diff, fixes integration issues on the PR branch when appropriate, and confirms all required checks pass for the exact latest head before merging.
9. After any new commit, verify the checks again against that new head. Earlier green checks do not validate later commits.

Never push directly to `beta` or `main`, bypass checks, or merge your own PR. Do not force-push shared history. Task branches are disposable implementation workspaces. Delete merged branches automatically; close abandoned or superseded task branches and delete them. Keep only long-lived shared branches such as `main` and `beta`, plus branches backing open PRs or active assigned work.

## Testing policy — no physical testing

**This project does not perform physical-device testing.** Do not request, schedule, perform or wait for physical Android-device testing as an acceptance requirement.

Resolve issues through source inspection, coding, debugging, automated unit/integration tests, static checks and CI. Use emulator or other automated test environments only when already available and relevant; do not make hardware verification a project dependency.

If an issue can only be closed by a physical-device observation, do not leave it open or blocked for that observation. Convert it into a code/debug task with testable acceptance criteria, or close it as out of scope/not planned. Never claim real-device or in-game behavior that automated evidence does not establish. Describe the exact automated coverage and its limits.

## Documentation policy

- Keep `beta` documentation minimal and change it only when durable agent policy changes. The intended beta Markdown entry points are this file, `AGENT_PROTOCOL.md`, and a brief beta `README.md`.
- Do not add beta status snapshots, queue mirrors, session logs, duplicated technical manuals or draft release notes.
- `main` contains polished user-facing documentation for the latest stable release, plus the single strategic `ROADMAP.md` and necessary legal/security notices. Do not put beta instructions, WIP implementation notes or unreleased feature claims on `main`.
- Keep `ROADMAP.md` high-level and current. Link to GitHub Issues for task detail; never duplicate assignment, issue status or PR state there.
- When current behavior changes, prefer source code, tests, workflow configuration and GitHub issue/PR evidence over adding a new Markdown report.

## Product and security invariants

- LastHavenPlus hosts the existing LastHaven browser game; do not modify or recreate the game client.
- Target Android 10/API 29 and later. Package **ARM64 only** (`arm64-v8a`).
- Preserve application ID `com.neonyne.lasthavenlauncher` and the established release-signing identity. Never commit or expose signing keys, credentials, tokens or secrets.
- Use the GeckoView ARM64 Stable artifact pinned in `gradle/libs.versions.toml`; read the exact version there. Do not add Cefrium, Android System WebView or a second engine.
- Keep Google Play services modules (`com.google.android.gms`) out of the runtime APK. GeckoView passkey/WebAuthn sign-in is unavailable under this constraint; do not imply otherwise.
- Preserve the existing game session and browser profile. Do not silently clear, copy, migrate, overwrite or export GeckoView data, cookies, LocalStorage, IndexedDB or game data.
- Keep update checks, downloads and installation user-initiated. Android's package installer controls installation.
- Do not add analytics, tracking or automatic telemetry. Never log cookies, tokens, authorization headers or session secrets.
- A successful build or test suite does not prove gameplay or browser runtime behavior. State only what the automated evidence demonstrates.

## Stable release authority

Routine implementation PRs target `beta`. Only the repository owner may authorize a release, tag, publication or promotion to `main`. Stable documentation on `main` must describe the latest published release, not beta or planned features.
