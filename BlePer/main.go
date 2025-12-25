package main

import (
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// CommandMessage is sent by controllers to instruct device actions.
type CommandMessage struct {
	Type     string `json:"type"`   // "command"
	Action   string `json:"action"` // e.g. "next", "prev", "capture"
	DeviceID string `json:"deviceId"`
	URL      string `json:"url,omitempty"`
}

// StatusMessage is sent by devices to report progress back to controllers.
type StatusMessage struct {
	Type     string `json:"type"` // "status"
	DeviceID string `json:"deviceId"`
	Status   string `json:"status"`
	Detail   string `json:"detail,omitempty"`
}

// ImageMessage carries base64 image from device to controllers.
type ImageMessage struct {
	Type     string `json:"type"` // "image"
	DeviceID string `json:"deviceId"`
	Data     string `json:"data"`           // base64 (no data URI prefix)
	MIME     string `json:"mime,omitempty"` // default image/png if empty
}

// Client wraps a websocket connection with metadata.
type Client struct {
	conn     *websocket.Conn
	role     string // "controller" or "device"
	deviceID string
	send     chan []byte
}

type Hub struct {
	mu          sync.RWMutex
	devices     map[string]*Client
	controllers map[*Client]struct{}
}

func NewHub() *Hub {
	return &Hub{
		devices:     make(map[string]*Client),
		controllers: make(map[*Client]struct{}),
	}
}

func (h *Hub) addClient(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if c.role == "device" {
		h.devices[c.deviceID] = c
		log.Printf("device connected: %s", c.deviceID)
	} else {
		h.controllers[c] = struct{}{}
		log.Printf("controller connected")
	}
}

func (h *Hub) removeClient(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if c.role == "device" {
		if existing, ok := h.devices[c.deviceID]; ok && existing == c {
			delete(h.devices, c.deviceID)
			log.Printf("device disconnected: %s", c.deviceID)
		}
	} else {
		delete(h.controllers, c)
		log.Printf("controller disconnected")
	}
}

// forwardCommand sends a controller command to a device, if present.
func (h *Hub) forwardCommand(cmd CommandMessage) {
	h.mu.RLock()
	target := h.devices[cmd.DeviceID]
	h.mu.RUnlock()

	if target == nil {
		log.Printf("no device for command: %s", cmd.DeviceID)
		return
	}
	payload, err := json.Marshal(cmd)
	if err != nil {
		log.Printf("marshal command err: %v", err)
		return
	}
	select {
	case target.send <- payload:
	default:
		log.Printf("device channel full, drop command: %s", cmd.DeviceID)
	}
}

// broadcastStatus pushes device status to all controllers.
func (h *Hub) broadcastStatus(msg StatusMessage) {
	payload, err := json.Marshal(msg)
	if err != nil {
		log.Printf("marshal status err: %v", err)
		return
	}
	h.broadcastRawToControllers(payload)
}

func (h *Hub) broadcastRawToControllers(payload []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for c := range h.controllers {
		select {
		case c.send <- payload:
		default:
			log.Printf("controller channel full, drop status")
		}
	}
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

const pageHTML = `<!doctype html>
<html lang="en">
<head>
	<meta charset="UTF-8" />
	<meta name="viewport" content="width=device-width, initial-scale=1.0" />
	<title>Remote Viewer</title>
	<style>
		body { margin: 0; font-family: sans-serif; background: #0f172a; color: #e2e8f0; }
		header { padding: 12px 16px; background: #1e293b; display: flex; gap: 12px; align-items: center; }
		input, button { font-size: 14px; padding: 8px 12px; border-radius: 6px; border: 1px solid #334155; background: #111827; color: #e2e8f0; }
		button { cursor: pointer; background: #2563eb; border: none; }
		button:hover { background: #1d4ed8; }
		#status { margin-left: auto; font-size: 13px; color: #a5b4fc; }
		main { display: grid; grid-template-columns: 320px 1fr; min-height: calc(100vh - 56px); }
		.panel { padding: 16px; background: #0b1221; border-right: 1px solid #1e293b; }
		.panel h3 { margin: 0 0 8px 0; }
		.log { font-size: 13px; white-space: pre-wrap; background: #0f172a; padding: 8px; border-radius: 6px; height: 200px; overflow: auto; border: 1px solid #1f2a3d; }
		.viewer { display: flex; align-items: center; justify-content: center; background: #0f172a; }
		#img { max-width: 100%; max-height: calc(100vh - 56px); object-fit: contain; background: #000; }
	</style>
</head>
<body>
	<header>
		<label>Device ID <input id="device" value="x4-001" size="10"></label>
		<button id="prev">上一页</button>
		<button id="next">下一页</button>
		<button id="capture">截图</button>
		<div id="status">connecting...</div>
	</header>
	<main>
		<div class="panel">
			<h3>Log</h3>
			<div class="log" id="log"></div>
		</div>
		<div class="viewer">
			<img id="img" alt="preview" />
		</div>
	</main>
	<script>
		const deviceInput = document.getElementById('device');
		const logEl = document.getElementById('log');
		const statusEl = document.getElementById('status');
		const imgEl = document.getElementById('img');

		function logLine(msg) {
			const t = new Date().toLocaleTimeString();
			logEl.textContent = '[' + t + '] ' + msg + '\n' + logEl.textContent;
		}

		function sendCommand(action) {
			const deviceId = deviceInput.value.trim();
			if (!deviceId) { alert('Device ID required'); return; }
			const payload = { type: 'command', action, deviceId };
			ws.send(JSON.stringify(payload));
			logLine('send command ' + action + ' -> ' + deviceId);
		}

		const ws = new WebSocket('ws://' + location.host + '/ws?role=controller');
		ws.onopen = () => { statusEl.textContent = 'connected'; logLine('ws connected'); };
		ws.onclose = () => { statusEl.textContent = 'closed'; logLine('ws closed'); };
		ws.onerror = (e) => { statusEl.textContent = 'error'; logLine('ws error'); };
		ws.onmessage = (ev) => {
			try {
				const msg = JSON.parse(ev.data);
				if (msg.type === 'status') {
					statusEl.textContent = (msg.deviceId || '') + ' ' + (msg.status || '');
					logLine('status from ' + (msg.deviceId || '') + ': ' + (msg.status || '') + ' ' + (msg.detail || ''));
				} else if (msg.type === 'image') {
					const mime = msg.mime || 'image/png';
					imgEl.src = 'data:' + mime + ';base64,' + msg.data;
					statusEl.textContent = (msg.deviceId || '') + ' image @ ' + new Date().toLocaleTimeString();
				}
			} catch (err) {
				logLine('bad message: ' + err);
			}
		};

		document.getElementById('prev').onclick = () => sendCommand('prev');
		document.getElementById('next').onclick = () => sendCommand('next');
		document.getElementById('capture').onclick = () => sendCommand('capture');
	</script>
</body>
</html>`

func main() {
	port := getPort()
	hub := NewHub()

	http.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		role := r.URL.Query().Get("role")
		if role != "controller" && role != "device" {
			http.Error(w, "role must be controller or device", http.StatusBadRequest)
			return
		}
		deviceID := r.URL.Query().Get("id")
		if role == "device" && deviceID == "" {
			http.Error(w, "device role requires id", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("upgrade err: %v", err)
			return
		}
		client := &Client{conn: conn, role: role, deviceID: deviceID, send: make(chan []byte, 16)}
		hub.addClient(client)

		go writePump(client)
		go readPump(hub, client)
	})

	http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(pageHTML))
	})

	log.Printf("websocket server listening on :%s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}

// readPump handles inbound messages from a client.
func readPump(hub *Hub, c *Client) {
	defer func() {
		hub.removeClient(c)
		c.conn.Close()
	}()
	c.conn.SetReadLimit(8 << 20)
	_ = c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	c.conn.SetPongHandler(func(string) error {
		_ = c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("read err: %v", err)
			}
			break
		}
		_ = c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))

		if c.role == "controller" {
			var cmd CommandMessage
			if err := json.Unmarshal(data, &cmd); err != nil {
				log.Printf("bad command json: %v", err)
				continue
			}
			if cmd.Type != "command" {
				log.Printf("ignored non-command from controller")
				continue
			}
			hub.forwardCommand(cmd)
		} else { // device
			var envelope struct {
				Type string `json:"type"`
			}
			if err := json.Unmarshal(data, &envelope); err != nil {
				log.Printf("bad device json: %v", err)
				continue
			}
			switch envelope.Type {
			case "status":
				var status StatusMessage
				if err := json.Unmarshal(data, &status); err != nil {
					log.Printf("bad status json: %v", err)
					continue
				}
				if status.DeviceID == "" {
					status.DeviceID = c.deviceID
				}
				hub.broadcastStatus(status)
			case "image":
				var img ImageMessage
				if err := json.Unmarshal(data, &img); err != nil {
					log.Printf("bad image json: %v", err)
					continue
				}
				if img.DeviceID == "" {
					img.DeviceID = c.deviceID
				}
				if img.MIME == "" {
					img.MIME = "image/png"
				}
				payload, err := json.Marshal(img)
				if err != nil {
					log.Printf("marshal image err: %v", err)
					continue
				}
				hub.broadcastRawToControllers(payload)
			default:
				log.Printf("ignored device message type: %s", envelope.Type)
			}
		}
	}
}

// writePump pushes queued messages to a client.
func writePump(c *Client) {
	ticker := time.NewTicker(30 * time.Second)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case msg, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func getPort() string {
	if p := os.Getenv("PORT"); p != "" {
		return p
	}
	p := flag.String("port", "18081", "listen port")
	flag.Parse()
	return *p
}
