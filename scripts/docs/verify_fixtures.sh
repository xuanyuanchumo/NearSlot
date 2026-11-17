#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
FX=NearSlot/tests/fixtures/docs_negative
for f in c1_missing_frontmatter c2_bad_link c3_unreachable c4_bad_source; do
  # temporarily hide other fixtures so only one is checked
  mkdir -p /tmp/ns-hold
  mv $FX/*.md /tmp/ns-hold/ 2>/dev/null
  cp /tmp/ns-hold/README.md $FX/
  cp /tmp/ns-hold/$f.md $FX/
  echo "--- $f ---"
  python NearSlot/scripts/docs/check_docs.py $FX --repo-root . 2>&1 | grep -E "C[1-6]|通过|失败" | head -6
  cp /tmp/ns-hold/*.md $FX/
done