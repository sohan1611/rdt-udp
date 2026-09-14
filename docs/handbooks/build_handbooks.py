"""Build the four member handbooks from shared and per-member HTML parts.

Each handbook starts from src/<key>.html. Every <!--COMMON:name--> marker in
it is replaced by src/common/<name>.html, then {{PLACEHOLDER}} values are
filled in for that member. The finished HTML is written next to this script,
printed to PDF with headless Chrome, and the PDF is copied to the
CN Assignment folder.

Keeping the shared sections in one place means the four handbooks cannot
drift apart: change a team convention once and rebuild.

Usage:
    python build_handbooks.py           # all four
    python build_handbooks.py m2 m3     # only these
"""
import pathlib
import re
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / "src"
COMMON = SRC / "common"
DEST = pathlib.Path(r"D:\Downloads\CN Assignment\Handbooks")
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

MEMBERS = [
    dict(key="m1", file="Handbook-M1-Shaili-Seth", name="Shaili Seth", first="Shaili",
         first_lower="shaili", role="M1", title="Framing and Session", roll="2405901",
         gh="shailiseth24", branch="m1", pair_a="Sinjan Mishra", pair_b="Sohini Pandit",
         color="#1b6b4a", tint="#e3f2ea", hl="m1"),
    dict(key="m2", file="Handbook-M2-Sinjan-Mishra", name="Sinjan Mishra", first="Sinjan",
         first_lower="sinjan", role="M2", title="Timers and Go-Back-N", roll="2405910",
         gh="Thesin-01", branch="m2", pair_a="Shaili Seth", pair_b="Sohan Mandal",
         color="#1b4d7a", tint="#e3ecf5", hl="m2"),
    dict(key="m3", file="Handbook-M3-Sohini-Pandit", name="Sohini Pandit", first="Sohini",
         first_lower="sohini", role="M3", title="Selective Repeat", roll="2405913",
         gh="sohinix", branch="m3", pair_a="Sohan Mandal", pair_b="Shaili Seth",
         color="#7a2f5e", tint="#f3e6ee", hl="m3"),
    dict(key="m4", file="Handbook-M4-Sohan-Mandal", name="Sohan Mandal", first="Sohan",
         first_lower="sohan", role="M4", title="Channel and Evidence", roll="2405912",
         gh="sohan1611", branch="m4", pair_a="Sohini Pandit", pair_b="Sinjan Mishra",
         color="#8a5a00", tint="#f6ecd9", hl="m4"),
]

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{{ROLE}} Handbook - {{NAME}}</title>
<link rel="stylesheet" href="src/handbook.css">
<style>:root { --me: {{COLOR}}; --me-tint: {{TINT}}; }</style>
</head>
<body class="{{HL}}">
{{BODY}}
<footer>{{ROLE}} Handbook &middot; {{NAME}} &middot; CS-30003 Coding Assignment 1 &middot;
P3 Reliable Data Transfer over UDP &middot; revised 14 September 2026 &middot; source in
<code>docs/handbooks/</code> of the repository</footer>
</body>
</html>
"""

MARKER = re.compile(r"<!--COMMON:([a-z_]+)-->")
LEFTOVER = re.compile(r"\{\{[A-Z_]+\}\}")


def expand(member):
    body = (SRC / (member["key"] + ".html")).read_text(encoding="utf-8")
    body = MARKER.sub(
        lambda m: (COMMON / (m.group(1) + ".html")).read_text(encoding="utf-8"), body)
    html = TEMPLATE.replace("{{BODY}}", body)
    for field, value in member.items():
        html = html.replace("{{" + field.upper() + "}}", value)
    left = sorted(set(LEFTOVER.findall(html)))
    if left or "<!--COMMON:" in html:
        raise SystemExit("unfilled placeholders in %s: %s" % (member["key"], left))
    return html


def page_count(pdf_path):
    return len(re.findall(rb"/Type\s*/Page[^s]", pdf_path.read_bytes()))


def main(keys):
    DEST.mkdir(parents=True, exist_ok=True)
    chosen = [m for m in MEMBERS if not keys or m["key"] in keys]
    if not chosen:
        raise SystemExit("no member matches %s" % keys)
    for member in chosen:
        html_path = HERE / (member["file"] + ".html")
        pdf_path = HERE / (member["file"] + ".pdf")
        html_path.write_text(expand(member), encoding="utf-8")
        subprocess.run(
            [CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer",
             "--print-to-pdf=" + str(pdf_path), str(html_path)],
            check=True, capture_output=True)
        shutil.copy2(pdf_path, DEST / pdf_path.name)
        print("%-30s %3d pages  %4d KB" % (
            pdf_path.name, page_count(pdf_path), pdf_path.stat().st_size // 1024))
    print("copied to", DEST)


if __name__ == "__main__":
    main(sys.argv[1:])
