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
    
    # Print all iframe elements
    iframes = re.findall(r'<iframe[^>]+src="([^"]*)"[^>]*>', html, re.IGNORECASE)
    print(f"\nFound {len(iframes)} iframes:")
    for iframe in iframes:
        print("  Iframe src:", iframe)
        
    # Check if there is an embed or video element
    video_tags = re.findall(r'<video[^>]*>.*?</video>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(video_tags)} video tags:")
    
    # Find any javascript variables containing sources or links
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nSearching scripts for links:")
    for idx, s in enumerate(scripts):
        if "player" in s.lower() or "sources" in s.lower() or "iframe" in s.lower():
            print(f"  Script #{idx+1} contains player/sources keywords (length: {len(s)}).")
            
except Exception as e:
    print("Error:", e)
