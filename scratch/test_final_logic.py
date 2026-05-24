import urllib.request
import re
import base64
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'X-Requested-With': 'XMLHttpRequest'
}

# 1. Fetch watch page
url = "https://aniwatch.co.at/spy-x-family-episode-1-english-subbed/"
print("Step 1: Fetching watch page...")
req1 = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req1) as resp1:
        watch_html = resp1.read().decode('utf-8')
    
    # 2. Extract server hashes
    server_elements = re.findall(r'<div[^>]+class="[^"]*server-item[^"]*"[^>]*>', watch_html, re.DOTALL | re.IGNORECASE)
    print(f"Step 2: Found {len(server_elements)} servers.")
    
    for element in server_elements:
        data_hash = re.search(r'data-hash="([^"]*)"', element)
        server_name = re.search(r'data-server-name="([^"]*)"', element)
        
        if data_hash:
            h = data_hash.group(1)
            missing_padding = len(h) % 4
            if missing_padding:
                h += '=' * (4 - missing_padding)
            decoded = base64.b64decode(h).decode('utf-8')
            print(f"\nServer: {server_name.group(1) if server_name else 'None'} | Decoded URL: {decoded}")
            
            real_url = decoded.replace("1anime.site/megaplay", "megaplay.buzz")
            
            # Step 3: Fetch the player page to get data-id
            print(f"Step 3: Fetching player page: {real_url}")
            req2 = urllib.request.Request(real_url, headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://1anime.site/'
            })
            with urllib.request.urlopen(req2) as resp2:
                player_html = resp2.read().decode('utf-8')
            
            # Parse data-id from #megaplay-player
            data_id_match = re.search(r'id="megaplay-player"[^>]*data-id="([^"]*)"', player_html)
            if not data_id_match:
                # try alternative order
                data_id_match = re.search(r'data-id="([^"]*)"[^>]*id="megaplay-player"', player_html)
            if not data_id_match:
                # wider search for data-id in fix-area/megaplay-player
                data_id_match = re.search(r'data-id="([^"]*)"', player_html)
                
            if data_id_match:
                correct_id = data_id_match.group(1)
                print(f"  Parsed correct internal data-id: {correct_id}")
                
                # Step 4: Query sources
                api_url = f"https://megaplay.buzz/stream/getSources?id={correct_id}"
                print(f"  Step 4: Querying API: {api_url}")
                req3 = urllib.request.Request(api_url, headers={
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Referer': 'https://megaplay.buzz/'
                })
                with urllib.request.urlopen(req3) as resp3:
                    sources_json = json.loads(resp3.read().decode('utf-8'))
                
                print("  Sources JSON received successfully!")
                print(f"    M3U8 Stream: {sources_json.get('sources', {}).get('file')}")
                
                # Find English subtitle
                eng_sub = None
                for track in sources_json.get("tracks", []):
                    if track.get("label") == "English":
                        eng_sub = track["file"]
                        break
                
                if eng_sub:
                    print(f"    English Subtitle URL: {eng_sub}")
                    # Fetch first few lines of subtitle to verify
                    sub_req = urllib.request.Request(eng_sub, headers={
                        'User-Agent': 'Mozilla/5.0',
                        'Referer': 'https://megaplay.buzz/'
                    })
                    with urllib.request.urlopen(sub_req) as sub_resp:
                        sub_lines = sub_resp.read().decode('utf-8').splitlines()
                    
                    print("\n    Verification: Subtitle dialog matches show:")
                    count = 0
                    for line in sub_lines:
                        if "-->" in line or line.strip() == "" or "WEBVTT" in line:
                            continue
                        clean = line.encode('ascii', 'ignore').decode('ascii').strip()
                        if clean:
                            print(f"      - {clean}")
                            count += 1
                            if count >= 3:
                                break
                else:
                    print("    No English subtitle found!")
            else:
                print("  Could not find data-id on the player page!")

except Exception as e:
    print("Error:", e)
