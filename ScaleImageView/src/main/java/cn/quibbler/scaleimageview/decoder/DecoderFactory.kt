package cn.quibbler.scaleimageview.decoder

import java.lang.reflect.InvocationTargetException

/**
 * Interface for {@link ImageDecoder} and {@link ImageRegionDecoder} factories.
 * @param <T> the class of decoder that will be produced.
 */
interface DecoderFactory<T> {

    /**
     * Produce a new instance of a decoder with type {@link T}.
     * @return a new instance of your decoder.
     * @throws IllegalAccessException if the factory class cannot be instantiated.
     * @throws InstantiationException if the factory class cannot be instantiated.
     * @throws NoSuchMethodException if the factory class cannot be instantiated.
     * @throws InvocationTargetException if the factory class cannot be instantiated.
     */
    @Throws(IllegalAccessException::class, InstantiationException::class, NoSuchMethodException::class, InvocationTargetException::class)
    fun make(): T

}