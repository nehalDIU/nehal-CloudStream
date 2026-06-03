import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

# Fetch security key
print("Fetching security key...")
req = urllib.request.Request(
    "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"}
)
with urllib.request.urlopen(req) as response:
    key_data = json.loads(response.read().decode())
    api_key_b64 = key_data["data"]

# Fetch home page
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

# Load some suffix words from our JS and standard suspects
candidates = [
    # General words and app package parts
    "default_suffix", "default", "castle", "external", "com.external.castle", "com.external",
    "india", "India", "IndiaA", "channel", "system", "security", "getSecurityKey", "film-api",
    "v0.1", "1.8.7", "v1.8.7", "1001", "1", "api.hlowb.com", "hlowb", "hbayy", "static.hbayy.com",
    # JavaScript telemetry encryption suspects
    "u1tDxubl2IZ946Ly", "ZkpBVG0qa2dmSg==", "fJATm*kgfJ",
    # Common keys
    "1234567890123456", "0000000000000000",
    # Try empty suffix too
    ""
]

# We can also generate some variations
extended_candidates = []
for c in candidates:
    extended_candidates.append(c)
    extended_candidates.append("_" + c)
    extended_candidates.append(c + "_")
    extended_candidates.append(c.upper())
    extended_candidates.append(c.lower())

extended_candidates = list(set(extended_candidates))

print(f"Testing {len(extended_candidates)} candidates...")

for suffix in extended_candidates:
    suffix_bytes = suffix.encode('ascii')
    key_material = api_key_bytes + suffix_bytes
    
    if len(key_material) < 16:
        aes_key = key_material + b'\x00' * (16 - len(key_material))
    else:
        aes_key = key_material[:16]
        
    iv = aes_key # standard in this provider
    
    try:
        cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        decrypted = cipher.decrypt(encrypted_data)
        # Try unpadding with PKCS7
        decrypted_unpadded = unpad(decrypted, 16)
        decrypted_str = decrypted_unpadded.decode('utf-8')
        
        if "code" in decrypted_str and "msg" in decrypted_str:
            print(f"\nSUCCESS! Suffix: '{suffix}'")
            print("Decrypted sample:", decrypted_str[:200])
            break
    except Exception as e:
        pass
else:
    print("No simple suffix matched.")
