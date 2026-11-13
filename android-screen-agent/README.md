# BonnE • Teach by Doing (Offline Android)
Native Android app with Accessibility-based action recording and replay. **No keys, no model, no account.**

1. Install the debug APK from the GitHub Actions artifact.
2. Open Android settings using the Enable Accessibility button; switch on BonnE.
3. Press START RECORDING, switch to the target application and perform the task.
4. Return to BonnE and press STOP AND SAVE.
5. Press REPLAY. BonnE opens the recorded app (if it has a launchable activity), then repeats the captured steps.

Captures Accessibility click, long click, text change (non-password), and scroll events. Stores a JSON action sequence on device, with coordinates and view IDs where available. Prefers matching view IDs for replay. The user can edit saved JSON. Recording and replaying are local and can be stopped at any time.

**Limitations:** This is not visual learning from MP4; it learns actions live from accessibility event observations. Android does not provide arbitrary touch coordinates, canvas gestures, protected app UIs, or reliable semantics for inaccessible custom elements. Password fields are deliberately excluded. Android may require extra settings permission for sideloaded accessibility services. A physical phone test is needed to validate compatibility.
