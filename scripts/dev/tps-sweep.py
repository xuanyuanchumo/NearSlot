import concurrent.futures, time, socket
HOST, PORT = "localhost", 8081
PATH = "/api/v1/system/ping"
REQ = (f"GET {PATH} HTTP/1.1\r\nHost: {HOST}\r\nConnection: close\r\n\r\n").encode()

def batch(conc, n):
    def one(_):
        try:
            with socket.create_connection((HOST, PORT), timeout=15) as s:
                s.sendall(REQ)
                d = b""
                while True:
                    c = s.recv(8192)
                    if not c: break
                    d += c
            return d.startswith(b"HTTP/1.1 200")
        except Exception:
            return False
    t0 = time.perf_counter()
    ok = sum(1 for r in concurrent.futures.ThreadPoolExecutor(max_workers=conc).map(one, range(n)) if r)
    return ok / (time.perf_counter() - t0)

# 预热
batch(20, 500)
print("=== 高并发测试（每档 8000 请求）===\n")
best = 0
for conc in (100, 200, 300, 500):
    tps = batch(conc, 8000)
    best = max(best, tps)
    print(f"并发{conc:3d}: TPS={tps:6.0f}")
print(f"\n峰值 TPS = {best:.0f}  (SLO 5000) -> {'达标' if best>=5000 else '未达标'}")
