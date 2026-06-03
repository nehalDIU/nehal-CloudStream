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
print("Fetching home page...")
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

# Derived AES key using suffix "default_suffix" and empty suffix
suffixes = ["default_suffix", ""]

found = False
for suffix in suffixes:
    suffix_bytes = suffix.encode('utf-8')
    key_material = api_key_bytes + suffix_bytes
    
    if len(key_material) < 16:
        aes_key = key_material + b'\x00' * (16 - len(key_material))
    else:
        aes_key = key_material[:16]
        
    iv = aes_key
    
    # Try different offsets from 0 to 32 bytes
    for offset in range(33):
        # AES ciphertext length must be a multiple of 16 bytes for standard PKCS7 padding
        payload = encrypted_data[offset:]
        if len(payload) % 16 != 0:
            continue # Skip invalid AES block lengths
            
        try:
            cipher = AES.new(aes_key, AES.MODE_CBC, iv)
            dec = cipher.decrypt(payload)
            dec_unpadded = unpad(dec, 16)
            dec_str = dec_unpadded.decode('utf-8')
            if "code" in dec_str and "msg" in dec_str:
                print(f"\n[!!!] SUCCESS! Suffix: '{suffix}', Offset: {offset} bytes")
                print("Decrypted sample:", dec_str[:300])
                found = True
                break
        except:
            pass
            
    if found:
        break
else:
    print("Offset brute force completed. No match found.")
