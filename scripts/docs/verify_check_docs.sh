#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
echo "=== PY COMPILE ==="
python -m py_compile NearSlot/scripts/docs/check_docs.py || exit 8
echo "=== POSITIVE (real docs) ==="
python NearSlot/scripts/docs/check_docs.py NearSlot/docs --repo-root .
POS=$?
echo "POS_EXIT=$POS"
echo "=== NEGATIVE (4 fixtures, skip C3) ==="
python NearSlot/scripts/docs/check_docs.py NearSlot/tests/fixtures/docs_negative --repo-root . --skip-c3
NEG=$?
echo "NEG_EXIT=$NEG"
if [ "$POS" -eq 0 ] && [ "$NEG" -ne 0 ]; then echo "VERIFY_OK"; else echo "VERIFY_FAIL"; fi
