#!/usr/bin/env python3
"""Verify the ACTUAL UI in Android emulator, not merely the Gradle build."""
import subprocess, time, re, os, xml.etree.ElementTree as ET, json
from pathlib import Path
out=Path("emulator-results")
out.mkdir(exist_ok=True)
def adb(*args, timeout=20):
    p=subprocess.run(["adb",*args],text=True,capture_output=True,timeout=timeout)
    if p.returncode: raise RuntimeError(f"ADB {' '.join(args)}: {p.stderr}")
    return p.stdout
def dump():
    adb("shell","uiautomator","dump","/sdcard/window.xml",timeout=90)
    xml=adb("exec-out","cat","/sdcard/window.xml")
    return ET.fromstring(xml)
def nodes():
    return list(dump().iter("node"))
def texts():
    return [x.get("text","") for x in nodes() if x.get("text")]
def press(phrase):
    matches=[x for x in nodes() if phrase in x.get("text","")]
    if not matches: raise AssertionError(f"BUTTON MISSING {phrase}: {texts()}")
    # Prefer exact text
    x=next((n for n in matches if n.get("text")==phrase),matches[0])
    bounds=x.get("bounds","")
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",bounds)
    assert m, f"No bounds for {phrase}: {bounds}"
    a,b,c,d=map(int,m.groups())
    print("TAPPING",phrase,"AT",(a+c)//2,(b+d)//2,"BOUNDS",bounds,flush=True)
    cx=(a+c)//2
    cy=(b+d)//2
    print(f"UI TAP: {phrase} at {cx},{cy}; bounds={bounds}",flush=True)
    adb("shell","input","tap",str(cx),str(cy))
    time.sleep(2)
def verify(phrase):
    visible=texts()
    assert any(phrase in s for s in visible), f"Missing '{phrase}' in {visible}"
    print("PASS:",phrase,flush=True)
def screenshot(label):
    with open(out/f"{label}.png","wb") as f:
        p=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True)
        f.write(p.stdout)
def app():
    adb("shell","am","start","-n","ai.bonne.screenagent/.MainActivity")
    time.sleep(1.2)
try:
    verify("BonnE")
    screenshot("01-launch")
    press("START RECORDING")
    verify("🔴 RECORDING")
    screenshot("02-recording")
    notification=adb("shell","dumpsys","notification","--noredact")
    (out/"notification.txt").write_text(notification)
    print("NOTIFICATION FOUND?",("ai.bonne.screenagent" in notification),flush=True)
    (out/"notifications.txt").write_text(notification)
    assert "BonnE is recording" in notification, "Recording notification not posted"
    print("PASS: recording notification",flush=True)
    press("CANCEL & DISCARD")
    verify("START RECORDING")
    assert not any("CANCEL & DISCARD" in t for t in texts()), "Cancel failed to dismiss record controls"
    screenshot("03-canceled")
    print("PASS: cancel recording",flush=True)
    press("START RECORDING")
    adb("shell","am","start","-a","android.settings.SETTINGS")
    time.sleep(2)
    # Settings UI creates accessibility events. Tap another accessible element if available.
    s=nodes()
    tappable=[n for n in s if n.get("clickable")=="true" and n.get("text") and n.get("bounds")]
    if tappable:
        b=re.findall(r"\d+",tappable[0].get("bounds"))
        x1,y1,x2,y2=map(int,b)
        adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
        time.sleep(1)
    app()
    press("STOP & SAVE")
    verify("Saved task:")
    screenshot("04-saved")
    press("REPLAY SAVED TASK")
    time.sleep(3)
    screenshot("05-replay")
    print("PASS: replay button (did not crash)",flush=True)
    print("ALL EMULATOR UI TESTS PASSED",flush=True)
except Exception as exc:
    screenshot("FAILED")
    try: (out/"notifications-failed.txt").write_text(adb("shell","dumpsys","notification","--noredact",timeout=60))
    except Exception: pass
    (out/"failure.txt").write_text(repr(exc))
    try:
        (out/"ui.txt").write_text("\n".join(texts()))
        (out/"logcat.txt").write_text(adb("logcat","-d","-t","6000"))
    except Exception as logerror: print("Diagnostic collection:",repr(logerror),flush=True)
    print("EMULATOR TEST FAILED:",repr(exc),flush=True)
    raise
