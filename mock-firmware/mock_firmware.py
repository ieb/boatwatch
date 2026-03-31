"""Mock ESP32 firmware HTTP + BLE server for BoatWatch WearOS app development.

Simulates the /api/store (battery) and /api/seasmart (autopilot) endpoints
over HTTP, and exposes the same data over BLE GATT characteristics.

Usage:
    uv run mock_firmware.py [--port 8080] [--host 0.0.0.0] [--no-ble]
"""

import argparse
import asyncio
import math
import socket
import struct
import threading
import time
import random
import json
from dataclasses import dataclass, field
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

from zeroconf import ServiceInfo, Zeroconf


# ---------------------------------------------------------------------------
# Angle encoding (Raymarine format: radians * 10000 as uint16 LE)
# ---------------------------------------------------------------------------

def encode_angle(degrees: float) -> bytes:
    raw = int(degrees * math.pi / 180.0 * 10000.0) & 0xFFFF
    return struct.pack("<H", raw)


def decode_angle(data: bytes) -> float:
    raw = struct.unpack("<H", data)[0]
    return raw / 10000.0 * 180.0 / math.pi


# ---------------------------------------------------------------------------
# SeaSmart codec
# ---------------------------------------------------------------------------

def encode_seasmart(pgn: int, source: int, data: bytes) -> str:
    pgn_hex = f"{pgn:06X}"
    ts_hex = f"{int(time.time() * 1000) & 0x7FFFFFFF:08X}"
    src_hex = f"{source:02X}"
    data_hex = data.hex().upper()
    body = f"PCDIN,{pgn_hex},{ts_hex},{src_hex},{data_hex}"
    checksum = 0
    for c in body:
        checksum ^= ord(c)
    return f"${body}*{checksum & 0xFF:02X}\n"


def decode_seasmart(line: str) -> tuple[int, int, bytes] | None:
    """Decode a SeaSmart sentence. Returns (pgn, source, data) or None."""
    line = line.strip()
    if not line.startswith("$PCDIN,"):
        return None
    star = line.rfind("*")
    if star < 0:
        return None
    body = line[1:star]  # strip $ and *XX
    cs_str = line[star + 1:]
    expected = 0
    for c in body:
        expected ^= ord(c)
    expected &= 0xFF
    try:
        actual = int(cs_str, 16)
    except ValueError:
        return None
    if expected != actual:
        return None
    parts = body.split(",")
    if len(parts) < 5:
        return None
    try:
        pgn = int(parts[1], 16)
        source = int(parts[3], 16)
        data_hex = parts[4]
        data = bytes.fromhex(data_hex)
    except (ValueError, IndexError):
        return None
    return pgn, source, data


# ---------------------------------------------------------------------------
# Simulated state
# ---------------------------------------------------------------------------

@dataclass
class BatteryState:
    pack_v: int = 1320
    current: int = -520
    remaining_ah: int = 15000
    full_ah: int = 20000
    soc: int = 75
    cycles: int = 42
    errors: int = 0
    fet_status: int = 3
    cell_voltages: list[int] = field(default_factory=lambda: [3300, 3295, 3305, 3290])
    ntc_temps: list[int] = field(default_factory=lambda: [2941, 2951, 2931])

    def to_bline(self) -> str:
        cells = ",".join(str(v) for v in self.cell_voltages)
        temps = ",".join(str(t) for t in self.ntc_temps)
        return (
            f"B,{self.pack_v},{self.current},{self.remaining_ah},{self.full_ah},"
            f"{self.soc},{self.cycles},{self.errors},{self.fet_status},"
            f"{len(self.cell_voltages)},{cells},"
            f"{len(self.ntc_temps)},{temps}"
        )

    def to_binary(self) -> bytes:
        """Compact binary encoding: 0xBB header + fixed fields + variable cells + NTCs."""
        header = struct.pack("<BHhHHBHHBB",
            0xBB,
            self.pack_v, self.current, self.remaining_ah, self.full_ah,
            self.soc, self.cycles, self.errors, self.fet_status,
            len(self.cell_voltages))
        cells = struct.pack(f"<{len(self.cell_voltages)}H", *self.cell_voltages)
        ntc_header = struct.pack("<B", len(self.ntc_temps))
        ntcs = struct.pack(f"<{len(self.ntc_temps)}H", *self.ntc_temps)
        return header + cells + ntc_header + ntcs


@dataclass
class AutopilotState:
    mode: str = "standby"  # standby / compass / wind_awa / wind_twa
    heading: float = 275.0
    target_heading: float = 275.0
    target_wind: float = 45.0
    sid: int = 0

    @property
    def is_wind(self) -> bool:
        return self.mode in ("wind_awa", "wind_twa")

    def mode_bytes(self) -> tuple[int, int]:
        if self.mode == "compass":
            return 0x40, 0x00
        elif self.is_wind:
            return 0x00, 0x01
        return 0x00, 0x00

    def build_mode_msg(self) -> bytes:
        mode, submode = self.mode_bytes()
        return bytes([0x3B, 0x9F, 0x01, mode, 0x00, submode, 0x00, 0x00, 0xFF])

    def build_heading_msg(self) -> bytes:
        self.sid = (self.sid + 1) & 0xFF
        hdg = encode_angle(self.heading % 360.0)
        return bytes([0x3B, 0x9F, self.sid, 0xFF, 0xFF]) + hdg + bytes([0xFF])

    def build_locked_heading_msg(self) -> bytes:
        tgt = encode_angle(self.target_heading % 360.0)
        return bytes([0x3B, 0x9F, self.sid, 0xFF, 0xFF]) + tgt + bytes([0xFF])

    MODE_MAP = {"standby": 0, "compass": 1, "wind_awa": 2, "wind_twa": 3}

    def to_binary(self) -> bytes:
        """Compact binary encoding: 0xAA header, 10 bytes total."""
        mode_byte = self.MODE_MAP.get(self.mode, 0)
        heading_raw = int(self.heading % 360.0 * 100) & 0xFFFF
        target_heading_raw = int(self.target_heading % 360.0 * 100) & 0xFFFF
        wind = self.target_wind
        if wind > 180.0:
            wind -= 360.0
        elif wind < -180.0:
            wind += 360.0
        target_wind_raw = int(wind * 100)
        return struct.pack("<BBHHhH",
            0xAA, mode_byte, heading_raw, target_heading_raw, target_wind_raw, 0)

    def build_wind_datum_msg(self) -> bytes:
        wind_deg = self.target_wind
        if wind_deg < 0:
            wind_deg += 360.0
        datum = encode_angle(wind_deg)
        # Rolling average AWA — just use the same value
        awa = datum
        return bytes([0x3B, 0x9F]) + datum + awa + bytes([0xFF, 0xFF])


SOURCE_ADDR = 0x1C  # firmware default source address

N_FIELDS_H = 20
N_FIELDS_P = 17


class SimulatorState:
    def __init__(self):
        self.lock = threading.Lock()
        self.battery = BatteryState()
        self.autopilot = AutopilotState()
        self._tick = 0
        self._start_soc = 75

    def tick(self):
        """Called at ~1 Hz from background thread."""
        with self.lock:
            self._tick += 1

            # --- Battery simulation ---
            jitter = lambda base, spread: base + random.randint(-spread, spread)

            self.battery.pack_v = jitter(1320, 5)
            self.battery.current = jitter(-520, 30)
            self.battery.cell_voltages = [
                jitter(3300, 5),
                jitter(3295, 5),
                jitter(3305, 5),
                jitter(3290, 5),
            ]
            self.battery.ntc_temps = [
                jitter(2941, 5),
                jitter(2951, 5),
                jitter(2931, 5),
            ]
            # Drain SOC ~1% per minute
            self.battery.soc = max(0, self._start_soc - self._tick // 60)
            self.battery.remaining_ah = self.battery.soc * self.battery.full_ah // 100

            # Occasional error flag
            if self._tick >= 60 and self._tick % 60 < 3:
                self.battery.errors = 1
            else:
                self.battery.errors = 0

            # --- Autopilot simulation ---
            self.autopilot.heading = (self.autopilot.heading + 0.2) % 360.0

    def get_store_response(self) -> str:
        with self.lock:
            h_fields = ",".join(["-1e9"] * N_FIELDS_H)
            p_fields = ",".join(["-1e9"] * N_FIELDS_P)
            return f"H,{h_fields}\nP,{p_fields}\n{self.battery.to_bline()}\n"

    def get_bline(self) -> str:
        """Get just the B-line for BLE notifications (compact)."""
        with self.lock:
            return self.battery.to_bline()

    def get_autopilot_messages(self, pgn_filter: set[int] | None) -> list[str]:
        """Generate SeaSmart messages for current autopilot state."""
        with self.lock:
            msgs = []
            if pgn_filter is None or 65379 in pgn_filter:
                msgs.append(encode_seasmart(65379, SOURCE_ADDR, self.autopilot.build_mode_msg()))
            if pgn_filter is None or 65359 in pgn_filter:
                msgs.append(encode_seasmart(65359, SOURCE_ADDR, self.autopilot.build_heading_msg()))
            if pgn_filter is None or 65360 in pgn_filter:
                if self.autopilot.mode == "compass":
                    msgs.append(encode_seasmart(65360, SOURCE_ADDR, self.autopilot.build_locked_heading_msg()))
            if pgn_filter is None or 65345 in pgn_filter:
                if self.autopilot.is_wind:
                    msgs.append(encode_seasmart(65345, SOURCE_ADDR, self.autopilot.build_wind_datum_msg()))
            return msgs

    def handle_command(self, data: bytes) -> bool:
        """Process a PGN 126208 command. Returns True if handled."""
        if len(data) < 3 or data[0] != 0x01:
            return False

        target_lo = data[1]
        target_hi = data[2]

        with self.lock:
            # Mode change — target PGN 65379
            if target_lo == 0x63 and target_hi == 0xFF:
                if len(data) >= 14:
                    mode = data[12]
                    qualifier = data[13]
                    if mode == 0x00 and qualifier == 0x00:
                        self.autopilot.mode = "standby"
                        print(f"  -> Mode: STANDBY")
                    elif mode == 0x40 and qualifier == 0x00:
                        self.autopilot.mode = "compass"
                        self.autopilot.target_heading = self.autopilot.heading
                        print(f"  -> Mode: COMPASS (heading {self.autopilot.target_heading:.0f}°)")
                    elif mode == 0x00 and qualifier == 0x01:
                        # Check wind submode bytes [15]-[16]
                        wind_sub = "awa"
                        if len(data) >= 17:
                            sub_hi, sub_lo = data[15], data[16]
                            if sub_hi == 0x04 and sub_lo == 0x00:
                                wind_sub = "twa"
                            elif sub_hi == 0x03 and sub_lo == 0x00:
                                wind_sub = "awa"
                            # 0xFF 0xFF = keep current, default to awa
                        self.autopilot.mode = f"wind_{wind_sub}"
                        print(f"  -> Mode: WIND {wind_sub.upper()} (target {self.autopilot.target_wind:.0f}°)")
                return True

            # Heading set — target PGN 65360
            if target_lo == 0x50 and target_hi == 0xFF:
                if len(data) >= 14:
                    heading = decode_angle(data[12:14])
                    heading = heading % 360.0
                    self.autopilot.target_heading = heading
                    print(f"  -> Target heading: {heading:.1f}°")
                return True

            # Wind datum — target PGN 65345
            if target_lo == 0x41 and target_hi == 0xFF:
                if len(data) >= 14:
                    wind = decode_angle(data[12:14])
                    if wind > 180.0:
                        wind -= 360.0
                    self.autopilot.target_wind = wind
                    print(f"  -> Target wind: {wind:.1f}°")
                return True

        return False

    def handle_binary_autopilot_command(self, data: bytes) -> bool:
        """Process a binary autopilot command (0xAA prefix). Returns True if handled."""
        if len(data) < 2 or data[0] != 0xAA:
            return False
        cmd = data[1]
        with self.lock:
            if cmd == 0x01:
                self.autopilot.mode = "standby"
                print(f"[BIN CMD] STANDBY")
            elif cmd == 0x02:
                self.autopilot.mode = "compass"
                self.autopilot.target_heading = self.autopilot.heading
                print(f"[BIN CMD] COMPASS (heading {self.autopilot.target_heading:.0f}°)")
            elif cmd == 0x03:
                self.autopilot.mode = "wind_awa"
                print(f"[BIN CMD] WIND AWA (target {self.autopilot.target_wind:.0f}°)")
            elif cmd == 0x04:
                self.autopilot.mode = "wind_twa"
                print(f"[BIN CMD] WIND TWA (target {self.autopilot.target_wind:.0f}°)")
            elif cmd == 0x10 and len(data) >= 4:
                raw = struct.unpack_from("<H", data, 2)[0]
                self.autopilot.target_heading = raw * 0.01
                print(f"[BIN CMD] SET_HEADING {self.autopilot.target_heading:.1f}°")
            elif cmd == 0x11 and len(data) >= 4:
                raw = struct.unpack_from("<h", data, 2)[0]
                self.autopilot.target_wind = raw * 0.01
                print(f"[BIN CMD] SET_WIND {self.autopilot.target_wind:.1f}°")
            elif cmd == 0x20 and len(data) >= 4:
                raw = struct.unpack_from("<h", data, 2)[0]
                delta = raw * 0.01
                self.autopilot.target_heading = (self.autopilot.target_heading + delta) % 360.0
                print(f"[BIN CMD] ADJUST_HEADING {delta:+.1f}° -> {self.autopilot.target_heading:.1f}°")
            elif cmd == 0x21 and len(data) >= 4:
                raw = struct.unpack_from("<h", data, 2)[0]
                delta = raw * 0.01
                wind = self.autopilot.target_wind + delta
                while wind > 180.0: wind -= 360.0
                while wind < -180.0: wind += 360.0
                self.autopilot.target_wind = wind
                print(f"[BIN CMD] ADJUST_WIND {delta:+.1f}° -> {self.autopilot.target_wind:.1f}°")
            else:
                return False
        return True


# ---------------------------------------------------------------------------
# HTTP Handler
# ---------------------------------------------------------------------------

state = SimulatorState()


class FirmwareHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[HTTP] {self.client_address[0]} {format % args}")

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/store":
            self._handle_store()
        elif path == "/api/seasmart":
            params = parse_qs(parsed.query)
            pgn_str = params.get("pgns", [None])[0]
            pgn_filter = None
            if pgn_str:
                try:
                    pgn_filter = set(int(p) for p in pgn_str.split(","))
                except ValueError:
                    pass
            self._handle_seasmart_stream(pgn_filter)
        else:
            self.send_error(404)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/seasmart":
            self._handle_seasmart_post()
        else:
            self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(200)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")

    def _handle_store(self):
        body = state.get_store_response().encode()
        self.send_response(200)
        self._cors_headers()
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle_seasmart_stream(self, pgn_filter: set[int] | None):
        self.send_response(200)
        self._cors_headers()
        self.send_header("Content-Type", "text/plain")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        # Timing: PGN 65359 at 5Hz, PGN 65360 at 2Hz, PGN 65379 at 1Hz, PGN 65345 at 2Hz
        tick = 0
        try:
            while True:
                msgs = []

                # 65359 heading — every tick (5 Hz)
                if pgn_filter is None or 65359 in pgn_filter:
                    with state.lock:
                        msgs.append(encode_seasmart(65359, SOURCE_ADDR, state.autopilot.build_heading_msg()))

                # 65360 locked heading — every 2-3 ticks (2 Hz)
                if tick % 3 == 0 and (pgn_filter is None or 65360 in pgn_filter):
                    with state.lock:
                        if state.autopilot.mode == "compass":
                            msgs.append(encode_seasmart(65360, SOURCE_ADDR, state.autopilot.build_locked_heading_msg()))

                # 65345 wind datum — every 2-3 ticks (2 Hz)
                if tick % 3 == 1 and (pgn_filter is None or 65345 in pgn_filter):
                    with state.lock:
                        if state.autopilot.is_wind:
                            msgs.append(encode_seasmart(65345, SOURCE_ADDR, state.autopilot.build_wind_datum_msg()))

                # 65379 mode — every 5 ticks (1 Hz)
                if tick % 5 == 0 and (pgn_filter is None or 65379 in pgn_filter):
                    with state.lock:
                        msgs.append(encode_seasmart(65379, SOURCE_ADDR, state.autopilot.build_mode_msg()))

                for msg in msgs:
                    chunk = msg.encode()
                    self.wfile.write(f"{len(chunk):X}\r\n".encode())
                    self.wfile.write(chunk)
                    self.wfile.write(b"\r\n")
                self.wfile.flush()

                tick += 1
                time.sleep(0.2)  # 5 Hz base rate
        except (BrokenPipeError, ConnectionResetError):
            pass  # client disconnected

    def _handle_seasmart_post(self):
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_len).decode("utf-8", errors="replace")

        # Parse form-encoded body: msg=<seasmart>
        msg = None
        for param in body.split("&"):
            if param.startswith("msg="):
                from urllib.parse import unquote_plus
                msg = unquote_plus(param[4:])
                break

        if not msg:
            self._json_response(400, {"ok": False, "msg": "missing msg parameter"})
            return

        result = decode_seasmart(msg)
        if result is None:
            self._json_response(400, {"ok": False, "msg": "invalid seasmart sentence"})
            return

        pgn, source, data = result
        print(f"[CMD] PGN {pgn} from source {source:02X}, {len(data)} bytes")

        if pgn == 126208:
            if state.handle_command(data):
                self._json_response(200, {"ok": True, "msg": "sent"})
            else:
                self._json_response(400, {"ok": False, "msg": "unrecognized command"})
        else:
            self._json_response(403, {"ok": False, "msg": f"PGN {pgn} not allowed"})

    def _json_response(self, code: int, obj: dict):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self._cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


# ---------------------------------------------------------------------------
# Background ticker
# ---------------------------------------------------------------------------

def background_ticker():
    while True:
        state.tick()
        time.sleep(1.0)


# ---------------------------------------------------------------------------
# BLE GATT Server (direct CoreBluetooth via PyObjC)
# ---------------------------------------------------------------------------

SERVICE_UUID = "0000aa00-0000-1000-8000-00805f9b34fb"
AUTOPILOT_NOTIFY_UUID = "0000aa01-0000-1000-8000-00805f9b34fb"
COMMAND_UUID = "0000aa02-0000-1000-8000-00805f9b34fb"
BATTERY_NOTIFY_UUID = "0000aa03-0000-1000-8000-00805f9b34fb"


async def run_ble_server(sim_state: SimulatorState, ble_name: str = "BoatWatch"):
    """Run the BLE GATT server. Must be called from the main thread on macOS
    because CoreBluetooth requires the main thread's CFRunLoop."""
    try:
        from ble_server import BleGattServer, GATTCharacteristic
    except ImportError as e:
        print(f"[BLE] BLE server not available: {e}")
        return

    server = BleGattServer(ble_name)

    # Build GATT service
    service = server.add_service(SERVICE_UUID)

    autopilot_char = server.add_characteristic(
        service, AUTOPILOT_NOTIFY_UUID,
        GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
        GATTCharacteristic.PERM_READ,
    )

    server.add_characteristic(
        service, COMMAND_UUID,
        GATTCharacteristic.PROP_WRITE | GATTCharacteristic.PROP_WRITE_NO_RESP,
        GATTCharacteristic.PERM_WRITE,
    )

    battery_char = server.add_characteristic(
        service, BATTERY_NOTIFY_UUID,
        GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
        GATTCharacteristic.PERM_READ,
    )

    def on_write(char_uuid: str, data: bytes):
        sim_state.handle_binary_autopilot_command(data)

    server.on_write = on_write

    await server.start()
    print(f"[BLE] GATT server started — advertising as 'BoatWatch'")
    print(f"[BLE]   Service:           {SERVICE_UUID}")
    print(f"[BLE]   Autopilot notify:  {AUTOPILOT_NOTIFY_UUID}")
    print(f"[BLE]   Command write:     {COMMAND_UUID}")
    print(f"[BLE]   Battery notify:    {BATTERY_NOTIFY_UUID}")
    print()

    # Notification loop — each characteristic notifies independently
    tick = 0
    try:
        while True:
            # Autopilot state at ~5 Hz
            with sim_state.lock:
                ap_data = sim_state.autopilot.to_binary()
            server.notify(autopilot_char, bytearray(ap_data))

            # Battery state at ~1 Hz (every 5th tick)
            if tick % 5 == 0:
                with sim_state.lock:
                    bat_data = sim_state.battery.to_binary()
                server.notify(battery_char, bytearray(bat_data))

            tick += 1
            await asyncio.sleep(0.2)
    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()
        print("[BLE] GATT server stopped")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def get_local_ip() -> str:
    """Get the local IP address of this machine."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def register_mdns(port: int, hostname: str = "boatsystems") -> tuple[Zeroconf, ServiceInfo]:
    """Register an mDNS service and hostname."""
    local_ip = get_local_ip()
    ip_bytes = socket.inet_aton(local_ip)

    service_info = ServiceInfo(
        "_http._tcp.local.",
        f"{hostname}._http._tcp.local.",
        addresses=[ip_bytes],
        port=port,
        properties={"path": "/api/", "server": "mock-firmware"},
        server=f"{hostname}.local.",
    )

    zc = Zeroconf()
    zc.register_service(service_info)
    return zc, service_info


def run_http_server(host: str, port: int):
    """Run the HTTP server in a background thread."""
    server = ThreadingHTTPServer((host, port), FirmwareHandler)
    print(f"Mock firmware running on http://{host}:{port}")
    print(f"  Battery:   GET  /api/store")
    print(f"  Autopilot: GET  /api/seasmart?pgns=65379,65359,65360,65345")
    print(f"  Commands:  POST /api/seasmart")
    print()
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description="Mock firmware HTTP + BLE server for BoatWatch")
    parser.add_argument("--port", type=int, default=8080, help="Listen port (default: 8080)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    parser.add_argument("--hostname", type=str, default="boatsystems", help="mDNS hostname (default: boatsystems)")
    parser.add_argument("--no-ble", action="store_true", help="Disable BLE GATT server")
    parser.add_argument("--ble-name", type=str, default=None,
                        help="BLE device name (default: BoatWatch-<hostname-suffix>)")
    args = parser.parse_args()

    # Generate a unique BLE name from the hostname if not specified
    if args.ble_name is None:
        import platform
        host_short = platform.node().split(".")[0][:8]
        args.ble_name = f"BoatWatch-{host_short}"

    ticker = threading.Thread(target=background_ticker, daemon=True)
    ticker.start()

    # Register mDNS service
    local_ip = get_local_ip()
    zc, service_info = register_mdns(args.port, args.hostname)
    print(f"mDNS: registered {args.hostname}.local -> {local_ip}")
    print(f"mDNS: service {args.hostname}._http._tcp.local. on port {args.port}")
    print()

    # Start HTTP server in background thread
    http_thread = threading.Thread(target=run_http_server, args=(args.host, args.port), daemon=True)
    http_thread.start()

    # Run BLE on main thread (macOS CoreBluetooth requires the main thread's CFRunLoop)
    if not args.no_ble:
        try:
            asyncio.run(run_ble_server(state, args.ble_name))
        except KeyboardInterrupt:
            pass
    else:
        print("[BLE] Disabled via --no-ble flag")
        print()
        try:
            # Block main thread so HTTP server keeps running
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass

    print("\nShutting down.")
    zc.unregister_service(service_info)
    zc.close()


if __name__ == "__main__":
    main()
