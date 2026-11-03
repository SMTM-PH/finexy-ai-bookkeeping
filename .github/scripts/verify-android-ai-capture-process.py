"""Actual Camera foreground -> target-process exit -> real return -> new-process UI recovery. QA only."""
from pathlib import Path
import importlib.util
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = (ROOT / os.environ.get('FINEXY_CAPTURE_PROCESS_EVIDENCE', 'artifacts/ai-review-android-capture-20261020/process')).resolve()
assert EVIDENCE.is_relative_to(ROOT / 'artifacts')
APP = 'com.finexy.mobile.qa'
CLASS = 'com.finexy.mobile.AIPendingCaptureProcessE2ETest'


def adb(*args, timeout=30):
    result = subprocess.run(['adb', '-s', 'emulator-5554', *args], capture_output=True, encoding='utf-8', timeout=timeout)
    if result.returncode:
        raise RuntimeError('QA adb command failed: ' + result.stderr[:300])
    return result.stdout.strip()


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(__file__, EVIDENCE / 'driver-executed.py')
    namespace = 'ledger-write-recovery-' + secrets.token_hex(8)
    (EVIDENCE / 'run.json').write_text(json.dumps({'namespace': namespace, 'qaOnly': True}), encoding='utf-8')
    # No credentials or financial values enter host evidence. The two application UIDs must differ.
    packages = adb('shell', 'pm', 'list', 'packages', 'com.finexy').splitlines()
    if 'package:com.finexy.mobile' in packages:
        assert adb('shell', 'run-as', APP, 'id', '-u') != adb('shell', 'run-as', 'com.finexy.mobile', 'id', '-u')
    else:
        assert adb('emu', 'avd', 'name').splitlines()[0].strip() == 'Finexy_API_35_Clean'
    spec = importlib.util.spec_from_file_location('gate', ROOT / '.github/scripts/verify-android-instrumentation.py')
    gate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gate)
    expected = gate.expected_tests('ai-pending-capture-process')

    def pid():
        result = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'pidof', APP],
                                capture_output=True, encoding='utf-8', timeout=15)
        if result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
            return None
        assert result.returncode == 0 and result.stdout.strip().isdigit(), 'QA process probe failed'
        return int(result.stdout.strip())

    def terminate_verified_pid(expected_pid):
        assert pid() == expected_pid
        command_line = adb('exec-out', 'run-as', APP, 'cat', f"/proc/{expected_pid}/cmdline").rstrip('\x00')
        assert command_line == APP, 'Only the exact isolated QA process may be terminated'
        qa_uid = int(adb('shell', 'run-as', APP, 'id', '-u'))
        assert qa_uid >= 10000
        adb('shell', 'run-as', APP, 'kill', '-9', str(expected_pid))
        for _ in range(50):
            next_pid = pid()
            if next_pid != expected_pid:
                return qa_uid, next_pid
            time.sleep(.1)
        raise RuntimeError('Original QA PID did not exit; no new-process claim allowed')

    def phase(method, filename):
        # Normal instrumentation completion force-stops the target and revokes Camera's URI grant.
        # Without-restart instrumentation must attach to a live process; preserve it for am kill.
        extra = ['--no-restart', '-e', 'waitForActivitiesToComplete', 'false'] if method.startswith('prepare') else []
        output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'finexy.ai.source.url', 'http://10.0.2.2:18141',
                     '-e', 'finexy.capture.namespace', namespace, '-e', 'class', CLASS + '#' + method,
                     *extra, APP + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=240)
        (EVIDENCE / filename).write_text(output, encoding='utf-8')
        subset = {test for test in expected if test[1] == method}
        assert len(subset) == 1
        return gate.verify_output(output, subset)

    def witness():
        return json.loads(adb('exec-out', 'run-as', APP, 'cat', 'files/capture-process/' + namespace + '.json'))

    def camera_tree():
        raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=40)
        begin, end = raw.find('<?xml'), raw.rfind('</hierarchy>')
        assert begin >= 0 and end > begin, 'Camera UI tree unavailable'
        xml = raw[begin:end + len('</hierarchy>')]
        return xml, ET.fromstring(xml)

    stale_pid = pid()
    if stale_pid is not None:
        # Espresso/runner singletons cannot be attached a second time in a prior test process.
        terminate_verified_pid(stale_pid)
    (EVIDENCE / 'initial-process.json').write_text(json.dumps({'previousQaPid': stale_pid,
        'freshRunnerProcessRequired': True}, indent=2), encoding='utf-8')
    adb('shell', 'am', 'start', '-W', '-n', APP + '/com.finexy.mobile.MainActivity')
    warm_pid = pid()
    assert warm_pid is not None, 'Without-restart preparation requires a live QA process'
    phase('prepareActualCameraInForegroundWithDurableSourceOwnership', 'prepare.log')
    before = witness()
    assert before['preparePid'] == warm_pid and pid() == warm_pid, 'Preparation must leave its original process alive'
    xml, tree = camera_tree()
    assert any(node.get('package') == 'com.android.camera2' for node in tree.iter('node')), 'Camera must be foreground before process exit'
    (EVIDENCE / 'camera-before-exit.xml').write_text(xml, encoding='utf-8')
    # am kill only removes cached processes, and a stopped Activity may still be at adj=700.
    # Send process death from the app's own UID after pinning its exact PID and command line.
    qa_uid, after_kill_pid = terminate_verified_pid(before['preparePid'])
    (EVIDENCE / 'process-exit.json').write_text(json.dumps({'preparePid': before['preparePid'],
                                                          'originalProcessExited': True, 'qaPidAfterKill': after_kill_pid,
                                                          'absentAfterKill': after_kill_pid is None, 'termination': 'verified-QA-UID SIGKILL', 'qaUid': qa_uid,
                                                          'cameraForeground': True, 'pidVerifiedBeforeKill': True,
                                                          'prepareWithoutRestart': True}, indent=2), encoding='utf-8')
    xml, tree = camera_tree()
    done = [node for node in tree.iter('node') if node.get('package') == 'com.android.camera2'
            and node.get('resource-id', '').endswith(':id/done_button') and node.get('enabled') == 'true']
    assert len(done) == 1, 'Actual camera confirmation must still be available after QA exit'
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', done[0].get('bounds', ''))
    assert bounds
    left, top, right, bottom = map(int, bounds.groups())
    assert right > left and bottom > top
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    # Camera writes asynchronously. Starting normal verification too early would revoke its grant.
    # Restrict the witness to an owned relative path, even for older canonical-path test witnesses.
    capture_path = 'files/receipt-captures/' + before['capturePath'].split('receipt-captures/', 1)[1]
    assert re.fullmatch(r'files/receipt-captures/[a-f0-9]{64}-0/receipt-[a-f0-9-]{36}\.jpg', capture_path)
    deadline = time.monotonic() + 30
    camera_finished = False
    size = None
    captured_hash = None
    while time.monotonic() < deadline:
        xml, tree = camera_tree()
        camera_finished = not any(node.get('package') == 'com.android.camera2' for node in tree.iter('node'))
        status = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'run-as', APP,
                                 'stat', '-c', '%s', capture_path], capture_output=True, encoding='utf-8', timeout=15)
        if status.returncode == 0:
            size = int(status.stdout.strip())
            if camera_finished and size > 0:
                captured_hash = adb('shell', 'run-as', APP, 'sha256sum', capture_path).split()[0]
                assert re.fullmatch(r'[a-f0-9]{64}', captured_hash)
                break
        elif camera_finished and 'No such file or directory' in status.stderr:
            # The normal app may already have encrypted and cleaned it; verification must prove that.
            break
        else:
            assert status.returncode == 0, 'Capture byte probe failed without proving absence'
        time.sleep(.2)
    else:
        raise RuntimeError('Camera did not finish writing; verification must not interrupt it')
    (EVIDENCE / 'camera-completion.json').write_text(json.dumps({'cameraFinishedBeforeVerification': camera_finished,
        'rawBytesObserved': size, 'rawSHA256': captured_hash, 'ownedRelativePath': capture_path}, indent=2), encoding='utf-8')
    phase('verifyNewProcessRecoversActualCameraWithoutSendingOrLosingOriginal', 'verify.log')
    after = witness()
    assert after['passed'] and after['verifyPid'] != after['preparePid']
    if captured_hash is not None:
        assert after['encryptedOriginalSHA256'] == captured_hash, 'Encrypted original must equal actual Camera output bytes'
    (EVIDENCE / 'summary.json').write_text(json.dumps(after, indent=2), encoding='utf-8')
    print(json.dumps(after, indent=2), flush=True)


if __name__ == '__main__':
    main()
