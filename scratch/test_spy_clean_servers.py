import urllib.request
import re
import base64

url = "https://aniwatch.co.at/spy-x-family-episode-1/"
req = urllib.request.Request(
    url, 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        
    print("Page fetched successfully. Size:", len(html))
    
    # Let's search for server-item links
    server_elements = re.findall(r'<div[^>]+class="[^"]*server-item[^"]*"[^>]*>', html, re.DOTALL | re.IGNORECASE)
    print(f"\nFound {len(server_elements)} servers:")
    for element in server_elements:
        print("Element:", element)
        data_id = re.search(r'data-id="([^"]*)"', element)
        data_hash = re.search(r'data-hash="([^"]*)"', element)
        server_name = re.search(r'data-server-name="([^"]*)"', element)
        data_type = re.search(r'data-type="([^"]*)"', element)
        
        print(f"  data-id: {data_id.group(1) if data_id else 'None'}")
        print(f"  data-server-name: {server_name.group(1) if server_name else 'None'}")
        print(f"  data-type: {data_type.group(1) if data_type else 'None'}")
        if data_hash:
            h = data_hash.group(1)
            print(f"  data-hash: {h}")
            try:
                # Pad hash if needed
                missing_padding = len(h) % 4
                if missing_padding:
                    h += '=' * (4 - missing_padding)
                decoded = base64.b64decode(h).decode('utf-8')
                print(f"    Decoded: {decoded}")
            except Exception as e:
                print(f"    Failed to decode hash: {e}")
                
except Exception as e:
    print("Error:", e)
