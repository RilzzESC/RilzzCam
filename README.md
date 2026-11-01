# RilzzCam v4.0 — Full HD / 60 FPS target Android to Windows camera stream

This version improves the first MVP's frame pipeline:
- CameraX `ImageAnalysis` replaces repeated `ImageCapture` photo saves.
- Requests Full HD 1920x1080 YUV frames and converts them to JPEG in memory (quality 72).
- `STRATEGY_KEEP_ONLY_LATEST` and a latest-frame sender prevent old frames building up in a queue.
- Requests up to 60 FPS using the camera AE frame-rate range when advertised by the selected camera. The Android analyzer throttles at approximately 16 ms per frame.
- Preview uses FIT_CENTER to avoid cropping the phone preview.
- Windows receiver listens on all network interfaces, `0.0.0.0:4747`.

This is still a preview window, not a system-wide virtual webcam device. OBS/Zoom integration is a later step.

## Windows setup

Install Python 3.10+ and run in PowerShell inside `windows`:

```powershell
py -m pip install -r requirements.txt
py rilzzcam_receiver.py
```

Keep the receiver running while connecting the phone.

## USB connection

1. Connect the phone by USB and accept the USB debugging prompt.
2. Run in PowerShell:

```powershell
adb devices
adb reverse tcp:4747 tcp:4747
```

3. In RilzzCam Android, set host `127.0.0.1`, port `4747`, then tap **CONNECT & START / STOP**.

## Wi-Fi connection

1. Connect the phone and PC to the same normal Wi-Fi network (not a guest network).
2. On Windows run `ipconfig`; use the PC's Wi-Fi **IPv4 Address**, not the gateway.
3. Allow TCP port 4747 through Windows Firewall. Open PowerShell **as Administrator** in this folder and run:

```powershell
.\allow_wifi_firewall.ps1
```

4. In the Android app, replace `127.0.0.1` with the PC's IPv4 address, keep port `4747`, then connect.
5. If it still times out, check that the Wi-Fi router does not have AP/client isolation enabled and that Windows identifies the Wi-Fi network as Private.

## Wi-Fi troubleshooting

- `Connection refused`: receiver is not running, wrong IP/port, or port 4747 is not listening.
- `Connection timed out`: firewall, wrong subnet/IP, guest Wi-Fi, or router client isolation is likely.
- Check the listener on Windows with:

```powershell
netstat -ano | findstr :4747
```

It should show `0.0.0.0:4747` in LISTENING state.

## Build Android APK with GitHub Actions

Upload the contents of this project to your GitHub repository, including `.github/workflows/android.yml`. In GitHub open **Actions → Build RilzzCam Android APK → Run workflow**. Download the `RilzzCam-Android-APK` artifact after a successful build.

## Notes

- This build requests Full HD 1920x1080 and targets up to 60 FPS. The actual frame rate is not guaranteed: it depends on the camera's supported AE ranges at the selected resolution, phone thermal/CPU limits, JPEG encoding speed, USB/Wi-Fi throughput, and Windows decoding speed. The receiver displays the measured incoming FPS and actual frame dimensions in its window. A phone may advertise 60 FPS for some stream sizes but deliver less when Full HD analysis and preview are bound together.
- For sustained 1080p60, the current JPEG-over-TCP prototype may hit CPU or bandwidth limits. A later H.264/MediaCodec transport would be the better architecture for reliably reaching 60 FPS with lower network bandwidth.
- It does not yet provide camera zoom controls, audio, or a virtual webcam driver.
- Android app ID: `com.rilzzcam.app`.
