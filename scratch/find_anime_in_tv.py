import urllib.request
import urllib.parse
import json

host = "http://172.16.50.12"
groups = [
    "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/",
    "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/",
    "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20M%20%20%E2%80%94%20%20R/",
    "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20S%20%20%E2%80%94%20%20Z/"
]

headers = {
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}

keywords = ["naruto", "attack", "death note", "dragon ball", "demon slayer", "one piece", "jujutsu", "anime", "cartoon"]
matches = []

for path in groups:
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
            for item in items:
                href = item.get("href")
                if href != path:
                    decoded = urllib.parse.unquote(href).lower()
                    if any(kw in decoded for kw in keywords):
                        matches.append(href)
    except Exception as e:
        print(f"Error querying a group: {type(e).__name__}")

with open("scratch/find_anime_out.txt", "w", encoding="utf-8") as f:
    f.write(f"Found {len(matches)} matches:\n")
    for m in matches:
        f.write(f"  - {m} ({urllib.parse.unquote(m)})\n")

print("Finished! Output written to scratch/find_anime_out.txt")
