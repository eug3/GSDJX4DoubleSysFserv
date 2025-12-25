# BlePer Go WebSocket Control Server

Simple hub to relay page-control commands to devices (Android app) over WebSocket. Controllers send commands, devices receive them; devices can push status back to controllers.

## Protocol

- WebSocket endpoint: `/ws` with query params:
  - `role=controller` or `role=device`
  - `id=<deviceId>` required for devices; optional for controllers.
- Messages are JSON text.

### Controller → Server → Device
```json
{
  "type": "command",
  "action": "next",   // or "prev", "capture", "reload"
  "deviceId": "x4-001",
  "url": "https://weread.qq.com/"    // optional
}
```

### Device → Server → Controllers
```json
{
  "type": "status",
  "deviceId": "x4-001",
  "status": "ok",             // e.g. ok, busy, failed
  "detail": "page advanced"    // optional
}
```

## Run
```
cd BlePer
GO111MODULE=on go run .
# or specify port
PORT=18081 go run .
```

## Android-side expectations
- App connects as device: `ws://<server>:18081/ws?role=device&id=<deviceId>`
- Listens for `command` messages and maps `action` to: prev/next/capture/reload
- Sends back `status` messages when a command is handled.

## Notes
- Uses `github.com/gorilla/websocket`
- Allows all origins; place behind reverse proxy if needed.
- Keep-alive ping every 30s; 60s read deadline.
