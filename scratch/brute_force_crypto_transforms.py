import urllib.request
import json
import base64
import hashlib
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
api_key_bytes = base64.b64decode(api_key_b64) # fJATm*kgfJ (10 bytes)

# Suffixes
suffixes = ["default_suffix", ""]

# Let's define some typical IVs
iv_options = [
    # Zero IV (16 bytes of 0)
    b'\x00' * 16,
    # ASCII '0' IV
    b'0' * 16,
    # Standard IV
    b'0123456789abcdef',
    # Telemetry IV
    b'u1tDxubl2IZ946Ly',
]

print("Starting cryptographic brute force...")

def try_decrypt(key, iv_cand, label):
    try:
        cipher = AES.new(key, AES.MODE_CBC, iv_cand)
        dec = cipher.decrypt(encrypted_data)
        dec_unpadded = unpad(dec, 16)
        dec_str = dec_unpadded.decode('utf-8')
        if "code" in dec_str and "msg" in dec_str:
            print(f"\n[!!!] SUCCESS! [{label}]")
            print("Decrypted sample:", dec_str[:300])
            return True
    except:
        pass
    return False

found = False
for suffix in suffixes:
    suffix_bytes = suffix.encode('utf-8')
    raw_material = api_key_bytes + suffix_bytes
    
    # 1. Plain truncated/padded key
    if len(raw_material) < 16:
        plain_key = raw_material + b'\x00' * (16 - len(raw_material))
    else:
        plain_key = raw_material[:16]
        
    # Standard same-key-as-IV
    if try_decrypt(plain_key, plain_key, f"Plain key, same key as IV, suffix: '{suffix}'"):
        found = True; break
        
    for iv in iv_options:
        if try_decrypt(plain_key, iv, f"Plain key, custom IV, suffix: '{suffix}'"):
            found = True; break
            
    if found: break
    
    # 2. MD5 key
    md5_key = hashlib.md5(raw_material).digest() # 16 bytes
    if try_decrypt(md5_key, md5_key, f"MD5 key, same as IV, suffix: '{suffix}'"):
        found = True; break
    for iv in iv_options:
        if try_decrypt(md5_key, iv, f"MD5 key, custom IV, suffix: '{suffix}'"):
            found = True; break
            
    if found: break

    # 3. SHA-256 key (truncated to 16 bytes)
    sha256_key = hashlib.sha256(raw_material).digest()[:16]
    if try_decrypt(sha256_key, sha256_key, f"SHA-256 key (16B), same as IV, suffix: '{suffix}'"):
        found = True; break
    for iv in iv_options:
        if try_decrypt(sha256_key, iv, f"SHA-256 key (16B), custom IV, suffix: '{suffix}'"):
            found = True; break
            
    if found: break

    # 4. SHA-1 key (truncated to 16 bytes)
    sha1_key = hashlib.sha1(raw_material).digest()[:16]
    if try_decrypt(sha1_key, sha1_key, f"SHA-1 key (16B), same as IV, suffix: '{suffix}'"):
        found = True; break
    for iv in iv_options:
        if try_decrypt(sha1_key, iv, f"SHA-1 key (16B), custom IV, suffix: '{suffix}'"):
            found = True; break
            
    if found: break

if not found:
    print("Crypto brute force completed. No combination decrypted successfully.")
