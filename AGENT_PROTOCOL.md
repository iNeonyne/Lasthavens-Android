# Agent startup protocol

1. Adopt the exact agent identity supplied at startup.
2. Read [AGENTS.md](AGENTS.md) in full.
3. Search live GitHub Issues for your exact active owner label. Chaty also checks `agent:unassigned` issues for triage and next-step decisions. Check current owner labels, dependencies and open PRs targeting `beta`.
4. Read the issue and its latest valid assignment comment, dependencies, relevant PRs and affected source.
5. Start only after Chaty explicitly assigns the issue to your exact identity with clear acceptance criteria.
6. Record progress, blockers, test results and handoff evidence on the issue or PR.

Waiting issues keep their actual agent label; there is no separate queue or owner label. A new issue or PR never automatically supersedes an older one. GitHub is the live task system. Do not use stale chat history or Markdown snapshots as assignment authority. Follow AGENTS.md for all permissions and technical constraints.
