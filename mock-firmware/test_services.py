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

async def test_ble(timeout_s: float = 20.0, expected_name: str = "BoatWatch", expected_pin: str = "0000"):
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
    ad_data = None
    try:
        # Use callback-based scanner to capture advertisement data
        found_devices = {}

        def scan_callback(dev, advertisement):
            found_devices[dev.address] = (dev, advertisement)

        scanner = BleakScanner(detection_callback=scan_callback,
                               service_uuids=[SERVICE_UUID])
        await scanner.start()
        await asyncio.sleep(5.0)
        await scanner.stop()

        if found_devices:
            device, ad_data = next(iter(found_devices.values()))
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

    # --- Check advertised name ---
    print(f"\n--- BLE Name (expecting prefix '{expected_name}') ---")
    advertised_name = ad_data.local_name if ad_data else None
    device_name = device.name if device else None
    print(f"  Advertised local_name: {advertised_name}")
    print(f"  Device name: {device_name}")
    effective_name = advertised_name or device_name
    if effective_name and effective_name.startswith(expected_name):
        print(f"  PASS: Name '{effective_name}' starts with '{expected_name}'")
    elif effective_name:
        print(f"  FAIL: Name '{effective_name}' does not start with '{expected_name}'")
        print(f"  NOTE: macOS may override the advertised name with the system Bluetooth name")
        errors += 1
    else:
        print(f"  FAIL: No name found")
        errors += 1

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

            # --- Test authentication with correct PIN ---
            print(f"\n--- Authentication (PIN: {expected_pin}) ---")
            auth_responses = []

            def auth_callback(sender, data: bytearray):
                if len(data) >= 2 and data[0] == 0xAF:
                    auth_responses.append(bytes(data))

            await client.start_notify(SEASMART_NOTIFY_UUID, auth_callback)
            try:
                auth_cmd = bytes([0xAA, 0xF0]) + expected_pin.encode("ascii")[:4]
                await client.write_gatt_char(COMMAND_UUID, auth_cmd)
                print(f"  Sent auth: {auth_cmd.hex()}")
                await asyncio.sleep(1)

                if auth_responses:
                    resp = auth_responses[0]
                    if resp[1] == 0x01:
                        print(f"  PASS: Auth accepted (0xAF 0x01)")
                    else:
                        print(f"  FAIL: Auth denied (0xAF 0x{resp[1]:02X})")
                        errors += 1
                else:
                    print(f"  FAIL: No auth response received")
                    errors += 1
            finally:
                await client.stop_notify(SEASMART_NOTIFY_UUID)

            # --- Test notifications flow after auth ---
            print("\n--- Post-Auth Notifications ---")
            post_auth_data = []

            def post_auth_callback(sender, data: bytearray):
                if len(data) > 0 and data[0] in (0xAA, 0xBB):
                    post_auth_data.append(bytes(data))

            await client.start_notify(SEASMART_NOTIFY_UUID, post_auth_callback)
            await asyncio.sleep(2)
            await client.stop_notify(SEASMART_NOTIFY_UUID)

            if post_auth_data:
                print(f"  Received {len(post_auth_data)} notification(s) after auth")
                print(f"  PASS: Notifications flowing after authentication")
            else:
                print(f"  FAIL: No notifications after authentication")
                errors += 1

            # --- Test command write (should work after auth) ---
            print("\n--- Command Write (binary STANDBY, after auth) ---")
            try:
                cmd = bytes([0xAA, 0x01])  # STANDBY
                await client.write_gatt_char(COMMAND_UUID, cmd)
                print(f"  Wrote {len(cmd)} bytes: {cmd.hex()}")
                print(f"  PASS: Command write accepted")
            except Exception as e:
                print(f"  FAIL: Command write error: {e}")
                errors += 1

            # --- Test wrong PIN ---
            print("\n--- Authentication (wrong PIN) ---")
            wrong_responses = []

            def wrong_callback(sender, data: bytearray):
                if len(data) >= 2 and data[0] == 0xAF:
                    wrong_responses.append(bytes(data))

            await client.start_notify(SEASMART_NOTIFY_UUID, wrong_callback)
            try:
                wrong_cmd = bytes([0xAA, 0xF0, 0x39, 0x39, 0x39, 0x39])  # PIN "9999"
                await client.write_gatt_char(COMMAND_UUID, wrong_cmd)
                print(f"  Sent wrong PIN: {wrong_cmd.hex()}")
                await asyncio.sleep(1)

                if wrong_responses:
                    resp = wrong_responses[0]
                    if resp[1] == 0x00:
                        print(f"  PASS: Auth correctly denied (0xAF 0x00)")
                    else:
                        print(f"  FAIL: Auth should have been denied but got 0x{resp[1]:02X}")
                        errors += 1
                else:
                    print(f"  FAIL: No auth response for wrong PIN")
                    errors += 1
            finally:
                await client.stop_notify(SEASMART_NOTIFY_UUID)

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
    parser.add_argument("--ble-name", default="BoatWatch",
                        help="Expected BLE name prefix (default: BoatWatch)")
    parser.add_argument("--ble-pin", default="0000",
                        help="BLE PIN for authentication (default: 0000)")
    args = parser.parse_args()

    total_errors = 0

    if not args.ble_only:
        total_errors += test_http(args.url)

    if not args.http_only:
        total_errors += asyncio.run(test_ble(expected_name=args.ble_name, expected_pin=args.ble_pin))

    print("\n" + "=" * 60)
    if total_errors == 0:
        print("ALL TESTS PASSED")
    else:
        print(f"FAILED: {total_errors} error(s)")
    print("=" * 60)
    sys.exit(1 if total_errors > 0 else 0)


if __name__ == "__main__":
    main()
