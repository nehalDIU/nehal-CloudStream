import urllib.request
import re

url = "https://aniwatch.co.at/frieren-beyond-journeys-end-episode-1-english-subbed/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("PAGE FETCHED SUCCESSFULLY. Size:", len(html))
    
    # Let's search for server-item elements and check their attributes (like data-hash, data-type, data-sub, etc.)
    servers = re.findall(r'<div[^>]+class="[^"]*server-item[^"]*"[^>]*>.*?</div>', html, re.DOTALL | re.IGNORECASE)
    print(f"\n--- FOUND {len(servers)} SERVER ITEMS ---")
    for s in servers:
        print(s.strip())
        print("-" * 60)

    # Let's search for any divs that wrap the servers, e.g. class="servers-sub" or class="servers-dub"
    server_wrappers = re.findall(r'<div[^>]+class="[^"]*servers-[^"]*"[^>]*>.*?(?=<div class="servers-|\Z)', html, re.DOTALL | re.IGNORECASE)
    print(f"\n--- FOUND {len(server_wrappers)} SERVER WRAPPERS ---")
    for idx, w in enumerate(server_wrappers):
        print(f"Wrapper #{idx+1} head: {w[:300].strip()} ...")
        # Find server-items inside this wrapper
        inner_servers = re.findall(r'<div[^>]+class="[^"]*server-item[^"]*"[^>]*>(.*?)</div>', w, re.DOTALL | re.IGNORECASE)
        print("  Contains servers:", [re.sub(r'<[^>]+>', '', s).strip() for s in inner_servers])
        print("=" * 60)

except Exception as e:
    print("Error:", e)
