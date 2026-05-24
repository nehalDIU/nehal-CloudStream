import urllib.request
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'X-Requested-With': 'XMLHttpRequest'
}

endpoints = [
    "https://aniwatch.co.at/ajax/v2/episode/servers?episodeId=6135",
    "https://aniwatch.co.at/ajax/episode/servers?episodeId=6135",
    "https://aniwatch.co.at/ajax/v2/episode/sources?episodeId=6135",
    "https://aniwatch.co.at/ajax/episode/sources?episodeId=6135",
    "https://aniwatch.co.at/ajax/v2/servers?episodeId=6135",
    "https://aniwatch.co.at/ajax/servers?episodeId=6135"
]

for url in endpoints:
    print(f"\nTrying endpoint: {url}")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            content = response.read().decode('utf-8')
            print("  Status:", response.status)
            try:
                parsed = json.loads(content)
                print("  JSON Response:")
                print(json.dumps(parsed, indent=2)[:500])
            except Exception:
                print("  Text Response (first 200 chars):", content[:200])
    except Exception as e:
        print("  Error:", e)
