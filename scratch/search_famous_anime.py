import urllib.request
import urllib.parse
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

hosts = [
    "http://172.16.50.12",
    "http://172.16.50.14",
    "http://172.16.50.7",
    "http://172.16.50.8"
]

roots = {
    "http://172.16.50.12": ["/DHAKA-FLIX-12/"],
    "http://172.16.50.14": ["/DHAKA-FLIX-14/"],
    "http://172.16.50.7": ["/DHAKA-FLIX-7/"],
    "http://172.16.50.8": ["/DHAKA-FLIX-8/"]
}

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

anime_names = [
    "naruto", "one piece", "dragon ball", "demon slayer", "jujutsu", "attack on", "death note",
    "my hero", "pokemon", "bleach", "hunter", "fairy tail", "ben 10", "avatar", "rick and morty",
    "simpsons", "spongebob", "adventure time", "gravity falls", "cartoon", "anime", "doraemon", "shin-chan"
]

def fetch_children(host, path):
    data = {
        "action": "get",
        "items[href]": path,
        "items[what]": "1"
    }
    encoded = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(f"{host}{path}?", data=encoded, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            parsed = json.loads(resp.read().decode("utf-8"))
            return parsed.get("items", [])
    except Exception:
        return []

visited = set()
matches = []

def search(host, path, depth=1, max_depth=5):
    if depth > max_depth:
        return
    key = (host, path)
    if key in visited:
        return
    visited.add(key)
    
    items = fetch_children(host, path)
    folders_to_explore = []
    
    for item in items:
        href = item.get("href")
        if not href or href == path:
            continue
            
        decoded_name = urllib.parse.unquote(href).lower()
        if any(anime in decoded_name for anime in anime_names):
            match_str = f"MATCH [{host}]: {urllib.parse.unquote(href)}"
            print(match_str.encode('ascii', 'ignore').decode())
            matches.append((host, href))
            
        if item.get("size") is None and href.endswith("/"):
            folders_to_explore.append(href)
            
    if folders_to_explore:
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(search, host, folder, depth + 1, max_depth) for folder in folders_to_explore]
            for f in as_completed(futures):
                f.result()

def run():
    print("Starting deep search for anime/cartoon keywords...")
    for host in hosts:
        for root in roots[host]:
            search(host, root)
    print(f"Search done. Found {len(matches)} matches.")

if __name__ == "__main__":
    run()
