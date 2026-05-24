import urllib.request

urls = [
    "https://aniwatch.co.at/spy-x-family-episode-1-english-dubbed/",
    "https://aniwatch.co.at/spy-x-family-episode-1-english-subbed/"
]

for url in urls:
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"URL: {url} | Status: {resp.status} | Final URL: {resp.url}")
    except Exception as e:
        print(f"URL: {url} | Error: {e}")
