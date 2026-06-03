with open("scratch/strings_ascii.txt", "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "suffix" in line.lower() or "default" in line.lower():
        print(f"Line {i+1}: {line.strip()}")
