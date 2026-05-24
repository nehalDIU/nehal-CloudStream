import urllib.request
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'X-Requested-With': 'XMLHttpRequest',
    'Referer': 'https://1anime.site/megaplay/stream/s-2/89506/sub'
}

url = "https://1anime.site/megaplay/stream/getSources?id=89506"
req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        content = response.read().decode('utf-8')
        parsed = json.loads(content)
        print("1Anime Direct Response:")
        print(json.dumps(parsed, indent=2))
except Exception as e:
    print("Error:", e)
