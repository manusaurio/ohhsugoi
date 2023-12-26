package ar.pelotude.ohhsugoi.util.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

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
                else throw UnsupportedDownloadException(
                    "Format not supported",
                    code = DownloadErrorType.UNSUPPORTED_FORMAT
                )
            }

            reader.input = imgStream

            val (width, height) = (reader.getWidth(0) to reader.getHeight(0))

            if (width * height > 3000 * 4000) {
                throw UnsupportedDownloadException("The image is too big", code = DownloadErrorType.DIMENSIONS_EXCEEDED)
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

fun stitch(urls: Iterable<URL>, scalingAlgorithm: Int = BufferedImage.SCALE_SMOOTH): BufferedImage {
    val imgs = urls.map(ImageIO::read)
    val targetHeight = imgs.minOf(BufferedImage::getHeight)

    val resizedImgs = imgs.map { img ->
        val resizeAmount: Double = targetHeight.toDouble() /  img.height.toDouble()
        val targetWidth = (img.width * resizeAmount).toInt()

        val scaled = img.getScaledInstance(
                targetWidth,
                targetHeight,
                scalingAlgorithm,
        )

        // I don't really need a `BufferedImage`, but let's try to avoid awt's `Image`...
        BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB).apply {
            graphics.drawImage(scaled, 0, 0, null)
        }
    }

    val totalWidth = resizedImgs.sumOf(BufferedImage::getWidth)

    val canvasBuffer = BufferedImage(totalWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val canvasG2d = canvasBuffer.createGraphics()

    var xOffset = 0

    for (img in resizedImgs) {
        canvasG2d.drawImage(img, xOffset, 0, null)
        xOffset += img.width
    }

    canvasG2d.dispose()

    return canvasBuffer
}

fun BufferedImage.asJpgByteArray(): ByteArray {
    val imgWriter = ImageIO.getImageWritersByFormatName("jpg").next()

    val writerParams: ImageWriteParam = imgWriter.defaultWriteParam!!.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = 0.9f
    }

    val stream = ByteArrayOutputStream()
    val output = ImageIO.createImageOutputStream(stream)

    imgWriter.output = output

    imgWriter.write(null, IIOImage(this, null, null), writerParams)
    imgWriter.dispose()

    return stream.use {
        it.toByteArray()
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

fun InputStream.toBufferedImage(): BufferedImage = ImageIO.read(this)