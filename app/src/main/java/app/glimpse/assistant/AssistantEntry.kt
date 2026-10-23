package app.glimpse.assistant

import android.animation.ValueAnimator
import android.view.View

/** Small intent-only handoff: no retained session, bitmap extras, or delayed activity launches. */
internal object AssistantEntry {
    const val ACTION = "assistant_action"
    const val IMAGE_ID = "assistant_image_id"
    const val QUERY = "assistant_query"
    const val SUBMIT_SEARCH = "assistant_submit_search"
    const val CAPTURE_ERROR = "capture_error"
}

/** Alpha is the only exit transform; the system animator switch also applies to this exit. */
internal fun View.fadeAssistantOut(complete: () -> Unit) {
    animate().cancel()
    if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
        alpha = 0f
        complete()
    } else animate().alpha(0f).setDuration(140).withEndAction(complete).start()
}
