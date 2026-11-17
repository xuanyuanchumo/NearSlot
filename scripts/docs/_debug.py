import subprocess, sys, tempfile
from pathlib import Path
HERE = Path("NearSlot/scripts/docs").resolve()
REPO = Path(".").resolve()
with tempfile.TemporaryDirectory() as td:
    t = Path(td)
    (t / "README.md").write_text("---\ntitle: R\nstatus: current\nsource_of_truth: [README.md]\nupdated: 2026-11-16\n---\n\n# R\n", encoding="utf-8")
    r = subprocess.run([sys.executable, str(HERE / "check_docs.py"), str(t), "--repo-root", str(REPO)], capture_output=True, text=True, encoding="utf-8")
    print("RC", r.returncode)
    print("OUT", r.stdout[:1500])
    print("ERR", r.stderr[:800])
