import argparse
import base64
import binascii
import datetime as _dt
import sys
from typing import Optional, Tuple


def _now_str() -> str:
    return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _hex(data: bytes) -> str:
    if not data:
        return ""
    return binascii.hexlify(data).decode("ascii")


def _try_utf8(data: bytes) -> Optional[str]:
    if not data:
        return ""
    try:
        return data.decode("utf-8")
    except Exception:
        return None


def _render_bitmap_ascii(
    data: bytes,
    width: int,
    height: int,
    *,
    msb_first: bool,
    invert: bool,
    row_bytes: Optional[int],
    on: str,
    off: str,
) -> str:
    if width <= 0 or height <= 0:
        raise ValueError("width/height must be positive")
    if len(on) != 1 or len(off) != 1:
        raise ValueError("on/off must be single characters")

    if row_bytes is None:
        row_bits = width
        row_bytes = (row_bits + 7) // 8
    if row_bytes <= 0:
        raise ValueError("row_bytes must be positive")

    needed = row_bytes * height
    if len(data) < needed:
        raise ValueError(f"bitmap data too short: need >= {needed} bytes, got {len(data)}")

    lines = []
    for y in range(height):
        row = data[y * row_bytes : (y + 1) * row_bytes]
        chars = []
        for x in range(width):
            byte_index = x // 8
            bit_in_byte = x % 8
            bit_pos = 7 - bit_in_byte if msb_first else bit_in_byte
            bit = (row[byte_index] >> bit_pos) & 1
            if invert:
                bit ^= 1
            chars.append(on if bit else off)
        lines.append("".join(chars))
    return "\n".join(lines)


def _parse_size(s: str) -> Tuple[int, int]:
    s = s.strip().lower().replace(",", "x")
    if "x" not in s:
        raise ValueError("size must look like 128x64")
    w_str, h_str = s.split("x", 1)
    return int(w_str), int(h_str)


_BMP_MAGIC = b"BMP1"
_BMP_HEADER_LEN = 12


def _parse_bitmap_header(data: bytes) -> Tuple[int, int, int, bool, bool, bytes]:
    """Parse a tiny self-describing bitmap header.

    Layout (little-endian):
      0..3   magic = b"BMP1"
      4..5   width (u16)
      6..7   height (u16)
      8..9   row_bytes (u16)
      10     flags (u8): bit0=LSB-first, bit1=invert
      11     reserved
      12..   bitmap payload
    """
    if len(data) < _BMP_HEADER_LEN:
        raise ValueError(f"need >= {_BMP_HEADER_LEN} bytes for header, got {len(data)}")
    if data[:4] != _BMP_MAGIC:
        raise ValueError("bad magic (expected BMP1)")

    width = int.from_bytes(data[4:6], "little")
    height = int.from_bytes(data[6:8], "little")
    row_bytes = int.from_bytes(data[8:10], "little")
    flags = data[10]

    lsb_first = bool(flags & 0x01)
    invert = bool(flags & 0x02)

    if width <= 0 or height <= 0:
        raise ValueError(f"invalid size {width}x{height}")
    if row_bytes <= 0:
        raise ValueError("row_bytes must be > 0")

    payload = data[_BMP_HEADER_LEN:]
    return width, height, row_bytes, (not lsb_first), invert, payload


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="macOS BLE peripheral simulator (CoreBluetooth via PyObjC)."
    )
    parser.add_argument("--name", default="BlePer", help="Advertised local name")
    parser.add_argument(
        "--service-uuid",
        default="FFF0",
        help="Service UUID (default: FFF0)",
    )
    parser.add_argument(
        "--cmd-uuid",
        default="FFF1",
        help="Writable characteristic UUID for commands (default: FFF1)",
    )
    parser.add_argument(
        "--data-uuid",
        default="FFF2",
        help="Writable characteristic UUID for data (default: FFF2)",
    )
    parser.add_argument(
        "--show-base64",
        action="store_true",
        help="Also print base64 of incoming bytes",
    )
    parser.add_argument(
        "--no-utf8",
        action="store_true",
        help="Do not attempt to decode utf-8",
    )
    parser.add_argument(
        "--bitmap",
        default=None,
        help='Render incoming bytes as 1bpp bitmap, e.g. "128x64"',
    )
    parser.add_argument(
        "--bitmap-header",
        action="store_true",
        help='Treat incoming bytes as "BMP1" header + payload (width/height/row_bytes/flags).',
    )
    parser.add_argument(
        "--bitmap-row-bytes",
        type=int,
        default=None,
        help="Bytes per row for bitmap (optional; default=ceil(width/8))",
    )
    parser.add_argument(
        "--bitmap-lsb-first",
        action="store_true",
        help="Bitmap bit order: LSB first",
    )
    parser.add_argument(
        "--bitmap-invert",
        action="store_true",
        help="Invert bitmap bits",
    )
    parser.add_argument("--bitmap-on", default="#", help="Bitmap on pixel char")
    parser.add_argument("--bitmap-off", default=".", help="Bitmap off pixel char")

    args = parser.parse_args(argv)

    if args.bitmap_lsb_first:
        msb_first = False
    else:
        msb_first = True

    bitmap_size = None
    if args.bitmap:
        try:
            bitmap_size = _parse_size(args.bitmap)
        except Exception as e:
            print(f"Invalid --bitmap: {e}", file=sys.stderr)
            return 2

    if args.bitmap_header and args.bitmap:
        print("--bitmap-header and --bitmap are mutually exclusive", file=sys.stderr)
        return 2

    try:
        import objc
        from Foundation import NSDate, NSDefaultRunLoopMode, NSObject, NSRunLoop
        from CoreBluetooth import (
            CBATTErrorSuccess,
            CBCharacteristicPropertyRead,
            CBCharacteristicPropertyWrite,
            CBCharacteristicPropertyWriteWithoutResponse,
            CBMutableCharacteristic,
            CBMutableService,
            CBPeripheralManager,
            CBPeripheralManagerStatePoweredOn,
            CBAttributePermissionsReadable,
            CBAttributePermissionsWriteable,
            CBUUID,
        )
    except Exception as e:
        print(
            "Missing PyObjC CoreBluetooth bindings. Install deps then re-run.\n"
            "  pip install pyobjc-core pyobjc-framework-Cocoa pyobjc-framework-CoreBluetooth\n"
            f"Import error: {e}",
            file=sys.stderr,
        )
        return 3

    service_uuid = CBUUID.UUIDWithString_(args.service_uuid)
    cmd_uuid = CBUUID.UUIDWithString_(args.cmd_uuid)
    data_uuid = CBUUID.UUIDWithString_(args.data_uuid)

    class PeripheralDelegate(NSObject):
        def init(self):
            self = objc.super(PeripheralDelegate, self).init()
            if self is None:
                return None
            self.peripheral = None
            self.cmd_characteristic = None
            self.data_characteristic = None
            return self

        def peripheralManagerDidUpdateState_(self, peripheral):
            self.peripheral = peripheral
            state = int(peripheral.state())
            if state != int(CBPeripheralManagerStatePoweredOn):
                print(f"[{_now_str()}] BLE not ready (state={state}).")
                return

            props = (
                int(CBCharacteristicPropertyWrite)
                | int(CBCharacteristicPropertyWriteWithoutResponse)
                | int(CBCharacteristicPropertyRead)
            )
            perms = int(CBAttributePermissionsWriteable) | int(CBAttributePermissionsReadable)
            self.cmd_characteristic = (
                CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
                    cmd_uuid,
                    props,
                    None,
                    perms,
                )
            )
            self.data_characteristic = (
                CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
                    data_uuid,
                    props,
                    None,
                    perms,
                )
            )

            service = CBMutableService.alloc().initWithType_primary_(service_uuid, True)
            service.setCharacteristics_([self.cmd_characteristic, self.data_characteristic])

            print(f"[{_now_str()}] BLE powered on. Adding service {args.service_uuid}...")
            peripheral.addService_(service)

        def peripheralManager_didAddService_error_(self, peripheral, service, error):
            if error is not None:
                print(f"[{_now_str()}] addService error: {error}", file=sys.stderr)
                return

            adv = {
                "CBAdvertisementDataLocalNameKey": args.name,
                "CBAdvertisementDataServiceUUIDsKey": [service_uuid],
            }
            print(
                f"[{_now_str()}] Advertising name={args.name} service={args.service_uuid} "
                f"cmd={args.cmd_uuid} data={args.data_uuid}"
            )
            peripheral.startAdvertising_(adv)

        def peripheralManagerDidStartAdvertising_error_(self, peripheral, error):
            if error is not None:
                print(f"[{_now_str()}] startAdvertising error: {error}", file=sys.stderr)
                return
            print(f"[{_now_str()}] Advertising started. Waiting for writes...")

        def peripheralManager_didReceiveReadRequest_(self, peripheral, request):
            # Expose last value (if any). For simplicity we always return empty.
            request.setValue_(b"")
            peripheral.respondToRequest_withResult_(request, int(CBATTErrorSuccess))

        def peripheralManager_didReceiveWriteRequests_(self, peripheral, requests):
            for req in requests:
                try:
                    nsdata = req.value()
                    data = bytes(nsdata) if nsdata is not None else b""
                except Exception:
                    data = b""

                try:
                    c_uuid = str(req.characteristic().UUID().UUIDString())
                except Exception:
                    c_uuid = "?"

                if c_uuid.upper() == args.cmd_uuid.upper():
                    channel = "cmd"
                elif c_uuid.upper() == args.data_uuid.upper():
                    channel = "data"
                else:
                    channel = "unknown"

                try:
                    central = req.central()
                    central_id = str(central.identifier()) if central is not None else "?"
                except Exception:
                    central_id = "?"

                print(
                    f"\n[{_now_str()}] from central={central_id} char={c_uuid} channel={channel} len={len(data)}"
                )
                print(f"hex: {_hex(data)}")

                if args.show_base64:
                    print(f"b64: {base64.b64encode(data).decode('ascii')}")

                if not args.no_utf8:
                    txt = _try_utf8(data)
                    if txt is not None:
                        print(f"utf8: {txt}")

                if bitmap_size is not None:
                    w, h = bitmap_size
                    try:
                        art = _render_bitmap_ascii(
                            data,
                            w,
                            h,
                            msb_first=msb_first,
                            invert=bool(args.bitmap_invert),
                            row_bytes=args.bitmap_row_bytes,
                            on=args.bitmap_on,
                            off=args.bitmap_off,
                        )
                        print("bitmap:\n" + art)
                    except Exception as e:
                        print(f"bitmap render error: {e}", file=sys.stderr)

                if args.bitmap_header:
                    try:
                        w, h, row_bytes, header_msb_first, header_invert, payload = _parse_bitmap_header(
                            data
                        )
                        print(
                            f"bitmap-header: {w}x{h} row_bytes={row_bytes} "
                            f"msb_first={header_msb_first} invert={header_invert}"
                        )
                        art = _render_bitmap_ascii(
                            payload,
                            w,
                            h,
                            msb_first=header_msb_first,
                            invert=header_invert,
                            row_bytes=row_bytes,
                            on=args.bitmap_on,
                            off=args.bitmap_off,
                        )
                        print("bitmap:\n" + art)
                    except Exception as e:
                        print(f"bitmap header render error: {e}", file=sys.stderr)

                try:
                    # Reply success for each request.
                    peripheral.respondToRequest_withResult_(req, int(CBATTErrorSuccess))
                except Exception:
                    pass

    delegate = PeripheralDelegate.alloc().init()
    _ = CBPeripheralManager.alloc().initWithDelegate_queue_options_(delegate, None, None)

    run_loop = NSRunLoop.currentRunLoop()
    try:
        while True:
            # Run the loop in short slices so Ctrl+C is handled in Python code,
            # instead of surfacing as an ObjC exception during a callback.
            run_loop.runMode_beforeDate_(
                NSDefaultRunLoopMode,
                NSDate.dateWithTimeIntervalSinceNow_(0.2),
            )
    except KeyboardInterrupt:
        print(f"\n[{_now_str()}] Stopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
