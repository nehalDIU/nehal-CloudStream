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
    
    # Extract film description
    desc_match = re.search(r'<div[^>]+class="[^"]*film-description[^"]*"[^>]*>.*?<div class="text"[^>]*>(.*?)</div>', html, re.DOTALL | re.IGNORECASE)
    if desc_match:
        print("\nDescription:")
        print(desc_match.group(1).strip())
    else:
        # fallback search
        desc_match_fallback = re.search(r'description[^>]*>([^<]+)', html, re.IGNORECASE)
        if desc_match_fallback:
            print("\nFallback Description:")
            print(desc_match_fallback.group(1).strip())
            
except Exception as e:
    print("Error:", e)
