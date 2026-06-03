import zipfile

apk_path = "scratch/castle.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    try:
        jar_data = z.read("assets/classes0.jar")
        print(f"Extracted assets/classes0.jar of size {len(jar_data)} bytes")
        print("First 100 bytes (hex):", jar_data[:100].hex())
        print("First 100 bytes (ASCII):", jar_data[:100].decode('ascii', errors='ignore'))
    except Exception as e:
        print("Failed to read classes0.jar:", e)
