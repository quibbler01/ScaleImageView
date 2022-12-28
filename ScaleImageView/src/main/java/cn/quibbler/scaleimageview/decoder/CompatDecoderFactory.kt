package cn.quibbler.scaleimageview.decoder

import android.graphics.Bitmap

/**
 * Compatibility factory to instantiate decoders with empty public constructors.
 * @param <T> The base type of the decoder this factory will produce.
 */
class CompatDecoderFactory<T>(
    private val clazz: Class<out T>,
    private val bitmapConfig: Bitmap.Config? = null
) : DecoderFactory<T> {

    override fun make(): T {
        if (bitmapConfig == null) {
            return clazz.newInstance()
        } else {
            return clazz.getConstructor(Bitmap.Config::class.java).newInstance(bitmapConfig)
        }
    }

}