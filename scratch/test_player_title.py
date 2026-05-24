import urllib.request
import re

url = "https://megaplay.buzz/stream/s-2/89506/sub"
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
}

req = urllib.request.Request(url, headers=headers)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Let's search for the title, head tags, or scripts
    title = re.search(r'<title>(.*?)</title>', html, re.IGNORECASE)
    print("Title:", title.group(1) if title else "None")
    
    player_id = re.search(r'data-id="([^"]*)"', html)
    print("data-id in page:", player_id.group(1) if player_id else "None")
    
    # Print the script elements
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(scripts)} scripts:")
    for idx, s in enumerate(scripts):
        if "megaplay" in s or "id" in s or "source" in s:
            print(f"\nScript #{idx+1}:")
            print(s[:500].strip())
            
except Exception as e:
    print("Error:", e)
