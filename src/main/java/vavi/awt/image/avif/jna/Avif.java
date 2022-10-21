// Copyright 2022 Google LLC. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif.jna;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import vavi.awt.image.jna.avif.AvifLibrary;
import vavi.awt.image.jna.avif.avifDecoder;
import vavi.awt.image.jna.avif.avifRGBImage;
import vavi.awt.image.jna.avif.avifROData;


/**
 * An AVIF Decoder.
 *
 * @see "AVIF Specification: https://aomediacodec.github.io/av1-avif/."
 */
public class Avif {

    private static final Avif avif = new Avif();

    // This is a utility class and cannot be instantiated.
    private Avif() {}

    public static Avif getInstance() {
        return avif;
    }

    /**
     * Returns true if the bytes in the buffer seem like an AVIF image.
     *
     * @param encoded The encoded image. buffer.position() must be 0.
     * @return true if the bytes seem like an AVIF image, false otherwise.
     */
    public static boolean isAvifImage(ByteBuffer encoded, int length) {
        avifROData data = new avifROData();
        data.data = Native.getDirectBufferPointer(encoded);
        data.size = length;
        return AvifLibrary.INSTANCE.avifPeekCompatibleFileType(data) == AvifLibrary.AVIF_TRUE;
    }

    /**
     * Parses the AVIF header and populates the Info.
     *
     * @param encoded The encoded AVIF image. encoded.position() must be 0.
     * @param length Length of the encoded buffer.
     * @return true on success and false on failure.
     */
    public BufferedImage getCompatibleImage(ByteBuffer encoded, int length) {
        Pointer buffer = Native.getDirectBufferPointer(encoded);
        avifDecoder decoder = createDecoderAndParse(buffer, length);
        BufferedImage image = new BufferedImage(decoder.image.width, decoder.image.height, BufferedImage.TYPE_4BYTE_ABGR);
//System.err.println("image depth: " + decoder.image.depth);
        return image;
    }

    private avifDecoder createDecoderAndParse(Pointer buffer, int length) {
        avifDecoder decoder = AvifLibrary.INSTANCE.avifDecoderCreate();
        if (decoder == null) {
            throw new IllegalStateException("Failed to create AVIF Decoder.");
        }
        decoder.ignoreXMP = AvifLibrary.AVIF_TRUE;
        decoder.ignoreExif = AvifLibrary.AVIF_TRUE;

        // Turn off 'clap' (clean aperture) property validation. The JNI wrapper
        // ignores the 'clap' property.
        decoder.strictFlags &= ~AvifLibrary.avifStrictFlag.AVIF_STRICT_CLAP_VALID;
        // Allow 'pixi' (pixel information) property to be missing. Older versions of
        // libheif did not add the 'pixi' item property to AV1 image items (See
        // crbug.com/1198455).
        decoder.strictFlags &= ~AvifLibrary.avifStrictFlag.AVIF_STRICT_PIXI_REQUIRED;

        int res = AvifLibrary.INSTANCE.avifDecoderSetIOMemory(decoder, buffer, length);
        if (res != AvifLibrary.avifResult.AVIF_RESULT_OK) {
            throw new IllegalStateException("Failed to set AVIF IO to a memory reader.");
        }
        res = AvifLibrary.INSTANCE.avifDecoderParse(decoder);
        if (res != AvifLibrary.avifResult.AVIF_RESULT_OK) {
            throw new IllegalStateException(String.format("Failed to parse AVIF image: %s.", AvifLibrary.INSTANCE.avifResultToString(res)));
        }
        return decoder;
    }

    /**
     * Decodes the AVIF image into the bitmap.
     *
     * @param encoded The encoded AVIF image. encoded.position() must be 0.
     * @param length  Length of the encoded buffer.
     * @param bitmap  The decoded pixels will be copied into the bitmap.
     * @return the decoded image.
     */
    public BufferedImage decode(ByteBuffer encoded, int length, BufferedImage bitmap) {
        Pointer buffer = Native.getDirectBufferPointer(encoded);
        avifDecoder decoder = createDecoderAndParse(buffer, length);
        int res = AvifLibrary.INSTANCE.avifDecoderNextImage(decoder);
        if (res != AvifLibrary.avifResult.AVIF_RESULT_OK) {
            throw new IllegalStateException(String.format("Failed to decode AVIF image. Status: %d", res));
        }
        // Ensure that the bitmap is large enough to store the decoded image.
        if (bitmap.getWidth() < decoder.image.width ||
                bitmap.getHeight() < decoder.image.height) {
            throw new IllegalStateException(String.format(
                    "Bitmap is not large enough to fit the image. Bitmap %dx%d Image %dx%d.",
                    bitmap.getWidth(), bitmap.getHeight(), decoder.image.width,
                    decoder.image.height));
        }
        // Ensure that the bitmap format is RGBA_8888, RGB_565 or RGBA_F16.
        if (bitmap.getType() != BufferedImage.TYPE_4BYTE_ABGR &&
                bitmap.getType() != BufferedImage.TYPE_USHORT_565_RGB) {
            throw new IllegalStateException(String.format("Bitmap format (%d) is not supported.", bitmap.getType()));
        }
        avifRGBImage rgb_image = new avifRGBImage();
        AvifLibrary.INSTANCE.avifRGBImageSetDefaults(rgb_image, decoder.image);
        if (bitmap.getType() == BufferedImage.TYPE_USHORT_565_RGB) {
            rgb_image.format = AvifLibrary.avifRGBFormat.AVIF_RGB_FORMAT_RGB;
            rgb_image.depth = 8;
        } else {
            rgb_image.depth = 8;
        }
        ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(bitmap.getWidth() * bitmap.getHeight() * 4);
        rgb_image.pixels = Native.getDirectBufferPointer(nativeBuffer);
        rgb_image.rowBytes = bitmap.getWidth() * 4;
        res = AvifLibrary.INSTANCE.avifImageYUVToRGB(decoder.image, rgb_image);
        if (res != AvifLibrary.avifResult.AVIF_RESULT_OK) {
            throw new IllegalStateException(String.format("Failed to convert YUV Pixels to RGB. Status: %d", res));
        }
        ByteBuffer localBuffer = ByteBuffer.allocate(nativeBuffer.capacity());
        localBuffer.put(nativeBuffer);
        bitmap.getRaster().setDataElements(0, 0, bitmap.getWidth(), bitmap.getHeight(), localBuffer.array());
        return bitmap;
    }
}