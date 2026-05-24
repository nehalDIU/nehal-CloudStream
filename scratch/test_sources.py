import urllib.request
import urllib.parse
import json

url = "https://megaplay.buzz/stream/getSources?id=89506"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
    'Accept': '*/*',
    'X-Requested-With': 'XMLHttpRequest',
    'Referer': 'https://megaplay.buzz/'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        content = response.read().decode('utf-8')
        print("Response status:", response.status)
        print("Response body:")
        try:
            parsed = json.loads(content)
            print(json.dumps(parsed, indent=2))
        except Exception:
            print(content)
except Exception as e:
    print("Error:", e)
