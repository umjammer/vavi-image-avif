// Copyright 2020 Google LLC. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.avif.avifAddImageFlag;
import vavi.awt.image.avif.avif.avifChromaSamplePosition;
import vavi.awt.image.avif.avif.avifCodec;
import vavi.awt.image.avif.avif.avifCodecEncodeOutput;
import vavi.awt.image.avif.avif.avifColorPrimaries;
import vavi.awt.image.avif.avif.avifDecoder;
import vavi.awt.image.avif.avif.avifEncoder;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifMatrixCoefficients;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifPixelFormat.avifPixelFormatInfo;
import vavi.awt.image.avif.avif.avifPlanesFlag;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.avif.avifTransferCharacteristics;
import vavi.awt.image.avif.read.avifDecodeSample;


class codec_libgav1 extends avifCodec {

    Libgav1DecoderSettings gav1Settings;
    Libgav1Decoder gav1Decoder;
    Libgav1DecoderBuffer gav1Image;

    avifRange colorRange;

    @Override
    public boolean getNextImage(avifDecoder decoder, final avifDecodeSample sample, boolean alpha, avifImage image) {
        if (this.gav1Decoder == null) {
            this.gav1Settings.threads = decoder.maxThreads;
            this.gav1Settings.operating_point = this.operatingPoint;
            this.gav1Settings.output_all_layers = this.allLayers;

            if (Libgav1DecoderCreate(this.gav1Settings, this.gav1Decoder) != kLibgav1StatusOk) {
                return false;
            }
        }

        if (Libgav1DecoderEnqueueFrame(this.gav1Decoder,
                                       sample.data,
                                       sample.data.length,
                                       /* user_private_data= */0,
                                       /* buffer_private_data= */null) != kLibgav1StatusOk) {
            return false;
        }
        // Each Libgav1DecoderDequeueFrame() call invalidates the output frame
        // returned by the previous Libgav1DecoderDequeueFrame() call. Clear
        // our pointer to the previous output frame.
        this.gav1Image = null;

        final Libgav1DecoderBuffer nextFrame = null;
        for (;;) {
            if (Libgav1DecoderDequeueFrame(this.gav1Decoder, nextFrame) != kLibgav1StatusOk) {
                return false;
            }
            if (nextFrame && (sample.spatialID != AVIF_SPATIAL_ID_UNSET) && (nextFrame.spatial_id != sample.spatialID)) {
                nextFrame = null;
            } else {
                break;
            }
        }
        // Got an image!

        if (nextFrame) {
            this.gav1Image = nextFrame;
            this.colorRange = (nextFrame.color_range == kLibgav1ColorRangeStudio) ? avifRange.AVIF_RANGE_LIMITED
                                                                                  : avifRange.AVIF_RANGE_FULL;
        } else {
            if (alpha && this.gav1Image) {
                // Special case: reuse last alpha frame
            } else {
                return false;
            }
        }

        final Libgav1DecoderBuffer gav1Image = this.gav1Image;
        boolean isColor = !alpha;
        if (isColor) {
            // Color (YUV) planes - set image to correct size / format, fill
            // color

            avifPixelFormat yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_NONE;
            switch (gav1Image.image_format) {
            case kLibgav1ImageFormatMonochrome400:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
                break;
            case kLibgav1ImageFormatYuv420:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
                break;
            case kLibgav1ImageFormatYuv422:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;
                break;
            case kLibgav1ImageFormatYuv444:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
                break;
            }

            image.width = gav1Image.displayed_width[0];
            image.height = gav1Image.displayed_height[0];
            image.depth = gav1Image.bitdepth;

            image.yuvFormat = yuvFormat;
            image.yuvRange = this.colorRange;
            image.yuvChromaSamplePosition = (avifChromaSamplePosition) gav1Image.chroma_sample_position;

            image.colorPrimaries = (avifColorPrimaries) gav1Image.color_primary;
            image.transferCharacteristics = (avifTransferCharacteristics) gav1Image.transfer_characteristics;
            image.matrixCoefficients = (avifMatrixCoefficients) gav1Image.matrix_coefficients;

            avifPixelFormatInfo formatInfo = yuvFormat.avifGetPixelFormatInfo();

            // Steal the pointers from the decoder's image directly
            int yuvPlaneCount = (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) ? 1 : 3;
            for (int yuvPlane = 0; yuvPlane < yuvPlaneCount; ++yuvPlane) {
                image.yuvPlanes[yuvPlane] = gav1Image.plane[yuvPlane];
                image.yuvRowBytes[yuvPlane] = gav1Image.stride[yuvPlane];
            }
            image.imageOwnsYUVPlanes = false;
        } else {
            // Alpha plane - ensure image is correct size, fill color

            if (image.width != 0 && image.height != 0) {
                if ((image.width != (int) gav1Image.displayed_width[0]) ||
                    (image.height != (int) gav1Image.displayed_height[0]) || (image.depth != (int) gav1Image.bitdepth)) {
                    // Alpha plane doesn't match previous alpha plane decode,
                    // bail out
                    return false;
                }
            }
            image.width = gav1Image.displayed_width[0];
            image.height = gav1Image.displayed_height[0];
            image.depth = gav1Image.bitdepth;

            image.alphaPlane = gav1Image.plane[0];
            image.alphaRowBytes = gav1Image.stride[0];
            image.alphaRange = this.colorRange;
            image.imageOwnsAlphaPlane = false;
        }

        return true;
    }

    @Override
    public final String version() {
        return Libgav1GetVersionString();
    }

    public codec_libgav1() {
        Libgav1DecoderSettingsInitDefault(gav1Settings);
    }

    @Override
    public avifResult encodeImage(avifEncoder decoder,
                           avifImage image,
                           boolean b,
                           EnumSet<avifAddImageFlag> flags,
                           avifCodecEncodeOutput out) {
        return null;
    }

    @Override
    public boolean encodeFinish(avifCodecEncodeOutput out) {
        return false;
    }
}
