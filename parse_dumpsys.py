with open('phase0_dumpsys_camera.txt', 'r', encoding='utf-8') as f:
    text = f.read()

import re
headings = re.findall(r'^[=\-]{2,}\s*([^=\-\n]+)\s*[=\-]{2,}', text, re.M)
print("Main headings in dumpsys:")
for h in headings:
    print(" -", h.strip())

# Check where camera characteristics appear
print("\nOccurrences of 'CameraCharacteristics' or 'characteristics':")
for m in re.finditer(r'(.{0,40}characteristics.{0,40})', text, re.I):
    print(" *", m.group(1).strip())
