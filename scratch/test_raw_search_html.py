import urllib.request
import re
import urllib.parse

url = "https://aniwatch.co.at/filter?keyword=spy%20x%20family"
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')
        
    print("HTML Fetched successfully. Size:", len(html))
    
    # Print the lines containing spy-x-family
    lines = html.splitlines()
    matches = 0
    for idx, line in enumerate(lines):
        if "spy-x-family" in line.lower():
            print(f"Line {idx+1}: {line.strip()[:200]}")
            matches += 1
            if matches >= 15:
                break
                
except Exception as e:
    print("Error:", e)
