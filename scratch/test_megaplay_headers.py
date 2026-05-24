import urllib.request

url = "https://megaplay.buzz/stream/s-2/89506/sub"

test_cases = [
    {
        "name": "Referer: megaplay.buzz",
        "headers": {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'https://megaplay.buzz/'
        }
    },
    {
        "name": "Referer: 1anime.site",
        "headers": {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'https://1anime.site/'
        }
    },
    {
        "name": "Referer: aniwatch.co.at",
        "headers": {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'https://aniwatch.co.at/'
        }
    },
    {
        "name": "No Referer",
        "headers": {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
    }
]

for tc in test_cases:
    req = urllib.request.Request(url, headers=tc["headers"])
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode('utf-8')
            print(f"Case: {tc['name']} | Status: {resp.status} | Length: {len(content)}")
    except Exception as e:
        print(f"Case: {tc['name']} | Error: {e}")
