cd /d/Projects/TraeProjects/Studying/NearSlot
B=http://localhost:8081/api/v1
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
AT_M=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13900139000","password":"pass12345"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 else '')")
H="authorization: Bearer $AT"

echo "== 配置化回归：Token/分页/半径 =="
ck "登录成功(AT 来自配置TTL)" "$([ -n "$AT" ] && echo yes || echo no)" "yes"
ck "分页 size=1000 被钳制为 50" "$(curl -s "$B/shops?page=1&size=1000" | python -c "import sys,json;print(json.load(sys.stdin)['data']['size'])")" "50"
ck "默认分页=10(来自配置)" "$(curl -s "$B/shops" | python -c "import sys,json;print(json.load(sys.stdin)['data']['size'])")" "10"
ck "附近默认半径可读(不传km)" "$(curl -s "$B/shops/nearby?x=120.15&y=30.28" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "附近自定义半径" "$(curl -s "$B/shops/nearby?x=120.15&y=30.28&km=20" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"

echo "== 内容社区回归 =="
ck "分类匿名可读" "$(curl -s "$B/shop-types" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "热门笔记匿名可读" "$(curl -s "$B/blogs/hot" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "签到状态" "$(curl -s "$B/points/sign/status" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
# 语义：同一天重复签到，第二次必须 fresh=false（首次可能 true，故显式签两次）
curl -s -X POST $B/points/sign -H "$H" >/dev/null
ck "签到幂等(第二次)" "$(curl -s -X POST $B/points/sign -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data']['fresh'])")" "False"
ck "关注流可用" "$(curl -s "$B/feed" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
echo "=========="; echo "PASS=$pass FAIL=$fail"
# 评审 E-002：此前只 echo FAIL 不 exit，CI/自动流程里失败也算成功（假绿门禁）
if [ "$fail" -gt 0 ]; then exit 1; fi
exit 0