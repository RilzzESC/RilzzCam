import socket
import struct
import sys
import cv2
import numpy as np

HOST = "0.0.0.0"
PORT = 4747
MAX_FRAME_SIZE = 12 * 1024 * 1024

def recv_exact(sock, count):
    chunks = bytearray()
    while len(chunks) < count:
        data = sock.recv(count - len(chunks))
        if not data:
            return None
        chunks.extend(data)
    return bytes(chunks)

def handle_client(conn, address):
    print(f"[RilzzCam] Android connected: {address}")
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    try:
        while True:
            header = recv_exact(conn, 4)
            if header is None:
                break
            frame_size = struct.unpack(">I", header)[0]
            if frame_size <= 0 or frame_size > MAX_FRAME_SIZE:
                print(f"[RilzzCam] Invalid frame size: {frame_size}")
                break
            payload = recv_exact(conn, frame_size)
            if payload is None:
                break
            frame = cv2.imdecode(np.frombuffer(payload, dtype=np.uint8), cv2.IMREAD_COLOR)
            if frame is None:
                continue
            cv2.imshow("RilzzCam - Live Camera  |  Press Q to quit", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
    except (ConnectionError, OSError) as exc:
        print(f"[RilzzCam] Connection ended: {exc}")
    finally:
        conn.close()
        cv2.destroyAllWindows()
        print("[RilzzCam] Android disconnected")

def main():
    print("====================================")
    print(" RilzzCam Windows Receiver v1.0")
    print(f" Listening on {HOST}:{PORT}")
    print(" Start Android app and connect.")
    print("====================================")
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(1)
    try:
        while True:
            conn, address = server.accept()
            handle_client(conn, address)
    except KeyboardInterrupt:
        print("\\n[RilzzCam] Receiver stopped")
    finally:
        server.close()
        cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
