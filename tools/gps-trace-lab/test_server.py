from datetime import datetime, timedelta, timezone
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest

from server import Handler, ThreadingHTTPServer, analyse


def trace(second_segment=False):
    points = []
    for i in range(4):
        stamp = (datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=i * 60)).isoformat()
        if i == 2 and second_segment:
            points.append('</trkseg><trkseg>')
        points.append(f'<trkpt lat="0" lon="{i * .001}"><time>{stamp}</time></trkpt>')
    return ('<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>' + ''.join(points) + '</trkseg></trk></gpx>').encode()


class AnalysisTests(unittest.TestCase):
    def test_geometry_annotation_and_immutable_source(self):
        payload = trace()
        report = analyse(payload, "WALK", 1)
        self.assertEqual(4, report['pointCount'])
        self.assertEqual(180, report['elapsedSeconds'])
        self.assertAlmostEqual(333.585, report['rawChordMeters'], places=2)
        self.assertEqual(3, report['stationaryAnnotation']['points'])
        self.assertEqual(payload, trace())

    def test_segments_do_not_create_chords(self):
        report = analyse(trace(True), 'CYCLING')
        self.assertAlmostEqual(222.39, report['rawChordMeters'], places=2)

    def test_unsafe_or_incomplete_inputs(self):
        for payload in [b'<!DOCTYPE gpx><gpx/>', b'<!ENTITY x "abc"><gpx/>', b'<gpx/>',
                        trace().replace(b'lat="0"', b'lat="nan"'),
                        trace().replace(b'+00:00', b''), trace().replace(b'00:03:00', b'00:00:30')]:
            with self.assertRaises(ValueError):
                analyse(payload, 'WALK')
        for minute in [float('nan'), -1, 3]:
            with self.assertRaises(ValueError):
                analyse(trace(), 'WALK', minute)

    def test_loopback_upload_origin_host_and_private_file_routes(self):
        with tempfile.TemporaryDirectory(prefix='trackme-trace-test-') as directory:
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.inbox = Path(directory)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            origin = f'http://127.0.0.1:{server.server_port}'
            try:
                for headers, expected in [({}, 403), ({'Origin': 'https://example.org', 'X-TrackMe-Upload': 'local'}, 403),
                                          ({'Host': 'example.org', 'Origin': origin, 'X-TrackMe-Upload': 'local'}, 403),
                                          ({'Origin': origin, 'X-TrackMe-Upload': 'local'}, 201)]:
                    connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
                    connection.request('POST', '/api/traces?persona=WALK&stationaryAfter=1', trace(), headers)
                    response = connection.getresponse()
                    self.assertEqual(expected, response.status)
                    body = response.read()
                    if expected == 201:
                        saved = Path(json.loads(body)['savedDirectory'])
                        self.assertEqual(trace(), (saved / 'trace.gpx').read_bytes())
                    connection.close()
                self.assertEqual(1, len(list(Path(directory).iterdir())))
                connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
                connection.request('GET', '/../../local-traces/trace.gpx')
                response = connection.getresponse()
                self.assertEqual(404, response.status)
                response.read()
                connection.close()
            finally:
                server.shutdown()
                server.server_close()
                worker.join()


if __name__ == '__main__':
    unittest.main()
