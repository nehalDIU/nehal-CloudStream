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
    
    # Print lines containing "/anime/spy-x-family/"
    lines = html.splitlines()
    for idx, line in enumerate(lines):
        if "/anime/spy-x-family/" in line:
            print(f"\nMatch found on Line {idx+1}:")
            # print 3 lines around it
            for i in range(max(0, idx - 2), min(len(lines), idx + 3)):
                print(f"Line {i+1}: {lines[i]}")
            
except Exception as e:
    print("Error:", e)
