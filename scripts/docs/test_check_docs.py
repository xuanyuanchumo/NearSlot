#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_docs.py 单元测试（G0 V-1 证据）：C1/C2/C4 与端到端退出码。"""
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import check_docs as cd

REPO = HERE.parents[2]

def run(tmp: Path, *extra):
    return subprocess.run(
        [sys.executable, str(HERE / "check_docs.py"), str(tmp), "--repo-root", str(REPO), *extra],
        capture_output=True, text=True, encoding="utf-8")

def write(tmp, name, body):
    p = tmp / name
    p.write_text(body, encoding="utf-8")
    return p

def main():
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        # 基线：合法文档 -> 通过
        write(tmp, "README.md", "---\ntitle: R\nstatus: current\nsource_of_truth: [README.md]\nupdated: 2026-11-16\n---\n\n# R\n")
        assert run(tmp).returncode == 0, "合法文档应通过"
        # C1: 缺 frontmatter
        write(tmp, "c1.md", "# 无 frontmatter\n")
        r = run(tmp, "--skip-c3"); assert r.returncode != 0 and "C1" in r.stdout, "C1 应触发"
        (tmp / "c1.md").unlink()
        # C2: 坏链接
        write(tmp, "c2.md", "---\ntitle: C2\nstatus: current\nsource_of_truth: [c2.md]\nupdated: 2026-11-16\n---\n\n[坏](./nope.md)\n")
        r = run(tmp, "--skip-c3"); assert r.returncode != 0 and "C2" in r.stdout, "C2 应触发"
        (tmp / "c2.md").unlink()
        # C4: 假事实源
        write(tmp, "c4.md", "---\ntitle: C4\nstatus: current\nsource_of_truth: [nope-source.md]\nupdated: 2026-11-16\n---\n\n# C4\n")
        r = run(tmp, "--skip-c3"); assert r.returncode != 0 and "C4" in r.stdout, "C4 应触发"
        (tmp / "c4.md").unlink()
        # C3: current 未被索引链接
        write(tmp, "c3.md", "---\ntitle: C3\nstatus: current\nsource_of_truth: [c3.md]\nupdated: 2026-11-16\n---\n\n# C3\n")
        r = run(tmp); assert r.returncode != 0 and "C3" in r.stdout, "C3 应触发"
    print("check_docs 单元测试通过：C1/C2/C3/C4 四个负向用例均被正确拦截")

if __name__ == "__main__":
    main()
