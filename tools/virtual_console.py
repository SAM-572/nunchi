"""Pleos Connect Virtual Console을 스크립트로 조작한다 (시연·검증용).

준비:  adb forward tcp:8088 tcp:8088   (에뮬레이터 실행 후 한 번)

미리 만든 시나리오:
    python virtual_console.py hazard     # S1: 비상등을 켠 채 주행 → 10초 뒤 알림 → 끄기
    python virtual_console.py signal     # S2: 오른쪽 깜빡이를 켠 채 직진 → 15초 뒤 알림 → 끄기
    python virtual_console.py reset      # 모두 끄고 P단으로

직접 조작 (순서대로 실행):
    python virtual_console.py "/vehicle/gear?gear=Drive" sleep:2 "state:lights"
    - "/vehicle/..."  콘솔에 PUT 요청 (경로 목록: http://127.0.0.1:8088/vehicle/catalog)
    - "sleep:초"      대기
    - "state:키.경로"  현재 차량 상태 출력

콘솔은 브라우저가 1초마다 보내는 하트비트가 있어야 명령을 받으므로, 실행하는 동안 하트비트를 대신 보낸다.

비상등 참고: 에뮬레이터의 Hazard Lights 카드는 계기판 화살표 경고등까지 켜 주지 않는다.
실차에서는 비상등 스위치가 좌·우 화살표 경고등을 함께 켜므로, 시나리오에서 경고등도 같이 켠다.
"""
import json
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8088"
CLIENT = "nunchi-script"

TELLTALE = "/vehicle/telltales/simulator?color=Green&type={type}&state={state}"
HAZARD_ON = [
    "/vehicle/lights?feature=hazard&value=true",
    TELLTALE.format(type="TurnSignalLeft", state="Blink"),
    TELLTALE.format(type="TurnSignalRight", state="Blink"),
]
HAZARD_OFF = [
    "/vehicle/lights?feature=hazard&value=false",
    TELLTALE.format(type="TurnSignalLeft", state="Off"),
    TELLTALE.format(type="TurnSignalRight", state="Off"),
]
DRIVE = ["/vehicle/gear?gear=Drive", "/vehicle/controls?speed=40", "/vehicle/controls?steering=0"]
PARK = ["/vehicle/controls?speed=0", "/vehicle/gear?gear=Park"]

SCENARIOS = {
    "hazard": [*DRIVE, "sleep:3", *HAZARD_ON, "sleep:16", *HAZARD_OFF, "sleep:3", *PARK],
    "signal": [*DRIVE, "sleep:3", "/vehicle/turn-signal?mode=BlinkRight", "sleep:21",
               "/vehicle/turn-signal?mode=Off", "sleep:3", *PARK],
    "reset": [*HAZARD_OFF, "/vehicle/turn-signal?mode=Off", *PARK],
}


def call(method, path):
    request = urllib.request.Request(BASE + path, method=method, data=None if method == "GET" else b"")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')}"
    except OSError as e:
        return f"연결 실패: {e} (에뮬레이터 실행과 adb forward tcp:8088 tcp:8088 확인)"


def heartbeat(stop):
    while not stop.is_set():
        call("POST", f"/browser/session?client={CLIENT}")
        stop.wait(1.0)


def dig(value, path):
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    return value


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    steps = []
    for arg in sys.argv[1:]:
        steps.extend(SCENARIOS.get(arg, [arg]))
    if not steps:
        print(__doc__)
        return

    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    time.sleep(2.5)  # 콘솔이 하트비트를 받고 활성화될 때까지
    try:
        for step in steps:
            if step.startswith("sleep:"):
                time.sleep(float(step[6:]))
            elif step.startswith("state:"):
                state = json.loads(call("GET", "/vehicle/state"))
                print(f"{step} = {json.dumps(dig(state, step[6:]), ensure_ascii=False)}", flush=True)
            else:
                print(f"PUT {step} -> {call('PUT', step)[:120]}", flush=True)
    finally:
        stop.set()
        call("POST", f"/browser/session?client={CLIENT}&closing=1")


if __name__ == "__main__":
    main()
