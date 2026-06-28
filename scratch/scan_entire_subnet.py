import socket
import urllib.request
import urllib.parse
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

def check_ip_port(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.5)
    result = s.connect_ex((ip, 80))
    s.close()
    if result == 0:
        return ip
    return None

def check_dhakaflix_path(ip, path):
    data = {
        "action": "get",
        "items[href]": path,
        "items[what]": "1"
    }
    encoded_data = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(f"http://{ip}{path}?", data=encoded_data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=1.5) as response:
            res_body = response.read().decode("utf-8")
            parsed = json.loads(res_body)
            items = parsed.get("items", [])
            if items:
                return len(items)
    except Exception:
        pass
    return None

def scan():
    print("Step 1: Scanning for open port 80 in 172.16.50.1-254...")
    ips = [f"172.16.50.{i}" for i in range(1, 255)]
    live_ips = []
    
    with ThreadPoolExecutor(max_workers=50) as executor:
        futures = {executor.submit(check_ip_port, ip): ip for ip in ips}
        for future in as_completed(futures):
            res = future.result()
            if res:
                live_ips.append(res)
                print(f"  Found open port 80 on: {res}")
                
    print(f"\nStep 2: Checking paths on {len(live_ips)} live IPs...")
    for ip in sorted(live_ips, key=lambda x: int(x.split('.')[-1])):
        suffix = ip.split('.')[-1]
        paths_to_test = [f"/DHAKA-FLIX-{suffix}/", "/DHAKA-FLIX-9/", "/DHAKA-FLIX-12/", "/DHAKA-FLIX-14/", "/DHAKA-FLIX-7/"]
        found = False
        for path in paths_to_test:
            num = check_dhakaflix_path(ip, path)
            if num is not None:
                print(f"  IP {ip} hosts '{path}' with {num} items")
                found = True
        if not found:
            print(f"  IP {ip} has open port 80 but did not match any tested paths.")

if __name__ == "__main__":
    scan()
