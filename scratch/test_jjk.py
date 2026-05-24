import urllib.request
import urllib.parse
import re
import base64
import json

# 1. Search for Jujutsu Kaisen
query = "Jujutsu Kaisen"
search_url = f"https://aniwatch.co.at/?s={urllib.parse.quote(query)}"
req = urllib.request.Request(search_url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})

try:
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode('utf-8')
    
    # Get first match card
    url_match = re.search(r'<h3 class="film-name">.*?<a[^>]+href="([^"]*)"', html, re.DOTALL)
    card_url = url_match.group(1).strip() if url_match else None
    print("Found Card URL:", card_url)
    
    if card_url:
        # Load the watch page
        req2 = urllib.request.Request(card_url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
        with urllib.request.urlopen(req2) as resp2:
            watch_html = resp2.read().decode('utf-8')
            
        print("Fetched watch page size:", len(watch_html))
        
        # Extract server hashes
        server_elements = re.findall(r'<div[^>]+class="[^"]*server-item[^"]*"[^>]*>', watch_html, re.DOTALL | re.IGNORECASE)
        print(f"Found {len(server_elements)} server items:")
        for element in server_elements:
            print("Element:", element)
            data_hash = re.search(r'data-hash="([^"]*)"', element)
            server_name = re.search(r'data-server-name="([^"]*)"', element)
            
            if data_hash:
                h = data_hash.group(1)
                missing_padding = len(h) % 4
                if missing_padding:
                    h += '=' * (4 - missing_padding)
                decoded = base64.b64decode(h).decode('utf-8')
                print(f"  Name: {server_name.group(1) if server_name else 'None'} | Decoded: {decoded}")
                
                # If it's megaplay, query sources and read subtitles
                if "megaplay" in decoded:
                    pathId = decoded.substringBeforeLast("/") if hasattr(decoded, "substringBeforeLast") else decoded.split("/")[-2]
                    megaplay_id = pathId
                    sources_url = f"https://megaplay.buzz/stream/getSources?id={megaplay_id}"
                    print(f"  Querying sources from: {sources_url}")
                    sources_req = urllib.request.Request(sources_url, headers={
                        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
                        'X-Requested-With': 'XMLHttpRequest',
                        'Referer': 'https://megaplay.buzz/'
                    })
                    with urllib.request.urlopen(sources_req) as sources_resp:
                        sources_content = sources_resp.read().decode('utf-8')
                        sources_json = json.loads(sources_content)
                        print("  Sources JSON:", sources_json)
                        if sources_json.get("tracks"):
                            sub_url = sources_json["tracks"][0]["file"]
                            sub_req = urllib.request.Request(sub_url, headers={
                                'User-Agent': 'Mozilla/5.0',
                                'Referer': 'https://megaplay.buzz/'
                            })
                            with urllib.request.urlopen(sub_req) as sub_resp:
                                sub_content = sub_resp.read().decode('utf-8')
                                print("\n  First 10 lines of subtitle:")
                                for line in sub_content.splitlines()[:20]:
                                    print("    ", line)
                                    
except Exception as e:
    print("Error:", e)
