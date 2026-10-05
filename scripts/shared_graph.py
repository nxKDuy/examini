#!/usr/bin/env python3
"""Shared Graphify snapshots for all Git worktrees (run with the shared runtime)."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import html
import json
import os
from pathlib import Path
import subprocess
import tempfile


def git(*args, cwd=None):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def build(shared):
    from graphify.extract import extract
    from graphify.build import build_from_json
    from graphify.cluster import cluster, score_all
    from graphify.export import to_html, to_json
    from graphify.analyze import god_nodes, surprising_connections
    from graphify.report import generate

    # One coordinator writer; readers can keep using the previous snapshots.
    import fcntl
    lock = (shared / "build.lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    output = shared / "out"
    output.mkdir(exist_ok=True)
    entries = []
    for record in git("worktree", "list", "--porcelain").split("\n\n"):
        fields = dict(line.split(" ", 1) for line in record.splitlines() if " " in line)
        if "branch" not in fields:
            continue
        root = Path(fields["worktree"])
        branch = fields["branch"].removeprefix("refs/heads/")
        key = branch.replace("/", "--")
        commit = git("rev-parse", "HEAD", cwd=root)
        dirty = bool(git("status", "--porcelain", cwd=root))
        files = git("ls-files", "-z", cwd=root).split("\0")
        # Index source only: dependencies, build products and secrets are excluded.
        code = [p for p in files if Path(p).suffix in {".java", ".ts", ".tsx", ".js", ".py"} and (root / p).is_file()]
        docs = [p for p in files if Path(p).suffix == ".md" and (root / p).is_file()]
        dest = output / key
        dest.mkdir(exist_ok=True)
        extraction = extract([root / p for p in code], root=root, cache_root=shared / "cache" / key)
        for p in docs:
            extraction["nodes"].append({"id": "doc:" + p, "label": p, "file_type": "document", "source_file": p})
        graph = build_from_json(extraction, root=root)
        communities = cluster(graph)
        labels = {cid: Counter(str(graph.nodes[n].get("source_file", "external")).replace(str(root) + "/", "").split("/")[0] for n in nodes).most_common(1)[0][0] for cid, nodes in communities.items()}
        stamp = {"branch": branch, "path": str(root), "commit": commit, "dirty": dirty, "built_at": datetime.now(timezone.utc).isoformat(), "code_files": len(code), "documents": len(docs), "nodes": len(graph), "edges": graph.number_of_edges(), "extraction": "AST plus document inventory; no semantic LLM extraction"}
        report = generate(graph, communities, score_all(graph, communities), labels, god_nodes(graph), surprising_connections(graph, communities), {"total_files": len(code) + len(docs), "total_words": 0, "warning": "Structural snapshot only; documents are inventory nodes. Review source/contracts for business semantics."}, {"input": 0, "output": 0}, str(root), built_at_commit=commit)
        with tempfile.TemporaryDirectory(dir=shared) as tmp:
            stage = Path(tmp)
            to_json(graph, communities, str(stage / "graph.json"), force=True, built_at_commit=commit, community_labels=labels)
            to_html(graph, communities, str(stage / "graph.html"), community_labels=labels)
            (stage / "GRAPH_REPORT.md").write_text(report)
            (stage / "snapshot.json").write_text(json.dumps(stamp, indent=2))
            for artifact in stage.iterdir():
                os.replace(artifact, dest / artifact.name)
        entries.append(stamp | {"key": key})
        print(f"{branch}: {len(code)} code files, {len(graph)} nodes, {graph.number_of_edges()} edges")
    (output / "snapshots.json").write_text(json.dumps(entries, indent=2))
    links = "".join(f'<li><a href="{html.escape(e["key"])}/graph.html">{html.escape(e["branch"])}</a> · {e["commit"][:8]} · dirty={e["dirty"]} · <a href="{e["key"]}/GRAPH_REPORT.md">report</a> · <a href="{e["key"]}/graph.json">JSON</a></li>' for e in entries)
    (output / "index.html").write_text(f'<!doctype html><meta charset="utf-8"><title>EXAMINI Graphify</title><h1>EXAMINI shared Graphify</h1><p>Full system snapshots per worktree. AST relationships; document inventory. Refresh via MAIN. See snapshot.json for freshness.</p><ul>{links}</ul>')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "path", "query"])
    parser.add_argument("term", nargs="?", default="")
    args = parser.parse_args()
    common = Path(git("rev-parse", "--git-common-dir")).resolve()
    shared = common / "graphify"
    if args.command == "build":
        build(shared)
    elif args.command == "path":
        print(shared / "out")
    else:
        branch = git("branch", "--show-current").replace("/", "--")
        data = json.loads((shared / "out" / branch / "graph.json").read_text())
        matches = [n for n in data["nodes"] if args.term.lower() in (n.get("label", "") + " " + n.get("source_file", "")).lower()]
        print(json.dumps(matches[:30], indent=2))
