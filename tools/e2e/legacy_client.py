#!/usr/bin/env python3
"""Minimal 1.21.5 (protocol 770) client: log in through ViaBackwards, run /menu, and click
emulated dialog chest slots. Prints each screen the server opens (title text) and chat/action bar text.
usage: python3 -I tools/e2e/legacy_client.py <port> <name> <slot>[,<slot>...]
Needs ViaVersion + ViaBackwards on the test server and online-mode=false. Packet ids come from
ViaVersion 5.12.0 (ServerboundPackets1_21_5 / ClientboundPackets1_21_5)."""
import io, socket, struct, sys, time, uuid, zlib, hashlib, select

PROTO = 770

def varint(n):
    n &= 0xFFFFFFFF; out = b""
    while True:
        b = n & 0x7F; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])

def rv(bio):
    num = 0
    for i in range(5):
        b = bio.read(1)[0]; num |= (b & 0x7F) << (7 * i)
        if not b & 0x80: break
    return num - (1 << 32) if num & 0x80000000 else num

def mcstr(s):
    b = s.encode(); return varint(len(b)) + b

# --- minimal network NBT (unnamed root) -> python, collecting strings
def nbt_payload(bio, t, out):
    if t == 1: bio.read(1)
    elif t == 2: bio.read(2)
    elif t == 3: bio.read(4)
    elif t == 4: bio.read(8)
    elif t == 5: bio.read(4)
    elif t == 6: bio.read(8)
    elif t == 7: n = struct.unpack(">i", bio.read(4))[0]; bio.read(n)
    elif t == 8:
        n = struct.unpack(">H", bio.read(2))[0]; out.append(bio.read(n).decode("utf-8", "replace"))
    elif t == 9:
        et = bio.read(1)[0]; n = struct.unpack(">i", bio.read(4))[0]
        for _ in range(n): nbt_payload(bio, et, out)
    elif t == 10:
        while True:
            ct = bio.read(1)[0]
            if ct == 0: break
            n = struct.unpack(">H", bio.read(2))[0]; key = bio.read(n).decode()
            sub = []; nbt_payload(bio, ct, sub)
            if key in ("text", "translate", "") or key.isdigit(): out.extend(sub)
            elif ct in (9, 10): out.extend(sub)
    elif t == 11: n = struct.unpack(">i", bio.read(4))[0]; bio.read(4 * n)
    elif t == 12: n = struct.unpack(">i", bio.read(4))[0]; bio.read(8 * n)
    else: raise ValueError("nbt type %d" % t)

def nbt_text(bio):
    t = bio.read(1)[0]; out = []; nbt_payload(bio, t, out); return "".join(out)

class Conn:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=30); self.th = -1; self.buf = b""
    def send(self, pid, payload=b""):
        body = varint(pid) + payload
        if self.th >= 0: body = varint(0) + body
        self.s.sendall(varint(len(body)) + body)
    def _fill(self, n, timeout):
        end = time.time() + timeout
        while len(self.buf) < n:
            r, _, _ = select.select([self.s], [], [], max(0, end - time.time()))
            if not r: return False
            c = self.s.recv(65536)
            if not c: raise EOFError("closed")
            self.buf += c
        return True
    def recv(self, timeout=1.0):
        # read varint length
        i = 0; length = 0
        while True:
            if not self._fill(i + 1, timeout): return None
            b = self.buf[i]; length |= (b & 0x7F) << (7 * i); i += 1
            if not b & 0x80: break
        if not self._fill(i + length, 30): raise EOFError("short")
        data = self.buf[i:i + length]; self.buf = self.buf[i + length:]
        bio = io.BytesIO(data)
        if self.th >= 0:
            dlen = rv(bio); rest = bio.read()
            bio = io.BytesIO(zlib.decompress(rest) if dlen > 0 else rest)
        return rv(bio), bio

def main():
    port, name = int(sys.argv[1]), sys.argv[2]
    slots = [int(x) for x in sys.argv[3].split(",")] if len(sys.argv) > 3 else []
    c = Conn(port)
    c.send(0x00, varint(PROTO) + mcstr("localhost") + struct.pack(">H", port) + varint(2))
    off = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    off = uuid.UUID(int=(off.int & ~(0xF000 << 64)) | (3 << 76))
    c.send(0x00, mcstr(name) + off.bytes)
    state = "login"
    while state == "login":
        pid, bio = c.recv(30)
        if pid == 0x03: c.th = rv(bio)
        elif pid == 0x02: c.send(0x03); state = "config"
        elif pid == 0x00: print("LOGIN DISCONNECT", bio.read()[:200]); return
    while state == "config":
        r = c.recv(30)
        if r is None: print("config timeout"); return
        pid, bio = r
        if pid == 0x0E: c.send(0x07, bio.read())           # known packs: echo
        elif pid == 0x04: c.send(0x04, bio.read(8))         # keep alive
        elif pid == 0x05: c.send(0x05, bio.read(4))         # ping -> pong
        elif pid == 0x03: c.send(0x03); state = "play"      # finish -> ack
        elif pid == 0x02: print("CONFIG DISCONNECT", nbt_text(bio)); return
    print("joined play state as", name)
    loaded = False; t_loaded = None; screens = []; window = None; state_id = 0
    actions = ["menu"] + slots; step = 0; waiting_since = None
    deadline = time.time() + 60
    while time.time() < deadline:
        r = c.recv(0.2)
        if r is not None:
            pid, bio = r
            if pid == 0x26: c.send(0x1A, bio.read(8))
            elif pid == 0x36: c.send(0x2B, bio.read(4))
            elif pid == 0x0B: c.send(0x09, struct.pack(">f", 20.0))
            elif pid == 0x41:
                tid = rv(bio); c.send(0x00, varint(tid))
                if not loaded: c.send(0x2A); loaded = True; t_loaded = time.time()
            elif pid == 0x34:
                window = rv(bio); wtype = rv(bio); title = nbt_text(bio)
                screens.append(title); print(f"OPEN_SCREEN window={window} type={wtype} title={title!r}")
                waiting_since = None
            elif pid == 0x12:
                w = rv(bio); state_id = rv(bio); raw = bio.read()
                print(f"CONTAINER_SET_CONTENT window={w} state={state_id} bytes={len(raw)}")
            elif pid == 0x11: print("CONTAINER_CLOSE", rv(bio))
            elif pid == 0x72:
                try: print("CHAT", nbt_text(bio)[:160])
                except Exception as e: print("CHAT (unparsed)", e)
            elif pid == 0x50:
                try: print("ACTIONBAR", nbt_text(bio)[:160])
                except Exception: pass
            elif pid == 0x1C: print("DISCONNECT", nbt_text(bio)); return
        if loaded and time.time() - t_loaded > 2 and step < len(actions) and waiting_since is None:
            a = actions[step]; step += 1
            if a == "menu":
                print(">> /menu"); c.send(0x05, mcstr("menu")); waiting_since = time.time()
            else:
                time.sleep(0.5)
                print(f">> click slot {a} in window {window} (state {state_id})")
                c.send(0x10, varint(window) + varint(state_id) + struct.pack(">h", a) + b"\x00" + varint(0) + varint(0) + b"\x00")
                waiting_since = time.time()
        if waiting_since is not None and time.time() - waiting_since > 6:
            print("   (no new screen within 6s)"); waiting_since = None
        if step >= len(actions) and waiting_since is None:
            time.sleep(1); break
    print("SCREENS:", screens)

main()
