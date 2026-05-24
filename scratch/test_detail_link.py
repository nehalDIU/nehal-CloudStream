import urllib.request
import re

url = "https://aniwatch.co.at/spy-x-family-episode-12-english-subbed/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Watch page fetched successfully. Size:", len(html))
    
    # Let's search for links containing "View detail", "btn-light", "/anime/", film-name, etc.
    links = re.findall(r'<a[^>]+href="([^"]*)"[^>]*>.*?</a>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(links)} links. Let's see some interesting ones:")
    for l in links:
        if "/anime/" in l or "detail" in l.lower() or "btn" in l:
            print("  Link:", l)
            
except Exception as e:
    print("Error:", e)
