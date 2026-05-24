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
    
    # Print the lines around film-stats
    lines = html.splitlines()
    for idx, line in enumerate(lines):
        if "film-stats" in line:
            print(f"\nMatch on Line {idx+1}:")
            for i in range(max(0, idx - 2), min(len(lines), idx + 10)):
                print(f"Line {i+1}: {lines[i]}")
            break
            
except Exception as e:
    print("Error:", e)
