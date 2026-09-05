import textwrap, pathlib

HELMET = """<helmet>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&amp;family=JetBrains+Mono:wght@500;600&amp;display=swap">
  <style>
    body { margin: 0; background: #0f1115; font-family: 'Manrope', system-ui, sans-serif; color: #eceef2; -webkit-font-smoothing: antialiased; }
    a { color: #45d3c2; } a:hover { color: #7fe4d8; }
    .mono { font-family: 'JetBrains Mono', ui-monospace, Menlo, monospace; }
  </style>
</helmet>"""

def page(body):
    return f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
{HELMET}
{body}
</x-dc>
</body>
</html>
"""

ROOT_OPEN = '<div style="position: relative; width: 390px; height: 844px; overflow: hidden; background: #0f1115; display: flex; flex-direction: column;">'
ROOT_CLOSE = '</div>'

def icon(name, color="#9aa0ad", size=22):
    paths = {
        "live": '<circle cx="12" cy="12" r="2.5"></circle><path d="M8.5 8.5a5 5 0 0 0 0 7"></path><path d="M15.5 8.5a5 5 0 0 1 0 7"></path><path d="M5.6 5.6a9 9 0 0 0 0 12.8"></path><path d="M18.4 5.6a9 9 0 0 1 0 12.8"></path>',
        "survey": '<path d="M4 20c4-1 6-4 6-8V4"></path><path d="M10 12h4c3 0 5 2 5 5v3"></path><circle cx="10" cy="4" r="1.5"></circle><circle cx="19" cy="20" r="1.5"></circle>',
        "grid": '<rect x="4" y="4" width="7" height="7" rx="1.5"></rect><rect x="13" y="4" width="7" height="7" rx="1.5"></rect><rect x="4" y="13" width="7" height="7" rx="1.5"></rect><rect x="13" y="13" width="7" height="7" rx="1.5"></rect>',
        "tools": '<path d="M14.5 6.5a4 4 0 0 0 4 4l-8.5 8.5a2.1 2.1 0 0 1-3-3l8.5-8.5"></path><path d="M14.5 6.5a4 4 0 0 1 5.3-3.8l-2.3 2.3 1 2.5 2.5 1 2.3-2.3"></path>',
        "back": '<path d="M15 5l-7 7 7 7"></path>',
        "check": '<path d="M5 12.5l4.5 4.5L19 7"></path>',
        "ruler": '<rect x="3" y="8" width="18" height="8" rx="1.5"></rect><path d="M7 8v3M11 8v4M15 8v3M19 8v4"></path>',
        "beacon": '<circle cx="12" cy="13" r="3"></circle><path d="M7 8a7 7 0 0 1 10 0"></path><path d="M4.5 5.5a11 11 0 0 1 15 0"></path><path d="M12 16v5"></path>',
        "mqtt": '<path d="M4 18V6"></path><path d="M4 6h10a4 4 0 0 1 0 8H4"></path><path d="M20 12h-3"></path>',
        "compare": '<path d="M9 4v16"></path><path d="M15 4v16"></path><path d="M4 9h5M15 9h5M4 15h5M15 15h5"></path>',
        "export": '<path d="M12 4v11"></path><path d="M8 11l4 4 4-4"></path><path d="M5 19h14"></path>',
        "settings": '<circle cx="12" cy="12" r="3"></circle><path d="M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8"></path>',
        "chevron": '<path d="M9 6l6 6-6 6"></path>',
        "stop": '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor" stroke="none"></rect>',
        "tag": '<path d="M4 4h7l9 9-7 7-9-9z"></path><circle cx="8" cy="8" r="1.2"></circle>',
        "copy": '<rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"></path>',
        "upload": '<path d="M12 20V9"></path><path d="M8 13l4-4 4 4"></path><path d="M5 5h14"></path>',
    }
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="{color}" '
            f'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{paths[name]}</svg>')

def nav(active):
    items = [("live", "Live"), ("survey", "Survey"), ("grid", "Coverage"), ("tools", "Tools")]
    cells = []
    for key, label in items:
        on = key == active
        col = "#45d3c2" if on else "#7a8190"
        cells.append(
            f'<div style="display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; flex-grow: 1; height: 56px;">'
            f'{icon(key, col)}<div style="font-size: 11px; font-weight: {700 if on else 600}; color: {col};">{label}</div></div>')
    return ('<div style="display: flex; align-items: stretch; padding: 6px 12px 14px; border-top: 1px solid #1f232c; background: #12151a;">'
            + "".join(cells) + '</div>')

def header(title, sub=None, back=False, right=None):
    left = ''
    if back:
        left = f'<div style="width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; margin-left: -8px; border-radius: 12px;">{icon("back", "#eceef2")}</div>'
    subhtml = f'<div style="font-size: 13px; color: #8b92a0; font-weight: 500;">{sub}</div>' if sub else ''
    righthtml = right or ''
    return (f'<div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 18px 20px 10px;">'
            f'<div style="display: flex; align-items: center; gap: 4px;">{left}'
            f'<div style="display: flex; flex-direction: column; gap: 3px;"><div style="font-size: 24px; font-weight: 800; letter-spacing: -0.4px;">{title}</div>{subhtml}</div></div>'
            f'{righthtml}</div>')

def spark(points, color="#45d3c2", w=88, h=26):
    n = len(points)
    lo, hi = min(points), max(points)
    span = max(hi - lo, 4)
    pts = " ".join(f"{i * (w - 2) / (n - 1) + 1:.1f},{h - 2 - (p - lo) / span * (h - 4):.1f}" for i, p in enumerate(points))
    return (f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" fill="none">'
            f'<polyline points="{pts}" stroke="{color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></polyline></svg>')

def pct(rssi):
    return max(0, min(100, round((rssi + 100) / 60 * 100)))

# ---------------- Main: Live signals ----------------
nodes = [
    ("Kitchen", "hazri-kitchen · AtomS3 Lite", -52, "1.1 m", "9 pkt/s", "σ 1.8", [-54,-53,-55,-52,-51,-53,-52,-50,-52,-52], "#45d3c2"),
    ("Living room", "hazri-living · AtomS3 Lite", -61, "2.4 m", "8 pkt/s", "σ 2.6", [-63,-60,-64,-61,-59,-62,-61,-63,-60,-61], "#45d3c2"),
    ("Hallway", "hazri-hall · AtomS3 Lite", -68, "4.0 m", "7 pkt/s", "σ 3.1", [-70,-66,-69,-71,-67,-68,-66,-69,-68,-68], "#45d3c2"),
    ("Bedroom", "hazri-bedroom · AtomS3 Lite", -79, "8.5 m", "5 pkt/s", "σ 4.0", [-82,-77,-80,-79,-83,-78,-76,-80,-79,-79], "#45d3c2"),
    ("Office", "hazri-office · AtomS3 Lite", -88, "> 10 m", "2 pkt/s", "σ 5.2", [-90,-86,-91,-88,-87,-92,-86,-89,-88,-88], "#7a8190"),
]
cards = []
for i, (name, sub, rssi, dist, rate, sd, pts, col) in enumerate(nodes):
    lead = i == 0
    border = "#2a6f68" if lead else "#242933"
    numcol = "#45d3c2" if rssi > -85 else "#8b92a0"
    cards.append(f'''
      <div style="display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; background: #171a20; border: 1px solid {border}; border-radius: 14px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start;">
          <div style="display: flex; flex-direction: column; gap: 2px;">
            <div style="font-size: 16px; font-weight: 700;">{name}</div>
            <div style="font-size: 12px; color: #8b92a0; font-weight: 500;">{sub}</div>
          </div>
          <div style="display: flex; align-items: baseline; gap: 4px;">
            <div class="mono" style="font-size: 26px; font-weight: 600; color: {numcol}; letter-spacing: -0.5px;">{rssi}</div>
            <div style="font-size: 11px; color: #8b92a0; font-weight: 600;">dBm</div>
          </div>
        </div>
        <div style="display: flex; align-items: center; gap: 14px;">
          <div style="flex-grow: 1; height: 6px; background: #22262f; border-radius: 3px; overflow: hidden;">
            <div style="width: {pct(rssi)}%; height: 6px; background: {col}; border-radius: 3px;"></div>
          </div>
          {spark(pts, col, h=22)}
        </div>
        <div style="display: flex; gap: 14px; font-size: 12px; color: #8b92a0; font-weight: 600;">
          <div>{dist}</div><div>{rate}</div><div>{sd} dB</div>
        </div>
      </div>''')

main_body = ROOT_OPEN + header("Live signals", "Direct scan · 5 nodes · 31 pkt/s",
    right='''<div style="display: flex; background: #171a20; border: 1px solid #242933; border-radius: 10px; padding: 3px;">
      <div style="padding: 7px 12px; border-radius: 8px; background: #22262f; font-size: 12px; font-weight: 700; color: #eceef2;">Direct</div>
      <div style="padding: 7px 12px; border-radius: 8px; font-size: 12px; font-weight: 700; color: #7a8190;">MQTT</div>
    </div>''') + f'''
  <div style="display: flex; flex-direction: column; gap: 12px; padding: 4px 16px 16px; flex-grow: 1; overflow: hidden;">
    <div style="display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: #12211f; border: 1px solid #1f4a45; border-radius: 12px;">
      {icon("check", "#45d3c2", 18)}
      <div style="font-size: 13px; font-weight: 600; color: #cfe9e5;">Kitchen leads by <span class="mono" style="color: #45d3c2;">9 dB</span> over Living room</div>
    </div>
    {"".join(cards)}
  </div>
''' + nav("live") + ROOT_CLOSE
pathlib.Path("Main.dc.html").write_text(page(main_body))

# ---------------- Survey ----------------
rooms = ["Kitchen", "Living", "Hallway", "Bedroom", "Office", "Garage"]
chips = []
for r in rooms:
    on = r == "Kitchen"
    chips.append(f'<div style="padding: 9px 14px; border-radius: 999px; font-size: 13px; font-weight: 700; white-space: nowrap; background: {"#45d3c2" if on else "#171a20"}; color: {"#0f1115" if on else "#c5cad4"}; border: 1px solid {"#45d3c2" if on else "#242933"};">{r}</div>')
chips.append('<div style="padding: 9px 14px; border-radius: 999px; font-size: 13px; font-weight: 700; white-space: nowrap; color: #8b92a0; border: 1px dashed #343a46;">+ Room</div>')

live_rows = [("Kitchen", -53, 100), ("Living room", -64, 62), ("Hallway", -71, 48), ("Bedroom", -83, 28), ("Office", -89, 18)]
lrows = "".join(
    f'<div style="display: flex; align-items: center; gap: 12px;">'
    f'<div style="width: 92px; font-size: 13px; font-weight: 600; color: {"#eceef2" if i == 0 else "#aeb4c0"};">{n}</div>'
    f'<div style="flex-grow: 1; height: 6px; background: #22262f; border-radius: 3px; overflow: hidden;"><div style="width: {p}%; height: 6px; background: {"#45d3c2" if i == 0 else "#3a5f5b"}; border-radius: 3px;"></div></div>'
    f'<div class="mono" style="width: 40px; text-align: right; font-size: 13px; font-weight: 600; color: {"#45d3c2" if i == 0 else "#aeb4c0"};">{v}</div></div>'
    for i, (n, v, p) in enumerate(live_rows))

done_rows = [("Living room", "2 min ago", "Clear · 9 dB", "#45d3c2"), ("Hallway", "6 min ago", "Tight · 3 dB", "#e5b85a"), ("Bedroom", "yesterday", "Clear · 18 dB", "#45d3c2")]
drows = "".join(
    f'<div style="display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; background: #171a20; border: 1px solid #242933; border-radius: 12px;">'
    f'<div style="display: flex; flex-direction: column; gap: 2px;"><div style="font-size: 14px; font-weight: 700;">{n}</div><div style="font-size: 12px; color: #8b92a0; font-weight: 500;">{t}</div></div>'
    f'<div style="font-size: 12px; font-weight: 700; color: {c};">{v}</div></div>'
    for n, t, v, c in done_rows)

survey_body = ROOT_OPEN + header("Room survey", "Walk each room slowly, corners included") + f'''
  <div style="display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 16px; flex-grow: 1; overflow: hidden;">
    <div style="display: flex; gap: 8px; overflow: hidden;">{"".join(chips)}</div>
    <div style="display: flex; flex-direction: column; gap: 14px; padding: 16px; background: #171a20; border: 1px solid #2a6f68; border-radius: 16px;">
      <div style="display: flex; justify-content: space-between; align-items: center;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <div style="width: 8px; height: 8px; border-radius: 4px; background: #e0766c;"></div>
          <div style="font-size: 13px; font-weight: 700; color: #eceef2;">Recording · Kitchen</div>
        </div>
        <div class="mono" style="font-size: 13px; font-weight: 600; color: #8b92a0;">118 samples</div>
      </div>
      <div style="display: flex; align-items: center; justify-content: space-between;">
        <div class="mono" style="font-size: 44px; font-weight: 600; letter-spacing: -1px;">00:24</div>
        <div style="width: 56px; height: 56px; border-radius: 28px; background: #e0766c; display: flex; align-items: center; justify-content: center; color: #0f1115;">{icon("stop", "#0f1115", 26)}</div>
      </div>
      <div style="display: flex; flex-direction: column; gap: 10px; padding-top: 4px; border-top: 1px solid #242933;">{lrows}</div>
      <div style="display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: #cfe9e5;">{icon("check", "#45d3c2", 16)}<div>So far: Kitchen wins by <span class="mono" style="color: #45d3c2;">11 dB</span></div></div>
    </div>
    <div style="font-size: 12px; font-weight: 700; color: #8b92a0; letter-spacing: 0.6px; text-transform: uppercase; padding: 4px 2px 0;">Surveyed</div>
    <div style="display: flex; flex-direction: column; gap: 8px;">{drows}</div>
  </div>
''' + nav("survey") + ROOT_CLOSE
pathlib.Path("Survey.dc.html").write_text(page(survey_body))

# ---------------- Coverage matrix ----------------
def tint(v):
    if v is None: return None
    if v >= -55: return "#2a9d90"
    if v >= -65: return "#23716a"
    if v >= -75: return "#1e4f4c"
    if v >= -85: return "#1b343a"
    return "#171a20"

matrix = [
    ("Kitchen", [-52, -63, -70, -84, None], "Clear", "11 dB", "#45d3c2"),
    ("Living", [-64, -55, -66, -80, -90], "Clear", "9 dB", "#45d3c2"),
    ("Hallway", [-70, -68, -65, -74, -82], "Tight", "3 dB", "#e5b85a"),
    ("Bedroom", [-86, -80, -72, -54, -76], "Clear", "18 dB", "#45d3c2"),
    ("Office", [None, -88, -84, -78, -60], "Clear", "18 dB", "#45d3c2"),
    ("Garage", [None, None, -89, None, None], "Blind", "—", "#e0766c"),
]
cols = ["Kit", "Liv", "Hall", "Bed", "Off"]
head = '<div style="font-size: 11px; color: #8b92a0;"></div>' + "".join(
    f'<div style="font-size: 11px; font-weight: 700; color: #8b92a0; text-align: center;">{c}</div>' for c in cols) + '<div></div>'
rows = ""
for room, vals, verdict, margin, vc in matrix:
    best = max(v for v in vals if v is not None)
    rows += f'<div style="font-size: 13px; font-weight: 700; display: flex; align-items: center;">{room}</div>'
    for v in vals:
        if v is None:
            rows += '<div style="height: 40px; border-radius: 8px; border: 1px dashed #2c313c; display: flex; align-items: center; justify-content: center; font-size: 12px; color: #4d5462;">—</div>'
        else:
            ring = "box-shadow: inset 0 0 0 2px #45d3c2;" if v == best and verdict != "Blind" else ""
            ink = "#eceef2" if v >= -85 else "#8b92a0"
            rows += f'<div class="mono" style="height: 40px; border-radius: 8px; background: {tint(v)}; {ring} display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; color: {ink};">{v}</div>'
    rows += f'<div style="display: flex; flex-direction: column; align-items: flex-end; justify-content: center; gap: 1px;"><div style="font-size: 11px; font-weight: 800; color: {vc};">{verdict}</div><div class="mono" style="font-size: 10px; color: #8b92a0;">{margin}</div></div>'

coverage_body = ROOT_OPEN + header("Coverage", "Mean RSSI per room · last survey") + f'''
  <div style="display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 16px; flex-grow: 1; overflow: hidden;">
    <div style="display: grid; grid-template-columns: 66px repeat(5, minmax(0, 1fr)) 48px; gap: 4px; align-items: center; padding: 12px; background: #171a20; border: 1px solid #242933; border-radius: 14px;">
      {head}{rows}
    </div>
    <div style="display: flex; align-items: center; gap: 10px; font-size: 11px; color: #8b92a0; font-weight: 600; padding: 0 4px;">
      <div style="display: flex; gap: 2px;">
        <div style="width: 18px; height: 8px; border-radius: 2px; background: #171a20; border: 1px solid #242933;"></div>
        <div style="width: 18px; height: 8px; border-radius: 2px; background: #1b343a;"></div>
        <div style="width: 18px; height: 8px; border-radius: 2px; background: #1e4f4c;"></div>
        <div style="width: 18px; height: 8px; border-radius: 2px; background: #23716a;"></div>
        <div style="width: 18px; height: 8px; border-radius: 2px; background: #2a9d90;"></div>
      </div>
      <div>−95 → −50 dBm</div>
      <div style="width: 12px; height: 12px; border-radius: 3px; box-shadow: inset 0 0 0 2px #45d3c2; margin-left: 6px;"></div>
      <div>strongest in room</div>
    </div>
    <div style="font-size: 12px; font-weight: 700; color: #8b92a0; letter-spacing: 0.6px; text-transform: uppercase; padding: 4px 2px 0;">Suggestions</div>
    <div style="display: flex; flex-direction: column; gap: 12px; padding: 14px 16px; background: #171a20; border: 1px solid #242933; border-radius: 14px;">
      <div style="display: flex; flex-direction: column; gap: 4px;">
        <div style="display: flex; align-items: center; gap: 8px;"><div style="font-size: 14px; font-weight: 700;">Hallway</div><div style="font-size: 11px; font-weight: 800; color: #e5b85a;">TIGHT</div></div>
        <div style="font-size: 13px; color: #aeb4c0; line-height: 1.45;">Hall and Living nodes are within 3 dB. Move the Hall node 1–2 m toward the stairs, or set Living <span class="mono">max_distance</span> to 4 m.</div>
      </div>
      <div style="height: 1px; background: #242933;"></div>
      <div style="display: flex; flex-direction: column; gap: 4px;">
        <div style="display: flex; align-items: center; gap: 8px;"><div style="font-size: 14px; font-weight: 700;">Garage</div><div style="font-size: 11px; font-weight: 800; color: #e0766c;">BLIND</div></div>
        <div style="font-size: 13px; color: #aeb4c0; line-height: 1.45;">Only the Hall node hears the phone here, at −89 dBm. Add a node, or treat Garage as "away".</div>
      </div>
    </div>
  </div>
''' + nav("grid") + ROOT_CLOSE
pathlib.Path("Coverage.dc.html").write_text(page(coverage_body))

# ---------------- Node detail ----------------
hist = [-55,-54,-56,-53,-52,-54,-55,-53,-51,-52,-53,-52,-54,-56,-55,-53,-52,-50,-51,-53,-52,-52,-54,-53,-51,-52,-53,-52,-50,-52]
W, H = 326, 130
def y(v): return (-(v) - 40) / 60 * H  # -40 top, -100 bottom
pts = " ".join(f"{i * W / (len(hist) - 1):.1f},{y(v):.1f}" for i, v in enumerate(hist))
grid = "".join(f'<line x1="0" x2="{W}" y1="{y(g):.1f}" y2="{y(g):.1f}" stroke="#242933" stroke-width="1"></line>' for g in (-50, -70, -90))
glabels = "".join(f'<div class="mono" style="position: absolute; right: 0; top: {y(g) - 7:.0f}px; font-size: 10px; color: #6b7280;">{g}</div>' for g in (-50, -70, -90))
chart = f'''<div style="position: relative; height: {H}px;">
  <svg width="{W}" height="{H}" viewBox="0 0 {W} {H}" fill="none" style="position: absolute; left: 0; top: 0;">{grid}
    <polyline points="{pts}" stroke="#45d3c2" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></polyline>
    <circle cx="{W}" cy="{y(hist[-1]):.1f}" r="4" fill="#45d3c2" stroke="#171a20" stroke-width="2"></circle>
  </svg>{glabels}</div>'''

stats = [("Mean", "−53.4"), ("σ", "1.8"), ("Min / max", "−58 / −49"), ("Rate", "9.2 /s")]
stat_tiles = "".join(
    f'<div style="display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; background: #12151a; border-radius: 10px;"><div style="font-size: 11px; color: #8b92a0; font-weight: 600;">{k}</div><div class="mono" style="font-size: 14px; font-weight: 600;">{v}</div></div>'
    for k, v in stats)

cfg = [("room", "kitchen"), ("ref_rssi", "−65 dBm"), ("absorption", "2.7"), ("max_distance", "16 m")]
cfg_rows = "".join(
    f'<div style="display: flex; justify-content: space-between; align-items: center; height: 40px; border-bottom: 1px solid #22262f;"><div class="mono" style="font-size: 13px; color: #aeb4c0;">{k}</div><div class="mono" style="font-size: 13px; font-weight: 600;">{v}</div></div>'
    for k, v in cfg)

node_body = ROOT_OPEN + header("Kitchen", "hazri-kitchen · AtomS3 Lite · seen 0.3 s ago", back=True) + f'''
  <div style="display: flex; flex-direction: column; gap: 14px; padding: 4px 16px 16px; flex-grow: 1; overflow: hidden;">
    <div style="display: flex; align-items: flex-end; justify-content: space-between; padding: 0 4px;">
      <div style="display: flex; align-items: baseline; gap: 6px;">
        <div class="mono" style="font-size: 48px; font-weight: 600; color: #45d3c2; letter-spacing: -1.5px; line-height: 1;">−52</div>
        <div style="font-size: 13px; color: #8b92a0; font-weight: 700;">dBm</div>
      </div>
      <div style="display: flex; flex-direction: column; align-items: flex-end; gap: 2px;">
        <div class="mono" style="font-size: 20px; font-weight: 600;">1.1 m</div>
        <div style="font-size: 11px; color: #8b92a0; font-weight: 600;">est. distance</div>
      </div>
    </div>
    <div style="display: flex; flex-direction: column; gap: 10px; padding: 14px 16px 12px; background: #171a20; border: 1px solid #242933; border-radius: 14px;">
      <div style="display: flex; justify-content: space-between; font-size: 12px; font-weight: 700; color: #8b92a0;"><div>Last 60 s</div><div>smoothed · EMA 0.2</div></div>
      {chart}
      <div style="display: flex; justify-content: space-between; font-size: 10px; color: #6b7280; font-weight: 600;"><div>60 s ago</div><div>30 s</div><div>now</div></div>
      <div style="display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px;">{stat_tiles}</div>
    </div>
    <div style="display: flex; flex-direction: column; gap: 10px; padding: 14px 16px; background: #171a20; border: 1px solid #242933; border-radius: 14px;">
      <div style="display: flex; justify-content: space-between; align-items: center;">
        <div style="font-size: 12px; font-weight: 700; color: #8b92a0; letter-spacing: 0.6px; text-transform: uppercase;">Node config</div>
        <div style="font-size: 11px; font-weight: 700; color: #e5b85a;">not pushed</div>
      </div>
      <div style="display: flex; flex-direction: column;">{cfg_rows}</div>
      <div style="display: flex; align-items: center; justify-content: center; gap: 8px; height: 46px; border-radius: 12px; background: #45d3c2; color: #0f1115; font-size: 14px; font-weight: 800;">{icon("ruler", "#0f1115", 20)}<div>Calibrate at 1 m</div></div>
      <div style="display: flex; gap: 8px;">
        <div style="display: flex; align-items: center; justify-content: center; gap: 8px; height: 44px; flex-grow: 1; border-radius: 12px; border: 1px solid #2c313c; font-size: 13px; font-weight: 700; color: #eceef2;">{icon("copy", "#aeb4c0", 18)}<div>Copy config</div></div>
        <div style="display: flex; align-items: center; justify-content: center; gap: 8px; height: 44px; flex-grow: 1; border-radius: 12px; border: 1px solid #2c313c; font-size: 13px; font-weight: 700; color: #eceef2;">{icon("upload", "#aeb4c0", 18)}<div>Push via MQTT</div></div>
      </div>
    </div>
  </div>
''' + ROOT_CLOSE
pathlib.Path("NodeDetail.dc.html").write_text(page(node_body))

# ---------------- Tools ----------------
tools = [
    ("ruler", "Calibrate reference", "Stand 1 m from a node, capture ref_rssi"),
    ("beacon", "Beacon check", "Is this phone advertising? Interval, iBeacon UUID"),
    ("mqtt", "MQTT inspector", "Live messages from espresense/devices/…"),
    ("compare", "Compare sources", "Direct scan vs what nodes report, per node"),
    ("tag", "Nodes & rooms", "Aliases, room assignment, hide nodes"),
    ("export", "Export session", "CSV or JSON of every sample and survey"),
    ("settings", "Settings", "Broker, phone ID, smoothing window"),
]
trows = "".join(
    f'<div style="display: flex; align-items: center; gap: 14px; padding: 12px 14px; background: #171a20; border: 1px solid #242933; border-radius: 12px; min-height: 44px;">'
    f'<div style="width: 38px; height: 38px; border-radius: 10px; background: #12151a; display: flex; align-items: center; justify-content: center;">{icon(ic, "#45d3c2", 20)}</div>'
    f'<div style="display: flex; flex-direction: column; gap: 2px; flex-grow: 1;"><div style="font-size: 14px; font-weight: 700;">{t}</div><div style="font-size: 12px; color: #8b92a0; font-weight: 500;">{d}</div></div>'
    f'{icon("chevron", "#4d5462", 18)}</div>'
    for ic, t, d in tools)

tools_body = ROOT_OPEN + header("Tools") + f'''
  <div style="display: flex; flex-direction: column; gap: 10px; padding: 4px 16px 16px; flex-grow: 1; overflow: hidden;">
    <div style="display: flex; gap: 8px;">
      <div style="display: flex; align-items: center; gap: 8px; flex-grow: 1; padding: 10px 12px; background: #12211f; border: 1px solid #1f4a45; border-radius: 12px;">
        <div style="width: 8px; height: 8px; border-radius: 4px; background: #45d3c2;"></div>
        <div style="display: flex; flex-direction: column; gap: 1px;"><div style="font-size: 12px; font-weight: 700;">MQTT</div><div class="mono" style="font-size: 11px; color: #8b92a0;">10.0.0.12 · 42 msg/min</div></div>
      </div>
      <div style="display: flex; align-items: center; gap: 8px; flex-grow: 1; padding: 10px 12px; background: #12211f; border: 1px solid #1f4a45; border-radius: 12px;">
        <div style="width: 8px; height: 8px; border-radius: 4px; background: #45d3c2;"></div>
        <div style="display: flex; flex-direction: column; gap: 1px;"><div style="font-size: 12px; font-weight: 700;">Beacon</div><div class="mono" style="font-size: 11px; color: #8b92a0;">advertising · 100 ms</div></div>
      </div>
    </div>
    {trows}
  </div>
''' + nav("tools") + ROOT_CLOSE
pathlib.Path("Tools.dc.html").write_text(page(tools_body))

# ---------------- Low-fi alternate: light clinical ----------------
box = lambda h, w="100%", extra="": f'<div style="height: {h}px; width: {w}; border: 1.5px solid #9a9a9a; border-radius: 6px; background: #f4f4f4; {extra}"></div>'
alt_cards = "".join(
    f'<div style="display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1.5px solid #9a9a9a; border-radius: 8px; background: #ffffff;">'
    f'<div style="display: flex; justify-content: space-between; align-items: center;"><div style="font-size: 15px; font-weight: 700; color: #222;">{n}</div><div class="mono" style="font-size: 20px; color: #222;">{v}</div></div>'
    f'<div style="height: 8px; background: #e3e3e3; border-radius: 4px; overflow: hidden;"><div style="width: {p}%; height: 8px; background: #1f7a70;"></div></div></div>'
    for n, v, p in [("Kitchen", "−52", 80), ("Living room", "−61", 65), ("Hallway", "−68", 53), ("Bedroom", "−79", 35)])
alt_body = f'''<div style="position: relative; width: 390px; height: 844px; overflow: hidden; background: #fbfbfa; display: flex; flex-direction: column; font-family: 'Manrope', system-ui, sans-serif; color: #222;">
  <div style="padding: 20px 20px 8px; display: flex; flex-direction: column; gap: 4px;">
    <div style="font-size: 11px; font-weight: 800; color: #b45309; letter-spacing: 0.6px; text-transform: uppercase;">Alternate direction · light clinical (low-fi)</div>
    <div style="font-size: 24px; font-weight: 800;">Live signals</div>
    <div style="font-size: 13px; color: #666;">White surfaces, one dark teal, hairline borders. Better in sunlight, less glanceable in the dark.</div>
  </div>
  <div style="display: flex; flex-direction: column; gap: 10px; padding: 8px 16px; flex-grow: 1;">
    {box(36, extra="display: flex; align-items: center; padding: 0 12px; font-size: 13px; color: #444;")}
    {alt_cards}
  </div>
  <div style="display: flex; padding: 10px 12px 16px; border-top: 1.5px solid #9a9a9a; gap: 8px;">
    {box(40, "25%")}{box(40, "25%")}{box(40, "25%")}{box(40, "25%")}
  </div>
</div>'''
pathlib.Path("LightAlternate.dc.html").write_text(page(alt_body))
print("wrote", [p.name for p in pathlib.Path('.').glob('*.dc.html')])
