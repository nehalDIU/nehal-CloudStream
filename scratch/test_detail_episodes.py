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
        
    print("Detail page fetched successfully. Size:", len(html))
    
    # Check if a.ep-item is present
    ep_items = re.findall(r'<a[^>]+class="[^"]*ep-item[^"]*"[^>]*>.*?</a>', html, re.DOTALL | re.IGNORECASE)
    print(f"Found {len(ep_items)} ep-item elements directly on the detail page.")
    
    # Let's find "Watch now" button, "Play" button, or links containing "-episode-"
    links = re.findall(r'<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>', html, re.DOTALL | re.IGNORECASE)
    print("\nInteresting links on detail page:")
    for href, text in links:
        href = href.strip()
        text_clean = re.sub('<[^<]+?>', '', text).strip()
        if "btn" in href or "play" in href.lower() or "play" in text_clean.lower() or "-episode-" in href:
            print(f"  href: {href} | text: {text_clean}")
            
except Exception as e:
    print("Error:", e)
