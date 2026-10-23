# Using Glimpse

## Screen selection

Select Glimpse in **Android Settings → Apps → Default apps → Digital assistant app**. Allow screenshot access there, then use the assistant gesture/button configured on your phone. Exact gestures are controlled by GrapheneOS and navigation settings.

Circle an area to select it, drag the corner handles to refine the crop, or tap a text line. **Select all** returns to the whole image. **Copy** copies the active Original/Translation result. The pencil corrects OCR text before searching or translating.

The **camera icon in the top bar** switches directly to camera scanning. Images shared from other apps and photos chosen through the picker use the same workspace.

Some apps disable screenshots; Android's capture restrictions still apply. Glimpse offers camera/photo import when a screen is unavailable.

## Translation

Choose source/target languages above the image. English and Japanese are pinned first in the language chooser. The swap control reverses an explicit pair, or switches English/Japanese targets when detecting the source automatically.

Download Japanese in **Settings → Offline languages** for English ↔ Japanese translation. Additional translation languages are listed by the active engine. Language-pack downloads default to Wi-Fi only; change this in Settings when needed.

Press **Translate** for a readable result and translated text boxes on the image. The eye button toggles the translated overlay. **Translate online** opens your selected browser translator with the selected text.

## Camera

Open from the assistant camera button, app home, launcher shortcut, or **Scan camera** Quick Settings tile. Grant camera permission, then take a photo. Torch and zoom are available when supported.

Switch to **Live translate** for on-device translation over the preview. Hold steady briefly so text can stabilize. Use the language button to change the target and the download icon to manage language packs. The copy button copies currently recognized camera text; the shutter freezes a high-resolution picture for detailed selection/search.

## Search

**Search text** opens Google, Bing, Kagi, or a custom engine in your browser. Kagi uses the browser's existing login.

**Search image** opens Google's or Bing's visual-search page. Tap its camera/image-upload control and choose **Upload image**. Glimpse supplies the selected crop automatically to the page's file chooser. Provider pages may display their own consent, sign-in, or verification screens.

Add an engine in **Settings → Add a search or AI engine** using an HTTPS URL with `{query}`. A Perplexity template is included as a starting point.

The image-options menu also saves a crop through Android's document picker or shares it to another app.
