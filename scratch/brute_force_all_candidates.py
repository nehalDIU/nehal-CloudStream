import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

# 1. Fetch live security key
print("Fetching security key...")
req = urllib.request.Request(
    "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req) as response:
    key_data = json.loads(response.read().decode())
    api_key_b64 = key_data["data"]

# 2. Fetch live home page
print("Fetching home page ciphertext...")
req_home = urllib.request.Request(
    "https://api.hlowb.com/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req_home) as response:
    home_body = response.read().decode().strip()

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

# 3. Read candidates from candidates.txt
with open("scratch/candidates.txt", "r", encoding="utf-8") as f:
    candidates = [line.strip() for line in f if line.strip()]

# Add empty suffix and default_suffix just in case
if "" not in candidates:
    candidates.append("")
if "default_suffix" not in candidates:
    candidates.append("default_suffix")

print(f"Brute-forcing {len(candidates)} candidates against live ciphertext...")

found = False
for i, suffix in enumerate(candidates):
    # Suffix could be standard string
    suffix_variants = [suffix]
    
    for s in suffix_variants:
        try:
            suffix_bytes = s.encode('utf-8')
            key_material = api_key_bytes + suffix_bytes
            
            # pad or truncate to 16 bytes
            if len(key_material) < 16:
                aes_key = key_material + b'\x00' * (16 - len(key_material))
            else:
                aes_key = key_material[:16]
                
            iv = aes_key
            
            cipher = AES.new(aes_key, AES.MODE_CBC, iv)
            decrypted = cipher.decrypt(encrypted_data)
            decrypted_unpadded = unpad(decrypted, 16)
            decrypted_str = decrypted_unpadded.decode('utf-8')
            
            if "code" in decrypted_str and "msg" in decrypted_str:
                print(f"\n[!!!] SUCCESS [!!!] Suffix found: '{s}'")
                print("Decrypted prefix:", decrypted_str[:300])
                found = True
                break
        except Exception as e:
            pass
            
    if found:
        break
else:
    print("Brute-force completed. No matching suffix found.")
