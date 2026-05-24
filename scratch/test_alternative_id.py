import urllib.request
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
    'X-Requested-With': 'XMLHttpRequest',
    'Referer': 'https://megaplay.buzz/'
}

# Try data-id: 6135
ids_to_try = ["6135", "6136"]

for id_to_try in ids_to_try:
    url = f"https://megaplay.buzz/stream/getSources?id={id_to_try}"
    print(f"\nQuerying: {url}")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            content = response.read().decode('utf-8')
            parsed = json.loads(content)
            print("Response:", json.dumps(parsed, indent=2))
    except Exception as e:
        print("Error:", e)
