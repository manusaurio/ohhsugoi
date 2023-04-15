package ar.pelotude.ohhsugoi

import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

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

enum class DownloadErrorType {
    DIMENSIONS_EXCEEDED,
    UNSUPPORTED_FORMAT,
}

class UnsupportedDownloadException(
    message: String?,
    cause: Throwable? = null,
    code: DownloadErrorType,
) : Exception(message, cause)

/**
 * Synchronous function to download an image with
 * a max size of [maxWidth] and [maxHeight]. If the provided
 * image exceeds one of the two dimensions, it'll be scaled
 * down to fit in them while maintaining the original
 * aspect ratio.
 *
 * This function only takes the first image that the provided
 * file contains.
 *
 * @param[imageSource] [URL] pointing to an image
 * @param[maxWidth] The maximum width the image can have
 * @param[maxHeight] The maximum height the image can have
 * @param[scalingAlgorithm] A constant that represents the desired
 * type of scaling, [BufferedImage.SCALE_FAST] by default.
 *
 * @return[BufferedImage] The resulting image
 */
fun downloadImage(
    imageSource: URL,
    maxWidth: Int,
    maxHeight: Int,
    scalingAlgorithm: Int = BufferedImage.SCALE_SMOOTH,
): BufferedImage {
    imageSource.openStream().use { stream ->
        ImageIO.createImageInputStream(stream).use { imgStream ->
            val reader = ImageIO.getImageReaders(imgStream).let {
                if (it.hasNext()) it.next()
                else throw UnsupportedDownloadException("Format not supported", code=DownloadErrorType.UNSUPPORTED_FORMAT)
            }

            reader.input = imgStream

            val (width, height) = (reader.getWidth(0) to reader.getHeight(0))

            if (width * height > 3000 * 4000) {
                throw UnsupportedDownloadException("The image is too big", code=DownloadErrorType.DIMENSIONS_EXCEEDED)
            }

            // we check if we must make it fit horizontally, vertically or neither, and by how much...
            val currentRatio = width.toDouble() / height.toDouble()
            val targetRatio = maxWidth.toDouble() / maxHeight.toDouble()

            val (resizeAmount: Double, ss: Int) = when {
                // if the image is smaller, leave it as it is
                width <= maxWidth && height <= maxHeight -> 1.0 to 1

                // is it wider than allowed? check by how much we must resize it
                // base the subsampling on the width
                currentRatio > targetRatio -> maxWidth.toDouble() / width.toDouble() to
                        (width / (maxWidth*2)).coerceAtLeast(1)

                else -> maxHeight.toDouble() / height.toDouble() to
                        (height / (maxHeight*2)).coerceAtLeast(1)
            }

            val (totalWidth, totalHeight) = if (resizeAmount == 1.0) width to height
            else (width * resizeAmount).toInt() to (height * resizeAmount).toInt()

            val params = reader.defaultReadParam.apply {
                setSourceSubsampling(ss, ss, 0, 0)
            }

            val resizedImg = reader.read(0, params).getScaledInstance(totalWidth, totalHeight, scalingAlgorithm)

            val resizedImgBuffer = BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB).apply {
                graphics.drawImage(resizedImg, 0, 0, null)
            }

            return resizedImgBuffer
        }
    }
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
fun BufferedImage.saveAsJpg(destiny: File, quality: Float = 0.7f) {
    val imgWriter = ImageIO.getImageWritersByFormatName("jpg").next()

    val writerParams: ImageWriteParam = imgWriter.defaultWriteParam!!.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }

    try {
        imgWriter.output = FileImageOutputStream(destiny)
        imgWriter.write(null, IIOImage(this, null, null), writerParams)
    }
    finally {
        (imgWriter.output as FileImageOutputStream).close()
    }
}