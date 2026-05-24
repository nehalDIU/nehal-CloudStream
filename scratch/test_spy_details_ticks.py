import urllib.request
import re

url = "https://aniwatch.co.at/anime/spy-x-family/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Search for tick-sub, tick-dub, tick-item, etc.
    ticks = re.findall(r'<div[^>]+class="[^"]*tick[^"]*"[^>]*>.*?</div>', html, re.DOTALL | re.IGNORECASE)
    print("Found ticks:", ticks)
    
    # Also look at film-stats
    stats = re.findall(r'<div[^>]+class="[^"]*film-stats[^"]*"[^>]*>.*?</div>', html, re.DOTALL | re.IGNORECASE)
    print("\nStats blocks:")
    for stat in stats:
        # strip tags for safe printing
        clean_stat = re.sub('<[^<]+?>', ' | ', stat).strip()
        print("  -", clean_stat)
        
except Exception as e:
    print("Error:", e)
