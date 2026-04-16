"""Connect to a real BoatWatch BLE device, authenticate, and dump battery packets."""

import asyncio
import struct
import sys
from bleak import BleakScanner, BleakClient

SERVICE_UUID = "0000aa00-0000-1000-8000-00805f9b34fb"
COMMAND_UUID = "0000aa02-0000-1000-8000-00805f9b34fb"
BATTERY_NOTIFY_UUID = "0000aa03-0000-1000-8000-00805f9b34fb"

MAGIC = 0xBB
AUTH_CMD_MAGIC = 0xAA
AUTH_RESPONSE_MAGIC = 0xAF

packet_count = 0


def decode_battery(data: bytes):
    if len(data) < 17 or data[0] != MAGIC:
        return None
    buf = data
    pack_v = struct.unpack_from("<H", buf, 1)[0]
    current = struct.unpack_from("<h", buf, 3)[0]
    remaining_ah = struct.unpack_from("<H", buf, 5)[0]
    full_ah = struct.unpack_from("<H", buf, 7)[0]
    soc = buf[9]
    cycles = struct.unpack_from("<H", buf, 10)[0]
    errors = struct.unpack_from("<H", buf, 12)[0]
    fet_status = buf[14]
    n_cells = buf[15]

    offset = 16
    if len(buf) < offset + n_cells * 2 + 1:
        return None
    cells = []
    for i in range(n_cells):
        cells.append(struct.unpack_from("<H", buf, offset)[0])
        offset += 2

    n_ntc = buf[offset]
    offset += 1
    temps = []
    for i in range(n_ntc):
        if offset + 2 > len(buf):
            break
        temps.append(struct.unpack_from("<H", buf, offset)[0])
        offset += 2

    return {
        "raw_hex": data.hex(),
        "pack_v": pack_v * 0.01,
        "current": current * 0.01,
        "remaining_ah": remaining_ah * 0.01,
        "full_ah": full_ah * 0.01,
        "soc": soc,
        "cycles": cycles,
        "errors": errors,
        "errors_bin": f"0b{errors:016b}",
        "fet_status": fet_status,
        "fet_charge": bool(fet_status & 0x01),
        "fet_discharge": bool(fet_status & 0x02),
        "n_cells": n_cells,
        "cell_voltages": [v * 0.001 for v in cells],
        "n_ntc": n_ntc,
        "temperatures_c": [t * 0.1 - 273.15 for t in temps],
    }


def on_notify(sender, data: bytearray):
    global packet_count
    packet_count += 1

    # Check auth response
    if len(data) >= 2 and data[0] == AUTH_RESPONSE_MAGIC:
        status = "ACCEPTED" if data[1] == 0x01 else "DENIED"
        print(f"  [AUTH] {status}")
        return

    # Battery packet
    decoded = decode_battery(bytes(data))
    if decoded:
        print(f"\n--- Packet #{packet_count} ({len(data)} bytes) ---")
        print(f"  Raw:          {decoded['raw_hex']}")
        print(f"  Pack voltage: {decoded['pack_v']:.2f} V")
        print(f"  Current:      {decoded['current']:.2f} A")
        print(f"  SOC:          {decoded['soc']}%")
        print(f"  Remaining:    {decoded['remaining_ah']:.2f} Ah")
        print(f"  Full cap:     {decoded['full_ah']:.2f} Ah")
        print(f"  Cycles:       {decoded['cycles']}")
        print(f"  Errors:       {decoded['errors']} ({decoded['errors_bin']})")
        print(f"  FET status:   0x{decoded['fet_status']:02X} (charge={decoded['fet_charge']}, discharge={decoded['fet_discharge']})")
        print(f"  Cells ({decoded['n_cells']}):   {['%.3fV' % v for v in decoded['cell_voltages']]}")
        print(f"  Temps ({decoded['n_ntc']}):    {['%.1f°C' % t for t in decoded['temperatures_c']]}")
        has_error = decoded['errors'] != 0
        print(f"  hasError:     {has_error}  <-- this triggers ERROR on watch display")
    else:
        print(f"\n  [UNKNOWN] {len(data)} bytes: {data.hex()}")


async def main():
    pin = sys.argv[1] if len(sys.argv) > 1 else "0000"
    device_name = sys.argv[2] if len(sys.argv) > 2 else None

    print("Scanning for BoatWatch BLE devices...")
    devices = await BleakScanner.discover(timeout=5.0)

    boatwatch_devices = [d for d in devices if d.name and "BoatWatch" in d.name]

    if not boatwatch_devices:
        print("No BoatWatch devices found!")
        print(f"All devices seen: {[(d.name, d.address) for d in devices if d.name]}")
        return

    print(f"Found {len(boatwatch_devices)} BoatWatch device(s):")
    for d in boatwatch_devices:
        print(f"  {d.name} ({d.address})")

    # Pick the target
    target = boatwatch_devices[0]
    if device_name:
        for d in boatwatch_devices:
            if device_name in (d.name or ""):
                target = d
                break

    print(f"\nConnecting to {target.name} ({target.address})...")

    async with BleakClient(target.address) as client:
        print(f"Connected! MTU={client.mtu_size}")

        # Subscribe to battery notifications
        await client.start_notify(BATTERY_NOTIFY_UUID, on_notify)
        print(f"Subscribed to battery notifications")

        # Send auth
        auth_cmd = bytes([AUTH_CMD_MAGIC, 0xF0]) + pin.encode("ascii")[:4].ljust(4, b"0")
        await client.write_gatt_char(COMMAND_UUID, auth_cmd)
        print(f"Auth sent (PIN={pin})")

        print("\nListening for packets (Ctrl+C to stop)...\n")
        try:
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            pass

        await client.stop_notify(BATTERY_NOTIFY_UUID)
    print("\nDone.")


if __name__ == "__main__":
    asyncio.run(main())
