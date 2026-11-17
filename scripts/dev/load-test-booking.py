import concurrent.futures, time, json, statistics, urllib.request

BASE = "http://localhost:8081"

def call(method, path, body=None, token=None, timeout=20):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            body_txt = r.read()
            return (time.perf_counter() - t0) * 1000, r.status, body_txt
    except Exception as e:
        return (time.perf_counter() - t0) * 1000, getattr(e, "code", 0), (getattr(e, "read", lambda: b"")() or b"")

def login(phone, pwd):
    body = json.dumps({"phone": phone, "password": pwd}).encode()
    req = urllib.request.Request(BASE + "/api/v1/auth/login", method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, body, timeout=10) as r:
        return json.loads(r.read())["data"]["accessToken"]

def summary(name, lat):
    lat.sort()
    p50 = lat[int(len(lat)*0.5)]
    p95 = lat[int(len(lat)*0.95)]
    p99 = lat[min(int(len(lat)*0.99), len(lat)-1)]
    print(f"{name}: n={len(lat)} P50={p50:.1f} P95={p95:.1f} P99={p99:.1f} 均值={statistics.mean(lat):.1f} 最大={max(lat):.1f} (ms)")
    return p50, p95, p99

if __name__ == "__main__":
    tok = login("13800138000", "pass12345")
    print("=== 抢约链路压测（SLO 目标 P99 <=200ms）===\n")

    # 1) 抢约失败路径（无库存/重复），这是高并发下的真实热点
    def book_attempt(_):
        return call("POST", "/api/v1/appointments", {"slotId": 3001}, tok)

    lats, codes = [], {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
        for ms, st, body in ex.map(book_attempt, range(200)):
            lats.append(ms)
            try:
                c = json.loads(body).get("code", st)
            except Exception:
                c = st
            codes[c] = codes.get(c, 0) + 1
    summary("抢约(200次/并发20)", lats)
    print(f"  返回码分布: {codes}\n")

    # 2) 订单查询（支付后高频读）
    def order_detail(_):
        return call("GET", "/api/v1/appointments", None, tok)

    lats2 = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
        for ms, st, _ in ex.map(order_detail, range(200)):
            lats2.append(ms)
    summary("订单列表(200次/并发20)", lats2)
