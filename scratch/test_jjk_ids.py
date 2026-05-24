import urllib.request
import re

url = "https://megaplay.buzz/stream/s-2/113965/sub"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Referer': 'https://1anime.site/'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("JJK Megaplay page fetched successfully. Size:", len(html))
    
    # Print the lines containing megaplay-player
    lines = html.splitlines()
    for idx, line in enumerate(lines):
        if "megaplay-player" in line:
            print(f"\nMatch on Line {idx+1}:")
            for i in range(max(0, idx - 2), min(len(lines), idx + 5)):
                print(f"Line {i+1}: {lines[i]}")
                
except Exception as e:
    print("Error:", e)
