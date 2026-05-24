import urllib.request
import re

url = "https://aniwatch.co.at/mushoku-tensei-jobless-reincarnation-season-2-episode-12-english-subbed/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Check if there are any season containers or list elements
    # (Commonly class "os-list", os-item, detail-seasons, seasons, etc.)
    season_elements = re.findall(r'<div[^>]+class="[^"]*season[^"]*"[^>]*>.*?</div>', html, re.IGNORECASE | re.DOTALL)
    print(f"\nFound {len(season_elements)} class='season' elements.")
    
    # Print the links on this page that contain 'season' or 'mushoku'
    links = re.findall(r'<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>', html, re.DOTALL)
    print("\nLinks containing mushoku or season:")
    for href, text in links:
        href_lower = href.lower()
        text_clean = re.sub('<[^<]+?>', '', text).strip()
        if "mushoku" in href_lower or "season" in href_lower or "season" in text_clean.lower():
            if not href.startswith("javascript"):
                print(f"  Href: {href} | Text: {text_clean}")
            
except Exception as e:
    print("Error:", e)
