#!/usr/bin/env python3
"""Opt-in Android handoff coordinator. Raw echo tickets remain in local logs."""
import argparse
import os
import pathlib
import shutil
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

os.umask(0o077)
parser = argparse.ArgumentParser()
parser.add_argument('--sequence', default='wifi,cellular,wifi')
parser.add_argument('--adb', default=shutil.which('adb') or 'adb')
parser.add_argument('--serial')
parser.add_argument('--peer', default=str(pathlib.Path(__file__).resolve().parent / 'EchoPeer/bin/Debug/net10.0/EchoPeer.dll'))
parser.add_argument('--output', default='/tmp/pinhole-physical-handoff')
parser.add_argument('--relay-only', action='store_true')
parser.add_argument('--control-proton', action='store_true')
args = parser.parse_args()
adb = [args.adb] + (['-s', args.serial] if args.serial else [])
pathlib.Path(args.output).parent.mkdir(parents=True, exist_ok=True)
original_wifi = subprocess.check_output(adb + ['shell', 'settings', 'get', 'global', 'wifi_on'], text=True).strip() == '1'
original_mobile_data = subprocess.check_output(adb + ['shell', 'settings', 'get', 'global', 'mobile_data'], text=True).strip() == '1'
subprocess.run(adb + ['shell', 'am', 'force-stop', 'org.pinhole.devicetest'], check=True)
peer = subprocess.Popen(['dotnet', args.peer,
                         '--physical-network', '--tcp'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
ticket = []
peer_log = open(args.output + '-peer.log', 'w')
def peer_output():
    for line in peer.stdout:
        peer_log.write(line); peer_log.flush()
        if line.startswith('TICKET='): ticket.append(line.strip().split('=', 1)[1])
threading.Thread(target=peer_output, daemon=True).start()
instrument = None
logcat = None
vpn_connected = args.control_proton
def proton_action(labels):
    subprocess.run(adb + ['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=True)
    subprocess.run(adb + ['shell', 'uiautomator', 'dump', '/data/local/tmp/pinhole-vpn-action.xml'], check=True, stdout=subprocess.DEVNULL)
    local = args.output + '-vpn-ui.xml'
    subprocess.run(adb + ['pull', '/data/local/tmp/pinhole-vpn-action.xml', local], check=True, stdout=subprocess.DEVNULL)
    nodes = list(ET.parse(local).iter('node'))
    pathlib.Path(local).unlink()
    subprocess.run(adb + ['shell', 'rm', '/data/local/tmp/pinhole-vpn-action.xml'], check=True)
    for node in nodes:
        label = node.get('text') or node.get('content-desc') or ''
        if node.get('package') != 'ch.protonvpn.android' or label.lower() not in labels: continue
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if not bounds: continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        subprocess.run(adb + ['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
        return
    raise RuntimeError('The configured Proton VPN action was not visible; no UI control was clicked')
try:
    deadline = time.monotonic() + 40
    while not ticket and peer.poll() is None and time.monotonic() < deadline: time.sleep(.2)
    if not ticket: raise RuntimeError('The live echo peer did not publish a ticket')
    with open(args.output + '-device.log', 'w') as device_log, open(args.output + '-test.log', 'w') as test_log:
        logcat = subprocess.Popen(adb + ['logcat', '-T', '1', '-v', 'brief', 'PinholeDeviceTest:V', '*:S'], stdout=device_log, stderr=subprocess.STDOUT)
        instrument = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'org.pinhole.devicetest.PhysicalPeerTest#encryptedConnectionSurvivesNetworkHandoffs',
            '-e', 'pinholeTicket', ticket[0], '-e', 'networkSequence', args.sequence,
            '-e', 'relayOnly', str(args.relay_only).lower(),
            'org.pinhole.devicetest.test/androidx.test.runner.AndroidJUnitRunner'], stdout=test_log, stderr=subprocess.STDOUT)
        stages = args.sequence.split(',')
        handled = set()
        deadline = time.monotonic() + 300
        while instrument.poll() is None and time.monotonic() < deadline:
            output = pathlib.Path(args.output + '-device.log').read_text()
            for match in re.finditer(r'WAIT_HANDOFF stage=(\d+) network=(\w+)', output):
                stage, network = int(match[1]), match[2]
                if stage in handled: continue
                if stage >= len(stages) or stages[stage] != network: continue
                handled.add(stage)
                print(f'Network handoff stage {stage}: {network}', flush=True)
                if network in ('wifi', 'cellular'):
                    if args.control_proton and vpn_connected:
                        proton_action({'disconnect'})
                        vpn_connected = False
                    if network == 'cellular': subprocess.run(adb + ['shell', 'svc', 'data', 'enable'], check=True)
                    subprocess.run(adb + ['shell', 'svc', 'wifi', 'enable' if network == 'wifi' else 'disable'], check=True)
                else:
                    if args.control_proton and not vpn_connected:
                        proton_action({'connect', 'quick connect', 'reconnect', 'connect again'})
                        vpn_connected = True
                    print('Waiting for the configured VPN to connect.', flush=True)
            time.sleep(.5)
        if instrument.poll() is None:
            instrument.terminate()
            raise RuntimeError('Physical handoff test exceeded its total deadline')
        instrument.wait()
        output = pathlib.Path(args.output + '-test.log').read_text()
        if 'OK (1 test)' not in output: raise RuntimeError('Physical handoff assertion failed; see the captured test log')
        print('Physical handoff test passed.', flush=True)
finally:
    if instrument and instrument.poll() is None: instrument.terminate()
    subprocess.run(adb + ['shell', 'am', 'force-stop', 'org.pinhole.devicetest'], check=True)
    if logcat:
        logcat.terminate(); logcat.wait(timeout=5)
    peer.terminate()
    try: peer.wait(timeout=5)
    except subprocess.TimeoutExpired: peer.kill(); peer.wait()
    peer_log.close()
    subprocess.run(adb + ['shell', 'svc', 'wifi', 'enable' if original_wifi else 'disable'], check=True)
    subprocess.run(adb + ['shell', 'svc', 'data', 'enable' if original_mobile_data else 'disable'], check=True)
    if args.control_proton and not vpn_connected:
        proton_action({'connect', 'quick connect', 'reconnect', 'connect again'})
