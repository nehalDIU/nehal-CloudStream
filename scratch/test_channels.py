import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

channels = ["IndiaA", "IndexA", "GooglePlay", "Official", "OfficialA", "GuanWang", "default", "castle", "Index", "India"]

# Fetch home page first to get ciphertext
print("Fetching home page ciphertext...")
req_home = urllib.request.Request(
    "https://api.hlowb.com/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req_home) as response:
    home_body = response.read().decode().strip()

if home_body.startswith("{"):
    home_data = json.loads(home_body)
    ciphertext_b64 = home_data.get("data", "")
else:
    if home_body.startswith('"') and home_body.endswith('"'):
        home_body = home_body[1:-1]
    ciphertext_b64 = home_body

encrypted_data = base64.b64decode(ciphertext_b64)

for channel in channels:
    try:
        url = f"https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel={channel}&clientType=1&lang=en-US"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"})
        with urllib.request.urlopen(req) as resp:
            key_data = json.loads(resp.read().decode())
            key_b64 = key_data.get("data", "")
            print(f"Channel: {channel} -> Security Key B64: {key_b64}")
            
            if not key_b64:
                continue
                
            key_bytes = base64.b64decode(key_b64)
            
            # Try suffixes: "default_suffix", and ""
            for suffix in ["default_suffix", ""]:
                suffix_bytes = suffix.encode('ascii')
                key_material = key_bytes + suffix_bytes
                
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
                        print(f"  SUCCESS decrypting with suffix '{suffix}'!")
                        print("  Decrypted sample:", decrypted_str[:250])
                        exit(0)
                except Exception as e:
                    pass
    except Exception as e:
        print(f"Error fetching/processing for channel {channel}: {e}")
