import socket
import struct
import time
import cv2
import numpy as np

HOST = "0.0.0.0"
PORT = 4747
MAX_FRAME_SIZE = 12 * 1024 * 1024
WINDOW = "RilzzCam - Live Camera | Press Q to quit"


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
    cv2.namedWindow(WINDOW, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(WINDOW, 1280, 720)  # 16:9 window for Full HD stream
    frame_count = 0
    measured_fps = 0.0
    fps_window_start = time.perf_counter()
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

            frame_count += 1
            now = time.perf_counter()
            elapsed = now - fps_window_start
            if elapsed >= 1.0:
                measured_fps = frame_count / elapsed
                frame_count = 0
                fps_window_start = now

            height, width = frame.shape[:2]
            cv2.putText(
                frame,
                f"{width}x{height}  |  {measured_fps:.1f} FPS",
                (18, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.8,
                (40, 240, 40), 2, cv2.LINE_AA
            )
            cv2.imshow(WINDOW, frame)
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
    print(" RilzzCam Windows Receiver v4.0")
    print(f" Listening on {HOST}:{PORT}")
    print(" Stream target: Full HD 1920x1080 / up to 60 FPS (device/network dependent)")
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
        print("\n[RilzzCam] Receiver stopped")
    finally:
        server.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
