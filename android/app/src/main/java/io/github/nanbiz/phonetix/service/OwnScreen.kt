package io.github.nanbiz.phonetix.service

/**
 * Whether this app's own windows may be annotated.
 *
 * They may not. The app's screen is controls - switches, a bar, the names of settings - and
 * none of it is text anybody reads for its pronunciation; transcribing it covers the words a
 * reader is trying to work the app by, which is what the reader saw when "I read into" came
 * back as "I read ɪntʊ" and the choices under it were half IPA. The words on that screen are
 * the product speaking, not something it was asked to read.
 *
 * The exception is the surface the checks drive, which is this app's own activity and is meant
 * to be annotated: it says so while it is in front, and nothing else in the app does.
 */
object OwnScreen {

    /** Set by the test surface while it is the window in front. */
    @Volatile
    var readable: Boolean = false
}
