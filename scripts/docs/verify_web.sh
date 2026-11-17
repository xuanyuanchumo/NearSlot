#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying/NearSlot/nearslot-web" || exit 9
echo "=== vp build ==="
./node_modules/.bin/vp build > /tmp/b.log 2>&1; echo "BUILD_EXIT=$?"
[ -f dist/index.html ] && echo "dist OK"
echo "=== toolchain ==="
./node_modules/.bin/vp toolchain 2>&1 | head -20
