"""
Compliance Buddy v3 - Architecture & Design Document Generator
Produces: CB_v3_Architecture_Document.pdf
"""

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm, mm
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_RIGHT, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, HRFlowable, KeepTogether
)
from reportlab.platypus.flowables import Flowable
from reportlab.graphics.shapes import (
    Drawing, Rect, String, Line, Polygon, Group, Circle
)
from reportlab.graphics import renderPDF
import datetime

# ── Color Palette ─────────────────────────────────────────────────────────────
C_NAVY      = colors.HexColor("#0D1B2A")
C_BLUE      = colors.HexColor("#1565C0")
C_BLUE_LIGHT= colors.HexColor("#1976D2")
C_ACCENT    = colors.HexColor("#00ACC1")
C_GREEN     = colors.HexColor("#2E7D32")
C_AMBER     = colors.HexColor("#F57F17")
C_RED       = colors.HexColor("#B71C1C")
C_GRAY_DARK = colors.HexColor("#263238")
C_GRAY      = colors.HexColor("#546E7A")
C_GRAY_LIGHT= colors.HexColor("#ECEFF1")
C_GRAY_MID  = colors.HexColor("#CFD8DC")
C_WHITE     = colors.white
C_KAFKA     = colors.HexColor("#231F20")
C_KAFKA_ACC = colors.HexColor("#C0392B")
C_SPRING    = colors.HexColor("#6DB33F")
C_AI        = colors.HexColor("#7B1FA2")

PAGE_W, PAGE_H = A4
MARGIN = 2*cm


# ── Styles ────────────────────────────────────────────────────────────────────
def make_styles():
    base = getSampleStyleSheet()

    def S(name, **kw):
        return ParagraphStyle(name, **kw)

    return {
        "cover_title": S("cover_title",
            fontSize=34, textColor=C_WHITE, fontName="Helvetica-Bold",
            leading=42, alignment=TA_LEFT, spaceAfter=8),
        "cover_sub": S("cover_sub",
            fontSize=16, textColor=C_ACCENT, fontName="Helvetica",
            leading=22, alignment=TA_LEFT, spaceAfter=6),
        "cover_meta": S("cover_meta",
            fontSize=11, textColor=C_GRAY_LIGHT, fontName="Helvetica",
            leading=16, alignment=TA_LEFT),
        "h1": S("h1",
            fontSize=20, textColor=C_NAVY, fontName="Helvetica-Bold",
            leading=26, spaceBefore=18, spaceAfter=8),
        "h2": S("h2",
            fontSize=15, textColor=C_BLUE, fontName="Helvetica-Bold",
            leading=20, spaceBefore=14, spaceAfter=6),
        "h3": S("h3",
            fontSize=12, textColor=C_GRAY_DARK, fontName="Helvetica-Bold",
            leading=16, spaceBefore=10, spaceAfter=4),
        "body": S("body",
            fontSize=10, textColor=C_GRAY_DARK, fontName="Helvetica",
            leading=15, spaceAfter=6, alignment=TA_JUSTIFY),
        "body_left": S("body_left",
            fontSize=10, textColor=C_GRAY_DARK, fontName="Helvetica",
            leading=15, spaceAfter=4, alignment=TA_LEFT),
        "bullet": S("bullet",
            fontSize=10, textColor=C_GRAY_DARK, fontName="Helvetica",
            leading=14, leftIndent=14, spaceAfter=3, bulletIndent=0),
        "sub_bullet": S("sub_bullet",
            fontSize=9.5, textColor=C_GRAY, fontName="Helvetica",
            leading=13, leftIndent=28, spaceAfter=2, bulletIndent=14),
        "code": S("code",
            fontSize=8.5, textColor=C_NAVY, fontName="Courier",
            leading=13, leftIndent=12, spaceAfter=2,
            backColor=C_GRAY_LIGHT, borderPadding=(4,6,4,6)),
        "caption": S("caption",
            fontSize=8.5, textColor=C_GRAY, fontName="Helvetica-Oblique",
            leading=12, alignment=TA_CENTER, spaceAfter=6),
        "table_hdr": S("table_hdr",
            fontSize=9, textColor=C_WHITE, fontName="Helvetica-Bold",
            leading=13, alignment=TA_LEFT),
        "table_cell": S("table_cell",
            fontSize=9, textColor=C_GRAY_DARK, fontName="Helvetica",
            leading=13, alignment=TA_LEFT),
        "table_cell_center": S("table_cell_center",
            fontSize=9, textColor=C_GRAY_DARK, fontName="Helvetica",
            leading=13, alignment=TA_CENTER),
        "tag_green": S("tag_green",
            fontSize=8, textColor=C_WHITE, fontName="Helvetica-Bold",
            leading=12, alignment=TA_CENTER, backColor=C_GREEN,
            borderPadding=(2,4,2,4)),
        "note": S("note",
            fontSize=9.5, textColor=C_GRAY_DARK, fontName="Helvetica-Oblique",
            leading=13, leftIndent=12, spaceAfter=4,
            backColor=colors.HexColor("#E3F2FD"), borderPadding=(5,8,5,8)),
    }


# ── Custom Flowables ──────────────────────────────────────────────────────────
class SectionDivider(Flowable):
    """Colored band used as section header background."""
    def __init__(self, text, color=C_BLUE, width=None, height=32):
        super().__init__()
        self.text  = text
        self.color = color
        self.width = width or (PAGE_W - 2*MARGIN)
        self.height = height

    def wrap(self, availW, availH):
        self.width = availW
        return availW, self.height

    def draw(self):
        c = self.canv
        c.setFillColor(self.color)
        c.rect(0, 0, self.width, self.height, fill=1, stroke=0)
        c.setFillColor(C_WHITE)
        c.setFont("Helvetica-Bold", 13)
        c.drawString(12, 10, self.text)


class HLDDiagram(Flowable):
    """High Level Architecture diagram drawn with ReportLab shapes."""
    def __init__(self, width=None, height=340):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw
        return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height

        def box(x, y, w, h, label, sub=None, fill=C_BLUE, text_color=C_WHITE, font_size=8):
            c.setFillColor(fill)
            c.setStrokeColor(colors.HexColor("#90A4AE"))
            c.roundRect(x, y, w, h, 5, fill=1, stroke=1)
            c.setFillColor(text_color)
            c.setFont("Helvetica-Bold", font_size)
            tw = c.stringWidth(label, "Helvetica-Bold", font_size)
            c.drawString(x + (w - tw)/2, y + h/2 + (3 if sub else 1), label)
            if sub:
                c.setFont("Helvetica", 6.5)
                sw = c.stringWidth(sub, "Helvetica", 6.5)
                c.setFillColor(colors.HexColor("#B3E5FC") if fill == C_BLUE else C_GRAY)
                c.drawString(x + (w - sw)/2, y + h/2 - 8, sub)

        def arrow(x1, y1, x2, y2, label=None, color=C_GRAY):
            c.setStrokeColor(color)
            c.setLineWidth(1.5)
            c.line(x1, y1, x2, y2)
            # arrowhead
            import math
            dx, dy = x2-x1, y2-y1
            length = math.sqrt(dx*dx + dy*dy)
            if length == 0: return
            ux, uy = dx/length, dy/length
            s = 7
            c.setFillColor(color)
            c.setStrokeColor(color)
            pts = [x2, y2,
                   x2 - s*ux + s*0.4*uy, y2 - s*uy - s*0.4*ux,
                   x2 - s*ux - s*0.4*uy, y2 - s*uy + s*0.4*ux]
            p = c.beginPath()
            p.moveTo(pts[0], pts[1])
            p.lineTo(pts[2], pts[3])
            p.lineTo(pts[4], pts[5])
            p.close()
            c.drawPath(p, fill=1, stroke=0)
            if label:
                mx, my = (x1+x2)/2, (y1+y2)/2
                c.setFont("Helvetica", 6)
                c.setFillColor(C_KAFKA_ACC)
                lw = c.stringWidth(label, "Helvetica", 6)
                c.setFillColor(colors.HexColor("#FFF9C4"))
                c.rect(mx - lw/2 - 2, my - 4, lw + 4, 10, fill=1, stroke=0)
                c.setFillColor(C_KAFKA_ACC)
                c.drawString(mx - lw/2, my + 1, label)

        def kafka_bus(x, y, w, h):
            c.setFillColor(C_KAFKA)
            c.roundRect(x, y, w, h, 4, fill=1, stroke=0)
            c.setFillColor(C_KAFKA_ACC)
            c.setFont("Helvetica-Bold", 8)
            label = "KAFKA  (KRaft)"
            lw = c.stringWidth(label, "Helvetica-Bold", 8)
            c.drawString(x + (w - lw)/2, y + h/2 - 3, label)

        BW = 72   # box width
        BH = 36   # box height
        pad = 8

        # ── Row positions ──
        ROW1 = H - 50    # SonarQube + Scanner
        ROW2 = H - 130   # cb-agent
        KAFKA_Y = H - 175
        KAFKA_H = 22
        ROW3 = KAFKA_Y - BH - pad  # patcher / pr / escalation / notifier
        ROW4 = ROW3 - BH - pad*2   # github / jira / teams / email

        # ── Infrastructure strip (right side) ──
        infra_x = W - 105
        c.setFillColor(colors.HexColor("#F3E5F5"))
        c.setStrokeColor(colors.HexColor("#CE93D8"))
        c.roundRect(infra_x, ROW4 - 10, 100, H - ROW4 - 10, 4, fill=1, stroke=1)
        c.setFillColor(colors.HexColor("#6A1B9A"))
        c.setFont("Helvetica-Bold", 7.5)
        c.drawString(infra_x + 8, H - 38, "INFRASTRUCTURE")
        infra_items = [
            ("MongoDB 7", C_GREEN),
            ("Redis 7", C_KAFKA_ACC),
            ("Elasticsearch 8", C_BLUE_LIGHT),
            ("Qdrant", C_AI),
            ("OPA", colors.HexColor("#E65100")),
            ("Ollama", colors.HexColor("#4E342E")),
            ("Prometheus", colors.HexColor("#E65100")),
            ("Grafana", colors.HexColor("#F57F17")),
            ("Tempo", colors.HexColor("#1565C0")),
            ("Loki", C_NAVY),
        ]
        iy = H - 55
        for (name, col) in infra_items:
            c.setFillColor(col)
            c.roundRect(infra_x + 6, iy, 88, 14, 3, fill=1, stroke=0)
            c.setFillColor(C_WHITE)
            c.setFont("Helvetica", 7)
            c.drawString(infra_x + 12, iy + 4, name)
            iy -= 18

        # ── SonarQube ──
        box(pad, ROW1, BW, BH, "SonarQube", "Issue Scanner",
            fill=colors.HexColor("#4E9BCD"), text_color=C_WHITE)

        # ── cb-scanner ──
        box(pad + BW + pad, ROW1, BW, BH, "cb-scanner", ":8081",
            fill=C_SPRING, text_color=C_WHITE)

        # arrow SonarQube → scanner
        arrow(pad + BW, ROW1 + BH/2, pad + BW + pad, ROW1 + BH/2, color=C_GRAY)

        # ── cb-agent ──
        agent_x = pad + BW + pad
        box(agent_x, ROW2, BW + 20, BH + 4, "cb-agent", ":8082  LangGraph4j",
            fill=C_AI, text_color=C_WHITE)

        # scanner → agent (down)
        arrow(pad + BW + pad + BW/2, ROW1,
              agent_x + (BW+20)/2, ROW2 + BH + 4,
              "vulnerabilities.detected", color=C_KAFKA_ACC)

        # ── Kafka bus ──
        kafka_bus(pad, KAFKA_Y, W - 115, KAFKA_H)

        # agent → kafka
        arrow(agent_x + (BW+20)/2, ROW2,
              agent_x + (BW+20)/2, KAFKA_Y + KAFKA_H,
              "fixes.generated", color=C_KAFKA_ACC)

        # ── Row3 services ──
        r3_names = [
            ("cb-patcher", ":8083"),
            ("cb-pr",      ":8084"),
            ("cb-escalation", ":8089"),
            ("cb-notifier",":8085"),
        ]
        r3_colors = [C_GREEN, C_BLUE, C_AMBER, colors.HexColor("#00838F")]
        r3_x = pad
        spacing = (W - 120 - pad*2) / len(r3_names)
        r3_centers = []
        for i, ((name, port), col) in enumerate(zip(r3_names, r3_colors)):
            bx = r3_x + i * spacing
            box(bx, ROW3, BW, BH, name, port, fill=col, text_color=C_WHITE)
            cx = bx + BW/2
            r3_centers.append(cx)
            arrow(cx, KAFKA_Y, cx, ROW3 + BH, color=C_KAFKA_ACC)

        # ── Row4 external ──
        ext_names = ["GitHub PR", "Jira Ticket", "Teams Card", "Email / DD"]
        ext_colors = [colors.HexColor("#24292E"), colors.HexColor("#0052CC"),
                      colors.HexColor("#6264A7"), colors.HexColor("#EA4335")]
        for i, (name, col) in enumerate(zip(ext_names, ext_colors)):
            bx = r3_x + i * spacing
            box(bx, ROW4, BW, BH - 6, name, fill=col, text_color=C_WHITE, font_size=7.5)
            arrow(r3_centers[i], ROW3, r3_centers[i], ROW4 + BH - 6, color=C_GRAY)

        # ── cb-api ──
        api_x = W - 115 - BW - pad
        box(api_x, KAFKA_Y - 5, BW, BH, "cb-api", ":8080", fill=C_NAVY, text_color=C_WHITE)
        c.setFont("Helvetica", 6.5)
        c.setFillColor(C_GRAY)
        c.drawString(api_x - 4, KAFKA_Y + BH - 2, "REST clients")
        arrow(api_x + BW, KAFKA_Y + BH/2, W - 115, KAFKA_Y + KAFKA_H/2, color=C_GRAY)

        # ── cb-mcp-server ──
        mcp_x = api_x
        mcp_y = ROW1
        box(mcp_x, mcp_y, BW, BH, "cb-mcp-server", ":8086  12 tools",
            fill=colors.HexColor("#00796B"), text_color=C_WHITE)

        # ── Legend ──
        lx, ly = pad, 8
        c.setFont("Helvetica-Bold", 7)
        c.setFillColor(C_GRAY_DARK)
        c.drawString(lx, ly, "Legend:")
        items = [
            (C_SPRING, "Spring Service"),
            (C_AI, "AI / LLM"),
            (C_KAFKA_ACC, "Kafka topic"),
            (C_KAFKA, "Kafka bus"),
        ]
        lx += 52
        for (col, label) in items:
            c.setFillColor(col)
            c.rect(lx, ly - 1, 10, 10, fill=1, stroke=0)
            c.setFillColor(C_GRAY_DARK)
            c.setFont("Helvetica", 7)
            c.drawString(lx + 13, ly + 1, label)
            lx += c.stringWidth(label, "Helvetica", 7) + 30


class LangGraphDiagram(Flowable):
    """LangGraph4j workflow state-machine diagram."""
    def __init__(self, width=None, height=220):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw
        return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height

        node_w, node_h = 90, 32
        cx = W / 2

        def node(x, y, label, sub=None, fill=C_BLUE, text_color=C_WHITE):
            c.setFillColor(fill)
            c.setStrokeColor(C_GRAY_MID)
            c.setLineWidth(1)
            c.roundRect(x - node_w/2, y - node_h/2, node_w, node_h, 6, fill=1, stroke=1)
            c.setFillColor(text_color)
            c.setFont("Helvetica-Bold", 8.5)
            lw = c.stringWidth(label, "Helvetica-Bold", 8.5)
            c.drawString(x - lw/2, y + (4 if sub else 0), label)
            if sub:
                c.setFont("Helvetica", 6.5)
                sw = c.stringWidth(sub, "Helvetica", 6.5)
                c.setFillColor(colors.HexColor("#B3E5FC"))
                c.drawString(x - sw/2, y - 9, sub)

        def arr(x1, y1, x2, y2, label=None, color=C_GRAY):
            c.setStrokeColor(color)
            c.setLineWidth(1.2)
            c.line(x1, y1, x2, y2)
            import math
            dx, dy = x2-x1, y2-y1
            length = math.sqrt(dx*dx + dy*dy)
            if length == 0: return
            ux, uy = dx/length, dy/length
            s = 6
            c.setFillColor(color)
            pts = [x2, y2,
                   x2 - s*ux + s*0.4*uy, y2 - s*uy - s*0.4*ux,
                   x2 - s*ux - s*0.4*uy, y2 - s*uy + s*0.4*ux]
            p = c.beginPath()
            p.moveTo(pts[0], pts[1])
            p.lineTo(pts[2], pts[3])
            p.lineTo(pts[4], pts[5])
            p.close()
            c.drawPath(p, fill=1, stroke=0)
            if label:
                mx, my = (x1+x2)/2 + 4, (y1+y2)/2
                c.setFont("Helvetica", 6.5)
                c.setFillColor(C_GREEN)
                c.drawString(mx, my, label)

        # ── Nodes top-to-bottom ──
        spacing = 38
        y_start = H - 20

        nodes = [
            (cx,       y_start,          "START",     None,              colors.HexColor("#1B5E20")),
            (cx,       y_start-spacing,  "planner",   "severity routing",C_AI),
            (cx,       y_start-spacing*2,"retriever", "Qdrant+ES RRF",   C_BLUE),
            (cx,       y_start-spacing*3,"generator", "GPT-4o/Claude/Ollama", C_BLUE_LIGHT),
            (cx,       y_start-spacing*4,"validator", "JSON-LD + safety",colors.HexColor("#E65100")),
        ]

        for (x, y, lbl, sub, fill) in nodes:
            node(x, y, lbl, sub, fill)

        # Straight arrows down the main path
        for i in range(len(nodes)-1):
            x1, y1 = nodes[i][0], nodes[i][1] - node_h/2
            x2, y2 = nodes[i+1][0], nodes[i+1][1] + node_h/2
            arr(x1, y1, x2, y2, color=C_NAVY)

        # validator → reviewer (right branch)
        vx, vy = nodes[-1][0], nodes[-1][1]
        reviewer_x = cx + 120
        reviewer_y = vy
        node(reviewer_x, reviewer_y, "reviewer", "Anthropic fallback", C_AI)
        arr(vx + node_w/2, vy, reviewer_x - node_w/2, reviewer_y,
            "low confidence", color=C_AMBER)

        # validator → retry (left branch)
        retry_x = cx - 120
        retry_y = vy
        node(retry_x, retry_y, "retry", "max 3 rounds", C_KAFKA_ACC)
        arr(vx - node_w/2, vy, retry_x + node_w/2, retry_y,
            "build failed", color=C_KAFKA_ACC)
        # retry → generator
        arr(retry_x, retry_y + node_h/2, cx - node_w/2 - 5, nodes[2][1],
            color=C_KAFKA_ACC)

        # validator → END
        end_y = y_start - spacing*5
        node(cx, end_y, "END", None, colors.HexColor("#1B5E20"))
        arr(vx, vy - node_h/2, cx, end_y + node_h/2, "accepted", color=C_GREEN)
        arr(reviewer_x, reviewer_y - node_h/2, cx, end_y + node_h/2,
            color=C_GREEN)

        # ── Model routing sidebar ──
        sx = 18
        c.setFillColor(C_GRAY_LIGHT)
        c.roundRect(sx, 10, 80, 95, 4, fill=1, stroke=0)
        c.setFont("Helvetica-Bold", 7)
        c.setFillColor(C_NAVY)
        c.drawString(sx+4, 93, "Model Routing")
        rows = [
            ("CRITICAL", "GPT-4o"),
            ("BLOCKER",  "GPT-4o"),
            ("MAJOR",    "qwen2:7b"),
            ("MINOR",    "llama3:8b"),
            ("INFO",     "llama3:8b"),
        ]
        ry = 80
        for sev, model in rows:
            c.setFont("Helvetica-Bold", 6.5)
            c.setFillColor(C_KAFKA_ACC if "CRIT" in sev or "BLOCK" in sev else
                           C_AMBER if "MAJOR" in sev else C_GREEN)
            c.drawString(sx+4, ry, sev)
            c.setFont("Helvetica", 6.5)
            c.setFillColor(C_GRAY_DARK)
            c.drawString(sx+44, ry, model)
            ry -= 13


class KafkaFlowDiagram(Flowable):
    """Kafka topic flow diagram."""
    def __init__(self, width=None, height=180):
        super().__init__()
        self._width = width or (PAGE_W - 2*MARGIN)
        self._height = height

    def wrap(self, aw, ah):
        self._width = aw
        return aw, self._height

    def draw(self):
        c = self.canv
        W, H = self._width, self._height

        rows = [
            ("cb-scanner",    "vulnerabilities.detected", "cb-agent",     C_SPRING,      C_AI),
            ("cb-agent",      "fixes.generated",          "cb-patcher",   C_AI,          C_GREEN),
            ("cb-patcher",    "fixes.validated",          "cb-pr",        C_GREEN,       C_BLUE),
            ("cb-pr",         "escalations.triggered",    "cb-escalation / cb-notifier", C_BLUE, C_AMBER),
            ("cb-pr",         "review.feedback",          "cb-agent",     C_BLUE,        C_AI),
        ]

        row_h = (H - 20) / len(rows)
        pub_w, sub_w = 80, 110
        topic_w = 160
        pad = 10
        total = pub_w + pad + topic_w + pad + sub_w
        start_x = (W - total) / 2

        # Header
        c.setFont("Helvetica-Bold", 8)
        c.setFillColor(C_NAVY)
        c.drawString(start_x, H - 12, "PUBLISHER")
        mid = start_x + pub_w + pad + topic_w/2
        c.drawCentredString(mid, H - 12, "KAFKA TOPIC")
        c.drawString(start_x + pub_w + pad + topic_w + pad, H - 12, "CONSUMER(S)")
        c.setStrokeColor(C_GRAY_MID)
        c.setLineWidth(0.5)
        c.line(start_x, H - 15, start_x + total, H - 15)

        for i, (pub, topic, sub, pub_col, sub_col) in enumerate(rows):
            y = H - 20 - i * row_h
            cy = y - row_h/2 + 8

            # Publisher box
            c.setFillColor(pub_col)
            c.roundRect(start_x, cy - 11, pub_w, 22, 4, fill=1, stroke=0)
            c.setFillColor(C_WHITE)
            c.setFont("Helvetica-Bold", 7)
            lw = c.stringWidth(pub, "Helvetica-Bold", 7)
            c.drawString(start_x + (pub_w - lw)/2, cy - 2, pub)

            # Arrow pub → topic
            c.setStrokeColor(C_KAFKA_ACC)
            c.setLineWidth(1.2)
            c.line(start_x + pub_w, cy, start_x + pub_w + pad, cy)

            # Topic box
            tx = start_x + pub_w + pad
            c.setFillColor(C_KAFKA)
            c.roundRect(tx, cy - 11, topic_w, 22, 4, fill=1, stroke=0)
            c.setFillColor(C_KAFKA_ACC)
            c.setFont("Helvetica-Bold", 7.5)
            tw = c.stringWidth(topic, "Helvetica-Bold", 7.5)
            c.drawString(tx + (topic_w - tw)/2, cy - 2, topic)

            # Arrow topic → subscriber
            sx2 = tx + topic_w
            c.setStrokeColor(C_KAFKA_ACC)
            c.line(sx2, cy, sx2 + pad, cy)
            # arrowhead
            c.setFillColor(C_KAFKA_ACC)
            c.setStrokeColor(C_KAFKA_ACC)
            pts = [sx2+pad, cy, sx2+pad-6, cy+4, sx2+pad-6, cy-4]
            p = c.beginPath()
            p.moveTo(pts[0], pts[1]); p.lineTo(pts[2], pts[3]); p.lineTo(pts[4], pts[5])
            p.close(); c.drawPath(p, fill=1, stroke=0)

            # Subscriber box
            c.setFillColor(sub_col)
            c.roundRect(sx2 + pad, cy - 11, sub_w, 22, 4, fill=1, stroke=0)
            c.setFillColor(C_WHITE)
            c.setFont("Helvetica-Bold", 7)
            slw = c.stringWidth(sub, "Helvetica-Bold", 7)
            if slw > sub_w - 4:
                c.setFont("Helvetica-Bold", 6)
                slw = c.stringWidth(sub, "Helvetica-Bold", 6)
            c.drawString(sx2 + pad + (sub_w - slw)/2, cy - 2, sub)

            # Row separator
            c.setStrokeColor(C_GRAY_LIGHT)
            c.setLineWidth(0.3)
            c.line(start_x, y - row_h + 2, start_x + total, y - row_h + 2)


# ── Page templates ────────────────────────────────────────────────────────────
def cover_page_bg(canvas, doc):
    """Draw cover page background."""
    canvas.saveState()
    W, H = A4
    # Dark navy background
    canvas.setFillColor(C_NAVY)
    canvas.rect(0, 0, W, H, fill=1, stroke=0)
    # Accent strip
    canvas.setFillColor(C_ACCENT)
    canvas.rect(0, H*0.38, W, 4, fill=1, stroke=0)
    # Top blue band
    canvas.setFillColor(C_BLUE)
    canvas.rect(0, H*0.72, W, H*0.28, fill=1, stroke=0)
    # Version badge
    canvas.setFillColor(C_ACCENT)
    canvas.roundRect(MARGIN, H*0.38 + 10, 56, 24, 5, fill=1, stroke=0)
    canvas.setFillColor(C_WHITE)
    canvas.setFont("Helvetica-Bold", 11)
    canvas.drawString(MARGIN + 6, H*0.38 + 18, "v3.0")
    canvas.restoreState()


def normal_page(canvas, doc):
    """Header/footer for normal pages."""
    canvas.saveState()
    W, H = A4
    # Header bar
    canvas.setFillColor(C_NAVY)
    canvas.rect(0, H - 1.2*cm, W, 1.2*cm, fill=1, stroke=0)
    canvas.setFillColor(C_WHITE)
    canvas.setFont("Helvetica-Bold", 8)
    canvas.drawString(MARGIN, H - 0.75*cm, "Compliance Buddy  |  v3  Architecture & Design Document")
    canvas.setFont("Helvetica", 8)
    canvas.drawRightString(W - MARGIN, H - 0.75*cm, f"Confidential  ·  {datetime.date.today().strftime('%B %Y')}")
    # Footer
    canvas.setStrokeColor(C_GRAY_MID)
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN, 1.5*cm, W - MARGIN, 1.5*cm)
    canvas.setFillColor(C_GRAY)
    canvas.setFont("Helvetica", 7.5)
    canvas.drawString(MARGIN, 0.9*cm, "© 2026 Compliance Buddy  –  Internal Architecture Document")
    canvas.drawRightString(W - MARGIN, 0.9*cm, f"Page {doc.page}")
    canvas.restoreState()


# ── Helper builders ───────────────────────────────────────────────────────────
def std_table(headers, rows, col_widths, S, hdr_color=C_NAVY):
    data = [[Paragraph(h, S["table_hdr"]) for h in headers]]
    for row in rows:
        data.append([Paragraph(str(c), S["table_cell"]) for c in row])
    t = Table(data, colWidths=col_widths)
    n = len(rows)
    t.setStyle(TableStyle([
        ("BACKGROUND",  (0,0), (-1,0), hdr_color),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [C_WHITE, C_GRAY_LIGHT]),
        ("GRID",        (0,0), (-1,-1), 0.4, C_GRAY_MID),
        ("TOPPADDING",  (0,0), (-1,-1), 5),
        ("BOTTOMPADDING",(0,0), (-1,-1), 5),
        ("LEFTPADDING", (0,0), (-1,-1), 6),
        ("RIGHTPADDING",(0,0), (-1,-1), 6),
        ("VALIGN",      (0,0), (-1,-1), "TOP"),
    ]))
    return t


def B(text, S):
    return Paragraph(f"• {text}", S["bullet"])

def BB(text, S):
    return Paragraph(f"– {text}", S["sub_bullet"])

def H1(text, S): return Paragraph(text, S["h1"])
def H2(text, S): return Paragraph(text, S["h2"])
def H3(text, S): return Paragraph(text, S["h3"])
def P(text, S):  return Paragraph(text, S["body"])
def PL(text, S): return Paragraph(text, S["body_left"])
def Note(text, S): return Paragraph(f"ℹ  {text}", S["note"])
def sp(h=6): return Spacer(1, h)
def hr(color=C_GRAY_MID): return HRFlowable(width="100%", thickness=0.5,
                                              color=color, spaceAfter=4, spaceBefore=4)


# ── Document Assembly ─────────────────────────────────────────────────────────
def build():
    out = "E:/CB_Automate/docs/CB_v3_Architecture_Document.pdf"
    doc = SimpleDocTemplate(
        out,
        pagesize=A4,
        leftMargin=MARGIN, rightMargin=MARGIN,
        topMargin=1.8*cm, bottomMargin=2*cm,
        title="Compliance Buddy v3 – Architecture & Design Document",
        author="Compliance Buddy Engineering",
        subject="HLD / LLD Architecture Document",
    )

    S = make_styles()
    story = []

    # ══════════════════════════════════════════════════════════════════════════
    # COVER PAGE
    # ══════════════════════════════════════════════════════════════════════════
    story.append(Spacer(1, 5.8*cm))
    story.append(Paragraph("Compliance Buddy", S["cover_title"]))
    story.append(Paragraph("Architecture &amp; Design Document", S["cover_sub"]))
    story.append(Spacer(1, 0.4*cm))
    story.append(Paragraph(
        "Autonomous AI-driven security remediation platform<br/>"
        "Kafka · Spring Boot 3 · LangGraph4j · GPT-4o · Claude · Ollama · k3d / Kubernetes",
        S["cover_meta"]))
    story.append(Spacer(1, 0.6*cm))
    meta_data = [
        ["Version",  "3.0.0"],
        ["Date",     datetime.date.today().strftime("%d %B %Y")],
        ["Status",   "Production"],
        ["Modules",  "9 microservices"],
        ["Author",   "Compliance Buddy Engineering"],
    ]
    for label, val in meta_data:
        story.append(Paragraph(
            f'<font color="#00ACC1"><b>{label}:</b></font> '
            f'<font color="#ECEFF1">{val}</font>',
            S["cover_meta"]))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 1. EXECUTIVE SUMMARY
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("1. Executive Summary", S))
    story.append(hr(C_BLUE))
    story.append(P(
        "Compliance Buddy (CB) v3 is a fully autonomous, AI-powered security remediation "
        "platform built on top of Apache Kafka, Spring Boot 3, and a multi-agent "
        "LangGraph4j workflow. It continuously monitors SonarQube for newly reported "
        "vulnerabilities, generates context-aware code fixes using GPT-4o, Claude Sonnet, "
        "or local Ollama models depending on severity, validates and applies those patches "
        "via JGit, opens pull requests on GitHub, and escalates unresolvable issues to Jira "
        "and Microsoft Teams — all without human intervention.", S))
    story.append(P(
        "v3 represents a complete architectural rewrite from the v1/v2 in-process "
        "Spring event model to a fully decoupled, fault-tolerant Kafka streaming pipeline "
        "with nine dedicated microservices, hybrid semantic + lexical RAG, Redis-backed "
        "prompt caching, OPA governance gates, and a comprehensive observability stack "
        "(Prometheus, Grafana, Tempo, Loki). A Model Context Protocol (MCP) server "
        "exposes 12 tools so AI assistants such as Claude Desktop and Cursor can "
        "participate directly in the remediation workflow.", S))

    story.append(sp(8))
    story.append(std_table(
        ["Capability", "v1/v2", "v3"],
        [
            ["Event transport", "In-process ApplicationEvents", "Apache Kafka (KRaft)"],
            ["AI workflow", "Single GPT-4o call", "LangGraph4j 5-node multi-agent graph"],
            ["Model routing", "GPT-4o only", "GPT-4o / Claude / Ollama by severity"],
            ["RAG", "None", "Hybrid: Qdrant (vector) + Elasticsearch (BM25) RRF"],
            ["Prompt cache", "None", "Redis 7 — SHA-256 keyed, 24 h TTL"],
            ["Escalation", "Email only", "Jira + Teams + RCA report"],
            ["PR governance", "None", "OPA Rego policy gate"],
            ["MCP tools", "0", "12 tools via cb-mcp-server"],
            ["Observability", "Logs only", "Prometheus + Grafana + Tempo + Loki"],
            ["Microservices", "3", "9"],
        ],
        [5.5*cm, 5.5*cm, 6.2*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 2. SYSTEM IMPORTANCE
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("2. System Importance &amp; Business Value", S))
    story.append(hr(C_BLUE))

    story.append(H2("2.1 The Problem", S))
    story.append(P(
        "Security debt compounds faster than engineering teams can address it. "
        "SonarQube scans routinely surface hundreds of CWEs — SQL injection, XSS, "
        "command injection, hardcoded credentials — yet the average time-to-fix "
        "in enterprise teams is measured in days or weeks. Manual triage, "
        "context-switching, and PR review overhead are the primary bottlenecks.", S))

    story.append(H2("2.2 Business Value Delivered", S))
    vals = [
        ("<b>Zero-touch remediation:</b>", "CRITICAL and BLOCKER vulnerabilities are fixed, "
         "build-validated, and PR-raised within minutes of SonarQube detection, "
         "with no engineer involvement required."),
        ("<b>Cost-aware model selection:</b>", "Routing MINOR/INFO vulns to free local Ollama "
         "models (llama3:8b) reduces LLM spend by 60–80% compared to always calling GPT-4o. "
         "The /api/v1/cost/* endpoints track spend per CWE and per model in real time."),
        ("<b>Institutional knowledge capture:</b>", "Every accepted fix is embedded and stored "
         "in Qdrant so future fixes for the same CWE benefit from previously validated patterns — "
         "accuracy improves with scale."),
        ("<b>Governance by policy:</b>", "OPA Rego policies block PRs that introduce "
         "new critical dependencies, touch security-sensitive modules without senior "
         "reviewer assignment, or violate licensing constraints — all without code changes."),
        ("<b>Autonomous escalation:</b>", "When a vulnerability cannot be fixed automatically "
         "after three retries, CB raises a Jira ticket with an LLM-generated RCA report "
         "and posts an adaptive card to the responsible team's Teams channel."),
        ("<b>AI-native tooling:</b>", "The MCP server lets engineers interrogate the "
         "pipeline through Claude Desktop or Cursor — searching past fixes, triggering "
         "rebuilds, querying traces — without writing curl commands."),
    ]
    for label, text in vals:
        story.append(B(f"{label} {text}", S))
        story.append(sp(2))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 3. HIGH LEVEL DESIGN
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("3. High Level Design (HLD)", S))
    story.append(hr(C_BLUE))

    story.append(H2("3.1 Architecture Diagram", S))
    story.append(HLDDiagram(height=345))
    story.append(Paragraph("Figure 1 — Compliance Buddy v3 High Level Architecture", S["caption"]))
    story.append(sp(8))

    story.append(H2("3.2 Architecture Principles", S))
    principles = [
        ("Event-driven &amp; decoupled", "Every service publishes to and consumes from "
         "Kafka topics. No direct HTTP calls between pipeline services. A service can "
         "be restarted, scaled, or replaced without impacting others."),
        ("Idempotent consumers", "All Kafka consumers operate in MANUAL_IMMEDIATE "
         "acknowledge mode with EARLIEST offset reset. The DefaultErrorHandler retries "
         "transient failures 3× before dead-lettering."),
        ("Defense in depth", "Three independent safety layers: (1) LangGraph4j validator "
         "node checks fix JSON-LD schema and runs a safety classifier; (2) cb-patcher runs "
         "the full build+test suite against the patch; (3) OPA governance gate in cb-pr."),
        ("Separation of AI concerns", "The planner node chooses the model; the generator "
         "calls it; the validator checks output; the reviewer (Anthropic) provides "
         "independent critique. No single node holds both generation and validation."),
        ("Observability first", "Every service emits OpenTelemetry traces to Tempo, "
         "Prometheus metrics via Micrometer, and structured logs to Loki. All Kafka "
         "message processing is correlated via a shared vulnerability ID trace attribute."),
    ]
    for title, desc in principles:
        story.append(KeepTogether([
            Paragraph(f"<b>{title}</b>", S["h3"]),
            Paragraph(desc, S["body"]),
            sp(4),
        ]))

    story.append(H2("3.3 Kafka Event Bus Design", S))
    story.append(KafkaFlowDiagram(height=185))
    story.append(Paragraph("Figure 2 — Kafka topic flow between services", S["caption"]))
    story.append(sp(8))

    story.append(std_table(
        ["Topic", "Event Type", "Publisher", "Consumer(s)", "Schema"],
        [
            ["vulnerabilities.detected","VulnerabilityKafkaEvent","cb-scanner","cb-agent","severity, cwe, file, line, ruleKey"],
            ["fixes.generated","FixKafkaEvent","cb-agent","cb-patcher","diff, confidence, model, tokensUsed"],
            ["fixes.validated","FixKafkaEvent","cb-patcher","cb-pr","patchedBranch, buildLog, testResults"],
            ["escalations.triggered","EscalationKafkaEvent","cb-pr","cb-escalation, cb-notifier","reason, retryCount, rcaReport"],
            ["review.feedback","ReviewFeedbackKafkaEvent","cb-pr","cb-agent","accepted, reviewerNotes, fixId"],
            ["cost.tracked","CostKafkaEvent","cb-agent","cb-api","model, inputTokens, outputTokens, cweId"],
        ],
        [3.5*cm, 4*cm, 2.5*cm, 3.5*cm, 3.2*cm], S
    ))
    story.append(PageBreak())

    story.append(H2("3.4 Infrastructure Topology", S))
    story.append(std_table(
        ["Component", "Role", "Port", "Persistence"],
        [
            ["Kafka (KRaft)","Async event bus — no ZooKeeper","9092","Topic segments on PVC"],
            ["MongoDB 7","Primary datastore (vulns, fixes, costs, audit)","27017","PVC"],
            ["Redis 7","LLM prompt cache (SHA-256, 24 h TTL)","6379","In-memory + AOF"],
            ["Elasticsearch 8.13","BM25 lexical index for hybrid RAG","9200","PVC"],
            ["Qdrant","Vector store — text-embedding-3-small (1536-d)","6333","PVC"],
            ["OPA","Rego governance policy engine","8181","Rego policy ConfigMap"],
            ["Ollama","Local LLM inference (llama3:8b, qwen2:7b)","11434","Model weights PVC"],
            ["SonarQube","SAST scanner — CB polls every 5 min","9000","PVC"],
            ["Prometheus","Metrics scraper (15 s interval)","9090","TSDB PVC"],
            ["Grafana","Dashboards — CB pipeline + JVM + Kafka","3000","Dashboard ConfigMap"],
            ["Tempo","OTLP distributed tracing collector","4318","Trace storage PVC"],
            ["Loki","Log aggregation (via Promtail DaemonSet)","3100","Log chunks PVC"],
        ],
        [3.5*cm, 5.5*cm, 1.8*cm, 5.4*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 4. LOW LEVEL DESIGN
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("4. Low Level Design (LLD)", S))
    story.append(hr(C_BLUE))

    # ── 4.1 cb-agent LangGraph4j workflow ────────────────────────────────────
    story.append(H2("4.1 cb-agent — LangGraph4j Workflow", S))
    story.append(LangGraphDiagram(height=230))
    story.append(Paragraph("Figure 3 — LangGraph4j StateGraph nodes and conditional edges", S["caption"]))
    story.append(sp(6))

    story.append(H3("Node Responsibilities", S))
    story.append(std_table(
        ["Node", "Input State Fields", "Action", "Output State Fields"],
        [
            ["planner","vulnerabilityId, severity, cweId, ruleKey",
             "Routes to model, builds retrieval query, sets retryCount=0",
             "selectedModel, retrievalQuery"],
            ["retriever","retrievalQuery, cweId",
             "Qdrant semantic search (top-5) + ES BM25 (top-5), RRF fusion → top-6 chunks",
             "retrievedChunks, hybridScore"],
            ["generator","retrievedChunks, selectedModel, fileContent, lineNumber",
             "Checks Redis prompt cache (SHA-256). On miss: calls LLM with RAG context. On hit: returns cached fix.",
             "generatedFix, confidence, tokensUsed, cacheHit"],
            ["validator","generatedFix, fileContent",
             "JSON-LD schema check, safety classifier (no exec/rm -rf patterns), diff syntactically valid",
             "validationPassed, validationErrors"],
            ["reviewer","generatedFix, validationErrors",
             "Calls Anthropic claude-sonnet-4-6 for independent code review",
             "reviewPassed, reviewNotes"],
            ["retry","retryCount, validationErrors",
             "Increments retryCount. If ≤3 → back to retriever with enriched query. Else → publishes escalation.",
             "retryCount, escalationTriggered"],
        ],
        [2*cm, 3.5*cm, 5*cm, 5.7*cm], S
    ))

    story.append(H3("State Object", S))
    story.append(Paragraph(
        "The shared <code>AgentState</code> record carries all fields through the graph. "
        "LangGraph4j serialises it as JSON between node executions, enabling pause/resume "
        "and future distributed execution across multiple cb-agent replicas.", S["body"]))
    code_lines = [
        "record AgentState(",
        "  String vulnerabilityId, String severity, String cweId,",
        "  String fileContent, int lineNumber, String ruleKey,",
        "  String selectedModel, String retrievalQuery,",
        "  List<String> retrievedChunks, double hybridScore,",
        "  String generatedFix, double confidence,",
        "  int tokensUsed, boolean cacheHit,",
        "  boolean validationPassed, List<String> validationErrors,",
        "  boolean reviewPassed, String reviewNotes,",
        "  int retryCount, boolean escalationTriggered",
        ") {}"
    ]
    for line in code_lines:
        story.append(Paragraph(line, S["code"]))
    story.append(sp(8))

    # ── 4.2 Kafka Consumer Pattern ────────────────────────────────────────────
    story.append(H2("4.2 Kafka Consumer Hardening Pattern", S))
    story.append(P(
        "Every CB consumer module uses an identical Kafka hardening configuration. "
        "This section documents the pattern once; it applies to "
        "cb-agent, cb-patcher, cb-pr, cb-notifier, and cb-escalation.", S))
    story.append(std_table(
        ["Configuration", "Value", "Rationale"],
        [
            ["value-deserializer","ErrorHandlingDeserializer",
             "Wraps JsonDeserializer so malformed messages route to DefaultErrorHandler instead of crashing the consumer thread"],
            ["spring.deserializer.value.delegate.class","JsonDeserializer",
             "The actual deserialiser used after ErrorHandlingDeserializer wraps it"],
            ["spring.json.use.type.headers","false",
             "Prevents __TypeId__ header injection attacks from untrusted producers"],
            ["spring.json.value.default.type","e.g. FixKafkaEvent",
             "Pins deserialization target — no dynamic class loading"],
            ["AckMode","MANUAL_IMMEDIATE",
             "Consumer controls offset commit — no message lost on crash"],
            ["FixedBackOff","1000 ms × 3 retries",
             "Transient listener errors retried 3× before skipping"],
            ["addNotRetryableExceptions","DeserializationException",
             "Poison-pill messages skipped immediately — no retry loop"],
        ],
        [4.5*cm, 4*cm, 7.7*cm], S
    ))
    story.append(sp(8))

    # ── 4.3 MongoDB Collections ───────────────────────────────────────────────
    story.append(H2("4.3 MongoDB Collections", S))
    story.append(std_table(
        ["Collection", "Key Fields", "Indexes", "Owner Module"],
        [
            ["vulnerabilities","id, projectKey, ruleKey, cweId, severity, status, component, line, message, createdAt, updatedAt",
             "status, severity, projectKey, createdAt (TTL 90d)",
             "cb-api reads; cb-scanner writes; cb-agent updates status"],
            ["fixes","id, vulnerabilityId, diff, model, confidence, tokensUsed, status, buildLog, branch, createdAt",
             "vulnerabilityId, status, model",
             "cb-agent writes; cb-patcher updates; cb-pr reads"],
            ["cost_records","id, fixId, cweId, model, inputTokens, outputTokens, estimatedCostUsd, createdAt",
             "createdAt (range queries), cweId, model",
             "cb-agent writes; cb-api reads for cost endpoints"],
            ["audit_events","id, entityId, entityType, action, actor, details, timestamp",
             "entityId, timestamp",
             "All modules write; cb-api exposes /audit"],
            ["escalations","id, vulnerabilityId, reason, rcaReport, jiraTicketId, teamsMessageId, createdAt",
             "vulnerabilityId",
             "cb-escalation writes"],
        ],
        [3*cm, 5.5*cm, 3.8*cm, 3.9*cm], S
    ))
    story.append(PageBreak())

    # ── 4.4 REST API Contract ─────────────────────────────────────────────────
    story.append(H2("4.4 REST API Contract (cb-api)", S))
    story.append(Note(
        "All endpoints require header X-API-Key: dev-key-change-in-prod. "
        "Swagger UI: http://compliance-buddy.local/swagger-ui/index.html", S))
    story.append(sp(4))
    story.append(std_table(
        ["Method", "Path", "Request", "Response", "Description"],
        [
            ["POST","/api/v1/scans/{projectKey}","—","{ newFindings, status }","Pull SonarQube issues"],
            ["GET","/api/v1/vulnerabilities","?status= ?severity= ?projectKey=","Page<Vulnerability>","List vulnerabilities"],
            ["GET","/api/v1/vulnerabilities/{id}","—","Vulnerability","Single vulnerability"],
            ["POST","/api/v1/vulnerabilities/retry/{id}","—","{ queued: true }","Re-queue AI fix"],
            ["GET","/api/v1/fixes","—","List<Fix>","All generated fixes"],
            ["GET","/api/v1/fixes/{id}","—","Fix","Single fix with diff"],
            ["GET","/api/v1/metrics","—","{ vulnerabilities{}, fixes{}, remediationRate }","Dashboard KPIs"],
            ["GET","/api/v1/cost/summary","?days=7","{ totalCostUsd, totalTokens, byModel[] }","Cost summary"],
            ["GET","/api/v1/cost/by-cwe","?days=30","[ { cweId, totalCostUsd } ]","Cost by CWE"],
            ["GET","/api/v1/cost/by-model","?days=30","[ { model, totalCostUsd, totalTokens } ]","Cost by model"],
            ["GET","/api/v1/audit","—","List<AuditEvent>","Full audit trail"],
            ["GET","/api/v1/audit/entity/{entityId}","—","List<AuditEvent>","Per-entity audit"],
            ["GET","/actuator/health","—","{ status: UP }","Health (no auth)"],
            ["GET","/actuator/prometheus","—","Prometheus text","Metrics scrape"],
        ],
        [1.4*cm, 5*cm, 3.2*cm, 3.2*cm, 3.4*cm], S
    ))
    story.append(PageBreak())

    # ── 4.5 OPA Governance ───────────────────────────────────────────────────
    story.append(H2("4.5 OPA Governance Gate (cb-pr)", S))
    story.append(P(
        "Before creating any GitHub PR, cb-pr sends a governance request to OPA at "
        "http://opa:8181/v1/data/cb/pr/allow. OPA evaluates Rego policies against the "
        "fix metadata. If the policy denies the request, cb-pr publishes an "
        "escalation event instead of opening a PR.", S))
    story.append(H3("Governance Rules (Rego)", S))
    rego_rules = [
        "deny if fix confidence < 0.70",
        "deny if patch touches any file in security-sensitive-paths (auth/*, crypto/*)",
        "  and no senior-reviewer is assigned",
        "deny if introduced dependency has known CVE in OSV database",
        "deny if fix modifies > 50 lines (too large for autonomous PR — escalate)",
        "allow if all deny conditions are absent",
    ]
    for line in rego_rules:
        story.append(Paragraph(line, S["code"]))
    story.append(sp(8))

    # ── 4.6 Hybrid RAG Pipeline ──────────────────────────────────────────────
    story.append(H2("4.6 Hybrid RAG Pipeline (cb-agent retriever node)", S))
    story.append(std_table(
        ["Step", "System", "Action", "Score"],
        [
            ["1. Semantic search","Qdrant","text-embedding-3-small query embedding → cosine top-10","Vector similarity 0–1"],
            ["2. Lexical search","Elasticsearch","BM25 query on cweId + ruleKey + fileExtension fields → top-10","BM25 relevance"],
            ["3. RRF fusion","cb-agent","Reciprocal Rank Fusion: score = Σ 1/(k+rank_i), k=60","Hybrid RRF score"],
            ["4. Top-6 chunks","cb-agent","Take top-6 RRF results → inject into LLM prompt as <context>","—"],
            ["5. Cache key","Redis","SHA-256(cweId + ruleKey + top-6 chunk IDs + model)","—"],
            ["6. Accepted fix indexing","Qdrant","review.feedback = ACCEPTED → embed fix diff → upsert cb_fixes collection","—"],
        ],
        [1.5*cm, 3*cm, 8*cm, 3.7*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 5. MODULE DETAILS
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("5. Module Details", S))
    story.append(hr(C_BLUE))

    modules = [
        {
            "name": "cb-core",
            "port": "—  (library, not deployed)",
            "color": C_GRAY,
            "desc": (
                "Shared library imported by all CB service modules. Contains domain records, "
                "Kafka event types, repository interfaces, async executor configuration, and "
                "Redis cache configuration. Centralising these here ensures all modules use "
                "identical event schemas and avoids duplicated bean definitions."
            ),
            "importance": (
                "cb-core is the contract layer. Any change to a Kafka event record here "
                "propagates to all publishers and consumers simultaneously, preventing "
                "schema drift between services."
            ),
            "key_classes": [
                "VulnerabilityKafkaEvent — Kafka message for new findings",
                "FixKafkaEvent — Kafka message for generated / validated fixes",
                "EscalationKafkaEvent — Kafka message triggering escalation pipeline",
                "ReviewFeedbackKafkaEvent — Kafka message carrying PR review outcome",
                "CostKafkaEvent — Kafka message for LLM cost tracking",
                "KafkaTopics — string constants for all topic names",
                "AsyncConfig — defines cbAgentExecutor, cbPatcherExecutor, cbPrExecutor, cbNotifierExecutor",
                "CacheConfig — Redis Lettuce pool, TTL policy",
                "VulnerabilityRepository, FixRepository, CostRepository — Spring Data MongoDB",
            ],
        },
        {
            "name": "cb-scanner",
            "port": "8081",
            "color": C_SPRING,
            "desc": (
                "Polls SonarQube every 5 minutes for OPEN issues. De-duplicates against "
                "MongoDB to avoid reprocessing. Maps SonarQube rule → CWE ID via an "
                "internal lookup table. Publishes VulnerabilityKafkaEvent to "
                "vulnerabilities.detected. Also exposes /api/v1/scans/{projectKey} for "
                "on-demand polling triggered by cb-api."
            ),
            "importance": (
                "The ingestion gateway — all downstream pipeline activity starts here. "
                "Correct severity mapping determines which LLM model the agent selects, "
                "so rule → CWE → severity accuracy directly impacts fix quality and cost."
            ),
            "key_classes": [
                "SonarQubePollerService — @Scheduled every 5 min, Feign client to SonarQube",
                "VulnerabilityKafkaPublisher — publishes to vulnerabilities.detected",
                "CweMapper — 40+ SonarQube rule keys → CWE IDs",
            ],
        },
        {
            "name": "cb-agent",
            "port": "8082",
            "color": C_AI,
            "desc": (
                "The intelligence core. Consumes VulnerabilityKafkaEvent, runs the "
                "5-node LangGraph4j StateGraph (planner → retriever → generator → validator → "
                "[retry|reviewer] → END), and publishes FixKafkaEvent to fixes.generated. "
                "Also consumes review.feedback to index accepted fixes back into Qdrant for "
                "continuous learning."
            ),
            "importance": (
                "The most complex module. Fix quality, cost efficiency, and remediation rate "
                "all depend on this module's hybrid RAG accuracy, prompt engineering, and "
                "model routing decisions."
            ),
            "key_classes": [
                "AgentWorkflowConsumer — @KafkaListener on vulnerabilities.detected",
                "LangGraphWorkflow — builds StateGraph, wires nodes and conditional edges",
                "PlannerNode — severity → model routing, query formulation",
                "RetrieverNode — Qdrant + Elasticsearch RRF fusion",
                "GeneratorNode — Redis cache check → LLM call (Spring AI ChatClient)",
                "ValidatorNode — JSON-LD schema + safety pattern checks",
                "ReviewerNode — Anthropic claude-sonnet-4-6 independent review",
                "RetryNode — retry logic, escalation trigger at retryCount > 3",
                "FeedbackConsumer — indexes accepted fixes into Qdrant",
                "AgentConfig — LangGraph4j bean, Spring AI ChatClient beans (OpenAI + Anthropic)",
            ],
        },
        {
            "name": "cb-patcher",
            "port": "8083",
            "color": C_GREEN,
            "desc": (
                "Consumes FixKafkaEvent from fixes.generated. Clones the target repository "
                "via JGit, applies the unified diff patch, runs ./gradlew build test "
                "(or mvn verify for Maven projects), and publishes fixes.validated on success. "
                "On build failure, increments retryCount and republishes to fixes.generated "
                "for the agent to regenerate. After three failed builds, publishes to "
                "escalations.triggered."
            ),
            "importance": (
                "The safety net before any code touches version control. A fix that passes "
                "the LLM validator but breaks the build is caught here, not after PR merge."
            ),
            "key_classes": [
                "PatchConsumer — @KafkaListener on fixes.generated",
                "JGitPatchService — clone, apply diff, commit to branch",
                "BuildValidationService — ProcessBuilder for gradle/maven, parses exit code",
                "PatcherConfig — ConcurrentKafkaListenerContainerFactory with DefaultErrorHandler",
            ],
        },
        {
            "name": "cb-pr",
            "port": "8084",
            "color": C_BLUE,
            "desc": (
                "Consumes FixKafkaEvent from fixes.validated. Sends fix metadata to OPA "
                "for governance check. If approved: creates GitHub PR via GitHub REST API, "
                "creates Version1 work item, requests reviewer. Publishes review.feedback "
                "when PR is merged/declined. Publishes escalations.triggered if OPA denies."
            ),
            "importance": (
                "The governance checkpoint. Ensures no AI-generated code reaches main without "
                "passing policy and receiving a human-assigned reviewer. The OPA integration "
                "makes policy changes purely declarative — no code deployments required."
            ),
            "key_classes": [
                "PrConsumer — @KafkaListener on fixes.validated",
                "OpaGovernanceService — POST /v1/data/cb/pr/allow to OPA",
                "GovernanceResult — record(boolean allowed, List<String> violations)",
                "GitHubPrService — GitHub REST API via RestTemplate",
                "Version1Service — VersionOne REST API ticket creation",
                "KafkaPrConfig — ConcurrentKafkaListenerContainerFactory with DefaultErrorHandler",
            ],
        },
        {
            "name": "cb-notifier",
            "port": "8085",
            "color": colors.HexColor("#00838F"),
            "desc": (
                "Consumes EscalationKafkaEvent. Sends email via JavaMailSender to the "
                "team email list defined in NOTIFIER_TEAM_EMAILS. Posts a Datadog event "
                "for observability dashboards. Stateless — all state lives in the Kafka "
                "event payload."
            ),
            "importance": (
                "Ensures human awareness for every escalation. Even if Jira or Teams is "
                "unavailable, the email channel provides a fallback notification path."
            ),
            "key_classes": [
                "NotificationConsumer — @KafkaListener on escalations.triggered",
                "EmailNotificationService — JavaMailSender template",
                "DatadogEventService — Datadog Events API",
                "KafkaNotifierConfig — ConcurrentKafkaListenerContainerFactory with DefaultErrorHandler",
            ],
        },
        {
            "name": "cb-mcp-server",
            "port": "8086",
            "color": colors.HexColor("#00796B"),
            "desc": (
                "Implements the Model Context Protocol (MCP) SSE transport at /sse. "
                "Exposes 12 tools for AI assistants (Claude Desktop, Cursor, etc.) to "
                "interact with the CB pipeline: querying fixes, triggering builds, "
                "fetching traces, searching Qdrant, creating PRs, rolling back merges."
            ),
            "importance": (
                "Bridges the CB backend with AI-native workflows. Engineers can use natural "
                "language via Claude Desktop to debug the pipeline, search past incidents, "
                "or trigger remediations — accelerating incident response."
            ),
            "key_classes": [
                "McpServerConfig — Spring AI MCP server bean, SSE transport",
                "FindPreviousFixesTool, GetSonarIssueTool, GetPRDiffTool",
                "BuildProjectTool, CreatePRTool, GetBuildLogTool",
                "SearchPastIncidentsTool, QueryQdrantTool, RegeneratFixTool",
                "GetTraceTool (Tempo), GetMetricsTool (Prometheus), TriggerRollbackTool",
            ],
        },
        {
            "name": "cb-escalation",
            "port": "8089",
            "color": C_AMBER,
            "desc": (
                "Consumes EscalationKafkaEvent. Generates an RCA (Root Cause Analysis) "
                "report using GPT-4o. Creates a Jira ticket with the RCA attached. "
                "Posts a Microsoft Teams adaptive card with severity badge, retry history, "
                "and a direct link to the Jira ticket. All three outputs "
                "(RCA, Jira, Teams) are configurable via feature flags so the module "
                "can be deployed in notification-only mode."
            ),
            "importance": (
                "Closes the escalation loop. Without this module, unresolvable "
                "vulnerabilities silently stall. cb-escalation ensures accountability: "
                "a Jira ticket means a human owns the issue and has an LLM-generated "
                "starting point for manual remediation."
            ),
            "key_classes": [
                "EscalationConsumer — @KafkaListener on escalations.triggered",
                "RcaGenerationService — GPT-4o prompt with vuln + retry history context",
                "JiraEscalationService — Jira REST API v3 (create issue + attach RCA)",
                "TeamsNotificationService — Teams Incoming Webhook (adaptive card JSON)",
                "EscalationConfig — ConcurrentKafkaListenerContainerFactory + escalationExecutor",
            ],
        },
        {
            "name": "cb-api",
            "port": "8080",
            "color": C_NAVY,
            "desc": (
                "Public REST API surface. Spring Security API-key filter (X-API-Key header). "
                "SpringDoc OpenAPI 3 with Swagger UI. Exposes all read/write operations "
                "on vulnerabilities, fixes, metrics, cost tracking, and audit. Prometheus "
                "metrics via /actuator/prometheus. Ingress controller routes "
                "compliance-buddy.local/* to this service."
            ),
            "importance": (
                "The single entry point for all external consumers: engineers, CI/CD "
                "pipelines, dashboards, and the cb-mcp-server. API-key authentication "
                "ensures every caller is identified in the audit trail."
            ),
            "key_classes": [
                "VulnerabilityController, FixController, MetricsController",
                "CostTrackingController — /cost/summary, /cost/by-cwe, /cost/by-model",
                "ScanController — triggers on-demand SonarQube poll via cb-scanner",
                "AuditController — full audit trail and per-entity audit history",
                "ApiKeyAuthFilter — Spring Security OncePerRequestFilter",
                "OpenApiConfig — SpringDoc bean with API-key security scheme",
            ],
        },
    ]

    for mod in modules:
        story.append(KeepTogether([
            SectionDivider(f"  {mod['name']}   ·   port {mod['port']}", mod["color"]),
            sp(6),
            Paragraph(mod["desc"], S["body"]),
            sp(4),
            Paragraph("<b>Importance:</b>", S["h3"]),
            Paragraph(mod["importance"], S["body"]),
            sp(4),
            Paragraph("<b>Key Classes / Beans:</b>", S["h3"]),
        ] + [BB(kc, S) for kc in mod["key_classes"]] + [sp(10)]))

    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 6. SECURITY
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("6. Security Design", S))
    story.append(hr(C_BLUE))

    security_areas = [
        ("API Authentication", [
            "All REST endpoints protected by ApiKeyAuthFilter (OncePerRequestFilter).",
            "Multiple keys supported via CB_API_KEYS comma-separated list — rotate without downtime.",
            "/actuator/health exempted for Kubernetes liveness/readiness probes.",
        ]),
        ("Secrets Management", [
            "All credentials injected via Kubernetes Secrets (k8s/base/secrets.yaml).",
            "Vault integration available via spring.cloud.vault.enabled=true — disabled by default.",
            "No credentials in environment variables at build time; no credentials in Docker images.",
        ]),
        ("Kafka Security", [
            "ErrorHandlingDeserializer prevents deserialization-based attacks.",
            "spring.json.use.type.headers=false blocks __TypeId__ header injection.",
            "spring.json.value.default.type pins deserialization class — no dynamic class loading.",
        ]),
        ("OPA Governance", [
            "Rego policies evaluated server-side in OPA — policy changes require no code deployment.",
            "cb-pr hard-fails (escalates) on OPA timeout or connection error — fail-closed.",
            "Policy decisions logged to audit_events collection for compliance reporting.",
        ]),
        ("Container Security", [
            "All CB images run as non-root user cbapp (UID 1001).",
            "eclipse-temurin:17-jre-jammy base image — minimal JRE, no JDK, no build tools.",
            "XX:+UseContainerSupport ensures JVM respects cgroup memory limits.",
        ]),
        ("Network Security", [
            "All inter-service communication is within the cb-system namespace.",
            "Only cb-api is exposed via Ingress — all other services are ClusterIP.",
            "OPA, MongoDB, Redis, Elasticsearch are not exposed outside the cluster.",
        ]),
    ]
    for title, points in security_areas:
        story.append(H3(title, S))
        for pt in points:
            story.append(B(pt, S))
        story.append(sp(6))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 7. OBSERVABILITY
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("7. Observability", S))
    story.append(hr(C_BLUE))

    story.append(H2("7.1 Metrics (Prometheus + Grafana)", S))
    story.append(std_table(
        ["Metric Name", "Type", "Labels", "Description"],
        [
            ["cb_vulnerabilities_detected_total","Counter","projectKey, severity, cweId","New vulns ingested from SonarQube"],
            ["cb_fixes_generated_total","Counter","model, cacheHit, cweId","AI fixes produced"],
            ["cb_prs_created_total","Counter","projectKey, severity","GitHub PRs opened"],
            ["cb_escalations_triggered_total","Counter","reason","Jira/Teams escalations"],
            ["cb_llm_cost_usd_total","Counter","model, cweId","Cumulative LLM API spend"],
            ["cb_llm_tokens_total","Counter","model, direction","Input + output token counts"],
            ["cb_fix_confidence","Gauge","model, cweId","Last fix confidence score (0–1)"],
            ["cb_pipeline_duration_seconds","Histogram","severity","End-to-end detection→PR latency"],
            ["cb_kafka_consumer_lag","Gauge","topic, group","Kafka consumer lag per topic"],
            ["cb_redis_cache_hit_total","Counter","cweId","Prompt cache hits"],
            ["cb_build_validation_duration_seconds","Histogram","buildTool","Gradle/Maven build time"],
        ],
        [5*cm, 1.8*cm, 3.8*cm, 5.6*cm], S
    ))

    story.append(H2("7.2 Distributed Tracing (Tempo)", S))
    story.append(P(
        "All CB services export OTLP traces to Tempo at http://tempo:4318/v1/traces. "
        "Sampling probability is 1.0 (100% of traces). Each vulnerability processing "
        "pipeline carries the vulnerabilityId as a baggage attribute, enabling "
        "end-to-end trace correlation from cb-scanner through cb-pr. "
        "Use Grafana Explore → Tempo data source to search by traceId or attribute.", S))

    story.append(H2("7.3 Logging (Loki + Promtail)", S))
    story.append(P(
        "Promtail runs as a DaemonSet and tails all pod logs in cb-system, labelling "
        "them with app, namespace, pod, and container. Logs are pushed to Loki and "
        "queryable in Grafana Explore → Loki. All CB services emit JSON-structured "
        "logs including vulnerabilityId, fixId, model, and duration where applicable.", S))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 8. CWE COVERAGE
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("8. CWE Coverage", S))
    story.append(hr(C_BLUE))

    story.append(std_table(
        ["CWE", "Name", "Fix Strategy", "Confidence"],
        [
            ["CWE-89","SQL Injection","Replace string concat with PreparedStatement / JPA named parameters","0.93"],
            ["CWE-79","Cross-Site Scripting (XSS)","HtmlUtils.htmlEscape on all user input output paths + CSP header","0.91"],
            ["CWE-78","OS Command Injection","Replace Runtime.exec(String) with ProcessBuilder(List<String>)","0.95"],
            ["CWE-22","Path Traversal","Path.toRealPath() + base-dir prefix check before file access","0.90"],
            ["CWE-798","Hardcoded Credentials","Extract to @Value + Kubernetes Secret / Vault reference","0.96"],
            ["CWE-327","Use of Broken/Risky Crypto","Upgrade MD5/SHA-1 to SHA-256; DES to AES-256-GCM","0.92"],
            ["CWE-918","Server-Side Request Forgery","URL allow-list validation before HTTP client calls","0.88"],
            ["CWE-330","Insufficient Randomness","Replace Math.random() / new Random() with SecureRandom","0.97"],
            ["CWE-502","Deserialization of Untrusted Data","Replace Java deserialization with Jackson JSON","0.89"],
            ["CWE-611","XXE Injection","Disable DOCTYPE + external entities on DocumentBuilderFactory","0.94"],
            ["CWE-200","Information Exposure","Remove stack traces from HTTP responses; add @ExceptionHandler","0.85"],
            ["CWE-352","CSRF","Enable Spring Security CSRF token for state-changing endpoints","0.91"],
        ],
        [1.8*cm, 4*cm, 7.5*cm, 2.5*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 9. DEPLOYMENT
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("9. Deployment &amp; Operations", S))
    story.append(hr(C_BLUE))

    story.append(H2("9.1 Local Deployment (k3d)", S))
    story.append(std_table(
        ["Step", "Command / Script", "Notes"],
        [
            ["1. Start cluster","start-cb.bat (or start-cb.ps1)","Starts Docker + k3d + waits for pods"],
            ["2. First-time secrets","kubectl apply -k k8s/base/","Edit secrets.yaml first"],
            ["3. Build a module","./gradlew :cb-agent:bootJar","Java 17, Gradle 8.14"],
            ["4. Package image","docker build -f cb-agent/Dockerfile.prebuilt -t cb-agent:latest cb-agent/","Note: module dir as build context"],
            ["5. Load into k3d","k3d image import cb-agent:latest -c compliance-buddy","Via Bash (k3d not on PS PATH)"],
            ["6. Restart pod","kubectl rollout restart deployment/cb-agent -n cb-system",""],
            ["7. Watch logs","kubectl logs -n cb-system -l app=cb-agent -f",""],
            ["8. Stop cluster","stop-cb.bat","Data preserved"],
        ],
        [1.5*cm, 7*cm, 7.7*cm], S
    ))

    story.append(H2("9.2 Dockerfile Pattern (Dockerfile.prebuilt)", S))
    story.append(P(
        "All CB modules use an identical Dockerfile.prebuilt pattern. "
        "The root .dockerignore excludes **/build so images must be built using "
        "the module directory as the Docker build context, not the repo root.", S))
    dockerfile_lines = [
        "FROM eclipse-temurin:17-jre-jammy",
        "RUN groupadd -r cbapp && useradd -r -g cbapp -u 1001 cbapp",
        "WORKDIR /app",
        "COPY build/libs/cb-<module>-1.0.0.jar app.jar   # local path, not cb-<module>/build/",
        "RUN chown cbapp:cbapp app.jar",
        "USER cbapp",
        "EXPOSE <port>",
        'ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0",',
        '            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]',
    ]
    for line in dockerfile_lines:
        story.append(Paragraph(line, S["code"]))
    story.append(sp(6))

    story.append(H2("9.3 Known Cluster Limitations", S))
    story.append(std_table(
        ["Pod", "State", "Reason", "Impact"],
        [
            ["cb-python-embedder","ImagePullBackOff","Python FastAPI not built/pushed","text-embedding fallback to Spring AI inline embedding"],
            ["opa","ImagePullBackOff","OPA image not in local registry","cb-pr runs with OPA_ENABLED=false"],
            ["ollama","CrashLoopBackOff","GPU/RAM resource constraints","MAJOR/MINOR vulns route to GPT-4o (higher cost)"],
        ],
        [3.5*cm, 3.5*cm, 5*cm, 4.2*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 10. FUTURE ENHANCEMENTS
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("10. Future Enhancements", S))
    story.append(hr(C_BLUE))

    story.append(P(
        "The following roadmap items are prioritised by business impact and engineering "
        "feasibility. Each item is self-contained and can be implemented independently "
        "without breaking existing v3 functionality.", S))

    enhancements = [
        {
            "priority": "P0  —  Critical Path",
            "color": C_KAFKA_ACC,
            "items": [
                ("Multi-repo support",
                 "cb-scanner currently polls a fixed SCANNER_PROJECTS list. "
                 "Extend to a database-backed project registry with per-project credentials, "
                 "polling intervals, and SonarQube instance URLs. Required for enterprise "
                 "deployments with hundreds of repositories."),
                ("OPA image fix",
                 "Build and push OPA 0.68 to the local k3d registry so the governance gate "
                 "activates in production. Currently cb-pr falls back to OPA_ENABLED=false "
                 "meaning all fixes bypass policy."),
                ("Ollama GPU scheduling",
                 "Add GPU resource requests to the Ollama Deployment so k3d assigns a "
                 "GPU node if available. This restores local model inference for MAJOR/MINOR "
                 "vulnerabilities, reducing OpenAI spend by ~60%."),
            ],
        },
        {
            "priority": "P1  —  High Value",
            "color": C_AMBER,
            "items": [
                ("Pull Request auto-merge on CI pass",
                 "Add a GitHub Actions webhook consumer in cb-pr that listens for "
                 "pull_request_review events. If all required checks pass and the PR "
                 "has an approving review, auto-merge via the GitHub Merge Queue API. "
                 "Completes the fully autonomous zero-touch pipeline."),
                ("cb-python-embedder activation",
                 "Build and deploy the FastAPI embedding service (text-embedding-3-large, "
                 "3072 dims). Update cb-agent retriever to use 3072-d Qdrant collection "
                 "for higher-fidelity semantic search. Expected +8–12% retrieval accuracy."),
                ("Slack notification channel",
                 "Add Slack Incoming Webhook support to cb-notifier as an alternative to "
                 "Teams. Toggle via SLACK_ENABLED / SLACK_WEBHOOK_URL env vars. "
                 "Many open-source teams use Slack rather than Teams."),
                ("Cost budget alerts",
                 "Add a budget threshold to cb-api (MAX_DAILY_COST_USD). When the threshold "
                 "is exceeded, cb-agent switches all models to Ollama-only until midnight UTC. "
                 "Prevents runaway LLM spend during vulnerability spikes."),
                ("Fix confidence trend dashboard",
                 "Add a Grafana dashboard panel tracking cb_fix_confidence histogram over time "
                 "per model and CWE. Enables data-driven model selection tuning and identifies "
                 "CWEs where RAG retrieval quality is degrading."),
            ],
        },
        {
            "priority": "P2  —  Platform Scale",
            "color": C_BLUE,
            "items": [
                ("Horizontal pod autoscaling",
                 "Add HPA for cb-agent (CPU target 70%) and cb-patcher (CPU target 60%). "
                 "During vulnerability spike events (post-SonarQube scan), agent and patcher "
                 "are the bottlenecks. HPA + Kafka consumer group rebalancing provides "
                 "linear throughput scaling."),
                ("Dead letter queue",
                 "Add a cb-dlq consumer that subscribes to the Kafka DLT (Dead Letter Topic) "
                 "automatically created by DefaultErrorHandler. Stores failed messages in "
                 "MongoDB dead_letter_events with full payload and error context. "
                 "Manual replay UI in Swagger."),
                ("Multi-cluster Kafka",
                 "Replace single-node KRaft Kafka with a 3-broker KRaft cluster for "
                 "production HA. Add topic replication factor 3, min-ISR 2. Required for "
                 "SLA > 99.5%."),
                ("Schema Registry",
                 "Introduce Confluent Schema Registry (or Karapace) for Kafka event schemas. "
                 "Migrate from JsonDeserializer to AvroDeserializer. Provides forward/backward "
                 "compatibility enforcement and eliminates the need to coordinate schema "
                 "changes across services manually."),
                ("GitLab + Bitbucket PR support",
                 "cb-pr currently only supports GitHub. Abstract GitHostClient interface and "
                 "add GitLabPrService and BitbucketPrService implementations. "
                 "Feature-flag selection via PR_HOST=github|gitlab|bitbucket env var."),
            ],
        },
        {
            "priority": "P3  —  AI Research",
            "color": C_AI,
            "items": [
                ("Fine-tuned CWE-specific models",
                 "Collect 10,000+ accepted CB fix pairs per CWE and fine-tune dedicated "
                 "LoRA adapters on top of qwen2:7b. Expected to outperform general-purpose "
                 "GPT-4o on high-frequency CWEs (SQL injection, XSS) at ~1/100th the cost."),
                ("LangGraph4j parallel nodes",
                 "Upgrade the workflow to run retriever (Qdrant) and retriever (Elasticsearch) "
                 "as parallel LangGraph4j nodes rather than sequential RRF. Reduces retrieval "
                 "latency by ~40% on p99."),
                ("Agentic code review",
                 "Expand the reviewer node to spawn a sub-graph: security review, "
                 "performance review, and style review as independent nodes with majority-vote "
                 "acceptance. More thorough than a single-model review call."),
                ("Vulnerability prediction",
                 "Train a classifier on historical vuln + fix data to predict which "
                 "newly committed files are likely to introduce CWE-89, CWE-79, or CWE-78 "
                 "before SonarQube runs. Integrate as a pre-commit GitHub Action."),
                ("Reinforcement Learning from human feedback (RLHF)",
                 "Capture PR reviewer edits on AI-generated fixes as negative preference "
                 "signal. Use DPO (Direct Preference Optimisation) to fine-tune the "
                 "generator model quarterly. Creates a continuous improvement loop."),
            ],
        },
        {
            "priority": "P4  —  Enterprise Readiness",
            "color": C_GRAY,
            "items": [
                ("LDAP / SSO authentication for cb-api",
                 "Replace static API key list with Spring Security OAuth2 Resource Server "
                 "(JWT). Support Entra ID (Azure AD), Okta, and Keycloak as identity providers. "
                 "Required for enterprise deployments."),
                ("Multi-tenancy",
                 "Partition MongoDB collections and Kafka topics by tenantId. Each tenant "
                 "has isolated fix history, cost tracking, and escalation channels. "
                 "Required for SaaS offering."),
                ("Compliance report generation",
                 "Add /api/v1/reports/compliance endpoint that generates a PDF/Excel "
                 "report: vulnerabilities detected, remediated, escalated, and outstanding "
                 "per CWE per time range. Required for SOC 2 / ISO 27001 audits."),
                ("Helm chart packaging",
                 "Package all k8s manifests as a Helm chart with configurable values.yaml. "
                 "Publish to OCI registry. Enables one-command installation: "
                 "helm install compliance-buddy oci://ghcr.io/org/cb-helm/compliance-buddy."),
            ],
        },
    ]

    for group in enhancements:
        story.append(SectionDivider(f"  {group['priority']}", group["color"]))
        story.append(sp(8))
        for title, desc in group["items"]:
            story.append(KeepTogether([
                Paragraph(f"<b>{title}</b>", S["h3"]),
                Paragraph(desc, S["body"]),
                sp(6),
            ]))

    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 11. TECH STACK
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("11. Technology Stack", S))
    story.append(hr(C_BLUE))

    story.append(std_table(
        ["Category", "Technology", "Version", "Role"],
        [
            ["Language","Java","17 LTS","All CB services"],
            ["Framework","Spring Boot","3.3.6","Application scaffold, auto-configuration"],
            ["AI Orchestration","LangGraph4j","1.5.14","Multi-agent StateGraph workflow in cb-agent"],
            ["AI Integration","Spring AI","1.0.0","ChatClient, VectorStore, MCP SDK"],
            ["LLM - Primary","OpenAI GPT-4o","gpt-4o","CRITICAL/BLOCKER fix generation"],
            ["LLM - Review","Anthropic Claude","claude-sonnet-4-6","Independent code review, fallback"],
            ["LLM - Local","Ollama (llama3:8b, qwen2:7b)","0.3","MAJOR/MINOR/INFO — zero API cost"],
            ["Event Streaming","Apache Kafka","KRaft 3.7","Async pipeline bus — no ZooKeeper"],
            ["Spring Kafka","Spring Kafka","3.2.5","Producer/consumer abstraction, ErrorHandlingDeserializer"],
            ["Primary DB","MongoDB","7.0","Vulns, fixes, costs, audit, escalations"],
            ["Cache","Redis","7.2","LLM prompt cache (Lettuce, 24 h TTL)"],
            ["Vector Store","Qdrant","1.9","Semantic RAG — text-embedding-3-small 1536-d"],
            ["Lexical Search","Elasticsearch","8.13","BM25 search for hybrid RAG"],
            ["Policy Engine","OPA","0.68","Rego governance gate in cb-pr"],
            ["Git Operations","JGit","6.8","Programmatic diff application in cb-patcher"],
            ["API Docs","SpringDoc OpenAPI","2.5","Swagger UI on cb-api"],
            ["Resilience","Resilience4j","2.2","CircuitBreaker on SonarQube and GitHub calls"],
            ["Metrics","Micrometer + Prometheus","1.13 / 2.52","Application metrics"],
            ["Tracing","OpenTelemetry / Tempo","1.39","Distributed traces (OTLP)"],
            ["Logging","Logback → Loki","—","Structured JSON logs via Promtail"],
            ["Dashboards","Grafana","11.1","Pipeline dashboards"],
            ["Containers","Docker + k3d + k3s","k3d 5.8","Local Kubernetes cluster"],
            ["Build","Gradle","8.14","Multi-module build, JVM pinned to JDK 17"],
            ["Security","Spring Security","6.3","API-key filter on cb-api"],
            ["Protocol","MCP (SSE)","1.0","cb-mcp-server AI tool interface"],
        ],
        [3*cm, 4*cm, 2.5*cm, 6.7*cm], S
    ))
    story.append(PageBreak())

    # ══════════════════════════════════════════════════════════════════════════
    # 12. APPENDIX
    # ══════════════════════════════════════════════════════════════════════════
    story.append(H1("12. Appendix — Configuration Reference", S))
    story.append(hr(C_BLUE))

    story.append(std_table(
        ["Environment Variable", "Default", "Module(s)", "Description"],
        [
            ["CB_API_KEYS","dev-key-change-in-prod","cb-api","Comma-sep valid REST API keys"],
            ["OPENAI_API_KEY","—","cb-agent, cb-pr","GPT-4o API key"],
            ["ANTHROPIC_API_KEY","—","cb-agent","Claude review + fallback"],
            ["SONARQUBE_URL","http://cb-sonarqube:9000/sonar","cb-scanner","SonarQube instance URL"],
            ["SONARQUBE_TOKEN","—","cb-scanner","SonarQube user token"],
            ["SCANNER_PROJECTS","—","cb-scanner","Comma-sep SonarQube project keys"],
            ["GITHUB_TOKEN","—","cb-pr, cb-mcp-server","GitHub PAT (repo scope)"],
            ["GITHUB_OWNER","—","cb-pr","GitHub org or username"],
            ["GITHUB_REPO","—","cb-pr","Repository name"],
            ["GITHUB_API_URL","https://api.github.com","cb-pr","GitHub or GHE API base"],
            ["GITHUB_REVIEWER_USERNAME","—","cb-pr","Auto-requested PR reviewer"],
            ["VERSION1_API_URL","—","cb-pr","VersionOne instance URL"],
            ["VERSION1_TOKEN","—","cb-pr","Version1 API token"],
            ["JIRA_ENABLED","false","cb-escalation","Enable Jira ticket creation"],
            ["JIRA_BASE_URL","—","cb-escalation","https://org.atlassian.net"],
            ["JIRA_EMAIL","—","cb-escalation","Jira account email"],
            ["JIRA_API_TOKEN","—","cb-escalation","Jira API token"],
            ["JIRA_PROJECT_KEY","CB","cb-escalation","Jira project key"],
            ["TEAMS_ENABLED","false","cb-escalation","Enable Teams adaptive card"],
            ["TEAMS_WEBHOOK_URL","—","cb-escalation","Teams Incoming Webhook URL"],
            ["SMTP_HOST","smtp.gmail.com","cb-notifier","SMTP server host"],
            ["SMTP_PORT","587","cb-notifier","SMTP port"],
            ["SMTP_USERNAME","—","cb-notifier","SMTP credentials"],
            ["SMTP_PASSWORD","—","cb-notifier","SMTP password / app password"],
            ["NOTIFIER_TEAM_EMAILS","—","cb-notifier","Comma-sep recipient emails"],
            ["DATADOG_API_KEY","none","cb-notifier","Datadog Events API key"],
            ["OPA_ENABLED","true","cb-pr","OPA governance gate toggle"],
            ["KAFKA_BOOTSTRAP_SERVERS","kafka:9092","all","Kafka broker address"],
            ["MONGODB_URI","mongodb://cb-mongodb:27017/compliance_buddy","all","MongoDB connection string"],
            ["VAULT_ENABLED","false","all","HashiCorp Vault integration"],
            ["OTEL_ENABLED","true","all","OpenTelemetry tracing"],
            ["OTEL_EXPORTER_OTLP_ENDPOINT","http://tempo:4318/v1/traces","all","Tempo OTLP endpoint"],
        ],
        [5*cm, 3.8*cm, 3*cm, 4.4*cm], S
    ))

    # ── Build the doc ─────────────────────────────────────────────────────────
    def page_template(canvas, doc):
        if doc.page == 1:
            cover_page_bg(canvas, doc)
        else:
            normal_page(canvas, doc)

    doc.build(story, onFirstPage=page_template, onLaterPages=page_template)
    print(f"PDF generated: {out}")


if __name__ == "__main__":
    build()
