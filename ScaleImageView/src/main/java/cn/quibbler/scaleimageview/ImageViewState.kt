package cn.quibbler.scaleimageview

import android.graphics.PointF
import java.io.Serializable

/**
 * Wraps the scale, center and orientation of a displayed image for easy restoration on screen rotate.
 */
class ImageViewState : Serializable {

    val scale: Float

    private val centerX: Float

    private val centerY: Float

    val orientation: Int

    constructor(scale: Float, center: PointF, orientation: Int) {
        this.scale = scale
        this.centerX = center.x
        this.centerY = center.y
        this.orientation = orientation
    }

    fun getCenter(): PointF = PointF(centerX, centerY)

}