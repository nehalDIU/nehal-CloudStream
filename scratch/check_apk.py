import os
import zipfile

apk_path = "scratch/castle.apk"
if not os.path.exists(apk_path):
    print("castle.apk not found in scratch directory!")
    exit(1)

stat = os.stat(apk_path)
print(f"File Size: {stat.st_size} bytes ({stat.st_size / 1024 / 1024:.2f} MB)")

# Let's list zip files inside to check date
with zipfile.ZipFile(apk_path, "r") as z:
    infos = z.infolist()
    print(f"Total files in APK: {len(infos)}")
    # Print dates of a few files
    for info in infos[:10]:
        print(f"File: {info.filename}, Date: {info.date_time}")
