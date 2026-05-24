import urllib.request
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

# Let's inspect the details page first
url = "https://aniwatch.co.at/spy-x-family-episode-1/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Let's search for ep-item links
    ep_items = re.findall(r'<a[^>]+class="[^"]*ep-item[^"]*"[^>]*>.*?</a>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(ep_items)} episode items:")
    for idx, ep in enumerate(ep_items[:5]):
        ep_clean = ep.encode('ascii', 'ignore').decode('ascii')
        print(f"Item #{idx+1}: {ep_clean.strip()}")
        href = re.search(r'href="([^"]*)"', ep)
        data_num = re.search(r'data-number="([^"]*)"', ep)
        print(f"  href: {href.group(1) if href else 'None'}")
        print(f"  data-number: {data_num.group(1) if data_num else 'None'}")
        
except Exception as e:
    print("Error:", e)
