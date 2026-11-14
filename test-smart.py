#!/usr/bin/env python3
"""End-to-end adaptive next-profile test with eight live search results.
Must visit DIFFERENT profiles rather than repeat the same recorded coordinate."""
import subprocess,time,re,json,xml.etree.ElementTree as ET
from pathlib import Path
out=Path("emulator-results");out.mkdir(exist_ok=True)
BONNE="ai.bonne.screenagent/.MainActivity"
FIXTURE="ai.bonne.fixture/.FixtureActivity"
def adb(*args,check=True,timeout=50):
    p=subprocess.run(["adb",*args],capture_output=True,text=True,timeout=timeout)
    if check and p.returncode:raise RuntimeError("ADB "+repr(args)+": "+p.stderr[:350])
    return p.stdout
def open_app(component):
    adb("shell","am","start","-n",component)
    time.sleep(1.5)
def screenshot(label):
    p=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=30)
    (out/(label+".png")).write_bytes(p.stdout)
def dump():
    adb("shell","uiautomator","dump","/sdcard/ui.xml",timeout=90)
    root=ET.fromstring(adb("exec-out","cat","/sdcard/ui.xml",timeout=50))
    return root
def locate(root,part):
    for node in root.iter("node"):
        title=node.attrib.get("text","")
        bound=node.attrib.get("bounds","")
        m=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",bound)
        if part in title and m:
            x1,y1,x2,y2=map(int,m.groups())
            return ((x1+x2)//2,(y1+y2)//2)
    return None
def tap(coord,description=""):
    assert coord,description+" not found"
    print("TAP",description,coord,flush=True)
    adb("shell","input","tap",str(coord[0]),str(coord[1]))
    time.sleep(.8)
def enable_service():
    package="ai.bonne.screenagent"
    svc=package+"/"+package+".ReplayService"
    adb("shell","cmd","appops","set",package,"ACCESS_RESTRICTED_SETTINGS","allow")
    old=adb("shell","settings","get","secure","enabled_accessibility_services").strip()
    if old=="null":old=""
    value=old if svc in old.split(":") else old+(":" if old else "")+svc
    adb("shell","settings","put","secure","enabled_accessibility_services",value)
    adb("shell","settings","put","secure","accessibility_enabled","1")
    for _ in range(15):
        state=adb("shell","dumpsys","accessibility")
        if "Bound services:{Service[label=BonnE" in state:
            print("PASS Accessibility bound",flush=True)
            return
        time.sleep(1)
    raise AssertionError("Accessibility service not bound")
def read_pref(pkg,path):
    return adb("shell","run-as",pkg,"cat",f"shared_prefs/{path}.xml",check=False)
def marks():
    data=read_pref("ai.bonne.fixture","fixture_marks")
    result={}
    if not data:return result
    for n in ET.fromstring(data).findall("int"):
        result[n.attrib.get("name")]=int(n.attrib.get("value",0))
    return result
def workflows():
    xml=read_pref("ai.bonne.screenagent","bonne_workflow_library_v1")
    root=ET.fromstring(xml)
    library=root.find("./string[@name='library']")
    return json.loads(library.text) if library is not None else []
def app():
    open_app(BONNE)
def logs():
    return adb("logcat","-d","-s","BonnE:I","*:S",timeout=60)
try:
    app()
    home=dump()
    rec=locate(home,"START RECORDING")
    save=locate(home,"STOP & SAVE RECORDING")
    stop=locate(home,"STOP PLAYBACK")
    assert rec and save and stop,"Main buttons unavailable"
    screenshot("smart-01-home")
    open_app(FIXTURE)
    listing=dump()
    first=locate(listing,"Profile 1")
    assert first,"Profile 1 not visible"
    tap(first,"Open first profile (before recording, inspect position)")
    mark=locate(dump(),"Mark Profile")
    assert mark,"Profile detail action unavailable"
    adb("shell","input","keyevent","KEYCODE_BACK")
    app()
    enable_service()
    tap(rec,"START")
    open_app(FIXTURE)
    tap(first,"First profile")
    opened_state=read_pref("ai.bonne.fixture","fixture_state")
    opened_node=ET.fromstring(opened_state).find("./int[@name='opened']")
    assert opened_node is not None and opened_node.get("value")=="1", "Forwarded touch did not open first profile: "+opened_state
    print("PASS forwarded tap really opened first profile",flush=True)
    tap(mark,"Mark first profile")
    adb("shell","input","keyevent","KEYCODE_BACK")
    time.sleep(1)
    app()
    tap(save,"STOP SAVE")
    saved=workflows()
    assert saved,"No saved workflow"
    steps=saved[-1]["steps"]
    print("DEMONSTRATION",[(x.get("type"),x.get("text")) for x in steps],flush=True)
    assert any(x.get("type")=="tap" and "Profile 1" in x.get("text","") for x in steps),"Open first profile not recorded"
    assert any(x.get("type")=="tap" and "Mark Profile" in x.get("text","") for x in steps),"Profile action not recorded"
    assert marks().get("profile_1")==1,"First profile was not marked manually"
    print("PASS taught one profile opening and action",flush=True)
    screenshot("smart-02-saved-task")

    # Read the saved-task button once, then leave UiAutomator alone:
    # invoking UiAutomator while accessibility is enabled can disrupt it.
    adb("shell","input","swipe","520","1580","520","410","500")
    time.sleep(.6)
    nextProfile=locate(dump(),"NEXT PROFILES")
    assert nextProfile,"Adaptive Next Profiles button absent"
    enable_service()
    tap(nextProfile,"NEXT PROFILES UNTIL STOP")
    # Switch to the search page during the 5-second handoff.
    open_app(FIXTURE)
    time.sleep(33)
    values=marks()
    (out/"smart-marks.json").write_text(json.dumps(values,indent=2))
    print("PROFILE ACTION COUNTS",values,flush=True)
    distinct=[k for k,v in values.items() if k!="profile_1" and v>=1]
    assert len(distinct)>=3, "Did not process at least 3 NEW profiles"
    assert values.get("profile_1")==1, "Repeated the taught profile instead of choosing the next"
    assert all(count<=1 for count in values.values()),"Repeated a profile, expected distinct records"
    print("PASS dynamically processed "+str(len(distinct))+" DIFFERENT profiles, no duplicate",flush=True)
    screenshot("smart-03-processed-list")
    app()
    tap(stop,"STOP")
    print("PASS stop action accepted",flush=True)
    (out/"smart-logcat.txt").write_text(logs())
    print("ALL ADAPTIVE NEXT PROFILE TESTS PASSED",flush=True)
except Exception as error:
    print("SMART PROFILE TEST FAILED:",repr(error),flush=True)
    try:screenshot("smart-FAILED")
    except Exception:pass
    try:(out/"smart-failure.txt").write_text(repr(error))
    except Exception:pass
    try:(out/"smart-logcat.txt").write_text(logs())
    except Exception:pass
    try:(out/"smart-marks.json").write_text(json.dumps(marks(),indent=2))
    except Exception:pass
    raise
