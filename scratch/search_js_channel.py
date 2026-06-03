with open("scratch/india.js", "r", encoding="utf-8") as f:
    content = f.read()

import re

for match in re.finditer(r"channel", content, re.IGNORECASE):
    start = max(0, match.start() - 100)
    end = min(len(content), match.end() + 100)
    print(f"Match found for channel at position {match.start()}:")
    print(content[start:end])
    print("-" * 50)

for match in re.finditer(r"clientType", content, re.IGNORECASE):
    start = max(0, match.start() - 100)
    end = min(len(content), match.end() + 100)
    print(f"Match found for clientType at position {match.start()}:")
    print(content[start:end])
    print("-" * 50)
