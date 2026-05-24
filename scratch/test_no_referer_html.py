import urllib.request

url = "https://megaplay.buzz/stream/s-2/89506/sub"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
}

req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req) as resp:
        content = resp.read().decode('utf-8')
        
    print("Content size without referer:", len(content))
    if "megaplay-player" in content:
        print("Found megaplay-player in No Referer content!")
    else:
        print("COULD NOT FIND megaplay-player in No Referer content!")
        print("Preview of content:")
        print(content[:1000])
        
except Exception as e:
    print("Error:", e)
