import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

ciphertext_b64 = "QUKnJlY6U+Ljnie2yl/Z8mkyHcvLS0rBNlqXvEXYbROzDGysRX5a89Zqc5wVF4pTLx0gKKCNWTfV4j2m6AxLsg=="
encrypted_data = base64.b64decode(ciphertext_b64)
api_key_bytes = base64.b64decode("ZkpBVG0qa2dmSg==")

with open("scratch/candidates.txt", "r", encoding="utf-8") as f:
    candidates = [line.strip() for line in f if line.strip()]

if "" not in candidates:
    candidates.append("")
if "default_suffix" not in candidates:
    candidates.append("default_suffix")

print(f"Brute-forcing {len(candidates)} candidates against short ciphertext...")

found = False
for suffix in candidates:
    try:
        suffix_bytes = suffix.encode('utf-8')
        key_material = api_key_bytes + suffix_bytes
        
        if len(key_material) < 16:
            aes_key = key_material + b'\x00' * (16 - len(key_material))
        else:
            aes_key = key_material[:16]
            
        iv = aes_key
        
        cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        decrypted = cipher.decrypt(encrypted_data)
        decrypted_unpadded = unpad(decrypted, 16)
        decrypted_str = decrypted_unpadded.decode('utf-8')
        
        # Check if the decrypted string looks like JSON
        if "code" in decrypted_str or "msg" in decrypted_str or "{" in decrypted_str:
            print(f"\n[!!!] SUCCESS [!!!] Suffix found: '{suffix}'")
            print("Decrypted sample:", decrypted_str)
            found = True
            break
    except Exception as e:
        pass
else:
    print("Brute-force completed. No matching suffix found.")
