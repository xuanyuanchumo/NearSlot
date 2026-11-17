import concurrent.futures, time, json, statistics, urllib.request, sys

BASE = "http://localhost:8081"

def call(method, path, body=None, token=None, timeout=10):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            r.read()
            return (time.perf_counter() - t0) * 1000, r.status
    except Exception as e:
        return (time.perf_counter() - t0) * 1000, getattr(e, "code", 0)

def login():
    body = json.dumps({"phone": "13800138000", "password": "pass12345"}).encode()
    req = urllib.request.Request(BASE + "/api/v1/auth/login", method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, body, timeout=10) as r:
        return json.loads(r.read())["data"]["accessToken"]

def run(name, fn, n, concurrency):
    lat, ok = [], 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        for ms, st in ex.map(lambda _: fn(), range(n)):
            lat.append(ms)
            if st == 200: ok += 1
    lat.sort()
    p50 = lat[int(len(lat)*0.5)]
    p99 = lat[min(int(len(lat)*0.99), len(lat)-1)]
    print(f"{name}: n={n} 并发={concurrency} 成功={ok} P50={p50:.1f}ms P99={p99:.1f}ms 均值={statistics.mean(lat):.1f}ms")
    return p50, p99

if __name__ == "__main__":
    tok = login()
    print("登录成功，开始压测\n")
    run("服务商列表 /shops", lambda: call("GET", "/api/v1/shops", None, tok), 300, 20)
    run("时段查询 /slots", lambda: call("GET", "/api/v1/slots?shopId=1", None, tok), 300, 20)
    run("订单列表 /appointments", lambda: call("GET", "/api/v1/appointments", None, tok), 300, 20)
    run("技师榜 /tech-rank", lambda: call("GET", "/api/v1/tech-rank/1", None, tok), 200, 20)
