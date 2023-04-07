package ar.pelotude.ohhsugoi

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import java.net.URL
import java.nio.file.Path
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageOutputStream
import javax.imageio.stream.ImageOutputStreamImpl

/** This isn't ideal but I'm not gonna add another dependency or write something fancy
 * for something meant to be used among trustworthy people */
fun String.isValidURL() = try {
    URL(this).toURI()
    true
} catch(e: java.net.MalformedURLException) {
    false
}

fun randomString(length: Int): String {
    val characters = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    return (0..length).map {
        characters.random()
    }.joinToString("")
}

fun uuidString() = UUID.randomUUID().toString()

fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/** But like in "This is a title", not "This Is A Title" */
fun String.makeTitle() = this.lowercase().capitalize()

/**
 * Synchronous function to download an image and cropping it
 * from the center adjusting it to a target desired size. This
 * means either 100% of the width or 100% of the height of the
 * original image will be preserved (although scaled), while
 * the other dimension will be cropped
 *
 * @param[imageSource] [URL] pointing to an image
 * @param[targetWidth] The desired new width
 * @param[targetHeight] The desired new height
 * @param[scalingAlgorithm] A constant that represents the desired
 * type of scaling, [Image.SCALE_FAST] by default.
 *
 * @return[BufferedImage] The resulting image
 */
fun downloadMangaCover(
        imageSource: URL,
        targetWidth: Int,
        targetHeight: Int,
        scalingAlgorithm: Int = Image.SCALE_FAST,
): BufferedImage {
    val img = ImageIO.read(imageSource)

    val (width, height) = (img.width to img.height)

    val currentRatio = width.toDouble() / height.toDouble()
    val targetRatio = targetWidth.toDouble() / targetHeight.toDouble()

    val (xData, yData) = if (currentRatio > targetRatio) {
        // -> target has a wider ratio
        // select all height, crop width

        val yData = 0 to height

        val xData = (targetRatio / currentRatio).let { widthPercent ->
            val offset = (width.toDouble() - (width.toDouble() * widthPercent)) / 2.0

            offset.toInt() to (width - offset*2).toInt()
        }

        xData to yData
    } else {
        // -> taller has a taller ratio
        // select all width, crop height

        val yData = (currentRatio / targetRatio).let { heightPercent ->
            val offset = (height.toDouble() - (height.toDouble() * heightPercent)) / 2.0

            offset.toInt() to (height - offset*2).toInt()
        }

        val xData = 0 to width

        xData to yData
    }

    val cropped = img.getSubimage(xData.first, yData.first, xData.second, yData.second)
    val scaled = cropped.getScaledInstance(targetWidth, targetHeight, scalingAlgorithm)

    val buffered = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)

    buffered.graphics.drawImage(scaled, 0, 0, null)

    return buffered
}

/**
 * Saves a [BufferedImage] to a specified file as JPG and closes the file.
 *
 * @param[destiny] A [File] the image should be written onto.
 * @param[quality] The compression level of the image, within the bounds 0.0 to 1.0
 *
 * @throws[IllegalArgumentException] If the provided value for [quality] is out of bounds
 * @throws[java.io.IOException] If something went wrong writing the file
 */
fun BufferedImage.saveAsJpg(destiny: File, quality: Float = 0.4f) {
    val jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next()

    val params: ImageWriteParam = jpgWriter.defaultWriteParam!!.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }

    try {
        jpgWriter.output = FileImageOutputStream(destiny)
        jpgWriter.write(null, IIOImage(this, null, null), params)
    } finally {
        // I've read of bugs where this thing doesn't work properly unless you close it/flush it
        // I don't think there's a practical way to use `.use { }` with the writer
        (jpgWriter.output as FileImageOutputStream).close()
    }
}