
# BlePer

macOS 上用 CoreBluetooth（PyObjC）模拟 BLE 外设：手机往特征值写入数据时，终端打印收到的原始字节（hex + 可选 base64 + 可选 UTF-8），并可选把数据按 1bpp 位图渲染成 ASCII。

另外提供一个 HTTP POST 服务用于接收图片（当蓝牙链路不方便/不稳定时）。

## 安装

在本目录：

```bash
pip install -e .
```

如果你不用 `-e`，也可以直接：

```bash
pip install pyobjc-core pyobjc-framework-Cocoa pyobjc-framework-CoreBluetooth
```

## 运行

```bash
python ble_peripheral.py
```

默认会广播：

- Local Name：`BlePer`
- Service UUID：`FFF0`
- Writable Char UUID：`FFF1`（命令）
- Writable Char UUID：`FFF2`（数据）

手机端用任意 BLE 工具（例如 nRF Connect / LightBlue）连接后，对特征执行 Write/Write Without Response，即可在终端看到打印（会标注 channel=cmd/data）。

### 用 LightBlue / nRF Connect 验证

- 创建一个 Peripheral
- 添加服务 UUID：`FFF0`
- 添加特征 UUID：
	- `FFF1`（命令）
	- `FFF2`（数据）
- 设置特征属性为 `Write`（或 `Write Without Response`）

## 位图直显（1bpp ASCII）

如果你要把写入的数据当作单色位图直接显示（例如 128x64）：

```bash
python ble_peripheral.py --bitmap 128x64
```

可选参数：

- `--bitmap-row-bytes N`：每行字节数（默认 `ceil(width/8)`）
- `--bitmap-lsb-first`：按 LSB-first 解 bit（默认 MSB-first）
- `--bitmap-invert`：反色
- `--bitmap-on` / `--bitmap-off`：像素字符（默认 `#` / `.`）

## 位图协议头（推荐：手机端自带宽高/stride）

启用：

```bash
python ble_peripheral.py --bitmap-header
```

此模式下，手机写入的 payload 需要以 12 字节 header 开头，脚本会从 header 自动解析位图参数再渲染：

```
offset  size  type     name
0       4     bytes    magic = "BMP1"
4       2     u16le    width
6       2     u16le    height
8       2     u16le    row_bytes
10      1     u8       flags: bit0=LSB-first, bit1=invert
11      1     u8       reserved (0)
12      ...   bytes    bitmap payload (1bpp, row-major)
```

说明：

- `row_bytes` 就是每行字节数（stride），允许每行做 padding。
- bit 序：默认 MSB-first；如果 flags.bit0=1 则按 LSB-first。
- 反色：flags.bit1=1。

## HTTP 图片接收（替代蓝牙）

启动服务：

```bash
python -m http_image_server --host 0.0.0.0 --port 8080
```

上传图片（multipart）：

```bash
curl -F "file=@/path/to/image.png" http://127.0.0.1:8080/image
```

上传图片（raw body）：

```bash
curl --data-binary @/path/to/image.png -H "Content-Type: image/png" http://127.0.0.1:8080/image
```

渲染模式：

- 默认 `--mode ascii`（灰度 ASCII 字符画）
- `--mode bitmap`（阈值二值化后 1bpp 输出，可配 `--threshold 0..255`、`--invert`、`--on/--off`）


