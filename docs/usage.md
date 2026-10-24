# Using Glimpse

## Screen selection

Select Glimpse in **Android Settings → Apps → Default apps → Digital assistant app**. Allow screenshot access there, then use the assistant gesture/button configured on your phone. Exact gestures are controlled by GrapheneOS and navigation settings.

The assistant opens a screen-sized overlay with a two-tone, blurred inset ring that follows the display's rounded corners. Its compact action strip offers **Mark to search**, **Translate**, **Music**, and **Camera** immediately, while screen recognition runs in the background.

Tap **Mark to search** first to enable screenshot selection, then circle an area, refine it with corner handles, or tap text. **Search** uses your preferred provider; Copy and **Search selected image** appear in the result panel. **Assistant options → Select whole screen** opens results for the whole screenshot. **Translate** starts live screen translation setup directly, as described below.

Use Android's **Back** gesture/button to fade out the assistant and return to the underlying app. There is no X close button. The assistant screenshot is cleared on exit and is not saved to your gallery.

The full image workspace remains available for imports and camera stills. There, **Select all** returns to the whole image, **Copy** copies the active Original/Translation result, and the pencil corrects recognized text.

The **camera icon in the top bar** switches directly to camera scanning. Images shared from other apps and photos chosen through the picker use the same workspace.

The assistant's **Camera** action is in its bottom strip; **Assistant options** opens Settings and Offline languages.

Some apps disable screenshots; Android's capture restrictions still apply. Glimpse offers camera/photo import when a screen is unavailable.

## Translation

Choose source/target languages above the image. English and Japanese are pinned first in the language chooser. The swap control reverses an explicit pair, or switches English/Japanese targets when detecting the source automatically.

Download Japanese in **Settings → Offline languages** for English ↔ Japanese translation. Additional translation languages are listed by the active engine. Language-pack downloads default to Wi-Fi only; change this in Settings when needed.

Press **Translate** for a readable result and translated text boxes on the image. The eye button toggles the translated overlay. **Translate online** opens your selected browser translator with the selected text.

## Camera

Open from the assistant camera button, app home, launcher shortcut, or **Scan camera** Quick Settings tile. Grant camera permission, then take a photo. Torch and zoom are available when supported.

Switch to **Live translate** for on-device translation over the preview. Hold steady briefly so text can stabilize. Use the language button to change the target and the download icon to manage language packs. The copy button copies currently recognized camera text; the shutter freezes a high-resolution picture for detailed selection/search.

## Live screen translation

Tap **Translate** in the assistant to open **Translate your screen**, then choose **Start translating**. Allow display over other apps when prompted and approve Android's platform screen-capture consent. Live translation uses the entire display by default, without an app chooser, so you can move between apps during the session.

Translations appear over the original text and refresh after scrolling or other screen movement settles. A compact floating dock changes the translation language, toggles the original text, pauses/resumes, or stops the session; there is no large translation-text panel. Android's capture indicator can also stop the session.

**Capture permission:** Android normally asks for consent for each new screen-sharing session. Pause/Resume and opening the controls reuse the running session. Display-over-other-apps access is a separate permission that can remain enabled.

For a user-managed device with ADB enabled, AOSP and the inspected GrapheneOS implementation also support a persistent app-op override that skips Android's screen-sharing confirmation:

```sh
adb shell appops set --user current app.glimpse PROJECT_MEDIA allow
adb shell appops get --user current app.glimpse PROJECT_MEDIA
```

Glimpse still obtains a fresh projection for each session; it never saves or reuses old capture tokens. Its own language/start panel still appears. To restore Android's confirmation, stop any running capture and run:

```sh
adb shell appops set --user current app.glimpse PROJECT_MEDIA default
```

Use the relevant profile ID instead of `current` if Glimpse is installed in a different Android user/profile. This override is managed by Android, not an in-app grant, and can be removed by an app-op reset or uninstall.

Protected content remains subject to Android's restrictions. Apps that hide third-party overlays, including some Android Settings screens, also hide the translated labels and floating controls; Android's capture indicator remains available to stop the session.

## Music

Tap **Music** in the assistant, select **Both**, **Phone**, or **Mic**, and press **Listen**. **Close music** closes this panel and returns to the assistant. Music capture starts only after Listen and stops automatically after a bounded session; Stop listening and the capture notification stop it early. Phone audio needs Android's capture approval and the same audio permission as microphone recording. A player may disable internal capture.

Configure your AudD API key and recognition mode in **Settings → Music recognition**. Downloadable offline packs recognize only their indexed songs; there is currently no Glimpse-hosted popular-song catalogue. Optional notification access enables playing-app labels when matching media-session metadata is available. See [Offline music and song packs](song-packs.md).

## Search

**Search text** opens Google, Bing, Kagi, or a custom engine in your browser. Kagi uses the browser's existing login.

**Search image** opens Google's or Bing's visual-search page. Tap its camera/image-upload control and choose **Upload image**. Glimpse supplies the selected crop automatically to the page's file chooser. Provider pages may display their own consent, sign-in, or verification screens.

Add an engine in **Settings → Add a search or AI engine** using an HTTPS URL with `{query}`. A Perplexity template is included as a starting point.

The image-options menu also saves a crop through Android's document picker or shares it to another app.
