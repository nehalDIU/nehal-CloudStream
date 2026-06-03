import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

domains = ["https://api.hbzws.com", "https://api.htocy.com", "https://api.hlowb.com"]

for domain in domains:
    print(f"\n--- Testing Domain: {domain} ---")
    try:
        # Fetch key
        key_url = f"{domain}/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"
        req = urllib.request.Request(key_url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"})
        with urllib.request.urlopen(req) as resp:
            key_data = json.loads(resp.read().decode())
            print(f"Key Response: {key_data}")
            api_key_b64 = key_data["data"]
            
        # Fetch home page
        home_url = f"{domain}/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17"
        req_home = urllib.request.Request(home_url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"})
        with urllib.request.urlopen(req_home) as resp_home:
            home_body = resp_home.read().decode().strip()
            print(f"Home page response length: {len(home_body)}")
            print(f"Home page preview: {home_body[:100]}")
            
            # Parse ciphertext
            if home_body.startswith("{"):
                home_data = json.loads(home_body)
                ciphertext_b64 = home_data.get("data", "")
            else:
                if home_body.startswith('"') and home_body.endswith('"'):
                    home_body = home_body[1:-1]
                ciphertext_b64 = home_body

            encrypted_data = base64.b64decode(ciphertext_b64)
            api_key_bytes = base64.b64decode(api_key_b64)

            # Try suffix "" and "default_suffix"
            for suffix in ["default_suffix", ""]:
                suffix_bytes = suffix.encode('ascii')
                key_material = api_key_bytes + suffix_bytes
                
                if len(key_material) < 16:
                    aes_key = key_material + b'\x00' * (16 - len(key_material))
                else:
                    aes_key = key_material[:16]
                    
                iv = aes_key
                
                try:
                    cipher = AES.new(aes_key, AES.MODE_CBC, iv)
                    decrypted = cipher.decrypt(encrypted_data)
                    decrypted_unpadded = unpad(decrypted, 16)
                    decrypted_str = decrypted_unpadded.decode('utf-8')
                    if "code" in decrypted_str and "msg" in decrypted_str:
                        print(f"SUCCESS with suffix '{suffix}' on domain {domain}!")
                        print("Decrypted sample:", decrypted_str[:250])
                except Exception as e:
                    pass
    except Exception as e:
        print(f"Error for domain {domain}: {e}")
