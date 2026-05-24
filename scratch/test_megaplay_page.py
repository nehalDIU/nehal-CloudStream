import urllib.request
import re

url = "https://megaplay.buzz/stream/s-2/89506/sub"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Referer': 'https://1anime.site/'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Megaplay page fetched successfully. Size:", len(html))
    
    # Check for variables, configs, servers, iframes, etc.
    data_id = re.search(r'data-id="([^"]*)"', html)
    print("Found data-id:", data_id.group(1) if data_id else "None")
    
    # Search for all scripts and print their lines
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(scripts)} scripts:")
    for idx, s in enumerate(scripts):
        if "source" in s.lower() or "player" in s.lower():
            print(f"  Script #{idx+1} (length: {len(s)}):")
            print(s.strip()[:500])
            
except Exception as e:
    print("Error:", e)
