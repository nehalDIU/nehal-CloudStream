import urllib.request
import urllib.parse
import json

targets = [
    "http://172.16.50.12",
    "http://172.16.50.14",
    "http://172.16.50.4",
    "http://172.16.50.7",
    "http://172.16.50.8"
]

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

for host in targets:
    path = "/"
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
            print(f"\n--- {host}{path} ---")
            for item in items:
                href = item.get("href")
                if href != path:
                    print(f"  {href} ({urllib.parse.unquote(href)})")
    except Exception as e:
        print(f"Failed to query {host}{path}: {e}")
