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
        
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL | re.IGNORECASE)
    for idx, s in enumerate(scripts):
        if "player" in s.lower() or "sources" in s.lower() or "iframe" in s.lower():
            print(f"\n--- Script #{idx+1} (length: {len(s)}) ---")
            print(s.strip())
            
except Exception as e:
    print("Error:", e)
