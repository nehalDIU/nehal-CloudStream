import urllib.request

url = "https://1oe.lostproject.club/anime/585af24ef8d0524a0cf1941e5ae9f599/8ddc782797b76e094c6c9496cfc8515d/subtitles/d6f9fc4d371073e5955b454dc44be530.vtt"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
    'Referer': 'https://megaplay.buzz/',
    'Origin': 'https://megaplay.buzz'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        content = response.read().decode('utf-8')
        
    print("Subtitle fetched successfully. Size:", len(content))
    lines = content.splitlines()
    print("\nFirst 30 subtitle lines:")
    for line in lines[:50]:
        print(line)
        
except Exception as e:
    print("Error:", e)
