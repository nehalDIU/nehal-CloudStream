with open("scratch/india.js", "r", encoding="utf-8") as f:
    content = f.read()

import re

# Find occurrences of decrypt
for match in re.finditer(r"decrypt", content, re.IGNORECASE):
    start = max(0, match.start() - 100)
    end = min(len(content), match.end() + 100)
    print(f"Match found at position {match.start()}:")
    print(content[start:end])
    print("-" * 50)
