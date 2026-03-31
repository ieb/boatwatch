"""Test client for mock firmware — verifies HTTP and BLE services.

Usage:
    uv run test_services.py [--ble-only] [--http-only]
"""

import argparse
import asyncio
import struct
import sys
import urllib.request


# ---------------------------------------------------------------------------
# HTTP tests
# ---------------------------------------------------------------------------

def test_http(base_url: str = "http://localhost:8080"):
    print("=" * 60)
    print("HTTP TESTS")
    print("=" * 60)
    errors = 0

    # Test /api/store
    print("\n--- GET /api/store ---")
    try:
        resp = urllib.request.urlopen(f"{base_url}/api/store", timeout=5)
        body = resp.read().decode()
        print(f"  Status: {resp.status}")
        print(f"  Body ({len(body)} chars): {body[:120]}...")
        bline = [l for l in body.splitlines() if l.startswith("B,")]
        if bline:
            parts = bline[0].split(",")
            print(f"  B-line fields: {len(parts)}")
            print(f"  Pack voltage: {int(parts[1]) * 0.01:.2f}V")
            print(f"  Current: {int(parts[2]) * 0.01:.2f}A")
            print(f"  SOC: {parts[5]}%")
            print("  PASS: /api/store returns valid B-line")
        else:
            print("  FAIL: No B-line found in response")
            errors += 1
    except Exception as e:
        print(f"  FAIL: {e}")
        errors += 1

    # Test /api/seasmart (read a few lines then close)
    print("\n--- GET /api/seasmart ---")
    try:
        resp = urllib.request.urlopen(f"{base_url}/api/seasmart?pgns=65379,65359", timeout=5)
        lines = []
        for _ in range(5):
            line = resp.readline().decode().strip()
            if line:
                lines.append(line)
        resp.close()
        print(f"  Got {len(lines)} lines")
        pcdin_lines = [l for l in lines if l.startswith("$PCDIN")]
        if pcdin_lines:
            print(f"  Sample: {pcdin_lines[0][:70]}...")
            print(f"  PASS: /api/seasmart returns $PCDIN lines")
        else:
            print(f"  Lines: {lines}")
            print("  FAIL: No $PCDIN lines found")
            errors += 1
    except Exception as e:
        print(f"  FAIL: {e}")
        errors += 1

    return errors


# ---------------------------------------------------------------------------
# BLE tests
# ---------------------------------------------------------------------------

async def test_ble(timeout_s: float = 20.0):
    print("=" * 60)
    print("BLE TESTS")
    print("=" * 60)
    errors = 0

    try:
        from bleak import BleakScanner, BleakClient
    except ImportError:
        print("  SKIP: bleak not installed (pip install bleak)")
        return 0

    SERVICE_UUID = "0000aa00-0000-1000-8000-00805f9b34fb"
    SEASMART_NOTIFY_UUID = "0000aa01-0000-1000-8000-00805f9b34fb"
    STORE_NOTIFY_UUID = "0000aa03-0000-1000-8000-00805f9b34fb"
    COMMAND_UUID = "0000aa02-0000-1000-8000-00805f9b34fb"

    # --- Scan for BoatWatch ---
    print("\n--- BLE Scan ---")
    device = None
    try:
        devices = await BleakScanner.discover(timeout=5.0, service_uuids=[SERVICE_UUID])
        if devices:
            device = devices[0]
            print(f"  Found: {device.name} ({device.address})")
            print(f"  PASS: BLE device advertising service {SERVICE_UUID}")
        else:
            print(f"  FAIL: No device found advertising {SERVICE_UUID}")
            # Try scanning without filter
            all_devices = await BleakScanner.discover(timeout=3.0)
            boatwatch = [d for d in all_devices if d.name and "boat" in d.name.lower()]
            if boatwatch:
                device = boatwatch[0]
                print(f"  Found by name: {device.name} ({device.address})")
                print(f"  WARNING: Device found but not advertising service UUID")
            else:
                names = [f"{d.name}({d.address})" for d in all_devices if d.name]
                print(f"  All named devices: {names[:10]}")
                errors += 1
                return errors
    except Exception as e:
        print(f"  FAIL: Scan error: {e}")
        errors += 1
        return errors

    if not device:
        return errors

    # --- Connect and inspect services ---
    print(f"\n--- BLE Connect to {device.address} ---")
    try:
        async with BleakClient(device.address, timeout=10.0) as client:
            print(f"  Connected: {client.is_connected}")
            print(f"  MTU: {client.mtu_size}")

            # List services
            print("\n--- Services ---")
            for service in client.services:
                print(f"  Service: {service.uuid}")
                for char in service.characteristics:
                    props = ",".join(char.properties)
                    print(f"    Char: {char.uuid} [{props}]")

            # Check our service exists
            our_service = None
            for service in client.services:
                if service.uuid == SERVICE_UUID:
                    our_service = service
                    break

            if our_service is None:
                print(f"  FAIL: Service {SERVICE_UUID} not found")
                errors += 1
                return errors
            print(f"  PASS: Service {SERVICE_UUID} found")

            # --- Test Store notifications (battery binary on AA03) ---
            print("\n--- Store Notifications (battery binary 0xBB on AA03) ---")
            store_data = []

            def store_callback(sender, data: bytearray):
                store_data.append(bytes(data))

            await client.start_notify(STORE_NOTIFY_UUID, store_callback)
            await asyncio.sleep(3)  # Battery notifies at ~1 Hz
            await client.stop_notify(STORE_NOTIFY_UUID)

            if store_data:
                print(f"  Received {len(store_data)} notification(s)")
                d = store_data[0]
                print(f"  First: {len(d)} bytes, hex={d[:16].hex()}")
                if d[0] == 0xBB:
                    # Parse binary battery format
                    if len(d) >= 16:
                        packV, current, remAh, fullAh, soc, cycles, errs, fet, nCells = \
                            struct.unpack_from("<HhHHBHHBB", d, 1)
                        print(f"  Parsed: packV={packV*0.01:.2f}V current={current*0.01:.2f}A "
                              f"soc={soc}% cycles={cycles} nCells={nCells}")
                        if nCells > 0 and len(d) >= 16 + nCells * 2 + 1:
                            cells = struct.unpack_from(f"<{nCells}H", d, 16)
                            nNtc = d[16 + nCells * 2]
                            print(f"  Cells: {[c*0.001 for c in cells]} nNtc={nNtc}")
                        print(f"  PASS: Binary battery notification (0xBB)")
                    else:
                        print(f"  FAIL: Too short ({len(d)} bytes)")
                        errors += 1
                else:
                    print(f"  First byte: 0x{d[0]:02X} (expected 0xBB)")
                    if d[0] == ord('B'):
                        text = d.decode("utf-8", errors="replace")
                        print(f"  Text: {text[:80]}")
                        print(f"  FAIL: Received text B-line, not binary")
                    else:
                        print(f"  FAIL: Unknown format")
                    errors += 1
            else:
                print(f"  FAIL: No store notifications received in 8s")
                errors += 1

            # --- Test Autopilot notifications (binary on AA01) ---
            print("\n--- Autopilot Notifications (binary 0xAA on AA01) ---")
            ap_data = []

            def ap_callback(sender, data: bytearray):
                ap_data.append(bytes(data))

            await client.start_notify(SEASMART_NOTIFY_UUID, ap_callback)
            await asyncio.sleep(2)  # 5Hz, should get ~10 notifications
            await client.stop_notify(SEASMART_NOTIFY_UUID)

            if ap_data:
                print(f"  Received {len(ap_data)} notification(s)")
                d = ap_data[0]
                print(f"  First: {len(d)} bytes, hex={d.hex()}")
                if d[0] == 0xAA and len(d) >= 10:
                    mode, heading, target_hdg, target_wind = \
                        struct.unpack_from("<BHHh", d, 1)
                    mode_names = {0: "STANDBY", 1: "COMPASS", 2: "WIND_AWA", 3: "WIND_TWA"}
                    print(f"  Parsed: mode={mode_names.get(mode, mode)} "
                          f"heading={heading*0.01:.1f}° target_hdg={target_hdg*0.01:.1f}° "
                          f"wind={target_wind*0.01:.1f}°")
                    print(f"  PASS: Binary autopilot notification (0xAA)")
                elif d[0] == ord('$'):
                    text = d.decode("utf-8", errors="replace")
                    print(f"  Text: {text[:70]}")
                    print(f"  FAIL: Received SeaSmart text, not binary")
                    errors += 1
                else:
                    print(f"  FAIL: Unknown format (first byte 0x{d[0]:02X})")
                    errors += 1
            else:
                print(f"  FAIL: No autopilot notifications received in 2s")
                errors += 1

            # --- Test command write ---
            print("\n--- Command Write (binary STANDBY) ---")
            try:
                cmd = bytes([0xAA, 0x01])  # STANDBY
                await client.write_gatt_char(COMMAND_UUID, cmd)
                print(f"  Wrote {len(cmd)} bytes: {cmd.hex()}")
                print(f"  PASS: Command write accepted")
            except Exception as e:
                print(f"  FAIL: Command write error: {e}")
                errors += 1

    except Exception as e:
        print(f"  FAIL: Connection error: {e}")
        errors += 1

    return errors


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Test mock firmware HTTP and BLE services")
    parser.add_argument("--ble-only", action="store_true", help="Only run BLE tests")
    parser.add_argument("--http-only", action="store_true", help="Only run HTTP tests")
    parser.add_argument("--url", default="http://localhost:8080", help="HTTP base URL")
    args = parser.parse_args()

    total_errors = 0

    if not args.ble_only:
        total_errors += test_http(args.url)

    if not args.http_only:
        total_errors += asyncio.run(test_ble())

    print("\n" + "=" * 60)
    if total_errors == 0:
        print("ALL TESTS PASSED")
    else:
        print(f"FAILED: {total_errors} error(s)")
    print("=" * 60)
    sys.exit(1 if total_errors > 0 else 0)


if __name__ == "__main__":
    main()
