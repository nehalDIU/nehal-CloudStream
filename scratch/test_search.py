import urllib.request
import urllib.parse
import re

query = "Spy x Family"
url = f"https://aniwatch.co.at/?s={urllib.parse.quote(query)}"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Search page fetched successfully. Size:", len(html))
    
    # Parse search cards
    cards = re.findall(r'<div[^>]+class="[^"]*flw-item[^"]*"[^>]*>.*?<div class="clearfix"></div>\s*</div>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(cards)} search items:")
    for idx, card in enumerate(cards):
        print(f"\nCard #{idx+1}:")
        
        # Extract title
        title_match = re.search(r'<h3 class="film-name">.*?<a[^>]*>(.*?)</a>', card, re.DOTALL)
        title = title_match.group(1).strip() if title_match else "None"
        
        # Extract URL
        url_match = re.search(r'<h3 class="film-name">.*?<a[^>]+href="([^"]*)"', card, re.DOTALL)
        card_url = url_match.group(1).strip() if url_match else "None"
        
        print(f"  Title: {title}")
        print(f"  URL: {card_url}")
        
except Exception as e:
    print("Error:", e)
