import urllib.request
import urllib.parse
import json

hosts = [
    "http://172.16.50.12",
    "http://172.16.50.14",
    "http://172.16.50.7"
]

paths = [
    "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/",
    "/DHAKA-FLIX-12/Anime%20%26%20Cartoon%20TV%20Series/",
    "/DHAKA-FLIX-14/Anime%20%26%20Cartoon%20TV%20Series/",
    "/DHAKA-FLIX-7/Anime%20%26%20Cartoon%20TV%20Series/",
    "/Anime%20%26%20Cartoon%20TV%20Series/"
]

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

for host in hosts:
    for path in paths:
        data = {
            "action": "get",
            "items[href]": path,
            "items[what]": "1"
        }
        encoded_data = urllib.parse.urlencode(data).encode("utf-8")
        req = urllib.request.Request(f"{host}{path}?", data=encoded_data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=2) as response:
                if response.status == 200:
                    res_body = response.read().decode("utf-8")
                    parsed = json.loads(res_body)
                    items = parsed.get("items", [])
                    print(f"FOUND: {host}{path} works! {len(items)} items found.")
        except Exception as e:
            pass
