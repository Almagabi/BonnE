# BonnE Screen Agent (Android)
Native Android application. Import an MP4 demonstration, extract up to six frames, send them to an OpenAI-compatible vision endpoint you choose, edit the returned JSON workflow, and replay it on device using Android AccessibilityService.

**Install:** GitHub Actions > BonnE Android APK > latest successful run > Artifacts > BonnE-Screen-Agent-APK. Download ZIP and extract APK.

**How:** Grant Accessibility in Android Settings. Choose a screen recording from your phone. Enter an OpenAI-compatible chat completions URL, model, and API key. Analyze; review and correct action coordinates; Save; Start. Stop is available from the app; to interrupt immediately disable accessibility service from Settings. Images are sent to the endpoint you enter only when you press Analyze.

**Limitations:** Video images alone do not reliably reveal every tap or text input, so review the proposed JSON. No background monitoring, no protected screen bypass, no automated financial transactions. Android screen recording is performed with your phone's built-in recorder; the app imports the MP4. No computer required for app operation. 
