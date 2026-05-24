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
    
    # Search for "myanimelist", "mal", "anilist", "data-id", etc.
    mal_links = re.findall(r'href="[^"]*myanimelist[^"]*"', html, re.IGNORECASE)
    print("Found MyAnimeList links:", mal_links)
    
    anilist_links = re.findall(r'href="[^"]*anilist[^"]*"', html, re.IGNORECASE)
    print("Found AniList links:", anilist_links)
    
    # Check any elements with "data-id" or similar attributes
    data_ids = re.findall(r'data-id="([^"]*)"', html)
    print("Found data-ids:", list(set(data_ids))[:10])
    
except Exception as e:
    print("Error:", e)
