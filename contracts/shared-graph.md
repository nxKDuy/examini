# Shared Graphify

Graphify is a local architecture aid, not an API contract. `contracts/**` remains authoritative.

## Read from any worktree

Browser: http://127.0.0.1:8765/ — select MAIN, Question Bank or Exam Taking. Each view indexes the whole system at that worktree, so unmerged branch changes remain distinguishable.

```sh
SHARED_GIT=$(git rev-parse --path-format=absolute --git-common-dir)
cat "$SHARED_GIT/graphify/out/main/GRAPH_REPORT.md"
"$SHARED_GIT/graphify/runtime/bin/python" scripts/shared_graph.py path
"$SHARED_GIT/graphify/runtime/bin/python" scripts/shared_graph.py query Question
```

Outputs live in the common Git directory: `graphify/out/<branch with / replaced by -->/{graph.html,graph.json,GRAPH_REPORT.md,snapshot.json}`. All linked worktrees read the same files. Generated graphs and Python runtime are not committed.

## Refresh

MAIN/coordinator runs from MAIN:

```sh
.git/graphify/runtime/bin/python scripts/shared_graph.py build
```

Refresh after relevant domain commits or shared changes. This is an explicit snapshot, not a live watcher. `snapshot.json` records branch, HEAD, dirty state and build time; dirty snapshots include tracked working-copy edits. Untracked business files are excluded until added to Git. Workers request refresh from MAIN rather than concurrently writing outputs. A process lock prevents overlapping builds.

Current extraction uses Graphify AST for Java/TypeScript/JavaScript/Python plus Markdown document inventory. It does not claim semantic extraction of docs, SQL, HTTP calls or business invariants. Verify relationships against source. No LLM/API call is required to refresh. Graphify preserves confidence tags for extracted and inferred relationships.

## Local installation and service

Configured on this machine with Graphify `0.9.76` and Python `3.12`, isolated under the shared Git directory. Dependencies are recorded in `graphify/requirements.lock`; runtime uses Graphify's native community clustering fallback. The loopback HTTP service is managed by macOS LaunchAgent `local.examini.graphify`; it starts at login and restarts on failure. Serve only `graphify/out`, never the repository or common Git directory. No auth tokens or `.env` contents are indexed.

To recreate on another machine: create a Python >=3.10 venv at the common Git directory's `graphify/runtime`, install `graphifyy==0.9.76`, run the build from MAIN, then serve the output directory on loopback. Worktree paths are discovered from Git; no fixed business-worktree paths are required.
