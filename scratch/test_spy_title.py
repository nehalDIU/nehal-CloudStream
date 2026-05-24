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
        
    print("Watch page fetched successfully. Size:", len(html))
    
    title = re.search(r'<title>(.*?)</title>', html, re.IGNORECASE)
    print("Page Title:", title.group(1).strip() if title else "None")
    
    # Check H2 film-name
    film_name = re.search(r'<h2 class="film-name">.*?<a[^>]*>(.*?)</a>', html, re.DOTALL | re.IGNORECASE)
    print("Film Name:", film_name.group(1).strip() if film_name else "None")
    
except Exception as e:
    print("Error:", e)
