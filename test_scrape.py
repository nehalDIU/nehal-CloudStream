import urllib.request

url = "https://my.1anime.site/videos/hells-paradise-jigokuraku-season-2-episode-2.mp4"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'},
    method='HEAD'
)

try:
    with urllib.request.urlopen(req) as response:
        print("HEAD Request Success!")
        print("Status:", response.status)
        print("Content-Type:", response.getheader("Content-Type"))
        print("Content-Length:", response.getheader("Content-Length"))
except Exception as e:
    print("Error:", e)
