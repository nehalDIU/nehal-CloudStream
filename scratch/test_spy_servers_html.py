import urllib.request
import re

url = "https://aniwatch.co.at/spy-x-family-episode-1-english-subbed/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Search for server list container or panels
    # Commonly named server-panel, ps-list, ps-servers, etc.
    servers_block = re.findall(r'<div[^>]+id="[^"]*servers[^"]*"[^>]*>.*?</div>\s*</div>\s*</div>', html, re.DOTALL | re.IGNORECASE)
    if not servers_block:
        servers_block = re.findall(r'<div[^>]+class="[^"]*servers[^"]*"[^>]*>.*?</div>\s*</div>', html, re.DOTALL | re.IGNORECASE)
    if not servers_block:
        # Just search for the server items and print a few lines around them
        matches = [m.start() for m in re.finditer(r'server-item', html)]
        if matches:
            start = max(0, matches[0] - 200)
            end = min(len(html), matches[-1] + 500)
            print("\nServers context block:")
            print(html[start:end])
    else:
        for idx, block in enumerate(servers_block):
            print(f"\nBlock #{idx+1}:")
            print(block)
            
except Exception as e:
    print("Error:", e)
