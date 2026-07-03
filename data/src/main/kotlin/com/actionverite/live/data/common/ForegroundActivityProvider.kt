package com.actionverite.live.data.common

import android.app.Activity

/**
 * Supplies the current foreground [Activity] to data-layer flows that genuinely
 * require one (e.g. Firebase phone verification reCAPTCHA). Implemented in the
 * app module by tracking the resumed activity, and bound via Hilt.
 */
interface ForegroundActivityProvider {
    fun current(): Activity?
}
