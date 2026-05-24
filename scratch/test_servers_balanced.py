import urllib.request
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

url = "https://aniwatch.co.at/spy-x-family-episode-1-english-subbed/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    start_pos = html.find('id="servers-content"')
    if start_pos != -1:
        block = html[start_pos:start_pos+5000]
        # Clean unicode characters for safe terminal output
        block_clean = block.encode('ascii', 'ignore').decode('ascii')
        print("HTML block around servers-content:")
        print(block_clean)
    else:
        print("Could not find servers-content id")
        
except Exception as e:
    print("Error:", e)
