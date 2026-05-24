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
    
    # Print 5 lines before and after "Watch now"
    lines = html.splitlines()
    for idx, line in enumerate(lines):
        if "Watch now" in line:
            print("\nLines around Watch now:")
            for i in range(max(0, idx - 5), min(len(lines), idx + 5)):
                print(f"Line {i+1}: {lines[i]}")
            break
            
except Exception as e:
    print("Error:", e)
