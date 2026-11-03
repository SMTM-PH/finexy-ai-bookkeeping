"""Restore the existing Finexy QA AVD to a phone without resetting user data.

Run with --apply. Evidence and original settings are retained under artifacts/.
This script does not install APKs, execute tests or access application data.
"""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time


ROOT = Path(__file__).resolve().parents[2]
SDK = Path(os.environ.get('ANDROID_HOME', 'E:/Android/Sdk'))
ADB = SDK / 'platform-tools/adb.exe'
EMULATOR = SDK / 'emulator/emulator.exe'
SERIAL = 'emulator-5554'
AVD = 'Finexy_API_35_Clean'
AVD_ROOT = Path(os.environ['USERPROFILE']) / '.android/avd'
CONFIG = AVD_ROOT / (AVD + '.avd') / 'config.ini'
REFERENCE = AVD_ROOT / 'Pixel_8_API_35.avd/config.ini'
QA_PACKAGES = ('com.finexy.mobile.qa', 'com.finexy.mobile.qa.test')


def command(*args, check=True, timeout=30):
    result = subprocess.run([str(arg) for arg in args], capture_output=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {args[:3]}: {result.stderr[:300]}')
    return result


def adb(*args, **kwargs):
    return command(ADB, '-s', SERIAL, *args, **kwargs)


def ini(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8-sig').splitlines()
                if '=' in line and not line.startswith('#'))


def classify_network_probe(result, marker):
    http_received = bool(re.search(r'(?:^|[\r\n])HTTP/', result['stdout']))
    status = re.search(r'(?:^|[\r\n])HTTP/\d(?:\.\d)?[ \t]+(\d{3})\b', result['stdout'])
    http_status = int(status.group(1)) if status else None
    marker_complete = result['stdout'].endswith(marker)
    passed = (not result['timedOut'] and result['exitCode'] == 0 and
              http_status == 200 and marker_complete)
    message = (result['stdout'] + '\n' + result['stderr']).lower()
    if passed:
        failure_kind = None
    elif http_received:
        # An HTTP response proves that a connection was established. A truncated
        # response or an unexpected status must not be treated as disconnected Wi-Fi.
        failure_kind = ('http-header-incomplete' if http_status is None else
                        'http-status' if http_status != 200 else
                        'http-body-incomplete' if not marker_complete else 'http-command-failed')
    elif result['timedOut'] or 'timed out' in message or 'timeout' in message:
        failure_kind = 'timeout'
    elif any(text in message for text in ('network is unreachable', 'network unreachable',
                                         'no route to host', 'network is down')):
        failure_kind = 'network-unreachable'
    elif 'connection refused' in message:
        failure_kind = 'connection-refused'
    else:
        failure_kind = 'unexpected-response'
    return {'passed': passed, 'httpResponseReceived': http_received,
            'httpStatus': http_status, 'markerComplete': marker_complete,
            'failureKind': failure_kind,
            'wifiRecoveryEligible': failure_kind in
            ('timeout', 'network-unreachable', 'connection-refused')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', required=True)
    args = parser.parse_args()
    assert args.apply
    assert CONFIG.is_file() and REFERENCE.is_file(), 'Only existing AVDs may be used'
    before = ini(CONFIG)
    reference = ini(REFERENCE)
    assert all(reference.get(key) == value for key, value in {
        'hw.device.name': 'pixel_8', 'hw.lcd.width': '1080', 'hw.lcd.height': '2400',
        'hw.lcd.density': '420', 'hw.screen': 'multi-touch', 'hw.mainKeys': 'no',
    }.items()), 'The reference AVD is no longer the verified Pixel phone profile'
    assert before['target'] == 'android-35' and before['abi.type'] == 'x86_64'
    assert before['image.sysdir.1'] == reference['image.sysdir.1']
    # Copy only the known Pixel phone hardware differences, retaining disk/image/AVD identity.
    desired = {key: value for key, value in reference.items() if key.startswith('hw.')}
    desired.update({'hw.ramSize': '4096', 'hw.cpu.ncore': '4',
                    'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'auto',
                    'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
                    'fastboot.forceChosenSnapshotBoot': 'no'})
    changes = {key: {'before': before.get(key), 'after': value}
               for key, value in desired.items() if before.get(key) != value}
    state = adb('get-state', check=False)
    running = state.returncode == 0 and state.stdout.strip() == 'device'
    if running:
        assert adb('emu', 'avd', 'name').stdout.splitlines()[0] == AVD, 'Another AVD owns serial 5554'
        assert not changes, 'Shut down this AVD normally before modifying its hardware profile'
    else:
        assert 'not found' in state.stderr, 'Device is offline or ADB is unavailable; do not start a duplicate'
        processes = command('powershell', '-NoProfile', '-Command',
                            "@(Get-Process -Name 'qemu-system-*','emulator' -ErrorAction SilentlyContinue).Count")
        assert processes.stdout.strip() == '0', 'An emulator is running; do not edit its configuration'
    stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
    folder = ROOT / ('artifacts/android-emulator-' + stamp[:8]) / stamp
    folder.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, folder / 'script-executed.py')
    shutil.copyfile(CONFIG, folder / 'config-before.ini')
    save = lambda name, data: (folder / name).write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    save('configuration-changes.json', changes)
    if changes:
        text = CONFIG.read_text(encoding='utf-8-sig')
        for key, change in changes.items():
            replacement = key + '=' + change['after']
            if key in before:
                text = re.sub(r'^' + re.escape(key) + r'=.*$', lambda _: replacement, text, flags=re.M)
            else:
                text += '\n' + replacement + '\n'
        CONFIG.write_text(text, encoding='utf-8', newline='\n')
    shutil.copyfile(CONFIG, folder / 'config-applied.ini')
    launch = [str(EMULATOR), '-avd', AVD, '-port', '5554', '-memory', '4096', '-cores', '4', '-no-snapshot',
              '-no-boot-anim', '-netdelay', 'none', '-netspeed', 'full']
    if not running:
        assert command(EMULATOR, '-accel-check').returncode == 0
        with (folder / 'emulator-stdout.log').open('w', encoding='utf-8') as stdout, \
             (folder / 'emulator-stderr.log').open('w', encoding='utf-8') as stderr:
            process = subprocess.Popen(launch, stdout=stdout, stderr=stderr,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
        save('launch.json', {'arguments': launch, 'processId': process.pid,
                             'dataReset': False, 'snapshotLoadOrSave': False})
        print(json.dumps({'phase': 'booting', 'avd': AVD, 'evidence': str(folder)}), flush=True)
    else:
        save('launch.json', {'desiredArguments': launch, 'reusedExistingEmulator': True,
                             'processStarted': False, 'dataReset': False})
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        boot = adb('shell', 'getprop', 'sys.boot_completed', check=False, timeout=8)
        if boot.returncode == 0 and boot.stdout.strip() == '1':
            break
        if not running and process.poll() is not None:
            raise RuntimeError('Emulator exited; inspect retained startup logs')
        time.sleep(2)
    else:
        raise RuntimeError('Boot timeout; retained emulator is not considered ready')
    assert adb('emu', 'avd', 'name').stdout.splitlines()[0] == AVD
    assert adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip() == '35'
    assert adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip() == 'x86_64'
    settings = [('global', 'auto_time_zone', '0'),
                ('system', 'font_scale', '1.0'), ('system', 'accelerometer_rotation', '0'),
                ('system', 'user_rotation', '0'), ('secure', 'show_ime_with_hard_keyboard', '1')]
    original = {'size': adb('shell', 'wm', 'size').stdout.strip(),
                'density': adb('shell', 'wm', 'density').stdout.strip(), 'settings': []}
    original['timezone'] = adb('shell', 'getprop', 'persist.sys.timezone').stdout.strip()
    for namespace, key, value in settings:
        original['settings'].append({'namespace': namespace, 'key': key,
                                     'value': adb('shell', 'settings', 'get', namespace, key).stdout.strip()})
    save('runtime-before.json', original)
    # Remove TV-era runtime overrides; the persisted hardware is already a phone.
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'wm', 'density', 'reset')
    for namespace, key, value in settings:
        adb('shell', 'settings', 'put', namespace, key, value)
        assert adb('shell', 'settings', 'get', namespace, key).stdout.strip() == value
    adb('shell', 'cmd', 'alarm', 'set-timezone', 'Asia/Shanghai')
    assert adb('shell', 'getprop', 'persist.sys.timezone').stdout.strip() == 'Asia/Shanghai'
    installed = adb('shell', 'pm', 'list', 'packages', 'finexy').stdout.splitlines()
    disabled = adb('shell', 'pm', 'list', 'packages', '-d', 'finexy').stdout.splitlines()
    enabled = []
    for package in QA_PACKAGES:
        if 'package:' + package in installed and 'package:' + package in disabled:
            adb('shell', 'pm', 'enable', '--user', '0', package)
            enabled.append(package)
    disabled_after = adb('shell', 'pm', 'list', 'packages', '-d', 'finexy').stdout.splitlines()
    assert not any('package:' + package in disabled_after for package in QA_PACKAGES)
    size = adb('shell', 'wm', 'size').stdout.strip()
    density = adb('shell', 'wm', 'density').stdout.strip()
    assert size == 'Physical size: 1080x2400' and density == 'Physical density: 420'
    cpus = adb('shell', 'cat', '/sys/devices/system/cpu/present').stdout.strip()
    memory = int(re.search(r'^MemTotal:\s+(\d+) kB',
                          adb('shell', 'cat', '/proc/meminfo').stdout, re.M).group(1))
    assert cpus == '0-3' and 3500000 < memory < 4300000, 'Actual CPU/RAM differs from configured hardware'
    hardware = ini(CONFIG.parent / 'hardware-qemu.ini')
    hardware = {key.strip(): value.strip() for key, value in hardware.items()}
    expected_hardware = {
        'hw.device.name': 'pixel_8', 'hw.ramSize': '4096', 'hw.cpu.ncore': '4',
        'hw.lcd.width': '1080', 'hw.lcd.height': '2400', 'hw.lcd.density': '420',
        'hw.screen': 'multi-touch', 'hw.mainKeys': 'false',
        'hw.initialOrientation': 'portrait', 'hw.camera.back': 'emulated',
        'hw.camera.front': 'none', 'hw.gpu.enabled': 'true',
    }
    hardware_mismatches = {
        key: {'expected': value, 'actual': hardware.get(key)}
        for key, value in expected_hardware.items() if hardware.get(key) != value
    }
    assert not hardware_mismatches, (
        'Actual emulator hardware differs from the required phone profile; '
        'shut down this AVD normally before reconfiguring: ' +
        json.dumps(hardware_mismatches, ensure_ascii=False)
    )
    instrumentation = adb('shell', 'pm', 'list', 'instrumentation', QA_PACKAGES[0]).stdout.splitlines()
    if 'package:' + QA_PACKAGES[1] in installed:
        assert instrumentation == ['instrumentation:' + QA_PACKAGES[1] +
                                    '/androidx.test.runner.AndroidJUnitRunner (target=' + QA_PACKAGES[0] + ')']
    network_started = time.monotonic()
    network_deadline = network_started + 30

    def network_command(*args):
        remaining = network_deadline - time.monotonic()
        if remaining <= 0:
            return {'exitCode': None, 'stdout': '', 'stderr': 'Network readiness budget exhausted',
                    'timedOut': True}
        try:
            result = adb(*args, check=False, timeout=min(5, remaining))
            return {'exitCode': result.returncode, 'stdout': result.stdout.strip()[:2000],
                    'stderr': result.stderr.strip()[:2000], 'timedOut': False}
        except subprocess.TimeoutExpired as error:
            decode = lambda value: (value.decode(errors='replace') if isinstance(value, bytes)
                                    else value or '')[:2000]
            return {'exitCode': None, 'stdout': decode(error.stdout), 'stderr': decode(error.stderr),
                    'timedOut': True}

    route_before = network_command('shell', 'ip', 'route')
    route_to_host_before = network_command('shell', 'ip', 'route', 'get', '10.0.2.2')
    save('network-routes-before.json', {'route': route_before, 'routeToHost': route_to_host_before})
    wifi_recovery = {'attempted': False, 'commands': []}
    save('network-recovery.json', wifi_recovery)
    marker = 'finexy-emulator-loopback-readiness'
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Length', str(len(marker)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(marker.encode())
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    port = server.server_address[1]
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    probes = []

    def probe():
        remaining = network_deadline - time.monotonic()
        if remaining <= 0:
            return False
        try:
            response = subprocess.run([str(ADB), '-s', SERIAL, 'shell', 'toybox', 'nc', '-w', '5',
                                       '10.0.2.2', str(port)],
                                      input=b'GET / HTTP/1.0\r\nHost: readiness\r\n\r\n',
                                      capture_output=True, timeout=min(6, remaining))
            result = {'exitCode': response.returncode,
                      'stdout': response.stdout.decode(errors='replace'),
                      'stderr': response.stderr.decode(errors='replace'), 'timedOut': False}
        except subprocess.TimeoutExpired as error:
            decode = lambda value: (value.decode(errors='replace') if isinstance(value, bytes)
                                    else value or '')
            result = {'exitCode': None, 'stdout': decode(error.stdout), 'stderr': decode(error.stderr),
                      'timedOut': True}
        result.update(classify_network_probe(result, marker))
        result['stdout'] = result['stdout'][:500]
        result['stderr'] = result['stderr'][:500]
        result.update({'attempt': len(probes) + 1,
                       'elapsedSeconds': round(time.monotonic() - network_started, 3)})
        probes.append(result)
        save('network-probe.json', result)
        save('network-probes.json', probes)
        return result['passed']

    try:
        passed = probe()
        while not passed and time.monotonic() < network_deadline:
            # Route text alone never establishes readiness. Recover Wi-Fi once
            # only for an explicit connection failure, then retry the actual HTTP.
            if probes and probes[-1]['wifiRecoveryEligible'] and not wifi_recovery['attempted']:
                wifi_recovery['attempted'] = True
                wifi_recovery['triggerAttempt'] = probes[-1]['attempt']
                wifi_recovery['triggerFailureKind'] = probes[-1]['failureKind']
                for args in [('shell', 'cmd', 'wifi', 'set-wifi-enabled', 'enabled'),
                             ('shell', 'cmd', 'wifi', 'connect-network', 'AndroidWifi', 'open')]:
                    wifi_recovery['commands'].append({'arguments': list(args), **network_command(*args)})
                    save('network-recovery.json', wifi_recovery)
            time.sleep(min(1, max(0, network_deadline - time.monotonic())))
            passed = probe()
        assert passed, 'Emulator cannot reach host loopback within the 30-second readiness budget'
        network = {'state': 'passed', 'hostBind': '127.0.0.1', 'temporaryPort': port,
                   'emulatorHostAlias': '10.0.2.2', 'applicationServiceTested': False,
                   'wifiRecoveryAttempted': wifi_recovery['attempted'],
                   'virtualWifiConnectionCommandSucceeded': any(
                       item['arguments'][3] == 'connect-network' and item['exitCode'] == 0
                       for item in wifi_recovery['commands']),
                   'routePresentBeforeProbe': bool(route_before['stdout']),
                   'routeToHostPresentBeforeProbe': route_to_host_before['exitCode'] == 0,
                   'probeAttempts': len(probes),
                   'readinessElapsedSeconds': round(time.monotonic() - network_started, 3)}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
        save('network-listener.json', {'temporaryListenerClosed': not worker.is_alive()})
    network['temporaryListenerClosed'] = not worker.is_alive()
    assert network['temporaryListenerClosed']
    save('network.json', network)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    summary = {'state': 'passed', 'recordedAt': stamp, 'serial': SERIAL, 'avd': AVD,
               'sdk': 35, 'abi': 'x86_64', 'size': size, 'density': density,
               'configuredRamMiB': 4096, 'configuredCpuCores': 4, 'configuredGpuMode': 'auto',
               'actualCpuPresent': cpus, 'actualMemTotalKiB': memory,
               'actualGpuMode': hardware['hw.gpu.mode'],
               'actualGpuEnabled': hardware['hw.gpu.enabled'] == 'true',
               'actualDeviceName': hardware['hw.device.name'],
               'touchScreen': hardware['hw.screen'],
               'mainKeys': hardware['hw.mainKeys'] == 'true',
               'fontScale': '1.0', 'orientation': hardware['hw.initialOrientation'],
               'rearCamera': hardware['hw.camera.back'], 'frontCamera': hardware['hw.camera.front'],
               'animations': {key: adb('shell', 'settings', 'get', 'global', key).stdout.strip()
                              for key in ('window_animation_scale', 'transition_animation_scale',
                                          'animator_duration_scale')},
               'timezone': adb('shell', 'getprop', 'persist.sys.timezone').stdout.strip(),
               'locales': adb('shell', 'settings', 'get', 'system', 'system_locales').stdout.strip(),
               'httpProxy': adb('shell', 'settings', 'get', 'global', 'http_proxy').stdout.strip(),
               'automaticTimezone': adb('shell', 'settings', 'get', 'global', 'auto_time_zone').stdout.strip(),
               'network': network,
               'packages': installed, 'enabledQaPackages': enabled,
               'instrumentation': instrumentation,
               'mainAppInstalled': 'package:com.finexy.mobile' in installed,
               'appDataAccessed': False, 'apksInstalled': False, 'testsExecuted': False,
               'configSHA256': hashlib.sha256(CONFIG.read_bytes()).hexdigest()}
    assert summary['timezone'] == 'Asia/Shanghai'
    save('summary.json', summary)
    print(json.dumps({'state': summary['state'], 'evidence': str(folder), 'size': size,
                      'density': density, 'qaPackagesEnabled': enabled}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
