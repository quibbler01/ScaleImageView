package cn.quibbler.scaleimageview

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * <p>
 * Displays an image subsampled as necessary to avoid loading too much image data into memory. After zooming in,
 * a set of image tiles subsampled at higher resolution are loaded and displayed over the base layer. During pan and
 * zoom, tiles off screen or higher/lower resolution than required are discarded from memory.
 * </p><p>
 * Tiles are no larger than the max supported bitmap size, so with large images tiling may be used even when zoomed out.
 * </p><p>
 * v prefixes - coordinates, translations and distances measured in screen (view) pixels
 * <br>
 * s prefixes - coordinates, translations and distances measured in rotated and cropped source image pixels (scaled)
 * <br>
 * f prefixes - coordinates, translations and distances measured in original unrotated, uncropped source file pixels
 * </p><p>
 * <a href="https://github.com/davemorrissey/subsampling-scale-image-view">View project on GitHub</a>
 * </p>
 */
class SubsamplingScaleImageView : View {

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)


}