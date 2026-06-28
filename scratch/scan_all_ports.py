import socket
from concurrent.futures import ThreadPoolExecutor, as_completed

def check_ip_port(ip, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.4)
    result = s.connect_ex((ip, port))
    s.close()
    if result == 0:
        return ip, port
    return None

def scan():
    print("Scanning 172.16.50.1-254 for ports 80, 8080, 8096...")
    targets = []
    for i in range(1, 255):
        for port in [80, 8080, 8096]:
            targets.append((f"172.16.50.{i}", port))
            
    live = []
    with ThreadPoolExecutor(max_workers=100) as executor:
        futures = {executor.submit(check_ip_port, ip, port): (ip, port) for ip, port in targets}
        for future in as_completed(futures):
            res = future.result()
            if res:
                print(f"  Found open port: {res[0]}:{res[1]}")
                live.append(res)
                
    print("\nScan completed. Found:", live)

if __name__ == "__main__":
    scan()
