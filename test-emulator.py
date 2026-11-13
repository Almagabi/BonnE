#!/usr/bin/env python3
"""True Android UI test. UiAutomator only runs BEFORE accessibility is enabled:
invoking the CLI later destroys the BonnE accessibility service on this emulator."""
import subprocess, time, re, json, xml.etree.ElementTree as ET
from pathlib import Path

out=Path("emulator-results")
out.mkdir(exist_ok=True)
def adb(*args,timeout=50,check=True):
    p=subprocess.run(["adb",*args],capture_output=True,text=True,timeout=timeout)
    if check and p.returncode:
        raise RuntimeError("adb "+str(args)+" return "+str(p.returncode)+": "+p.stderr[:400])
    return p.stdout

def screenshot(tag):
    p=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=45)
    (out/(tag+".png")).write_bytes(p.stdout)
def capture():
    try:(out/"logcat.txt").write_text(adb("logcat","-d","-t","6000"))
    except Exception:pass
    try:(out/"accessibility.txt").write_text(adb("shell","dumpsys","accessibility"))
    except Exception:pass
    try:(out/"notifications.txt").write_text(adb("shell","dumpsys","notification","--noredact"))
    except Exception:pass

def initial_controls():
    # IMPORTANT: NEVER call uiautomator after the service is enabled.
    adb("shell","uiautomator","dump","/sdcard/window.xml",timeout=90)
    root=ET.fromstring(adb("exec-out","cat","/sdcard/window.xml",timeout=45))
    found={}
    keys=["START RECORDING","STOP & SAVE","CANCEL & DISCARD","REPLAY SAVED TASK","STOP PLAYBACK","Enable Accessibility"]
    for node in root.iter("node"):
        title=node.attrib.get("text","")
        rect=node.attrib.get("bounds","")
        m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",rect)
        for key in keys:
            if key in title and m:
                x1,y1,x2,y2=map(int,m.groups())
                found[key]=((x1+x2)//2,(y1+y2)//2)
    missing=set(keys)-set(found)
    if missing: raise AssertionError("Buttons missing from UI: "+str(missing))
    print("UI button coordinates:",found,flush=True)
    return found

def tap(controls,name):
    x,y=controls[name]
    print("TAP",name,x,y,flush=True)
    adb("shell","input","tap",str(x),str(y))
    time.sleep(1.1)

def enable_service():
    pkg="ai.bonne.screenagent"
    svc=pkg+"/"+pkg+".ReplayService"
    adb("shell","cmd","appops","set",pkg,"ACCESS_RESTRICTED_SETTINGS","allow")
    cur=adb("shell","settings","get","secure","enabled_accessibility_services").strip()
    if cur=="null":cur=""
    new=cur if svc in cur.split(":") else ((cur+":") if cur else "")+svc
    adb("shell","settings","put","secure","enabled_accessibility_services",new)
    adb("shell","settings","put","secure","accessibility_enabled","1")
    connected=False
    for i in range(12):
        state=adb("shell","dumpsys","accessibility")
        if "Bound services:{Service[label=BonnE" in state:
            connected=True;break
        time.sleep(1)
    assert connected,"Accessibility service did not bind"
    time.sleep(1)
    print("PASS Accessibility bound",flush=True)

def notification_active():
    data=adb("shell","dumpsys","notification","--noredact")
    (out/"notification-check.txt").write_text(data)
    return "BonnE is recording" in data

try:
    screenshot("01-app-open")
    controls=initial_controls()
    enable_service()
    screenshot("02-service-enabled")
    tap(controls,"START RECORDING")
    time.sleep(2)
    screenshot("03-recording")
    assert notification_active(),"Start did not post active recording notification"
    print("PASS Start recording + persistent notification",flush=True)
    tap(controls,"CANCEL & DISCARD")
    time.sleep(1)
    screenshot("04-discarded")
    assert not notification_active(),"Cancel did not remove recording notification"
    print("PASS Cancel & discard",flush=True)
    tap(controls,"START RECORDING")
    assert notification_active(),"Second recording did not start"
    adb("shell","am","start","-a","android.settings.SETTINGS")
    time.sleep(2.5)
    adb("shell","am","start","-n","ai.bonne.screenagent/.MainActivity")
    time.sleep(2)
    tap(controls,"STOP & SAVE")
    time.sleep(1)
    assert not notification_active(),"Stop/save did not remove recording notification"
    prefs=adb("shell","run-as","ai.bonne.screenagent","cat","shared_prefs/MainActivity.xml",check=False)
    (out/"saved-prefs.txt").write_text(prefs)
    assert "workflow" in prefs and "launch" in prefs, "Save did not persist recorded Android events: "+prefs[:250]
    print("PASS Stop & Save stores workflow",flush=True)
    screenshot("05-saved")
    tap(controls,"REPLAY SAVED TASK")
    time.sleep(4)
    screenshot("06-replay")
    state=adb("shell","dumpsys","activity","activities")
    (out/"replay-activities.txt").write_text(state)
    assert re.search(r"(?:ResumedActivity|topResumedActivity|mResumedActivity).*com\.android\.settings",state,re.I), "Replay did not open the recorded Settings app"
    print("PASS Replay opened recorded target app",flush=True)
    print("ALL ANDROID EMULATOR FUNCTIONAL TESTS PASSED",flush=True)
except Exception as error:
    screenshot("FAILED")
    capture()
    (out/"failure.txt").write_text(repr(error))
    print("EMULATOR TEST FAILED:",repr(error),flush=True)
    raise
