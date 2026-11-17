import concurrent.futures, time, statistics, socket

HOST, PORT = "localhost", 8081
PATH = "/api/v1/system/ping"
REQ = (f"GET {PATH} HTTP/1.1\r\nHost: {HOST}\r\nConnection: close\r\n\r\n").encode()

def one(_):
    t0 = time.perf_counter()
    try:
        with socket.create_connection((HOST, PORT), timeout=10) as s:
            s.sendall(REQ)
            data = b""
            while True:
                c = s.recv(4096)
                if not c: break
                data += c
        # 响应行形如 b"HTTP/1.1 200 \r\n"，用 startswith 前缀判定
        ok = data.startswith(b"HTTP/1.1 200") or data.startswith(b"HTTP/1.0 200")
        return (time.perf_counter() - t0) * 1000, ok
    except Exception:
        return (time.perf_counter() - t0) * 1000, False

def bench(n, conc):
    start = time.perf_counter()
    lat, ok = [], 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=conc) as ex:
        for ms, good in ex.map(one, range(n)):
            lat.append(ms)
            if good: ok += 1
    elapsed = time.perf_counter() - start
    lat.sort()
    tps = ok / elapsed
    p50 = lat[int(len(lat)*0.5)]
    p99 = lat[min(int(len(lat)*0.99), len(lat)-1)]
    print(f"并发{conc:3d}: n={n} 成功={ok}/{n} TPS={tps:6.0f} P50={p50:6.2f}ms P95={lat[int(len(lat)*0.95)]:6.2f}ms P99={p99:6.2f}ms 耗时={elapsed:.2f}s")
    return tps

if __name__ == "__main__":
    print("=== 空接口 TPS 压测（SLO: >=5000）===\n")
    results = []
    for conc in (20, 50, 100, 200):
        results.append(bench(3000, conc))
    best = max(results)
    print(f"\n峰值 TPS = {best:.0f}  (SLO 目标 5000) -> {'达标' if best >= 5000 else '未达标'}")
