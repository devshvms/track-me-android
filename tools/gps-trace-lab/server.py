"""Loopback-only GPX evidence inbox. No third-party packages or outbound requests."""
import argparse
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import statistics
from urllib.parse import parse_qs, urlsplit
import uuid
import xml.etree.ElementTree as ET

MAX_BYTES = 10 * 1024 * 1024
MAX_POINTS = 100_000
PERSONAS = {"WALK", "RUN", "CYCLING", "BIKE_DRIVE", "CAR_DRIVE", "AUTO"}
STATIC = Path(__file__).resolve().parent / "dist"


def metres(a, b):
    lat1, lat2 = math.radians(a["lat"]), math.radians(b["lat"])
    dlat, dlon = lat2 - lat1, math.radians(b["lon"] - a["lon"])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 12_742_000 * math.asin(min(1, math.sqrt(max(0, h))))


def analyse(payload, persona, stationary_after=None):
    if persona not in PERSONAS or not 0 < len(payload) <= MAX_BYTES:
        raise ValueError("Choose a persona and a GPX file smaller than 10 MB.")
    xml = payload.decode("utf-8-sig")
    if "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
        raise ValueError("GPX with document types or entities is not accepted.")
    root = ET.fromstring(xml)
    if root.tag.split("}")[-1] != "gpx":
        raise ValueError("This is not a GPX document.")
    points = []
    raw_distance = 0.0
    gaps = duplicate_times = 0
    for segment, track in enumerate(root.findall(".//{*}trkseg")):
        for pt in track.findall("{*}trkpt"):
            lat, lon = float(pt.attrib["lat"]), float(pt.attrib["lon"])
            if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 90 or abs(lon) > 180:
                raise ValueError("Invalid coordinates.")
            stamp = pt.findtext("{*}time")
            if not stamp:
                raise ValueError("Every point needs a timestamp for timing analysis.")
            time = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            if time.tzinfo is None:
                raise ValueError("Timestamps must contain a timezone.")
            point = {"lat": lat, "lon": lon, "timeMillis": round(time.timestamp() * 1000), "segment": segment}
            if points:
                interval = point["timeMillis"] - points[-1]["timeMillis"]
                if interval < 0:
                    raise ValueError("Out-of-order timestamps: retain the original file for investigation.")
                duplicate_times += interval == 0
                gaps += interval > 15_000
                if points[-1]["segment"] == segment:
                    raw_distance += metres(points[-1], point)
            points.append(point)
            if len(points) > MAX_POINTS:
                raise ValueError("Limit is 100,000 points per file.")
    if len(points) < 2:
        raise ValueError("At least two track points are required.")
    elapsed = (points[-1]["timeMillis"] - points[0]["timeMillis"]) / 1000
    if elapsed <= 0:
        raise ValueError("The trace must span a positive duration.")
    tail_summary = None
    if stationary_after is not None:
        if not math.isfinite(stationary_after) or not 0 <= stationary_after * 60 < elapsed:
            raise ValueError("Stationary-start minute must be inside the recorded duration.")
        tail = [p for p in points if p["timeMillis"] >= points[0]["timeMillis"] + stationary_after * 60_000]
        centre = {"lat": statistics.median(p["lat"] for p in tail), "lon": statistics.median(p["lon"] for p in tail)}
        tail_summary = {"afterMinutes": stationary_after, "points": len(tail),
                        "radiusMeters": max(metres(centre, p) for p in tail),
                        "rawChordMeters": sum(metres(a, b) for a, b in zip(tail, tail[1:]) if a["segment"] == b["segment"])}
    return {"schema": "trackme-local-gpx-v1", "persona": persona, "pointCount": len(points),
            "elapsedSeconds": elapsed, "rawChordMeters": raw_distance, "gapsOver15Seconds": gaps,
            "duplicateTimes": duplicate_times, "stationaryAnnotation": tail_summary, "points": points,
            "limitations": "Geometry evidence only. Raw chord length is NOT activity distance. GPX omits motion, steps, accuracy, pause states and power mode; annotations are user supplied, not inferred truth."}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not put precise route data or user filenames in access logs.

    def reply(self, code, body, content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(body)

    def valid_host(self):
        return self.headers.get("Host") == f"127.0.0.1:{self.server.server_port}"

    def do_GET(self):
        if not self.valid_host():
            return self.reply(403, b'{}')
        assets = {"/": ("index.html", "text/html; charset=utf-8"),
                  "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                  "/style.css": ("style.css", "text/css; charset=utf-8")}
        asset = assets.get(self.path)
        if not asset:
            return self.reply(404, b'{}')
        self.reply(200, (STATIC / asset[0]).read_bytes(), asset[1])

    def do_POST(self):
        self.connection.settimeout(15)
        expected = f"http://127.0.0.1:{self.server.server_port}"
        if not self.valid_host() or self.headers.get("Origin") != expected or self.headers.get("X-TrackMe-Upload") != "local":
            return self.reply(403, b'{}')
        request = urlsplit(self.path)
        if request.path != "/api/traces":
            return self.reply(404, b'{}')
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= MAX_BYTES or self.headers.get("Transfer-Encoding"):
                raise ValueError("File must be smaller than 10 MB.")
            query = parse_qs(request.query)
            after = query.get("stationaryAfter", [""])[0]
            payload = self.rfile.read(size)
            if len(payload) != size:
                raise ValueError("Incomplete upload; please retry.")
            report = analyse(payload, query.get("persona", [""])[0], float(after) if after else None)
            target = self.server.inbox / uuid.uuid4().hex
            target.mkdir(mode=0o700, parents=True)
            (target / "trace.gpx").write_bytes(payload)
            report["savedDirectory"] = str(target)
            (target / "report.json").write_text(json.dumps(report), encoding="utf-8")
            self.reply(201, json.dumps(report).encode())
        except (ValueError, KeyError, UnicodeError, ET.ParseError, OverflowError):
            self.reply(400, json.dumps({"error": "Invalid GPX or annotation. Use UTF-8 GPX with ordered, timezone-bearing timestamps, valid coordinates, and a stationary-start minute inside its duration."}).encode())
        except OSError:
            self.reply(507, b'{"error":"Could not save the trace. Check free disk space and retry."}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    inbox = Path(__file__).resolve().parents[2] / "local-traces"
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.inbox = inbox
    print(f"TrackMe Trace Lab: http://127.0.0.1:{server.server_port}", flush=True)
    print(f"Private local inbox: {inbox}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
