import urllib.request
import urllib.parse
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

targets = [
    ("http://172.16.50.12", "/DHAKA-FLIX-12/"),
    ("http://172.16.50.14", "/DHAKA-FLIX-14/"),
    ("http://172.16.50.4", "/DHAKA-FLIX-14/"),
    ("http://172.16.50.7", "/DHAKA-FLIX-7/"),
    ("http://172.16.50.8", "/DHAKA-FLIX-8/")
]

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

def get_subfolders(host, path):
    data = {
        "action": "get",
        "items[href]": path,
        "items[what]": "1"
    }
    encoded_data = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(f"{host}{path}?", data=encoded_data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            res_body = response.read().decode("utf-8")
            parsed = json.loads(res_body)
            items = parsed.get("items", [])
            subfolders = []
            for item in items:
                href = item.get("href")
                if href and href != path and href.startswith(path):
                    # Check if it's a folder (usually has trailing slash or no size)
                    if href.endswith('/'):
                        subfolders.append(href)
            return subfolders
    except Exception:
        return []

visited = set()
matches = []

def search_recursive(host, path, depth=0, max_depth=3):
    if depth > max_depth:
        return
    key = (host, path)
    if key in visited:
        return
    visited.add(key)
    
    # Check if this folder name matches
    decoded_name = urllib.parse.unquote(path).lower()
    if any(kw in decoded_name for kw in ["anime", "cartoon", "animation"]):
        print(f"MATCH: {host}{path}")
        matches.append((host, path))
        
    subfolders = get_subfolders(host, path)
    
    # We can parallelize the subfolder searches
    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(search_recursive, host, sf, depth + 1, max_depth) for sf in subfolders]
        for future in as_completed(futures):
            future.result()

print("Searching recursively for anime, cartoon, animation folders...")
for host, root_path in targets:
    print(f"\nScanning {host}{root_path}...")
    search_recursive(host, root_path)

print("\nScan complete. Total matches found:", len(matches))
