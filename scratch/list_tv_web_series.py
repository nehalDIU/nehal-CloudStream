import urllib.request
import urllib.parse
import json

host = "http://172.16.50.12"
path = "/DHAKA-FLIX-12/TV-WEB-Series/"
headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

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
        with open("scratch/tv_web_series_out.txt", "w", encoding="utf-8") as f:
            f.write(f"Path '{path}' contains {len(items)} items:\n")
            for item in items:
                href = item.get("href")
                if href != path:
                    f.write(f"  - {href} ({urllib.parse.unquote(href)})\n")
        print("Success! Output written to scratch/tv_web_series_out.txt")
except Exception as e:
    print(f"Failed to query {host}{path}: {e}")
