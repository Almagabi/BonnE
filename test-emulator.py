#!/usr/bin/env python3
"""Android emulator end-to-end tests: record an actual Settings click,
persist named task, launch/run it, loop and stop. No UiAutomator while A11y runs."""
import subprocess, time, re, json, xml.etree.ElementTree as ET
from pathlib import Path
out=Path("emulator-results")
out.mkdir(exist_ok=True)
def adb(*args,timeout=60,check=True):
    p=subprocess.run(["adb",*args],text=True,capture_output=True,timeout=timeout)
    if check and p.returncode:raise RuntimeError("ADB "+str(args)+": "+p.stderr[:500])
    return p.stdout
def ui():
    adb("shell","uiautomator","dump","/sdcard/ui.xml",timeout=90)
    return ET.fromstring(adb("exec-out","cat","/sdcard/ui.xml",timeout=60))
def coordinate(node):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
    if not m:return None
    x1,y1,x2,y2=map(int,m.groups())
    return ((x1+x2)//2,(y1+y2)//2)
def controls(root,keys):
    result={}
    for n in root.iter("node"):
        t=n.attrib.get("text","")
        xy=coordinate(n)
        if not xy:continue
        for key in keys:
            if key in t and key not in result:result[key]=xy
    return result
def tap(xy,name=""):
    print("TAP",name,xy,flush=True)
    adb("shell","input","tap",str(xy[0]),str(xy[1]))
    time.sleep(1.25)
def screenshot(name):
    p=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=40)
    (out/(name+".png")).write_bytes(p.stdout)
def enable():
    pkg="ai.bonne.screenagent"
    svc=pkg+"/"+pkg+".ReplayService"
    adb("shell","cmd","appops","set",pkg,"ACCESS_RESTRICTED_SETTINGS","allow")
    cur=adb("shell","settings","get","secure","enabled_accessibility_services").strip()
    if cur=="null":cur=""
    val=cur if svc in cur.split(":") else ((cur+":") if cur else "")+svc
    adb("shell","settings","put","secure","enabled_accessibility_services",val)
    adb("shell","settings","put","secure","accessibility_enabled","1")
    for _ in range(14):
        d=adb("shell","dumpsys","accessibility",timeout=50)
        if "Bound services:{Service[label=BonnE" in d:
            time.sleep(1)
            print("PASS Accessibility bound",flush=True)
            return
        time.sleep(1)
    raise AssertionError("Accessibility service not bound")
def notification_active():
    s=adb("shell","dumpsys","notification","--noredact")
    (out/"last-notification.txt").write_text(s)
    return "BonnE is recording" in s
def package_prefs():
    raw=adb("shell","run-as","ai.bonne.screenagent","cat","shared_prefs/bonne_workflow_library_v1.xml")
    (out/"prefs.xml").write_text(raw)
    root=ET.fromstring(raw)
    data=root.find("./string[@name='library']")
    if data is None:return []
    return json.loads(data.text)
def logcat():
    return adb("logcat","-d","-s","BonnE:I","*:S",timeout=60)
def return_app():
    adb("shell","am","start","-n","ai.bonne.screenagent/.MainActivity")
    time.sleep(1.5)
try:
    # Read UI coordinates and target Settings item BEFORE enabling Accessibility.
    first=ui()
    keys=["START RECORDING","STOP & SAVE RECORDING","CANCEL & DISCARD RECORDING",
          "OPEN PHONE HOME","STOP PLAYBACK","Enable Accessibility"]
    btn=controls(first,keys)
    assert set(btn)==set(keys), f"Missing homepage controls: {set(keys)-set(btn)}"
    screenshot("01-home")
    print("PASS Home controls",btn,flush=True)
    adb("shell","am","start","-a","android.settings.SETTINGS")
    time.sleep(2)
    settings=controls(ui(),["Apps","Notifications","Battery","Display","Network & internet"])
    preferred=next((x for x in ["Apps","Notifications","Battery","Display","Network & internet"] if x in settings),None)
    assert preferred, "No supported tappable target found on Settings"
    target=settings[preferred]
    print("Settings target:",preferred,target,flush=True)
    return_app()
    enable()

    tap(btn["START RECORDING"],"Start")
    assert notification_active(),"Record button did not activate recording"
    print("PASS recording notification",flush=True)
    tap(btn["CANCEL & DISCARD RECORDING"],"Discard")
    assert not notification_active(),"Cancel did not stop recording"
    print("PASS cancel+discard",flush=True)

    tap(btn["START RECORDING"],"Start again")
    assert notification_active(),"Second recording failed"
    adb("shell","am","start","-a","android.settings.SETTINGS")
    time.sleep(2)
    screenshot("02-settings-before-action")
    tap(target,"Settings "+preferred)
    screenshot("03-settings-after-action")
    time.sleep(1.2)
    return_app()
    tap(btn["STOP & SAVE RECORDING"],"Save")
    assert not notification_active(),"Saving did not stop recording indicator"
    library=package_prefs()
    assert len(library)>=1,"No saved automation"
    actions=library[-1]["steps"]
    types=[a.get("type") for a in actions]
    print("RECORDED ACTION TYPES",types,flush=True)
    assert "launch" in types, "App launch not recorded"
    assert any(t in types for t in ("tap","scroll","long")), "Only app launch recorded, but no actual click"
    print("PASS actual target click recorded and saved in named task",flush=True)

    # Closing & reopening the app must not delete saved automations.
    adb("shell","input","keyevent","KEYCODE_HOME")
    return_app()
    assert len(package_prefs())==len(library),"Saved tasks lost after leaving app"
    print("PASS saved task survives closing and reopening app",flush=True)
    screenshot("04-saved-home")

    # UIAutomator can disrupt Accessibility on this runner: inspect only now
    # (after recording stopped), then restore Accessibility before replay.
    adb("shell","input","swipe","520","1610","520","375","450")
    time.sleep(1)
    after=ui()
    taskControls=controls(after,["▶ RUN","↻ LOOP"])
    print("Saved task controls",taskControls,flush=True)
    assert "▶ RUN" in taskControls and "↻ LOOP" in taskControls,"Saved task has no Run/Loop controls"
    enable()
    tap(taskControls["▶ RUN"],"Run saved automation")
    time.sleep(13)
    log=logcat()
    (out/"replay-logcat.txt").write_text(log)
    foreground=adb("shell","dumpsys","activity","activities")
    assert "com.android.settings" in foreground,"Replay did not reach recorded Android Settings"
    assert "EXECUTE tap" in log or "EXECUTE scroll" in log or "EXECUTE long" in log, "Replay did not execute the recorded action"
    screenshot("05-replay-result")
    print("PASS saved automation replay includes actual recorded action",flush=True)

    # Reopen and scroll to the saved task. Test Repeat dialog from the UI.
    return_app()
    adb("shell","input","swipe","520","1610","520","375","450")
    time.sleep(1)
    tap(taskControls["↻ LOOP"],"Loop")
    time.sleep(1.0)
    # Inspect dialog only after a controlled service re-enable.
    dlg=ui()
    dialogButtons=controls(dlg,["RUN","CANCEL"])
    assert "RUN" in dialogButtons,"Loop count dialog did not open"
    enable()
    adb("logcat","-c")
    tap(dialogButtons["RUN"],"Start 3-repeat loop")
    time.sleep(37)
    log=logcat()
    (out/"loop-logcat.txt").write_text(log)
    n=len(re.findall(r"BonnE.*EXECUTE tap",log))
    print("EXECUTED TAP LOG EVENTS:",n,flush=True)
    assert n>=3, "Loop did not execute the captured tap at least 3 times"
    print("PASS repeat-loop executes multiple iterations",flush=True)
    return_app()
    screenshot("06-after-loop")
    print("ALL END-TO-END ANDROID TESTS PASSED",flush=True)
except Exception as exc:
    print("END-TO-END TEST FAILED:",repr(exc),flush=True)
    try:screenshot("FAILED")
    except Exception:pass
    try:(out/"failure.txt").write_text(repr(exc))
    except Exception:pass
    try:(out/"logcat.txt").write_text(logcat())
    except Exception:pass
    try:(out/"accessibility.txt").write_text(adb("shell","dumpsys","accessibility"))
    except Exception:pass
    raise
