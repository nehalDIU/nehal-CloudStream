import urllib.request
import urllib.parse
import json

host = "http://172.16.50.7"
paths = [
    "/DHAKA-FLIX-7/3D%20Movies/",
    "/DHAKA-FLIX-7/Foreign%20Language%20Movies/",
    "/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"
]
headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

for path in paths:
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
            print(f"Path '{path}' has {len(items)} items:")
            subfolders = set()
            for item in items:
                href = item.get("href")
                if href and href != path and href.startswith(path):
                    subfolders.add(href)
            for sf in sorted(subfolders)[:10]:
                print(f"  - {sf}")
            if len(subfolders) > 10:
                print(f"  ... and {len(subfolders) - 10} more")
    except Exception as e:
        print(f"Path '{path}' failed: {e}")
