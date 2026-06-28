import urllib.request
import urllib.parse
import json

targets = [
    "http://172.16.50.4",
    "http://172.16.50.7",
    "http://172.16.50.8",
    "http://172.16.50.12",
    "http://172.16.50.14"
]

paths_to_test = [
    "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/",
    "/DHAKA-FLIX-9/",
    "/Anime%20%26%20Cartoon%20TV%20Series/"
]

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

with open("scratch/find_anime_host_out.txt", "w", encoding="utf-8") as f:
    for host in targets:
        for path in paths_to_test:
            data = {
                "action": "get",
                "items[href]": path,
                "items[what]": "1"
            }
            encoded_data = urllib.parse.urlencode(data).encode("utf-8")
            req = urllib.request.Request(f"{host}{path}?", data=encoded_data, headers=headers, method="POST")
            try:
                with urllib.request.urlopen(req, timeout=5) as response:
                    res_body = response.read().decode("utf-8")
                    parsed = json.loads(res_body)
                    items = parsed.get("items", [])
                    f.write(f"SUCCESS: {host} with path {path} returned {len(items)} items!\n")
                    for item in items[:5]:
                        f.write(f"  - {item.get('href')}\n")
            except Exception as e:
                f.write(f"FAILED: {host} with path {path}: {type(e).__name__}\n")

print("Done! Results written to scratch/find_anime_host_out.txt")
