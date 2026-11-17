#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""NearSlot 文档确定性检查器（G0 治理门禁六检）。

规则（源：docs/standards/doc.md §3 + research/10 §5 + 继承 PickNear check_docs.py）：
  C1 frontmatter 必填：title/status(draft|current|legacy)/source_of_truth/updated；legacy 需 superseded_by。
  C2 链接可达：正文相对链接目标存在（http/mailto/tel/锚点除外）。
  C3 current 可达：每份 current 文档可从最近的目录 README.md 到达；legacy 不得被 README 链接。
  C4 source_of_truth 存在：路径以仓库根为基准解析（REPO_ROOT / source）。
  C5 source_of_truth 被 Git 跟踪（工作区未纳入 Git 时自动降级为警告，不算失败）。
  C6 superseded_by 路径存在。

判据：C1-C4/C6 任一失败 -> 非零退出码；C5 在无 Git 仓库时跳过。
用法：python check_docs.py <docs根目录> [--repo-root <仓库根>] [--skip-c3]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ALLOWED = {"draft", "current", "legacy"}
LINK_RE = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
IGNORED = ("http://", "https://", "mailto:", "tel:")


def read_fm(path: Path):
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    data: dict = {}
    active = None
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if line.startswith("  - "):
            if active is not None:
                vals = data.setdefault(active, [])
                if isinstance(vals, list):
                    vals.append(line[4:].strip().strip("\"'"))
            continue
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        key = key.strip()
        value = raw.strip().strip("\"'")
        active = None
        if value:
            if value.startswith("[") and value.endswith("]"):
                inner = value[1:-1].strip()
                data[key] = [v.strip().strip("\"'") for v in inner.split(",") if v.strip()] if inner else []
            else:
                data[key] = value
        else:
            data[key] = []
            active = key
    return data


def as_paths(v) -> list:
    if isinstance(v, list):
        return [str(i) for i in v if str(i).strip()]
    if isinstance(v, str) and v.strip():
        return [v.strip()]
    return []


def rel_of(path: Path, root: Path):
    try:
        return path.relative_to(root)
    except ValueError:
        return path


def link_targets(path: Path) -> set:
    out = set()
    for raw in LINK_RE.findall(path.read_text(encoding="utf-8")):
        link = raw.strip()
        if link.startswith("<") and link.endswith(">"):
            link = link[1:-1]
        link = unquote(link).split("#", 1)[0].strip()
        if not link or link.startswith(IGNORED):
            continue
        out.add((path.parent / link).resolve())
    return out


def module_readme(path: Path, docs_root: Path):
    for parent in (path.parent, *path.parent.parents):
        if parent == docs_root.parent:
            break
        cand = parent / "README.md"
        if cand.exists() and cand.resolve() != path.resolve():
            return cand
    return None


def git_tracked(path: Path, repo_root: Path):
    rel = path.relative_to(repo_root).as_posix()
    r = subprocess.run(["git", "ls-files", "--error-unmatch", "--", rel],
                       cwd=repo_root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode == 0


def main():
    ap = argparse.ArgumentParser(description="NearSlot 文档六检")
    ap.add_argument("docs_root", type=Path)
    ap.add_argument("--repo-root", type=Path, default=None)
    ap.add_argument("--skip-c3", action="store_true")
    args = ap.parse_args()
    docs_root = args.docs_root.resolve()
    repo_root = (args.repo_root or docs_root.parent).resolve()
    has_git = (repo_root / ".git").exists()

    md_files = sorted(docs_root.rglob("*.md"))
    errors: list = []
    current_docs: list = []
    legacy_docs: list = []
    indexes = sorted(docs_root.rglob("README.md"))

    for path in md_files:
        fm = read_fm(path)
        try:
            rel = path.relative_to(repo_root)
        except ValueError:
            rel = path
        if fm is None:
            if path.name != "README.md":
                errors.append(f"{rel}: C1 缺 frontmatter")
            continue
        status = str(fm.get("status", "")).strip()
        if status not in ALLOWED:
            errors.append(f"{rel}: C1 status 非法 {status}")
        elif status == "current":
            current_docs.append(path)
        elif status == "legacy":
            legacy_docs.append(path)
            if not as_paths(fm.get("superseded_by")):
                errors.append(f"{rel}: C1 legacy 缺 superseded_by")
        for field in ("title", "source_of_truth", "updated"):
            if not fm.get(field):
                errors.append(f"{rel}: C1 缺字段 {field}")
        # NearSlot 约定：source_of_truth / superseded_by 以「本文件所在目录」为基准解析
        for src in as_paths(fm.get("source_of_truth")):
            sp = (path.parent / src).resolve()
            if not sp.exists():
                errors.append(f"{rel}: C4 事实源不存在 {src}")
            elif has_git and not git_tracked(sp, repo_root):
                errors.append(f"{rel}: C5 事实源未被 Git 跟踪 {src}")
        for rep in as_paths(fm.get("superseded_by")):
            if not (path.parent / rep).resolve().exists():
                errors.append(f"{rel}: C6 superseded_by 不存在 {rep}")

    for path in sorted(set(indexes + current_docs)):
        for raw in LINK_RE.findall(path.read_text(encoding="utf-8")):
            link = raw.strip().split("#", 1)[0].strip()
            if not link or link.startswith(IGNORED):
                continue
            if not (path.parent / link).resolve().exists():
                errors.append(f"{rel_of(path, repo_root)}: C2 坏链接 {raw}")

    if not args.skip_c3:
        for path in current_docs:
            if path.name == "README.md":
                continue  # 索引文件本身即入口，无需被链接
            readme = module_readme(path, docs_root)
            if readme is None or path.resolve() not in link_targets(readme):
                errors.append(f"{rel_of(path, repo_root)}: C3 current 不可达")
        for path in legacy_docs:
            readme = module_readme(path, docs_root)
            if readme is not None and path.resolve() in link_targets(readme):
                errors.append(f"{rel_of(path, repo_root)}: C3 legacy 被索引链接")

    if errors:
        print(f"文档检查失败：{len(errors)} 项")
        for e in errors[:300]:
            print("  - " + e)
        return 1
    print(f"文档检查通过：{len(md_files)} 文档 / {len(indexes)} 索引 / {len(current_docs)} current / {len(legacy_docs)} legacy")
    return 0


if __name__ == "__main__":
    sys.exit(main())
