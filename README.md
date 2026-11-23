# Delivery Map (v1)

Shows a small map under the Rapido / Porter order popup:
blue = you, green = pickup, red = drop, black = road route.
Hollow dot = app is not sure about that location.

## Get the APK (no Android Studio needed)
1. Make a free GitHub account, create a new repository.
2. Upload ALL files of this folder (keep the folder structure, include the hidden `.github` folder).
3. Open the repo > Actions > "Build APK" > wait ~5 min.
4. Open the finished run > Artifacts > download `delivery-map-apk` > unzip > `app-debug.apk`.
5. Send the APK to your 2 friends.

## Or with Android Studio
Open this folder > wait for Gradle sync > Build > Build APK(s).

## Phone setup (open the app, do the buttons in order)
1. Location: "Allow all the time"
2. Accessibility: turn on "Delivery Map"
   - If greyed out: App info > top-right menu > "Allow restricted settings", then try again
3. Battery: No restrictions
4. Xiaomi Autostart: ON
5. Press "Show sample map now" to test

## If popups are not detected
Package names are a best guess (Rapido: com.rapido.rider, Porter: com.theporter.android.driverapp).
The app shows "Saw ₹ text in app: ..." on its main screen - copy that name into the package boxes.
