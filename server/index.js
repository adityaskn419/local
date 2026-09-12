import express from "express";
import { WebSocketServer } from "ws";
import http from "http";
import crypto from "crypto";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Set this to a long random string and keep it secret. Both the phone agent
// and your control dashboard must present it to connect.
const AUTH_TOKEN = process.env.RELAY_TOKEN || "CHANGE_ME_TO_A_LONG_RANDOM_TOKEN";

const app = express();
app.use(express.static(path.join(__dirname, "public")));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Only one phone agent expected, but keep it a map in case of multiple devices.
const agents = new Map(); // deviceId -> ws
const dashboards = new Set();
const pending = new Map(); // requestId -> dashboard ws (to route responses back)

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

wss.on("connection", (ws, req) => {
  let role = null;
  let deviceId = null;

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }

    if (msg.type === "hello") {
      if (msg.token !== AUTH_TOKEN) {
        send(ws, { type: "error", message: "bad token" });
        ws.close();
        return;
      }
      role = msg.role; // "agent" or "dashboard"
      if (role === "agent") {
        deviceId = msg.deviceId || "default";
        agents.set(deviceId, ws);
        console.log(`agent connected: ${deviceId}`);
        for (const d of dashboards) send(d, { type: "agent_status", deviceId, online: true });
      } else if (role === "dashboard") {
        dashboards.add(ws);
        const online = [...agents.keys()];
        send(ws, { type: "agent_list", devices: online });
      }
      return;
    }

    if (role === "dashboard" && msg.type === "command") {
      // { type: "command", deviceId, requestId, action, args }
      const agentWs = agents.get(msg.deviceId);
      if (!agentWs) {
        send(ws, { type: "result", requestId: msg.requestId, ok: false, error: "device offline" });
        return;
      }
      pending.set(msg.requestId, ws);
      send(agentWs, msg);
      return;
    }

    if (role === "agent" && msg.type === "result") {
      const dashWs = pending.get(msg.requestId);
      pending.delete(msg.requestId);
      if (dashWs) send(dashWs, msg);
      return;
    }
  });

  ws.on("close", () => {
    if (role === "agent" && deviceId) {
      agents.delete(deviceId);
      for (const d of dashboards) send(d, { type: "agent_status", deviceId, online: false });
      console.log(`agent disconnected: ${deviceId}`);
    }
    if (role === "dashboard") dashboards.delete(ws);
  });
});

const PORT = process.env.PORT || 8080;
server.listen(PORT, () => console.log(`relay listening on :${PORT}`));
