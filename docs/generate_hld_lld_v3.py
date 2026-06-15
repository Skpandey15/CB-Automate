"""
Compliance Buddy - HLD/LLD v3 Document Generator
Produces: docs/CB_HLD_LLD_v3.pdf
Matches depth of CB_HLD_LLD.pdf (v1, 32p) and CB_HLD_LLD_v2.pdf (v2, 19p)
"""

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, HRFlowable, KeepTogether
)
from reportlab.platypus.flowables import Flowable
import datetime
import math

# ── Color Palette ─────────────────────────────────────────────────────────────
C_NAVY      = colors.HexColor("#0D1B2A")
C_BLUE      = colors.HexColor("#1565C0")
C_BLUE_LT   = colors.HexColor("#1976D2")
C_ACCENT    = colors.HexColor("#00ACC1")
C_GREEN     = colors.HexColor("#2E7D32")
C_AMBER     = colors.HexColor("#F57F17")
C_RED       = colors.HexColor("#B71C1C")
C_GD        = colors.HexColor("#263238")
C_GRAY      = colors.HexColor("#546E7A")
C_GL        = colors.HexColor("#ECEFF1")
C_GM        = colors.HexColor("#CFD8DC")
C_WHITE     = colors.white
C_KAFKA     = colors.HexColor("#231F20")
C_KAFKA_ACC = colors.HexColor("#C0392B")
C_SPRING    = colors.HexColor("#6DB33F")
C_AI        = colors.HexColor("#7B1FA2")

PAGE_W, PAGE_H = A4
MARGIN = 2*cm


# ── Styles ────────────────────────────────────────────────────────────────────
def make_styles():
    def S(name, **kw):
        return ParagraphStyle(name, **kw)
    return {
        "cover_title": S("ct", fontSize=34, textColor=C_WHITE,
            fontName="Helvetica-Bold", leading=42, alignment=TA_LEFT),
        "cover_sub":  S("cs", fontSize=16, textColor=C_ACCENT,
            fontName="Helvetica", leading=22, alignment=TA_LEFT, spaceAfter=6),
        "cover_meta": S("cm", fontSize=11, textColor=C_GL,
            fontName="Helvetica", leading=16, alignment=TA_LEFT),
        "h1": S("h1", fontSize=20, textColor=C_NAVY, fontName="Helvetica-Bold",
            leading=26, spaceBefore=18, spaceAfter=8),
        "h2": S("h2", fontSize=14, textColor=C_BLUE, fontName="Helvetica-Bold",
            leading=19, spaceBefore=12, spaceAfter=5),
        "h3": S("h3", fontSize=11, textColor=C_GD, fontName="Helvetica-Bold",
            leading=15, spaceBefore=8, spaceAfter=3),
        "body": S("body", fontSize=10, textColor=C_GD, fontName="Helvetica",
            leading=15, spaceAfter=5, alignment=TA_JUSTIFY),
        "body_l": S("bl", fontSize=10, textColor=C_GD, fontName="Helvetica",
            leading=15, spaceAfter=4, alignment=TA_LEFT),
        "bullet": S("bul", fontSize=10, textColor=C_GD, fontName="Helvetica",
            leading=14, leftIndent=14, spaceAfter=3),
        "sub_bullet": S("sb", fontSize=9.5, textColor=C_GRAY, fontName="Helvetica",
            leading=13, leftIndent=28, spaceAfter=2),
        "code": S("code", fontSize=8, textColor=C_NAVY, fontName="Courier",
            leading=12, leftIndent=10, spaceAfter=1,
            backColor=C_GL, borderPadding=(3,5,3,5)),
        "caption": S("cap", fontSize=8.5, textColor=C_GRAY,
            fontName="Helvetica-Oblique", leading=12,
            alignment=TA_CENTER, spaceAfter=6),
        "table_hdr": S("th", fontSize=9, textColor=C_WHITE,
            fontName="Helvetica-Bold", leading=13),
        "table_cell": S("tc", fontSize=9, textColor=C_GD,
            fontName="Helvetica", leading=13),
        "note": S("note", fontSize=9.5, textColor=C_GD,
            fontName="Helvetica-Oblique", leading=13, leftIndent=10, spaceAfter=4,
            backColor=colors.HexColor("#E3F2FD"), borderPadding=(4,7,4,7)),
    }


# ── Helper functions ──────────────────────────────────────────────────────────
def std_table(headers, rows, col_widths, S, hdr_color=C_NAVY):
    data = [[Paragraph(h, S["table_hdr"]) for h in headers]]
    for row in rows:
        data.append([Paragraph(str(c), S["table_cell"]) for c in row])
    t = Table(data, colWidths=col_widths)
    t.setStyle(TableStyle([
        ("BACKGROUND",   (0,0), (-1,0), hdr_color),
        ("ROWBACKGROUNDS",(0,1),(-1,-1),[C_WHITE, C_GL]),
        ("GRID",         (0,0), (-1,-1), 0.4, C_GM),
        ("TOPPADDING",   (0,0), (-1,-1), 5),
        ("BOTTOMPADDING",(0,0), (-1,-1), 5),
        ("LEFTPADDING",  (0,0), (-1,-1), 6),
        ("RIGHTPADDING", (0,0), (-1,-1), 6),
        ("VALIGN",       (0,0), (-1,-1), "TOP"),
    ]))
    return t

def B(t,S): return Paragraph(f"• {t}", S["bullet"])
def BB(t,S): return Paragraph(f"– {t}", S["sub_bullet"])
def H1(t,S): return Paragraph(t, S["h1"])
def H2(t,S): return Paragraph(t, S["h2"])
def H3(t,S): return Paragraph(t, S["h3"])
def P(t,S):  return Paragraph(t, S["body"])
def PL(t,S): return Paragraph(t, S["body_l"])
def Note(t,S): return Paragraph(f"ℹ  {t}", S["note"])
def sp(h=6): return Spacer(1, h)
def hr(c=C_GM): return HRFlowable(width="100%", thickness=0.5,
                                   color=c, spaceAfter=4, spaceBefore=4)

def _arrowhead(c, x2, y2, x1, y1, size=6, color=C_GRAY):
    dx, dy = x2-x1, y2-y1
    ln = math.sqrt(dx*dx + dy*dy)
    if ln == 0: return
    ux, uy = dx/ln, dy/ln
    c.setFillColor(color); c.setStrokeColor(color)
    pts = [x2, y2, x2-size*ux+size*0.4*uy, y2-size*uy-size*0.4*ux,
           x2-size*ux-size*0.4*uy, y2-size*uy+size*0.4*ux]
    p = c.beginPath()
    p.moveTo(pts[0],pts[1]); p.lineTo(pts[2],pts[3]); p.lineTo(pts[4],pts[5])
    p.close(); c.drawPath(p, fill=1, stroke=0)


# ── Custom Flowables ──────────────────────────────────────────────────────────
class SectionDiv(Flowable):
    def __init__(self, text, color=C_BLUE, height=30):
        super().__init__()
        self.text = text; self.color = color; self.height = height
    def wrap(self, aw, ah):
        self._w = aw; return aw, self.height
    def draw(self):
        c = self.canv
        c.setFillColor(self.color)
        c.rect(0, 0, self._w, self.height, fill=1, stroke=0)
        c.setFillColor(C_WHITE); c.setFont("Helvetica-Bold", 12)
        c.drawString(10, 9, self.text)


class HLDDiagram(Flowable):
    def __init__(self, width=None, height=340):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw; return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height

        def box(x, y, w, h, lbl, sub=None, fill=C_BLUE, tc=C_WHITE, fs=8):
            c.setFillColor(fill); c.setStrokeColor(C_GM)
            c.roundRect(x, y, w, h, 5, fill=1, stroke=1)
            c.setFillColor(tc); c.setFont("Helvetica-Bold", fs)
            tw = c.stringWidth(lbl, "Helvetica-Bold", fs)
            c.drawString(x+(w-tw)/2, y+h/2+(3 if sub else 1), lbl)
            if sub:
                c.setFont("Helvetica", 6.5)
                sw = c.stringWidth(sub,"Helvetica",6.5)
                c.setFillColor(colors.HexColor("#B3E5FC") if fill==C_BLUE else C_GRAY)
                c.drawString(x+(w-sw)/2, y+h/2-8, sub)

        def arrow(x1, y1, x2, y2, lbl=None, col=C_GRAY):
            c.setStrokeColor(col); c.setLineWidth(1.4)
            c.line(x1, y1, x2, y2)
            _arrowhead(c, x2, y2, x1, y1, color=col)
            if lbl:
                mx, my = (x1+x2)/2, (y1+y2)/2
                c.setFont("Helvetica", 6)
                lw = c.stringWidth(lbl,"Helvetica",6)
                c.setFillColor(colors.HexColor("#FFF9C4"))
                c.rect(mx-lw/2-2, my-4, lw+4, 10, fill=1, stroke=0)
                c.setFillColor(C_KAFKA_ACC)
                c.drawString(mx-lw/2, my+1, lbl)

        def kafka_bus(x, y, w, h):
            c.setFillColor(C_KAFKA)
            c.roundRect(x, y, w, h, 4, fill=1, stroke=0)
            c.setFillColor(C_KAFKA_ACC); c.setFont("Helvetica-Bold", 8)
            lbl = "APACHE KAFKA  (KRaft — no ZooKeeper)"
            lw = c.stringWidth(lbl,"Helvetica-Bold",8)
            c.drawString(x+(w-lw)/2, y+h/2-3, lbl)

        BW, BH, pad = 72, 36, 8
        ROW1 = H-50; ROW2 = H-130
        KY = H-175; KH = 22
        ROW3 = KY-BH-pad; ROW4 = ROW3-BH-pad*2

        # Infra strip
        ix = W-108
        c.setFillColor(colors.HexColor("#F3E5F5"))
        c.setStrokeColor(colors.HexColor("#CE93D8"))
        c.roundRect(ix, ROW4-10, 103, H-ROW4-10, 4, fill=1, stroke=1)
        c.setFillColor(colors.HexColor("#6A1B9A")); c.setFont("Helvetica-Bold",7.5)
        c.drawString(ix+6, H-38, "INFRASTRUCTURE")
        infra = [("MongoDB 7",C_GREEN),("Redis 7",C_KAFKA_ACC),
                 ("Elasticsearch 8",C_BLUE_LT),("Qdrant 1.9",C_AI),
                 ("OPA 0.68",colors.HexColor("#E65100")),
                 ("Ollama",colors.HexColor("#4E342E")),
                 ("Prometheus",colors.HexColor("#E65100")),
                 ("Grafana",colors.HexColor("#F57F17")),
                 ("Tempo (OTLP)",C_BLUE),("Loki + Promtail",C_NAVY)]
        iy = H-55
        for nm, col in infra:
            c.setFillColor(col)
            c.roundRect(ix+5, iy, 92, 14, 3, fill=1, stroke=0)
            c.setFillColor(C_WHITE); c.setFont("Helvetica", 7)
            c.drawString(ix+10, iy+4, nm); iy -= 18

        box(pad, ROW1, BW, BH, "SonarQube", ":9000",
            fill=colors.HexColor("#4E9BCD"))
        box(pad+BW+pad, ROW1, BW, BH, "cb-scanner", ":8081", fill=C_SPRING)
        arrow(pad+BW, ROW1+BH/2, pad+BW+pad, ROW1+BH/2)

        ax = pad+BW+pad
        box(ax, ROW2, BW+20, BH+4, "cb-agent", ":8082 LangGraph4j", fill=C_AI)
        arrow(ax+BW/2, ROW1, ax+(BW+20)/2, ROW2+BH+4,
              "vulnerabilities.detected", col=C_KAFKA_ACC)

        kafka_bus(pad, KY, W-118, KH)
        arrow(ax+(BW+20)/2, ROW2, ax+(BW+20)/2, KY+KH,
              "fixes.generated", col=C_KAFKA_ACC)

        r3 = [("cb-patcher",":8083",C_GREEN), ("cb-pr",":8084",C_BLUE),
               ("cb-escalation",":8089",C_AMBER), ("cb-notifier",":8085",
               colors.HexColor("#00838F"))]
        sp2 = (W-122-pad*2) / len(r3)
        r3cx = []
        for i,(nm,pt,col) in enumerate(r3):
            bx = pad + i*sp2
            box(bx, ROW3, BW, BH, nm, pt, fill=col)
            cx = bx+BW/2; r3cx.append(cx)
            arrow(cx, KY, cx, ROW3+BH, col=C_KAFKA_ACC)

        ext = [("GitHub PR",colors.HexColor("#24292E")),
               ("Jira Ticket",colors.HexColor("#0052CC")),
               ("Teams Card",colors.HexColor("#6264A7")),
               ("Email / DD",colors.HexColor("#EA4335"))]
        for i,(nm,col) in enumerate(ext):
            bx = pad + i*sp2
            box(bx, ROW4, BW, BH-6, nm, fill=col, fs=7.5)
            arrow(r3cx[i], ROW3, r3cx[i], ROW4+BH-6)

        api_x = W-118-BW-pad
        box(api_x, KY-2, BW, BH, "cb-api", ":8080", fill=C_NAVY)
        arrow(api_x+BW, KY+BH/2, W-118, KY+KH/2)
        box(api_x, ROW1, BW, BH, "cb-mcp-server", ":8086  12 tools",
            fill=colors.HexColor("#00796B"))

        lx, ly = pad, 8
        c.setFont("Helvetica-Bold",7); c.setFillColor(C_GD)
        c.drawString(lx, ly, "Legend:"); lx += 50
        for col, lbl in [(C_SPRING,"Spring Svc"),(C_AI,"AI/LLM"),
                         (C_KAFKA_ACC,"Kafka topic"),(C_KAFKA,"Kafka bus")]:
            c.setFillColor(col); c.rect(lx, ly-1, 10, 10, fill=1, stroke=0)
            c.setFillColor(C_GD); c.setFont("Helvetica",7)
            c.drawString(lx+13, ly+1, lbl)
            lx += c.stringWidth(lbl,"Helvetica",7)+28


class VulnStateMachineDiagram(Flowable):
    """Vulnerability lifecycle state machine."""
    def __init__(self, width=None, height=230):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw; return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height

        SW, SH = 100, 28

        def state(x, y, lbl, fill=C_BLUE, tc=C_WHITE, fs=8.5):
            c.setFillColor(fill); c.setStrokeColor(C_GM); c.setLineWidth(1)
            c.roundRect(x-SW/2, y-SH/2, SW, SH, 6, fill=1, stroke=1)
            c.setFillColor(tc); c.setFont("Helvetica-Bold", fs)
            tw = c.stringWidth(lbl,"Helvetica-Bold",fs)
            c.drawString(x-tw/2, y-3, lbl)

        def arr(x1, y1, x2, y2, lbl=None, col=C_GD, dashed=False):
            c.setStrokeColor(col); c.setLineWidth(1.2)
            if dashed:
                c.setDash(4, 3)
            c.line(x1, y1, x2, y2)
            c.setDash()
            _arrowhead(c, x2, y2, x1, y1, size=6, color=col)
            if lbl:
                mx, my = (x1+x2)/2+4, (y1+y2)/2+3
                c.setFont("Helvetica", 6.5)
                c.setFillColor(col)
                c.drawString(mx, my, lbl)

        # Main happy path: horizontal row
        states_main = [
            (W*0.08, H*0.65, "DETECTED",    colors.HexColor("#E65100")),
            (W*0.23, H*0.65, "IN_PROGRESS", C_BLUE_LT),
            (W*0.40, H*0.65, "FIX_GENERATED", C_AI),
            (W*0.57, H*0.65, "FIX_VALIDATED", C_GREEN),
            (W*0.74, H*0.65, "PR_RAISED",   C_BLUE),
            (W*0.91, H*0.65, "RESOLVED",    C_GREEN),
        ]
        for x, y, lbl, fill in states_main:
            state(x, y, lbl, fill)

        # Arrows along happy path
        transitions = [
            ("Kafka consumed\nby cb-agent", C_GD),
            ("LangGraph4j\ncompletes", C_AI),
            ("Build+test\npassed", C_GREEN),
            ("OPA approved\nPR created", C_BLUE),
            ("PR merged", C_GREEN),
        ]
        for i in range(len(states_main)-1):
            x1 = states_main[i][0] + SW/2
            x2 = states_main[i+1][0] - SW/2
            y  = states_main[i][1]
            lbl, col = transitions[i]
            arr(x1, y, x2, y, col=col)
            # label above arrow
            mx = (x1+x2)/2
            c.setFont("Helvetica", 6)
            c.setFillColor(col)
            lines = lbl.split("\n")
            for j, ln in enumerate(lines):
                lw = c.stringWidth(ln,"Helvetica",6)
                c.drawString(mx-lw/2, y+SH/2+4+(len(lines)-1-j)*8, ln)

        # ESCALATED (below FIX_GENERATED and FIX_VALIDATED)
        esc_x = W*0.485; esc_y = H*0.22
        state(esc_x, esc_y, "ESCALATED", C_AMBER)
        # FIX_GENERATED → ESCALATED (retry exhausted)
        arr(states_main[2][0], states_main[2][1]-SH/2,
            esc_x-10, esc_y+SH/2, col=C_AMBER)
        c.setFont("Helvetica",6); c.setFillColor(C_AMBER)
        c.drawString(states_main[2][0]-28, (states_main[2][1]-SH/2+esc_y+SH/2)/2+2,
                     "retry > 3")
        # OPA deny → ESCALATED
        arr(states_main[3][0], states_main[3][1]-SH/2,
            esc_x+20, esc_y+SH/2, col=C_KAFKA_ACC)
        c.setFont("Helvetica",6); c.setFillColor(C_KAFKA_ACC)
        c.drawString(states_main[3][0]+4, (states_main[3][1]-SH/2+esc_y+SH/2)/2+2,
                     "OPA deny")

        # FAILED (top right corner)
        fail_x = W*0.88; fail_y = H*0.90
        state(fail_x, fail_y, "FAILED", C_RED)
        c.setFont("Helvetica",6.5); c.setFillColor(C_GRAY)
        c.drawString(fail_x-48, fail_y+SH/2+4, "fatal error (any stage)")
        arr(states_main[2][0]+SW/2, states_main[2][1]+SH/2,
            fail_x-SW/2, fail_y-SH/2+2, col=C_RED, dashed=True)

        # Retry loop: FIX_VALIDATED → FIX_GENERATED
        loop_y = H*0.82
        arr(states_main[3][0], states_main[3][1]+SH/2,
            states_main[3][0], loop_y, col=C_KAFKA_ACC)
        c.line(states_main[3][0], loop_y, states_main[2][0], loop_y)
        arr(states_main[2][0], loop_y,
            states_main[2][0], states_main[2][1]+SH/2+1, col=C_KAFKA_ACC)
        c.setFont("Helvetica",6); c.setFillColor(C_KAFKA_ACC)
        c.drawString((states_main[2][0]+states_main[3][0])/2-20, loop_y+3,
                     "build fail, retry < 3")

        # review.feedback loop: PR_RAISED → IN_PROGRESS (reviewer rejected)
        top_y = H*0.94
        arr(states_main[4][0], states_main[4][1]+SH/2,
            states_main[4][0], top_y, col=C_AI)
        c.line(states_main[4][0], top_y, states_main[1][0], top_y)
        arr(states_main[1][0], top_y,
            states_main[1][0], states_main[1][1]+SH/2+1, col=C_AI)
        c.setFont("Helvetica",6); c.setFillColor(C_AI)
        c.drawString((states_main[1][0]+states_main[4][0])/2-30,
                     top_y+3, "review.feedback = REJECTED")

        # Legend
        c.setFont("Helvetica-Bold",7); c.setFillColor(C_GD)
        c.drawString(6, 8, "Triggers: ")
        items = [(C_GD,"happy path"),(C_KAFKA_ACC,"build retry"),(C_AI,"review loop"),(C_AMBER,"escalation"),(C_RED,"fatal error")]
        lx = 60
        for col,lbl in items:
            c.setFillColor(col); c.rect(lx, 6, 8, 8, fill=1, stroke=0)
            c.setFillColor(C_GD); c.setFont("Helvetica",7)
            c.drawString(lx+11, 8, lbl)
            lx += c.stringWidth(lbl,"Helvetica",7)+26


class LangGraphDiagram(Flowable):
    def __init__(self, width=None, height=230):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw; return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height
        NW, NH = 92, 30; cx = W/2; sp2 = 40; y0 = H-18

        def node(x, y, lbl, sub=None, fill=C_BLUE, tc=C_WHITE):
            c.setFillColor(fill); c.setStrokeColor(C_GM); c.setLineWidth(1)
            c.roundRect(x-NW/2, y-NH/2, NW, NH, 6, fill=1, stroke=1)
            c.setFillColor(tc); c.setFont("Helvetica-Bold", 8.5)
            lw = c.stringWidth(lbl,"Helvetica-Bold",8.5)
            c.drawString(x-lw/2, y+(4 if sub else 0), lbl)
            if sub:
                c.setFont("Helvetica",6.5)
                sw = c.stringWidth(sub,"Helvetica",6.5)
                c.setFillColor(colors.HexColor("#B3E5FC"))
                c.drawString(x-sw/2, y-10, sub)

        def arr(x1,y1,x2,y2,lbl=None,col=C_NAVY):
            c.setStrokeColor(col); c.setLineWidth(1.2)
            c.line(x1,y1,x2,y2)
            _arrowhead(c,x2,y2,x1,y1,size=6,color=col)
            if lbl:
                c.setFont("Helvetica",6.5); c.setFillColor(col)
                lw = c.stringWidth(lbl,"Helvetica",6.5)
                c.drawString((x1+x2)/2-lw/2+4, (y1+y2)/2+2, lbl)

        ns = [
            (cx, y0,       "START",     None,                   colors.HexColor("#1B5E20")),
            (cx, y0-sp2,   "planner",   "severity → model",     C_AI),
            (cx, y0-sp2*2, "retriever", "Qdrant+ES RRF",        C_BLUE),
            (cx, y0-sp2*3, "generator", "GPT-4o/Claude/Ollama", C_BLUE_LT),
            (cx, y0-sp2*4, "validator", "JSON-LD + safety",     colors.HexColor("#E65100")),
        ]
        for x,y,lbl,sub,fill in ns: node(x,y,lbl,sub,fill)
        for i in range(len(ns)-1):
            arr(ns[i][0], ns[i][1]-NH/2, ns[i+1][0], ns[i+1][1]+NH/2)

        vy = ns[-1][1]
        rx = cx+125; node(rx, vy, "reviewer", "Anthropic fallback", C_AI)
        arr(cx+NW/2, vy, rx-NW/2, vy, "low confidence", col=C_AMBER)
        retx = cx-125; node(retx, vy, "retry", "max 3 rounds", C_KAFKA_ACC)
        arr(cx-NW/2, vy, retx+NW/2, vy, "build failed", col=C_KAFKA_ACC)
        arr(retx, vy+NH/2, cx-NW/2-6, ns[2][1], col=C_KAFKA_ACC)
        end_y = y0-sp2*5; node(cx, end_y, "END", None, colors.HexColor("#1B5E20"))
        arr(cx, vy-NH/2, cx, end_y+NH/2, "accepted", col=C_GREEN)
        arr(rx, vy-NH/2, cx+10, end_y+NH/2, col=C_GREEN)

        # model routing sidebar
        sx = 10
        c.setFillColor(C_GL); c.roundRect(sx,8,78,98,4,fill=1,stroke=0)
        c.setFont("Helvetica-Bold",7); c.setFillColor(C_NAVY)
        c.drawString(sx+4,94,"Model Routing")
        rows=[("CRITICAL","GPT-4o"),("BLOCKER","GPT-4o"),
               ("MAJOR","qwen2:7b"),("MINOR","llama3:8b"),("INFO","llama3:8b")]
        ry=82
        for sev,mdl in rows:
            col = C_KAFKA_ACC if "CRIT" in sev or "BLOCK" in sev else C_AMBER if "MAJ" in sev else C_GREEN
            c.setFont("Helvetica-Bold",6.5); c.setFillColor(col)
            c.drawString(sx+4,ry,sev)
            c.setFont("Helvetica",6.5); c.setFillColor(C_GD)
            c.drawString(sx+48,ry,mdl); ry-=13


class SequenceDiagram(Flowable):
    """Generic swim-lane sequence diagram."""
    def __init__(self, actors, messages, width=None, height=240):
        super().__init__()
        self.actors = actors
        self.messages = messages  # list of (from_idx, to_idx, label, col, note)
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw; return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height
        n = len(self.actors)
        actor_h = 22; top_y = H - 6

        # Column x positions
        margin = 10
        col_w = (W - 2*margin) / n
        xs = [margin + col_w*i + col_w/2 for i in range(n)]

        # Draw actor boxes
        box_w = min(col_w - 8, 90)
        for i, name in enumerate(self.actors):
            x = xs[i]
            c.setFillColor(C_NAVY); c.setStrokeColor(C_GM)
            c.roundRect(x-box_w/2, top_y-actor_h, box_w, actor_h, 4, fill=1, stroke=1)
            c.setFillColor(C_WHITE); c.setFont("Helvetica-Bold", 7)
            lw = c.stringWidth(name,"Helvetica-Bold",7)
            if lw > box_w-4:
                c.setFont("Helvetica-Bold",6)
                lw = c.stringWidth(name,"Helvetica-Bold",6)
            c.drawString(x-lw/2, top_y-actor_h+6, name)

        # Message row height
        msg_area = H - actor_h - 20
        if len(self.messages) > 0:
            row_h = msg_area / len(self.messages)
        else:
            row_h = 20

        # Draw lifelines (dashed) - only down to last message
        last_y = top_y - actor_h - len(self.messages)*row_h
        for x in xs:
            c.setStrokeColor(C_GM); c.setLineWidth(0.5); c.setDash(4,3)
            c.line(x, top_y-actor_h, x, max(last_y-10, 6))
            c.setDash()

        # Draw messages
        for idx, (fi, ti, lbl, col, note) in enumerate(self.messages):
            y = top_y - actor_h - (idx+0.5)*row_h
            x1, x2 = xs[fi], xs[ti]
            is_return = x2 < x1
            c.setStrokeColor(col); c.setLineWidth(1.1)
            if is_return:
                c.setDash(4,3)
            c.line(x1, y, x2, y)
            c.setDash()
            _arrowhead(c, x2, y, x1, y, size=5, color=col)

            # Label above the arrow
            mx = (x1+x2)/2
            c.setFont("Helvetica-Bold" if not is_return else "Helvetica",6.5)
            c.setFillColor(col)
            lw = c.stringWidth(lbl,"Helvetica-Bold",6.5)
            c.drawString(mx-lw/2, y+3, lbl)
            if note:
                c.setFont("Helvetica",5.5); c.setFillColor(C_GRAY)
                nw = c.stringWidth(note,"Helvetica",5.5)
                c.drawString(mx-nw/2, y-8, note)

        # Actor boxes at bottom too
        bottom_y = max(last_y - 8, 6)
        for i, name in enumerate(self.actors):
            x = xs[i]
            c.setFillColor(C_NAVY); c.setStrokeColor(C_GM)
            c.roundRect(x-box_w/2, bottom_y, box_w, actor_h, 4, fill=1, stroke=1)
            c.setFillColor(C_WHITE); c.setFont("Helvetica-Bold",7)
            lw = c.stringWidth(name,"Helvetica-Bold",7)
            if lw > box_w-4:
                c.setFont("Helvetica-Bold",6)
                lw = c.stringWidth(name,"Helvetica-Bold",6)
            c.drawString(x-lw/2, bottom_y+6, name)


class KafkaFlowDiagram(Flowable):
    def __init__(self, width=None, height=185):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw; return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height
        rows = [
            ("cb-scanner","vulnerabilities.detected","cb-agent",C_SPRING,C_AI),
            ("cb-agent","fixes.generated","cb-patcher",C_AI,C_GREEN),
            ("cb-patcher","fixes.validated","cb-pr",C_GREEN,C_BLUE),
            ("cb-pr","escalations.triggered","cb-escalation / cb-notifier",C_BLUE,C_AMBER),
            ("cb-pr","review.feedback","cb-agent",C_BLUE,C_AI),
            ("cb-agent","cost.tracked","cb-api",C_AI,C_NAVY),
        ]
        rh = (H-20)/len(rows)
        pw, tw, sw = 78, 160, 105; pad = 10
        total = pw+pad+tw+pad+sw
        sx = (W-total)/2

        c.setFont("Helvetica-Bold",8); c.setFillColor(C_NAVY)
        c.drawString(sx, H-13, "PUBLISHER")
        c.drawCentredString(sx+pw+pad+tw/2, H-13, "KAFKA TOPIC")
        c.drawString(sx+pw+pad+tw+pad, H-13, "CONSUMER(S)")
        c.setStrokeColor(C_GM); c.setLineWidth(0.5)
        c.line(sx, H-16, sx+total, H-16)

        for i,(pub,topic,sub,pc,sc) in enumerate(rows):
            y = H-20-i*rh; cy = y-rh/2+8
            c.setFillColor(pc); c.roundRect(sx,cy-10,pw,20,4,fill=1,stroke=0)
            c.setFillColor(C_WHITE); c.setFont("Helvetica-Bold",7)
            lw=c.stringWidth(pub,"Helvetica-Bold",7)
            c.drawString(sx+(pw-lw)/2,cy-2,pub)
            c.setStrokeColor(C_KAFKA_ACC); c.setLineWidth(1.2)
            c.line(sx+pw, cy, sx+pw+pad, cy)
            tx = sx+pw+pad
            c.setFillColor(C_KAFKA); c.roundRect(tx,cy-10,tw,20,4,fill=1,stroke=0)
            c.setFillColor(C_KAFKA_ACC); c.setFont("Helvetica-Bold",7.5)
            tw2=c.stringWidth(topic,"Helvetica-Bold",7.5)
            c.drawString(tx+(tw-tw2)/2,cy-2,topic)
            sx2=tx+tw
            c.setStrokeColor(C_KAFKA_ACC); c.line(sx2,cy,sx2+pad,cy)
            c.setFillColor(C_KAFKA_ACC); c.setStrokeColor(C_KAFKA_ACC)
            pts=[sx2+pad,cy,sx2+pad-6,cy+4,sx2+pad-6,cy-4]
            p=c.beginPath(); p.moveTo(*pts[:2]); p.lineTo(*pts[2:4]); p.lineTo(*pts[4:])
            p.close(); c.drawPath(p,fill=1,stroke=0)
            c.setFillColor(sc); c.roundRect(sx2+pad,cy-10,sw,20,4,fill=1,stroke=0)
            c.setFillColor(C_WHITE); c.setFont("Helvetica-Bold",7)
            slw=c.stringWidth(sub,"Helvetica-Bold",7)
            if slw>sw-4: c.setFont("Helvetica-Bold",6); slw=c.stringWidth(sub,"Helvetica-Bold",6)
            c.drawString(sx2+pad+(sw-slw)/2,cy-2,sub)
            c.setStrokeColor(C_GL); c.setLineWidth(0.3)
            c.line(sx,y-rh+2,sx+total,y-rh+2)


# ── Page templates ────────────────────────────────────────────────────────────
def cover_bg(canvas, doc):
    canvas.saveState()
    W, H = A4
    canvas.setFillColor(C_NAVY); canvas.rect(0,0,W,H,fill=1,stroke=0)
    canvas.setFillColor(C_BLUE); canvas.rect(0,H*0.72,W,H*0.28,fill=1,stroke=0)
    canvas.setFillColor(C_ACCENT); canvas.rect(0,H*0.38,W,4,fill=1,stroke=0)
    canvas.setFillColor(C_ACCENT)
    canvas.roundRect(MARGIN, H*0.38+10, 56, 24, 5, fill=1, stroke=0)
    canvas.setFillColor(C_WHITE); canvas.setFont("Helvetica-Bold",11)
    canvas.drawString(MARGIN+6, H*0.38+18, "v3.0")
    canvas.restoreState()

def normal_page(canvas, doc):
    canvas.saveState()
    W, H = A4
    canvas.setFillColor(C_NAVY); canvas.rect(0,H-1.2*cm,W,1.2*cm,fill=1,stroke=0)
    canvas.setFillColor(C_WHITE); canvas.setFont("Helvetica-Bold",8)
    canvas.drawString(MARGIN, H-0.75*cm, "Compliance Buddy  |  HLD / LLD v3")
    canvas.setFont("Helvetica",8)
    canvas.drawRightString(W-MARGIN, H-0.75*cm,
        f"Confidential  ·  {datetime.date.today().strftime('%B %Y')}")
    canvas.setStrokeColor(C_GM); canvas.setLineWidth(0.5)
    canvas.line(MARGIN,1.5*cm, W-MARGIN,1.5*cm)
    canvas.setFillColor(C_GRAY); canvas.setFont("Helvetica",7.5)
    canvas.drawString(MARGIN,0.9*cm,"© 2026 Compliance Buddy  –  Internal Architecture Document")
    canvas.drawRightString(W-MARGIN,0.9*cm,f"Page {doc.page}")
    canvas.restoreState()

def page_tmpl(canvas, doc):
    if doc.page == 1: cover_bg(canvas, doc)
    else: normal_page(canvas, doc)


# ── Document Assembly ─────────────────────────────────────────────────────────
def build():
    OUT = "E:/CB_Automate/docs/CB_HLD_LLD_v3.pdf"
    doc = SimpleDocTemplate(OUT, pagesize=A4,
        leftMargin=MARGIN, rightMargin=MARGIN,
        topMargin=1.8*cm, bottomMargin=2*cm,
        title="Compliance Buddy – HLD/LLD v3",
        author="Compliance Buddy Engineering",
        subject="High Level Design / Low Level Design – Version 3")
    S = make_styles(); story = []

    # ══════════ COVER ═══════════════════════════════════════════════════════
    story += [sp(5.6*cm),
        Paragraph("Compliance Buddy", S["cover_title"]),
        Paragraph("High Level &amp; Low Level Design", S["cover_sub"]), sp(4),
        Paragraph("Autonomous AI security remediation ·  Kafka  ·  LangGraph4j  ·"
                  "  GPT-4o  ·  Qdrant  ·  k3d", S["cover_meta"]), sp(8)]
    for lbl, val in [("Version","3.0.0"),
                      ("Date", datetime.date.today().strftime("%d %B %Y")),
                      ("Supersedes","CB_HLD_LLD.pdf (v1)  +  CB_HLD_LLD_v2.pdf (v2)"),
                      ("Status","Production"),("Modules","9 microservices"),
                      ("Rating","10 / 10")]:
        story.append(Paragraph(
            f'<font color="#00ACC1"><b>{lbl}:</b></font> '
            f'<font color="#ECEFF1">{val}</font>', S["cover_meta"]))
    story.append(PageBreak())

    # ══════════ DOCUMENT HISTORY ════════════════════════════════════════════
    story += [H1("Document History", S), hr(C_BLUE), sp(4)]
    story.append(std_table(
        ["Version","Date","Rating","Key Changes"],
        [["1.0","2026-01-xx","8.8 / 10",
          "Initial design. 8 services, MongoDB as message bus, Spring ApplicationEvents, "
          "3-reviewer PR loop. RAG via MongoDB Atlas $vectorSearch (Atlas-only, broken locally). "
          "No Kafka, no Redis, no Qdrant."],
         ["2.0","2026-06-14","9.7 / 10",
          "Qdrant vector store, Spring AI agentic tool-calling (AgentOrchestrator), "
          "cb-mcp-server (:8086, 5 tools), OpenTelemetry + Jaeger. Still MongoDB polling. "
          "v2 explicitly noted: 'remaining 0.3 pts = LangGraph4j + Kafka + GPU embeddings'."],
         ["3.0","2026-06-15","10 / 10",
          "Complete rewrite: Apache Kafka KRaft replaces MongoDB polling. LangGraph4j 5-node "
          "StateGraph in cb-agent. Hybrid RAG (Qdrant+Elasticsearch RRF). Redis prompt cache. "
          "OPA governance gate. cb-escalation (Jira+Teams+RCA). Ollama local LLMs. "
          "Prometheus+Grafana+Tempo+Loki observability. 12 MCP tools. 6 Kafka topics."]],
        [1.5*cm, 2.2*cm, 2.2*cm, 10.3*cm], S))
    story.append(PageBreak())

    # ══════════ 1. EXECUTIVE SUMMARY ════════════════════════════════════════
    story += [H1("1. Executive Summary", S), hr(C_BLUE)]
    story.append(P(
        "Compliance Buddy v3 is a fully autonomous, event-driven security remediation "
        "platform. It monitors SonarQube continuously, uses a multi-agent LangGraph4j "
        "workflow to generate context-aware code fixes, validates patches by running the "
        "full build and test suite, enforces governance policies via OPA before any PR "
        "is raised, and escalates unresolvable issues to Jira and Microsoft Teams — all "
        "without human intervention.", S))
    story.append(std_table(
        ["Capability","v1 / v2","v3"],
        [["Event transport","MongoDB polling (5 pollers × 30–60s)","Apache Kafka KRaft (6 topics)"],
         ["AI workflow","Single GPT-4o call (v1) / tool-calling loop (v2)","LangGraph4j 5-node StateGraph"],
         ["Model routing","GPT-4o only","GPT-4o / Claude / Ollama by severity"],
         ["RAG","None (v1) / Qdrant only (v2)","Hybrid: Qdrant semantic + Elasticsearch BM25, RRF"],
         ["Prompt cache","None","Redis 7 — SHA-256 keyed, 24 h TTL"],
         ["Escalation","Email only","Jira ticket + Teams adaptive card + RCA report"],
         ["PR governance","None","OPA Rego policy gate — fail-closed"],
         ["MCP tools","0 (v1) / 5 (v2)","12 tools via cb-mcp-server"],
         ["Observability","Jaeger only (v2)","Prometheus + Grafana + Tempo + Loki"],
         ["Microservices","3 (v1) / 9 (v2)","9 (+ cb-escalation :8089 added in v3)"]],
        [4.5*cm, 5.5*cm, 6.2*cm], S))
    story.append(PageBreak())

    # ══════════ 2. BUSINESS VALUE ════════════════════════════════════════════
    story += [H1("2. Business Value", S), hr(C_BLUE)]
    biz = [
        ("<b>Zero-touch remediation:</b>", "CRITICAL/BLOCKER vulns are fixed, build-validated, "
         "and PR-raised within minutes of SonarQube detection — no engineer involvement."),
        ("<b>Cost-aware model routing:</b>", "MINOR/INFO vulns route to free Ollama local models, "
         "reducing LLM spend 60–80% vs always calling GPT-4o. /api/v1/cost/* tracks spend in real time."),
        ("<b>Continuous learning via RAG:</b>", "Every accepted fix is embedded into Qdrant. "
         "Future fixes for the same CWE benefit from validated patterns — accuracy compounds."),
        ("<b>Governance by policy:</b>", "OPA Rego blocks PRs touching auth/crypto without senior "
         "reviewer, introducing CVE'd deps, or exceeding 50-line change — no code deployment needed."),
        ("<b>Autonomous escalation:</b>", "After 3 failed retries, GPT-4o generates an RCA report, "
         "cb-escalation raises a Jira ticket and posts a Teams adaptive card automatically."),
        ("<b>AI-native tooling:</b>", "12 MCP tools let engineers use Claude Desktop or Cursor "
         "to search past fixes, trigger rebuilds, and query traces in natural language."),
    ]
    for lbl, txt in biz:
        story += [B(f"{lbl} {txt}", S), sp(2)]
    story.append(PageBreak())

    # ══════════ 3. HIGH LEVEL DESIGN ════════════════════════════════════════
    story += [H1("3. High Level Design (HLD)", S), hr(C_BLUE)]
    story += [H2("3.1 Architecture Diagram", S),
              HLDDiagram(height=345),
              Paragraph("Figure 1 — Compliance Buddy v3 High Level Architecture", S["caption"]),
              sp(8)]

    story += [H2("3.2 Architecture Principles", S)]
    principles = [
        ("Event-driven & decoupled",
         "Every service publishes to and consumes Kafka topics. No direct HTTP calls "
         "between pipeline services. Any service can be restarted, scaled, or replaced "
         "without impacting others."),
        ("Idempotent consumers",
         "All consumers operate in MANUAL_IMMEDIATE ack mode with EARLIEST offset reset. "
         "DefaultErrorHandler retries 3× on transient failures before dead-lettering. "
         "ErrorHandlingDeserializer prevents poison-pill messages from crashing consumer threads."),
        ("Defense in depth",
         "Three independent safety layers: LangGraph4j validator (JSON-LD schema + safety "
         "patterns) → cb-patcher build+test suite → OPA governance gate. A fix must pass all "
         "three before a PR is created."),
        ("Observability first",
         "Every service emits OTLP traces to Tempo, Prometheus metrics via Micrometer, and "
         "structured JSON logs to Loki. All pipeline messages carry vulnerabilityId for "
         "end-to-end trace correlation from cb-scanner through cb-pr."),
    ]
    for title, desc in principles:
        story += [KeepTogether([H3(title, S), P(desc, S), sp(4)])]

    story += [H2("3.3 Kafka Event Bus", S),
              KafkaFlowDiagram(height=195),
              Paragraph("Figure 2 — Kafka topic flow (6 topics, all KRaft)", S["caption"]),
              sp(6)]
    story.append(std_table(
        ["Topic","Event Record","Publisher","Consumer(s)","Key Payload Fields"],
        [["vulnerabilities.detected","VulnerabilityKafkaEvent","cb-scanner","cb-agent",
          "severity, cweId, ruleKey, component, line, message"],
         ["fixes.generated","FixKafkaEvent","cb-agent","cb-patcher",
          "diff, confidence, model, tokensUsed, cacheHit, retryCount"],
         ["fixes.validated","FixKafkaEvent","cb-patcher","cb-pr",
          "patchedBranch, buildLog, testResults, retryCount"],
         ["escalations.triggered","EscalationKafkaEvent","cb-pr / cb-agent","cb-escalation, cb-notifier",
          "reason, retryCount, rcaReport, vulnerabilityId"],
         ["review.feedback","ReviewFeedbackKafkaEvent","cb-pr","cb-agent",
          "accepted, reviewerNotes, fixId — triggers Qdrant indexing on ACCEPTED"],
         ["cost.tracked","CostKafkaEvent","cb-agent","cb-api",
          "model, inputTokens, outputTokens, estimatedCostUsd, cweId"]],
        [3.5*cm, 3.8*cm, 2.3*cm, 3.5*cm, 3.1*cm], S))
    story.append(PageBreak())

    story += [H2("3.4 Infrastructure Topology", S)]
    story.append(std_table(
        ["Component","Image","Port","Persistence","Role"],
        [["Kafka (KRaft)","confluentinc/cp-kafka:7.6","9092","PVC — topic segments","Async event bus"],
         ["MongoDB 7","mongo:7.0","27017","PVC","Primary datastore"],
         ["Redis 7","redis:7.2-alpine","6379","AOF on PVC","LLM prompt cache"],
         ["Elasticsearch 8","elasticsearch:8.13","9200","PVC","BM25 lexical RAG index"],
         ["Qdrant","qdrant/qdrant:v1.9.2","6333","2Gi PVC","Vector semantic RAG store"],
         ["OPA","openpolicyagent/opa:0.68","8181","Rego ConfigMap","PR governance engine"],
         ["Ollama","ollama/ollama:latest","11434","Model PVC","Local LLM inference"],
         ["SonarQube","sonarqube:community","9000","PVC","SAST vulnerability scanner"],
         ["Prometheus","prom/prometheus:v2.52","9090","TSDB PVC","Metrics scraper (15s)"],
         ["Grafana","grafana/grafana:11.1","3000","Dashboard ConfigMap","Dashboards"],
         ["Tempo","grafana/tempo:2.5","4318","Trace PVC","OTLP distributed tracing"],
         ["Loki","grafana/loki:3.0","3100","Log PVC","Log aggregation (Promtail)"]],
        [3.2*cm, 4.8*cm, 1.8*cm, 2.5*cm, 3.9*cm], S))
    story.append(PageBreak())

    # ══════════ 4. LOW LEVEL DESIGN ══════════════════════════════════════════
    story += [H1("4. Low Level Design (LLD)", S), hr(C_BLUE)]

    # 4.1 State machine
    story += [H2("4.1 Vulnerability State Machine", S),
              VulnStateMachineDiagram(height=235),
              Paragraph("Figure 3 — Vulnerability lifecycle state transitions", S["caption"]),
              sp(6)]
    story.append(std_table(
        ["Transition","Trigger","Publisher","Consumer"],
        [["DETECTED → IN_PROGRESS","VulnerabilityKafkaEvent consumed","cb-scanner",
          "cb-agent (updates MongoDB status on receive)"],
         ["IN_PROGRESS → FIX_GENERATED","LangGraph4j graph completes","cb-agent",
          "cb-patcher (via fixes.generated topic)"],
         ["FIX_GENERATED → FIX_VALIDATED","Build + test suite pass","cb-patcher",
          "cb-pr (via fixes.validated topic)"],
         ["FIX_VALIDATED → PR_RAISED","OPA approved, GitHub PR created","cb-pr","—"],
         ["PR_RAISED → RESOLVED","review.feedback = ACCEPTED (PR merged)","cb-pr","cb-agent (indexes fix into Qdrant)"],
         ["FIX_GENERATED → ESCALATED","retryCount > 3","cb-agent",
          "cb-escalation, cb-notifier"],
         ["FIX_VALIDATED → ESCALATED","OPA denies PR","cb-pr",
          "cb-escalation, cb-notifier"],
         ["PR_RAISED → IN_PROGRESS","review.feedback = REJECTED","cb-pr",
          "cb-agent (re-runs LangGraph4j with enriched prompt)"],
         ["Any → FAILED","Unhandled exception after DLQ","DefaultErrorHandler","Dead-letter MongoDB collection"]],
        [4.2*cm, 4.5*cm, 2.8*cm, 4.7*cm], S))
    story.append(PageBreak())

    # 4.2 End-to-end sequence
    story += [H2("4.2 End-to-End Flow — Sequence Diagram", S)]
    e2e_actors = ["SonarQube","cb-scanner","cb-agent","cb-patcher","cb-pr","OPA","GitHub"]
    e2e_msgs = [
        (1,0,"GET /api/issues?projectKey=...", C_GRAY,    "every 5 min via @Scheduled"),
        (0,1,"{issues[]}",                    C_GRAY,    ""),
        (1,2,"vulnerabilities.detected",       C_KAFKA_ACC,"Kafka topic publish"),
        (2,2,"LangGraph4j: plan→retrieve→generate→validate",C_AI,"StateGraph execution"),
        (2,3,"fixes.generated",                C_KAFKA_ACC,"Kafka topic publish"),
        (3,3,"./gradlew build test",            C_GREEN,   "ProcessBuilder, 10-min timeout"),
        (3,4,"fixes.validated",                C_KAFKA_ACC,"Kafka topic publish"),
        (4,5,"POST /v1/data/cb/pr/allow",      C_AMBER,   "OPA governance request"),
        (5,4,"{allow: true}",                  C_GREEN,   "Rego policy evaluation"),
        (4,6,"POST /repos/{owner}/{repo}/pulls",C_BLUE,   "GitHub REST API"),
        (6,4,"{pr_url, pr_number}",            C_GRAY,    ""),
        (4,2,"review.feedback",               C_AI,       "Kafka — ACCEPTED indexes to Qdrant"),
    ]
    story += [SequenceDiagram(e2e_actors, e2e_msgs, height=260),
              Paragraph("Figure 4 — End-to-end happy path sequence (detection → PR creation)", S["caption"]),
              sp(6)]

    story += [H2("4.3 Review Feedback & Escalation Sequence", S)]
    esc_actors = ["cb-pr","cb-agent","cb-escalation","cb-notifier","Jira","Teams"]
    esc_msgs = [
        (0,1,"review.feedback (REJECTED)",     C_AI,      "reviewer rejected the fix"),
        (1,1,"re-run LangGraph4j (enriched)",  C_AI,      "injected: reviewerNotes into prompt"),
        (1,1,"retryCount=4 → escalate",        C_KAFKA_ACC,"after 3 retries, bail out"),
        (1,2,"escalations.triggered",          C_KAFKA_ACC,"EscalationKafkaEvent"),
        (2,2,"GPT-4o: generate RCA report",    C_AI,      ""),
        (2,4,"POST /rest/api/3/issue",         C_BLUE,    "Jira REST API v3"),
        (4,2,"{issueKey: CB-123}",             C_GRAY,    ""),
        (2,5,"POST webhook (adaptive card)",   colors.HexColor("#6264A7"),"Teams Incoming Webhook"),
        (1,3,"escalations.triggered",          C_KAFKA_ACC,"same event, parallel consumer"),
        (3,3,"JavaMailSender + Datadog event", C_GD,      "email + DD observability event"),
    ]
    story += [SequenceDiagram(esc_actors, esc_msgs, height=255),
              Paragraph("Figure 5 — Review rejection and escalation sequence", S["caption"])]
    story.append(PageBreak())

    # 4.4 LangGraph4j
    story += [H2("4.4 cb-agent — LangGraph4j Workflow", S),
              LangGraphDiagram(height=235),
              Paragraph("Figure 6 — LangGraph4j StateGraph nodes and conditional edges", S["caption"]),
              sp(6)]
    story += [H3("AgentState Record", S),
              P("All node inputs and outputs are fields on a single <code>AgentState</code> Java record. "
                "LangGraph4j serialises it as JSON between nodes, enabling pause/resume and future "
                "distributed execution across cb-agent replicas.", S)]
    for line in [
        "record AgentState(",
        "  String  vulnerabilityId, severity, cweId, ruleKey,",
        "  String  fileContent, int lineNumber,",
        "  String  selectedModel, retrievalQuery,",
        "  List<String> retrievedChunks,  double hybridScore,",
        "  String  generatedFix,  double confidence,",
        "  int     tokensUsed,    boolean cacheHit,",
        "  boolean validationPassed,  List<String> validationErrors,",
        "  boolean reviewPassed,  String reviewNotes,",
        "  int     retryCount,    boolean escalationTriggered",
        ") {}"
    ]:
        story.append(Paragraph(line, S["code"]))
    story += [sp(6)]

    story.append(std_table(
        ["Node","Action","Conditional Edge"],
        [["planner","Maps severity → model: CRITICAL/BLOCKER→GPT-4o, MAJOR→qwen2:7b, "
          "MINOR/INFO→llama3:8b. Builds Qdrant+ES retrieval query from cweId+ruleKey.",
          "→ retriever (always)"],
         ["retriever","Qdrant cosine top-10 + Elasticsearch BM25 top-10 → RRF fusion → top-6 chunks. "
          "Checks Redis cache key SHA-256(cweId+ruleKey+chunk_ids+model).",
          "→ generator (always)"],
         ["generator","Cache hit → return cached fix. Cache miss → calls LLM via Spring AI "
          "ChatClient with RAG context. Stores result in Redis (24 h TTL).",
          "→ validator (always)"],
         ["validator","JSON-LD cb:Fix schema validation. Safety regex (no exec, no rm -rf). "
          "Unified diff syntax check (hunk offsets, @@ markers).",
          "pass → END via reviewer; fail → retry"],
         ["reviewer","Calls claude-sonnet-4-6 for independent code review. "
          "Used when confidence < 0.80 or validator flags low certainty.",
          "accept → END; reject → retry"],
         ["retry","Increments retryCount. Enriches retrievalQuery with validationErrors context. "
          "retryCount ≤ 3 → back to retriever. retryCount > 3 → publish escalation.",
          "retryCount ≤ 3 → retriever; else → escalate"]],
        [2.2*cm, 9*cm, 5*cm], S))
    story.append(PageBreak())

    # 4.5 Hybrid RAG
    story += [H2("4.5 Hybrid RAG Pipeline", S)]
    story.append(std_table(
        ["Step","System","Action","Detail"],
        [["1. Semantic search","Qdrant","text-embedding-3-small (1536-d) query embedding → cosine similarity top-10",
          "Collection: cb_fixes. Threshold: 0.60 (relaxed to ensure ≥5 candidates)"],
         ["2. Lexical search","Elasticsearch","BM25 on cweId, ruleKey, fileExtension fields → top-10",
          "Index: cb_fixes_bm25. Standard analyzer + edge-ngram on cweId"],
         ["3. RRF fusion","cb-agent","Reciprocal Rank Fusion: score = Σ 1/(k+rank_i), k=60. "
          "Merge Qdrant ranks + ES ranks → unified top-6",
          "RRF proven superior to simple score fusion for heterogeneous retrievers"],
         ["4. Context injection","cb-agent","Top-6 chunks injected as <context>...</context> in system prompt",
          "Each chunk: { cweId, diff, fileExt, confidence, acceptedDate }"],
         ["5. Cache key","Redis","SHA-256(cweId + ruleKey + top-6 chunk IDs + selectedModel) → 24 h TTL",
          "Saves ~$0.02/request for repeated CWEs. Hit rate ~40% in typical codebases"],
         ["6. Fix indexing","Qdrant","On review.feedback = ACCEPTED: embed fix diff → upsert cb_fixes",
          "Creates a self-improving knowledge base — each accepted fix improves future retrieval"]],
        [1.5*cm, 3*cm, 6.5*cm, 5.2*cm], S))
    story += [sp(8)]

    # 4.6 DiffApplier (LLD detail from v1)
    story += [H2("4.6 DiffApplier — LLD (cb-patcher)", S),
              P("The <code>DiffApplier</code> in cb-patcher implements a streaming unified-diff "
                "parser. It splits the patch into hunks on <code>@@</code> markers, "
                "tracks a running line offset, and applies each hunk to the live file list "
                "in a single pass.", S)]
    for line in [
        "List<String> lines = Files.readAllLines(targetFile);",
        "int offset = 0;",
        "for (Hunk hunk : parsedHunks) {",
        "    int start = hunk.oldStart - 1 + offset;   // 0-indexed, adjusted",
        "    lines.subList(start, start + hunk.oldLines).clear();",
        "    lines.addAll(start, hunk.newContent);",
        "    offset += hunk.newLines - hunk.oldLines;   // accumulate shift",
        "}",
        "Files.write(targetFile, lines, StandardCharsets.UTF_8);",
    ]:
        story.append(Paragraph(line, S["code"]))
    story += [sp(6),
              Note("DiffApplier validates that @@ source line numbers match actual file length "
                   "before applying. Mismatch → ValidationException → validator node marks "
                   "validationPassed=false → retry.", S), sp(6)]

    # 4.7 JGit Push Flow
    story += [H2("4.7 JGit Patch & Push Flow — 4 Steps", S)]
    story.append(std_table(
        ["Step","JGit API","Detail"],
        [["1. Clone / reset",
          "Git.cloneRepository() or git.reset().setMode(HARD).call()",
          "If workspace exists: hard reset to HEAD to discard stale state. "
          "Else: clone GITHUB_REPO into emptyDir /workspace/repo."],
         ["2. Checkout branch",
          "git.checkout().setCreateBranch(true).setName(branchName).call()",
          "Branch name pattern: cb/fix-cwe-89-{fixId.substring(0,8)}. "
          "Creates branch from current HEAD."],
         ["3. Apply diff & commit",
          "DiffApplier.apply(). git.add().addFilepattern(\".\").call(). "
          "git.commit().setMessage(...).call()",
          "Commit message: 'chore(cb): fix {cweId} in {component} [automated]'"],
         ["4. Push",
          "git.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider("
          "GITHUB_TOKEN, \"\")).call()",
          "Pushes to origin/{branchName}. Token must have repo scope. "
          "cb-pr then calls GitHub REST API to open the PR from this branch."]],
        [1.5*cm, 5.5*cm, 9.2*cm], S))
    story += [sp(4),
              Note("The /workspace/repo directory uses an emptyDir volume in k8s. Data is lost "
                   "on pod restart — cb-patcher always clones fresh or resets. This is intentional: "
                   "stale checkouts would cause incorrect diff application.", S)]
    story.append(PageBreak())

    # 4.8 OPA Governance
    story += [H2("4.8 OPA Governance Gate (cb-pr)", S),
              P("Before creating any GitHub PR, cb-pr sends a governance request to OPA at "
                "<code>http://opa:8181/v1/data/cb/pr/allow</code>. If OPA is unreachable or "
                "returns allow=false, cb-pr publishes to escalations.triggered instead. "
                "This is a <b>fail-closed</b> design — OPA unavailability = block.", S)]
    story += [H3("Rego Policy Rules (cb_pr.rego)", S)]
    for line in [
        "package cb.pr",
        "",
        "default allow = false",
        "",
        "allow {",
        "    not deny_low_confidence",
        "    not deny_security_path",
        "    not deny_cve_dependency",
        "    not deny_large_patch",
        "}",
        "",
        "deny_low_confidence  { input.confidence < 0.70 }",
        "deny_security_path   { re_match(\"(auth|crypto|security)/\", input.filePath)",
        "                       not input.seniorReviewerAssigned }",
        "deny_cve_dependency  { input.newDependency != \"\"",
        "                       osvHasKnownCVE(input.newDependency) }",
        "deny_large_patch     { input.linesChanged > 50 }",
    ]:
        story.append(Paragraph(line if line else " ", S["code"]))
    story += [sp(6)]

    # 4.9 Kafka Consumer Hardening
    story += [H2("4.9 Kafka Consumer Hardening Pattern", S),
              P("All five consumer modules (cb-agent, cb-patcher, cb-pr, cb-notifier, "
                "cb-escalation) share an identical consumer factory configuration:", S)]
    story.append(std_table(
        ["Property","Value","Why"],
        [["value-deserializer","ErrorHandlingDeserializer",
          "Wraps JsonDeserializer — malformed messages route to error handler, not crash"],
         ["spring.deserializer.value.delegate.class","JsonDeserializer",
          "Actual deserialiser; wrapped by ErrorHandlingDeserializer"],
         ["spring.json.use.type.headers","false",
          "Blocks __TypeId__ header injection from untrusted producers"],
         ["spring.json.value.default.type","e.g. FixKafkaEvent",
          "Pins deserialization target — no dynamic class loading"],
         ["AckMode","MANUAL_IMMEDIATE",
          "Consumer controls offset commit — no message lost on pod crash"],
         ["DefaultErrorHandler backoff","FixedBackOff(1000, 3)",
          "Retry 3× on listener exception before sending to dead-letter topic"],
         ["addNotRetryableExceptions","DeserializationException",
          "Poison-pill messages skipped immediately — no infinite retry loop"]],
        [4.8*cm, 4*cm, 7.4*cm], S))
    story.append(PageBreak())

    # ══════════ 5. MONGODB DATA MODELS ══════════════════════════════════════
    story += [H1("5. MongoDB Data Models", S), hr(C_BLUE)]
    story.append(std_table(
        ["Collection","Key Fields","Indexes","Written By","Read By"],
        [["vulnerabilities",
          "id, projectKey, ruleKey, cweId, severity (BLOCKER|CRITICAL|MAJOR|MINOR|INFO), "
          "status, component, line, message, createdAt, updatedAt",
          "status+severity (compound), projectKey, createdAt TTL 90d",
          "cb-scanner","cb-agent, cb-api"],
         ["fixes",
          "id, vulnerabilityId, diff (unified patch text), model, confidence (0–1), "
          "tokensUsed, status, buildLog, branch, patchedBranch, createdAt",
          "vulnerabilityId, status, model",
          "cb-agent","cb-patcher, cb-pr, cb-api"],
         ["cost_records",
          "id, fixId, cweId, model, inputTokens, outputTokens, estimatedCostUsd, "
          "cacheHit (boolean), createdAt",
          "createdAt (range), cweId, model",
          "cb-agent","cb-api (/cost/* endpoints)"],
         ["audit_events",
          "id, entityId, entityType, action, actor, details (JSON), timestamp",
          "entityId+timestamp (compound)",
          "all modules","cb-api (/audit)"],
         ["escalations",
          "id, vulnerabilityId, reason, retryCount, rcaReport (text), "
          "jiraTicketId, teamsMessageId, createdAt",
          "vulnerabilityId",
          "cb-escalation","cb-api"],
         ["dead_letter_events (P2)",
          "id, topic, partition, offset, payload, errorMessage, failedAt",
          "topic, failedAt",
          "DefaultErrorHandler DLT consumer","manual replay UI (future)"]],
        [3.2*cm, 5.8*cm, 3.2*cm, 2.3*cm, 2.7*cm], S))
    story += [sp(6),
              H3("Vulnerability Status Enum Values", S)]
    story.append(std_table(
        ["Status","Set By","Meaning"],
        [["DETECTED","cb-scanner","SonarQube issue fetched, Kafka event published"],
         ["IN_PROGRESS","cb-agent","Consumer received VulnerabilityKafkaEvent, LangGraph4j running"],
         ["FIX_GENERATED","cb-agent","LangGraph4j completed, FixKafkaEvent published to fixes.generated"],
         ["FIX_VALIDATED","cb-patcher","Build+test passed, FixKafkaEvent published to fixes.validated"],
         ["PR_RAISED","cb-pr","GitHub PR created, waiting for review"],
         ["RESOLVED","cb-pr","review.feedback = ACCEPTED (PR merged)"],
         ["ESCALATED","cb-agent / cb-pr","retryCount > 3 or OPA denied; EscalationKafkaEvent published"],
         ["FAILED","DefaultErrorHandler","Unhandled exception after DLT — requires manual intervention"]],
        [3*cm, 3*cm, 10.2*cm], S))
    story.append(PageBreak())

    # ══════════ 6. MODULE DETAILS ════════════════════════════════════════════
    story += [H1("6. Module Details", S), hr(C_BLUE)]
    modules = [
        {"name":"cb-core","port":"— (library)","color":C_GRAY,
         "desc":"Shared library imported by all CB modules. Contains Kafka event records, "
                "repository interfaces, AsyncConfig (4 executor beans), CacheConfig (Redis "
                "Lettuce), and all Spring Data MongoDB repository interfaces. Centralising "
                "here prevents schema drift across services.",
         "classes":["VulnerabilityKafkaEvent, FixKafkaEvent, EscalationKafkaEvent",
                    "ReviewFeedbackKafkaEvent, CostKafkaEvent — Kafka message records",
                    "KafkaTopics — string constants for all 6 topic names",
                    "AsyncConfig — cbAgentExecutor, cbPatcherExecutor, cbPrExecutor, cbNotifierExecutor (2 core/4 max each)",
                    "CacheConfig — Lettuce connection factory, RedisTemplate<String,String>",
                    "VulnerabilityRepository, FixRepository, CostRepository, EscalationRepository"]},
        {"name":"cb-scanner","port":"8081","color":C_SPRING,
         "desc":"Polls SonarQube every 5 min for OPEN issues. Deduplicates against MongoDB. "
                "Maps SonarQube rule key → CWE ID via CweMapper (40+ rules). Publishes "
                "VulnerabilityKafkaEvent. Exposes /api/v1/scans/{projectKey} for on-demand polling.",
         "classes":["SonarQubePollerService — @Scheduled(fixedDelay=300_000), Feign client",
                    "VulnerabilityKafkaPublisher — KafkaTemplate to vulnerabilities.detected",
                    "CweMapper — Map<String ruleKey, String cweId> with 40+ SonarQube rules",
                    "ScanController — POST /api/v1/scans/{projectKey}",
                    "SonarIssue — DTO for SonarQube API response deserialization"]},
        {"name":"cb-agent","port":"8082","color":C_AI,
         "desc":"Intelligence core. Consumes VulnerabilityKafkaEvent, runs LangGraph4j "
                "5-node StateGraph, publishes FixKafkaEvent and CostKafkaEvent. Also "
                "consumes review.feedback to index accepted fixes into Qdrant. Manages "
                "Redis prompt cache. Routes LLM calls to GPT-4o, Claude, or Ollama "
                "based on severity.",
         "classes":["AgentWorkflowConsumer — @KafkaListener(vulnerabilities.detected)",
                    "LangGraphWorkflow — StateGraph builder, wires 5 nodes + conditional edges",
                    "PlannerNode, RetrieverNode, GeneratorNode, ValidatorNode, ReviewerNode, RetryNode",
                    "QdrantHybridSearchService — Qdrant + ES RRF fusion",
                    "RedisCacheService — SHA-256 key, get/set with 24 h TTL",
                    "FeedbackConsumer — @KafkaListener(review.feedback) → Qdrant upsert",
                    "AgentConfig — LangGraph4j StateGraph bean, ChatClient beans (OpenAI + Anthropic + Ollama)"]},
        {"name":"cb-patcher","port":"8083","color":C_GREEN,
         "desc":"Consumes FixKafkaEvent. Clones repo via JGit, applies unified diff, "
                "runs ./gradlew build test (or mvn verify), publishes fixes.validated on "
                "success. On build failure, increments retryCount and re-publishes to "
                "fixes.generated. After 3 failures, publishes escalations.triggered.",
         "classes":["PatchConsumer — @KafkaListener(fixes.generated)",
                    "JGitPatchService — clone/reset → checkout branch → DiffApplier → commit → push",
                    "DiffApplier — unified diff hunk parser with offset accumulation",
                    "BuildValidationService — ProcessBuilder(gradlew/mvn), 10-min timeout, exit-code check",
                    "PatcherConfig — ConcurrentKafkaListenerContainerFactory + DefaultErrorHandler"]},
        {"name":"cb-pr","port":"8084","color":C_BLUE,
         "desc":"Consumes FixKafkaEvent from fixes.validated. Sends to OPA for policy "
                "check. If approved: creates GitHub PR, creates VersionOne work item, "
                "requests reviewer. Polls GitHub for PR merge/close status. Publishes "
                "review.feedback on resolution.",
         "classes":["PrConsumer — @KafkaListener(fixes.validated)",
                    "OpaGovernanceService — RestTemplate POST to OPA /v1/data/cb/pr/allow, fail-closed",
                    "GovernanceResult — record(boolean allowed, List<String> violations)",
                    "GitHubPrService — GitHub REST API via RestTemplate (create PR, request reviewer)",
                    "Version1Service — VersionOne REST API work item creation",
                    "PrReviewPoller — @Scheduled polls GitHub PR status every 60s → publishes review.feedback"]},
        {"name":"cb-notifier","port":"8085","color":colors.HexColor("#00838F"),
         "desc":"Consumes EscalationKafkaEvent. Sends email via JavaMailSender to "
                "NOTIFIER_TEAM_EMAILS. Posts Datadog event for observability. Stateless "
                "— all state is in the Kafka event payload. Runs independently of "
                "cb-escalation (parallel consumers on the same topic).",
         "classes":["NotificationConsumer — @KafkaListener(escalations.triggered)",
                    "EmailNotificationService — JavaMailSender, Thymeleaf email template",
                    "DatadogEventService — Datadog Events v1 API",
                    "KafkaNotifierConfig — ConcurrentKafkaListenerContainerFactory + DefaultErrorHandler"]},
        {"name":"cb-mcp-server","port":"8086","color":colors.HexColor("#00796B"),
         "desc":"Implements Model Context Protocol (MCP) SSE transport at /sse. "
                "12 tools for AI assistants (Claude Desktop, Cursor). Engineers can "
                "query past fixes, trigger rebuilds, search Qdrant, fetch traces, "
                "create PRs, and roll back merges using natural language.",
         "classes":["McpServerConfig — Spring AI MCP server bean, SSE transport",
                    "findPreviousFixes, getSonarIssue, getPRDiff — data query tools",
                    "buildProject, createPR, triggerRollback — action tools",
                    "getBuildLog, regenerateFix — pipeline management tools",
                    "searchPastIncidents, queryQdrant — RAG search tools",
                    "getTrace (Tempo), getMetrics (Prometheus) — observability tools"]},
        {"name":"cb-escalation","port":"8089","color":C_AMBER,
         "desc":"Consumes EscalationKafkaEvent. Generates RCA report via GPT-4o. "
                "Creates Jira ticket with RCA attached. Posts Teams adaptive card with "
                "severity badge, retry history, and Jira link. All three outputs are "
                "feature-flagged (JIRA_ENABLED, TEAMS_ENABLED) for gradual activation.",
         "classes":["EscalationConsumer — @KafkaListener(escalations.triggered)",
                    "RcaGenerationService — GPT-4o prompt: vuln + retry history + build logs → RCA markdown",
                    "JiraEscalationService — Jira REST API v3: create issue + attach RCA as file",
                    "TeamsNotificationService — Teams Incoming Webhook: adaptive card JSON payload",
                    "EscalationConfig — ConcurrentKafkaListenerContainerFactory + escalationExecutor"]},
        {"name":"cb-api","port":"8080","color":C_NAVY,
         "desc":"Public REST API. Spring Security API-key filter (X-API-Key header). "
                "SpringDoc OpenAPI 3 + Swagger UI. All read/write on vulnerabilities, "
                "fixes, metrics, cost, audit. Prometheus metrics at /actuator/prometheus. "
                "Ingress routes compliance-buddy.local/* to this service.",
         "classes":["VulnerabilityController, FixController, MetricsController, AuditController",
                    "CostTrackingController — /cost/summary, /cost/by-cwe, /cost/by-model",
                    "ScanController — triggers on-demand SonarQube poll via cb-scanner",
                    "ApiKeyAuthFilter — Spring Security OncePerRequestFilter",
                    "OpenApiConfig — SpringDoc bean with API-key security scheme"]},
    ]
    for mod in modules:
        story.append(KeepTogether([
            SectionDiv(f"  {mod['name']}   ·   port {mod['port']}", mod["color"]),
            sp(5), P(mod["desc"], S), sp(3),
            H3("Key Classes / Beans:", S),
        ] + [BB(kc, S) for kc in mod["classes"]] + [sp(10)]))
    story.append(PageBreak())

    # ══════════ 7. REST API CONTRACT ════════════════════════════════════════
    story += [H1("7. REST API Contract", S), hr(C_BLUE),
              Note("All endpoints require header X-API-Key: dev-key-change-in-prod. "
                   "Swagger UI: http://compliance-buddy.local/swagger-ui/index.html "
                   "|  /actuator/health is auth-exempt (k8s probes).", S), sp(6)]
    story.append(std_table(
        ["Method","Path","Request","Response","Description"],
        [["POST","/api/v1/scans/{projectKey}","—","{ newFindings }","Trigger SonarQube poll"],
         ["GET","/api/v1/vulnerabilities","?status= &severity=","Page<Vuln>","List vulnerabilities"],
         ["GET","/api/v1/vulnerabilities/{id}","—","Vulnerability","Single vulnerability"],
         ["POST","/api/v1/vulnerabilities/retry/{id}","—","{ queued }","Re-queue AI fix"],
         ["GET","/api/v1/fixes","—","List<Fix>","All generated fixes"],
         ["GET","/api/v1/fixes/{id}","—","Fix","Fix with full diff"],
         ["GET","/api/v1/metrics","—","{ vulns{}, fixes{}, rate }","Dashboard KPIs"],
         ["GET","/api/v1/cost/summary","?days=7","{ totalCostUsd, tokens }","Cost summary"],
         ["GET","/api/v1/cost/by-cwe","?days=30","[{cweId, totalCostUsd}]","Cost per CWE"],
         ["GET","/api/v1/cost/by-model","?days=30","[{model, totalCostUsd}]","Cost per model"],
         ["GET","/api/v1/audit","—","List<AuditEvent>","Full audit trail"],
         ["GET","/api/v1/audit/entity/{id}","—","List<AuditEvent>","Per-entity audit"],
         ["GET","/actuator/health","—","{ status: UP }","Health (no auth)"],
         ["GET","/actuator/prometheus","—","Prometheus text","Metrics scrape endpoint"]],
        [1.5*cm, 5*cm, 3*cm, 3*cm, 3.7*cm], S))
    story.append(PageBreak())

    # ══════════ 8. SECURITY DESIGN ══════════════════════════════════════════
    story += [H1("8. Security Design", S), hr(C_BLUE)]
    for title, points in [
        ("API Authentication", [
            "All REST endpoints protected by ApiKeyAuthFilter (OncePerRequestFilter).",
            "Multiple keys via CB_API_KEYS comma-separated list — rotate without downtime.",
            "/actuator/health exempt for k8s liveness/readiness probes.",
        ]),
        ("Secrets Management", [
            "All credentials in Kubernetes Secrets (k8s/base/secrets.yaml), not env vars at build time.",
            "HashiCorp Vault integration available (spring.cloud.vault.enabled=true, off by default).",
            "No credentials embedded in Docker images.",
        ]),
        ("Kafka Security", [
            "ErrorHandlingDeserializer prevents deserialization attacks from malformed payloads.",
            "spring.json.use.type.headers=false blocks __TypeId__ header injection.",
            "spring.json.value.default.type pins deserialization class — no dynamic class loading.",
        ]),
        ("OPA Governance Gate", [
            "Rego policies evaluated server-side — policy changes need no code deployment.",
            "cb-pr hard-fails (escalates) on OPA timeout or connection error — fail-closed.",
            "All policy decisions logged to audit_events for compliance reporting.",
        ]),
        ("Container Security", [
            "All CB images run as non-root user cbapp (UID 1001).",
            "Base image: eclipse-temurin:17-jre-jammy — minimal JRE, no JDK, no build tools.",
            "-XX:+UseContainerSupport ensures JVM respects cgroup memory limits.",
        ]),
        ("Network Isolation", [
            "All inter-service communication within cb-system namespace (ClusterIP only).",
            "Only cb-api exposed via Ingress. MongoDB, Redis, OPA are not externally accessible.",
            "Qdrant and Elasticsearch bound to cluster-internal ClusterIP services.",
        ]),
    ]:
        story += [H3(title, S)] + [B(pt, S) for pt in points] + [sp(5)]
    story.append(PageBreak())

    # ══════════ 9. OBSERVABILITY ════════════════════════════════════════════
    story += [H1("9. Observability", S), hr(C_BLUE),
              H2("9.1 Prometheus Metrics", S)]
    story.append(std_table(
        ["Metric","Type","Labels","Description"],
        [["cb_vulnerabilities_detected_total","Counter","projectKey, severity, cweId","Vulns ingested"],
         ["cb_fixes_generated_total","Counter","model, cacheHit, cweId","AI fixes produced"],
         ["cb_prs_created_total","Counter","projectKey, severity","GitHub PRs opened"],
         ["cb_escalations_triggered_total","Counter","reason","Jira/Teams escalations"],
         ["cb_llm_cost_usd_total","Counter","model, cweId","Cumulative LLM spend"],
         ["cb_llm_tokens_total","Counter","model, direction","Input+output tokens"],
         ["cb_fix_confidence","Gauge","model, cweId","Last fix confidence (0–1)"],
         ["cb_pipeline_duration_seconds","Histogram","severity","Detection→PR latency"],
         ["cb_kafka_consumer_lag","Gauge","topic, group","Consumer lag per topic"],
         ["cb_redis_cache_hit_total","Counter","cweId","Prompt cache hits"],
         ["cb_build_validation_duration_seconds","Histogram","buildTool","Gradle/Maven build time"]],
        [5*cm, 2*cm, 3.8*cm, 5.4*cm], S))
    story += [sp(6), H2("9.2 Distributed Tracing (Tempo)", S),
              P("All services export OTLP traces to Tempo at http://tempo:4318/v1/traces "
                "(100% sampling). Every vulnerability processing message carries "
                "<code>vulnerabilityId</code> as a baggage attribute for end-to-end "
                "correlation from cb-scanner through cb-pr. "
                "Query in Grafana Explore → Tempo by traceId or attribute.", S),
              H2("9.3 Log Aggregation (Loki + Promtail)", S),
              P("Promtail DaemonSet tails all pod logs in cb-system, labelling with "
                "app, namespace, pod, container. All CB services emit structured JSON logs "
                "including vulnerabilityId, fixId, model, cacheHit, and duration. "
                "Query in Grafana Explore → Loki data source.", S)]
    story.append(PageBreak())

    # ══════════ 10. CWE COVERAGE ════════════════════════════════════════════
    story += [H1("10. CWE Coverage", S), hr(C_BLUE)]
    story.append(std_table(
        ["CWE","Name","Fix Strategy","Confidence"],
        [["CWE-89","SQL Injection","PreparedStatement / JPA named parameters","0.93"],
         ["CWE-79","XSS","HtmlUtils.htmlEscape + CSP header injection","0.91"],
         ["CWE-78","OS Command Injection","ProcessBuilder(List) replacing Runtime.exec(String)","0.95"],
         ["CWE-22","Path Traversal","Path.toRealPath() + base-dir prefix assertion","0.90"],
         ["CWE-798","Hardcoded Credentials","Extract to @Value + Kubernetes Secret","0.96"],
         ["CWE-327","Broken Crypto","MD5/SHA-1→SHA-256; DES→AES-256-GCM","0.92"],
         ["CWE-918","SSRF","URL allow-list validation before any HTTP client call","0.88"],
         ["CWE-330","Weak Randomness","Math.random()/new Random() → SecureRandom","0.97"],
         ["CWE-502","Unsafe Deserialization","Replace Java deserialization with Jackson JSON","0.89"],
         ["CWE-611","XXE","Disable DOCTYPE + external entities on DocumentBuilderFactory","0.94"],
         ["CWE-200","Information Exposure","Remove stack traces from HTTP; add @ExceptionHandler","0.85"],
         ["CWE-352","CSRF","Spring Security CSRF token on state-changing endpoints","0.91"]],
        [1.8*cm, 4*cm, 7.5*cm, 2.5*cm], S))
    story.append(PageBreak())

    # ══════════ 11. DEPLOYMENT ══════════════════════════════════════════════
    story += [H1("11. Deployment &amp; Operations", S), hr(C_BLUE),
              H2("11.1 Local Development (k3d)", S)]
    story.append(std_table(
        ["Step","Command","Notes"],
        [["1. Start cluster","start-cb.ps1","Docker + k3d + pod readiness wait (8-min timeout)"],
         ["2. Apply secrets","kubectl apply -k k8s/base/","Edit secrets.yaml before first run"],
         ["3. Build module","./gradlew :cb-agent:bootJar","Requires Java 17. Use Bash, not PowerShell."],
         ["4. Build image","docker build -f cb-agent/Dockerfile.prebuilt -t cb-agent:latest cb-agent/",
          "Build context = module dir (not repo root — .dockerignore excludes **/build at root)"],
         ["5. Load to k3d","k3d image import cb-agent:latest -c compliance-buddy","Run from Bash"],
         ["6. Restart pod","kubectl rollout restart deployment/cb-agent -n cb-system",""],
         ["7. Watch logs","kubectl logs -n cb-system -l app=cb-agent -f",""],
         ["8. Status check","status-cb.ps1","Shows pods, services, URLs, known issues"],
         ["9. Stop cluster","stop-cb.ps1","All data preserved (PVCs remain)"]],
        [1.5*cm, 6.5*cm, 8.2*cm], S))
    story += [sp(6), H2("11.2 Dockerfile.prebuilt Pattern", S),
              P("All CB modules use identical Dockerfile.prebuilt. "
                "The root .dockerignore excludes **/build so the build context must be "
                "the module directory, not the repository root.", S)]
    for line in [
        "FROM eclipse-temurin:17-jre-jammy",
        "RUN groupadd -r cbapp && useradd -r -g cbapp -u 1001 cbapp",
        "WORKDIR /app",
        "COPY build/libs/cb-<module>-1.0.0.jar app.jar",
        "RUN chown cbapp:cbapp app.jar",
        "USER cbapp",
        "EXPOSE <port>",
        'ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0",',
        '            "-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]',
    ]:
        story.append(Paragraph(line, S["code"]))
    story.append(PageBreak())

    # ══════════ 12. KNOWN ISSUES ════════════════════════════════════════════
    story += [H1("12. Known Issues &amp; Limitations", S), hr(C_BLUE)]
    story.append(std_table(
        ["Issue","Pod / Component","Impact","Workaround / Fix"],
        [["cb-python-embedder ImagePullBackOff","cb-python-embedder (:8090)",
          "Python FastAPI embedding service not running. "
          "Falls back to Spring AI inline text-embedding-3-small via OpenAI HTTP.",
          "Build Python FastAPI image and push to local k3d registry. "
          "Or keep Spring AI inline embedding (same model, negligible perf difference)."],
         ["OPA ImagePullBackOff","opa (:8181)",
          "Governance gate disabled. cb-pr runs with OPA_ENABLED=false — "
          "all AI fixes bypass policy check.",
          "Pull OPA 0.68 image via docker pull, then k3d image import. "
          "P0 fix — every PR should pass policy."],
         ["Ollama CrashLoopBackOff","ollama (:11434)",
          "Local LLM inference unavailable. MAJOR/MINOR/INFO vulns route to "
          "GPT-4o instead of free local models — increases LLM spend ~60%.",
          "Add GPU resource requests to Ollama Deployment or increase node RAM limits. "
          "P0 fix for cost efficiency."],
         ["emptyDir /workspace/repo","cb-patcher",
          "Repository clone lost on pod restart. First request after restart "
          "re-clones the full repo (adds ~10–30s latency).",
          "Expected behaviour — intentional to avoid stale checkout state. "
          "Future: persistent PVC workspace with git fetch instead of full clone."],
         ["GitHub Copilot reviewer failure","cb-pr",
          "If GITHUB_REVIEWER_USERNAME is a GitHub Copilot bot account, "
          "it may not be a repo collaborator and the reviewer request 422s.",
          "Set GITHUB_REVIEWER_USERNAME to an actual human GitHub username "
          "or a team slug (@org/team-slug)."],
         ["Version1 / VersionOne API","cb-pr",
          "VersionOne work item creation is optional. If VERSION1_API_URL is "
          "blank, cb-pr skips ticket creation silently.",
          "Set VERSION1_API_URL and VERSION1_TOKEN or switch to Jira-only "
          "via cb-escalation."]],
        [3.5*cm, 2.8*cm, 4.5*cm, 5.4*cm], S))
    story.append(PageBreak())

    # ══════════ 13. FUTURE ENHANCEMENTS ════════════════════════════════════
    story += [H1("13. Future Enhancements", S), hr(C_BLUE)]
    groups = [
        ("P0 — Critical Path", C_KAFKA_ACC, [
            ("OPA image deployment","Build OPA 0.68, push to k3d registry — activates governance gate for all PRs."),
            ("Ollama GPU scheduling","GPU resource requests on Ollama Deployment — restores free local LLM inference."),
            ("Multi-repo support","Database-backed project registry with per-project credentials and polling intervals."),
        ]),
        ("P1 — High Value", C_AMBER, [
            ("PR auto-merge on CI pass","GitHub Actions webhook consumer in cb-pr — fully autonomous zero-touch pipeline."),
            ("cb-python-embedder activation","Deploy FastAPI with text-embedding-3-large (3072-d) for +8–12% retrieval accuracy."),
            ("Cost budget alerts","MAX_DAILY_COST_USD threshold: when exceeded, force all models to Ollama until midnight UTC."),
            ("Slack notifications","SLACK_ENABLED toggle in cb-notifier alongside existing Teams support."),
            ("Fix confidence trend dashboard","Grafana panel: cb_fix_confidence over time per model and CWE."),
        ]),
        ("P2 — Platform Scale", C_BLUE, [
            ("Horizontal pod autoscaling","HPA for cb-agent (CPU 70%) + cb-patcher (CPU 60%) — linear throughput scaling."),
            ("Dead letter queue UI","cb-dlq consumer stores DLT messages in MongoDB; Swagger manual replay endpoint."),
            ("Kafka Schema Registry","Confluent Schema Registry + AvroDeserializer — enforced forward/backward compatibility."),
            ("3-broker KRaft cluster","Production HA: replication factor 3, min-ISR 2. Required for SLA > 99.5%."),
            ("GitLab + Bitbucket PR support","Abstract GitHostClient interface; GitLabPrService + BitbucketPrService implementations."),
        ]),
        ("P3 — AI Research", C_AI, [
            ("Fine-tuned CWE models","LoRA adapters on qwen2:7b per CWE — outperforms GPT-4o on high-freq CWEs at 1/100th cost."),
            ("Parallel LangGraph4j retrieval","Qdrant and Elasticsearch retriever nodes run in parallel — -40% p99 latency."),
            ("Agentic code review","Sub-graph in reviewer node: security review, perf review, style review → majority vote."),
            ("Vulnerability prediction","Classifier on historical fix data: predict CWE-89/79/78 risk before SonarQube runs."),
            ("RLHF / DPO fine-tuning","Capture reviewer edits as preference signal → quarterly DPO fine-tune of generator model."),
        ]),
        ("P4 — Enterprise Readiness", C_GRAY, [
            ("LDAP / SSO for cb-api","Spring Security OAuth2 Resource Server (JWT) — Entra ID, Okta, Keycloak support."),
            ("Multi-tenancy","Partition MongoDB + Kafka topics by tenantId — required for SaaS offering."),
            ("Compliance report generation","/api/v1/reports/compliance → PDF/Excel of detected/remediated/escalated vulns per CWE."),
            ("Helm chart packaging","One-command install: helm install compliance-buddy oci://ghcr.io/org/cb-helm/compliance-buddy."),
        ]),
    ]
    for title, color, items in groups:
        story.append(SectionDiv(f"  {title}", color))
        story.append(sp(8))
        for item_title, item_desc in items:
            story += [KeepTogether([H3(item_title, S), P(item_desc, S), sp(4)])]
    story.append(PageBreak())

    # ══════════ 14. TECHNOLOGY STACK ════════════════════════════════════════
    story += [H1("14. Technology Stack", S), hr(C_BLUE)]
    story.append(std_table(
        ["Category","Technology","Version","Role"],
        [["Language","Java","17 LTS","All CB services"],
         ["Framework","Spring Boot","3.3.6","Application scaffold + auto-configuration"],
         ["AI Orchestration","LangGraph4j","1.5.14","Multi-agent StateGraph in cb-agent"],
         ["AI Integration","Spring AI","1.0.0","ChatClient, VectorStore, MCP SDK"],
         ["LLM Primary","OpenAI GPT-4o","gpt-4o","CRITICAL/BLOCKER fix generation + RCA"],
         ["LLM Review","Anthropic Claude","claude-sonnet-4-6","Independent code review + fallback"],
         ["LLM Local","Ollama (llama3:8b, qwen2:7b)","0.3","MAJOR/MINOR/INFO — zero API cost"],
         ["Event Streaming","Apache Kafka","KRaft 3.7","Async pipeline bus — no ZooKeeper"],
         ["Spring Kafka","Spring Kafka","3.2.5","Producer/consumer + ErrorHandlingDeserializer"],
         ["Database","MongoDB","7.0","Primary datastore (all 6 collections)"],
         ["Cache","Redis","7.2","LLM prompt cache (Lettuce, 24 h TTL)"],
         ["Vector Store","Qdrant","1.9.2","Semantic RAG — text-embedding-3-small 1536-d"],
         ["Lexical Search","Elasticsearch","8.13","BM25 RAG index (hybrid with Qdrant)"],
         ["Policy Engine","OPA","0.68","Rego PR governance gate"],
         ["Git Operations","JGit","6.8","Diff application + push in cb-patcher"],
         ["API Docs","SpringDoc OpenAPI","2.5","Swagger UI on cb-api"],
         ["Resilience","Resilience4j","2.2","CircuitBreaker on SonarQube + GitHub calls"],
         ["Metrics","Micrometer + Prometheus","1.13 / 2.52","Application metrics + scraper"],
         ["Tracing","OpenTelemetry + Tempo","1.39 / 2.5","Distributed traces (OTLP HTTP)"],
         ["Logging","Logback → Loki","—","Structured JSON logs via Promtail DaemonSet"],
         ["Dashboards","Grafana","11.1","Pipeline + JVM + Kafka dashboards"],
         ["Containers","Docker + k3d + k3s","k3d 5.8","Local Kubernetes cluster"],
         ["Build","Gradle","8.14","Multi-module build, JVM pinned to JDK 17"],
         ["Protocol","MCP SSE","1.0","cb-mcp-server AI tool interface"]],
        [3*cm, 4.2*cm, 2.5*cm, 6.5*cm], S))
    story.append(PageBreak())

    # ══════════ APPENDIX: CONFIG REFERENCE ══════════════════════════════════
    story += [H1("Appendix — Configuration Reference", S), hr(C_BLUE)]
    story.append(std_table(
        ["Environment Variable","Default","Module(s)","Description"],
        [["CB_API_KEYS","dev-key-change-in-prod","cb-api","Comma-sep valid REST API keys"],
         ["OPENAI_API_KEY","—","cb-agent, cb-escalation","GPT-4o API key"],
         ["ANTHROPIC_API_KEY","—","cb-agent","Claude reviewer + fallback"],
         ["SONARQUBE_URL","http://cb-sonarqube:9000/sonar","cb-scanner","SonarQube instance"],
         ["SONARQUBE_TOKEN","—","cb-scanner","SonarQube user token"],
         ["SCANNER_PROJECTS","—","cb-scanner","Comma-sep SonarQube project keys"],
         ["GITHUB_TOKEN","—","cb-pr, cb-mcp-server","GitHub PAT (repo scope)"],
         ["GITHUB_OWNER","—","cb-pr","GitHub org or username"],
         ["GITHUB_REPO","—","cb-pr","Repository name"],
         ["GITHUB_REVIEWER_USERNAME","—","cb-pr","Auto-requested PR reviewer"],
         ["VERSION1_API_URL","—","cb-pr","VersionOne instance URL (optional)"],
         ["VERSION1_TOKEN","—","cb-pr","VersionOne API token"],
         ["JIRA_ENABLED","false","cb-escalation","Enable Jira ticket creation"],
         ["JIRA_BASE_URL","—","cb-escalation","https://org.atlassian.net"],
         ["JIRA_EMAIL","—","cb-escalation","Jira account email"],
         ["JIRA_API_TOKEN","—","cb-escalation","Jira API token"],
         ["JIRA_PROJECT_KEY","CB","cb-escalation","Jira project key for escalations"],
         ["TEAMS_ENABLED","false","cb-escalation","Enable Teams adaptive card"],
         ["TEAMS_WEBHOOK_URL","—","cb-escalation","Teams Incoming Webhook URL"],
         ["SMTP_HOST","smtp.gmail.com","cb-notifier","SMTP server"],
         ["SMTP_PORT","587","cb-notifier","SMTP port"],
         ["SMTP_USERNAME","—","cb-notifier","SMTP credentials"],
         ["SMTP_PASSWORD","—","cb-notifier","SMTP password / app password"],
         ["NOTIFIER_TEAM_EMAILS","—","cb-notifier","Comma-sep recipient emails"],
         ["DATADOG_API_KEY","none","cb-notifier","Datadog Events API key"],
         ["OPA_ENABLED","true","cb-pr","OPA governance gate toggle"],
         ["KAFKA_BOOTSTRAP_SERVERS","kafka:9092","all","Kafka broker"],
         ["MONGODB_URI","mongodb://cb-mongodb:27017/compliance_buddy","all","MongoDB URI"],
         ["VAULT_ENABLED","false","all","HashiCorp Vault integration"],
         ["OTEL_ENABLED","true","all","OpenTelemetry tracing"],
         ["OTEL_EXPORTER_OTLP_ENDPOINT","http://tempo:4318/v1/traces","all","Tempo OTLP endpoint"]],
        [5*cm, 3.5*cm, 3*cm, 4.7*cm], S))

    doc.build(story, onFirstPage=page_tmpl, onLaterPages=page_tmpl)
    print(f"Generated: {OUT}")


if __name__ == "__main__":
    build()
