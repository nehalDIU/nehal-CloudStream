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
    
    # Let's search for "Watch now" and print the full <a> element
    match = re.search(r'(<a[^>]*>[^<]*Watch now[^<]*</a>)', html, re.IGNORECASE)
    if match:
        print("\nFound Watch now button HTML:")
        print(match.group(1))
    else:
        print("\nCould not find 'Watch now' button directly. Let's print any element containing 'Watch now'")
        # Try wider search
        match_wide = re.search(r'(<[^>]+>[^<]*Watch now.*?</[^>]+>)', html, re.DOTALL | re.IGNORECASE)
        if match_wide:
            print(match_wide.group(1))
            
except Exception as e:
    print("Error:", e)
