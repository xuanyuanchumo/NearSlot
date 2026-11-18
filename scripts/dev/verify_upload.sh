cd /d/Projects/TraeProjects/Studying/NearSlot
W=$(mktemp -d ./_upltmp.XXXXXX) || W=./_upltmp
mkdir -p "$W"
B=http://localhost:8081/api/v1
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H="authorization: Bearer $AT"
python -c "import sys;open(sys.argv[1],'wb').write(bytes([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A])+b'\x00'*64)" "$W/t.png"
printf "<script>alert(1)</script>" > "$W/fake.png"
printf "x" > "$W/a.jsp"

ck "匿名上传 401" "$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/files -F "file=@$W/t.png")" "401"
UP=$(curl -s -X POST $B/files -H "$H" -F "file=@$W/t.png")
KEY=$(echo "$UP" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['key'] if d.get('code')==200 else '')" 2>/dev/null)
URL=$(echo "$UP" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['url'] if d.get('code')==200 else '')" 2>/dev/null)
ck "上传返回 key（日期分目录）" "$(echo "$KEY" | grep -cE '^[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9]+\.png$')" "1"
ck "url 由配置前缀拼装" "$(echo "$URL" | grep -c "^/api/v1/files/")" "1"
curl -s -D "$W/h.txt" -o "$W/back.png" "http://localhost:8081$URL"
ck "匿名可读回 200" "$(head -1 "$W/h.txt" | grep -c 200)" "1"
ck "读回内容一致" "$(cmp -s "$W/t.png" "$W/back.png" && echo same || echo diff)" "same"
ck "带 immutable 长缓存" "$(grep -ci immutable "$W/h.txt")" "1"
ck "Content-Type=image/png" "$(grep -ci "content-type: image/png" "$W/h.txt")" "1"

echo "== 安全闸 =="
ck "伪装 png 的脚本 40001" "$(curl -s -X POST $B/files -H "$H" -F "file=@$W/fake.png" | python -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)" "40001"
ck ".jsp 扩展名 40001" "$(curl -s -X POST $B/files -H "$H" -F "file=@$W/a.jsp" | python -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)" "40001"
ck "穿越读取 404" "$(curl -s -o /dev/null -w "%{http_code}" "$B/files/../../../../etc/passwd")" "404"
ck "不存在 key 404" "$(curl -s -o /dev/null -w "%{http_code}" "$B/files/2026/01/01/none.png")" "404"
echo "=========="; echo "PASS=$pass FAIL=$fail"
rm -rf "$W"