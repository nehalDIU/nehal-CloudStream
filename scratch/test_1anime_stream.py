import urllib.request
import re

url = "https://1anime.site/megaplay/stream/s-2/89506/sub"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Referer': 'https://aniwatch.co.at/'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("1Anime stream page fetched successfully. Size:", len(html))
    
    # Search for ids, players, data-id, megaplay-player etc.
    data_id = re.search(r'data-id="([^"]*)"', html)
    print("Found data-id in 1Anime:", data_id.group(1) if data_id else "None")
    
    # Print all iframe/script elements in 1Anime stream page
    iframes = re.findall(r'<iframe[^>]+src="([^"]*)"[^>]*>', html, re.IGNORECASE)
    print("\nIframes in 1Anime:")
    for iframe in iframes:
        print("  ", iframe)
        
    # Search for anything megaplay-related
    print("\nMegaplay patterns in html:")
    lines = html.splitlines()
    for idx, line in enumerate(lines):
        if "megaplay" in line.lower() or "player" in line.lower() or "sources" in line.lower():
            print(f"  Line {idx+1}: {line.strip()[:200]}")
            
except Exception as e:
    print("Error:", e)
