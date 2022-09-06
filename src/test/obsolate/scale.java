// Copyright 2021 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.avif.avifChannelIndex;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifPixelFormat.avifPixelFormatInfo;
import vavi.awt.image.avif.avif.avifPlanesFlag;


class scale {

// This should be configurable and/or smarter. kFilterBox has the highest quality but is the slowest.
    public static final int AVIF_LIBYUV_FILTER_MODE = kFilterBox;

    static boolean avifImageScale(avifImage image, int dstWidth, int dstHeight, int imageSizeLimit) {
        if ((image.width == dstWidth) && (image.height == dstHeight)) {
            // Nothing to do
            return true;
        }

        if ((dstWidth == 0) || (dstHeight == 0)) {
            System.err.printf("avifImageScale requested invalid dst dimensions [%ux%u]", dstWidth, dstHeight);
            return false;
        }
        if (dstWidth > (imageSizeLimit / dstHeight)) {
            System.err.printf("avifImageScale requested dst dimensions that are too large [%ux%u]", dstWidth, dstHeight);
            return false;
        }

        byte[][] srcYUVPlanes = new byte[avif.AVIF_PLANE_COUNT_YUV][];
        int[] srcYUVRowBytes = new int[avif.AVIF_PLANE_COUNT_YUV];
        for (int i = 0; i < avif.AVIF_PLANE_COUNT_YUV; ++i) {
            srcYUVPlanes[i] = image.yuvPlanes[i];
            image.yuvPlanes[i] = null;
            srcYUVRowBytes[i] = image.yuvRowBytes[i];
            image.yuvRowBytes[i] = 0;
        }
        final boolean srcImageOwnsYUVPlanes = image.imageOwnsYUVPlanes;
        image.imageOwnsYUVPlanes = false;

        byte[] srcAlphaPlane = image.alphaPlane;
        image.alphaPlane = null;
        int srcAlphaRowBytes = image.alphaRowBytes;
        image.alphaRowBytes = 0;
        final boolean srcImageOwnsAlphaPlane = image.imageOwnsAlphaPlane;
        image.imageOwnsAlphaPlane = false;

        final int srcWidth = image.width;
        image.width = dstWidth;
        final int srcHeight = image.height;
        image.height = dstHeight;

        if (srcYUVPlanes[0] != null || srcAlphaPlane != null) {
            // A simple conservative check to avoid integer overflows in
            // libyuv's ScalePlane() and
            // ScalePlane_12() functions.
            if (srcWidth > 16384) {
                System.err.printf("avifImageScale requested invalid width scale for libyuv [%u . %u]", srcWidth, dstWidth);
                return false;
            }
            if (srcHeight > 16384) {
                System.err.printf("avifImageScale requested invalid height scale for libyuv [%u . %u]", srcHeight, dstHeight);
                return false;
            }
        }

        if (srcYUVPlanes[0] != null) {
            image.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_YUV));

            avifPixelFormatInfo formatInfo = image.yuvFormat.avifGetPixelFormatInfo();
            final int srcUVWidth = (srcWidth + formatInfo.chromaShiftX) >> formatInfo.chromaShiftX;
            final int srcUVHeight = (srcHeight + formatInfo.chromaShiftY) >> formatInfo.chromaShiftY;
            final int dstUVWidth = (dstWidth + formatInfo.chromaShiftX) >> formatInfo.chromaShiftX;
            final int dstUVHeight = (dstHeight + formatInfo.chromaShiftY) >> formatInfo.chromaShiftY;

            for (int i = 0; i < avif.AVIF_PLANE_COUNT_YUV; ++i) {
                if (srcYUVPlanes[i] == null) {
                    continue;
                }

                final int srcW = (i == avifChannelIndex.AVIF_CHAN_Y.v) ? srcWidth : srcUVWidth;
                final int srcH = (i == avifChannelIndex.AVIF_CHAN_Y.v) ? srcHeight : srcUVHeight;
                final int dstW = (i == avifChannelIndex.AVIF_CHAN_Y.v) ? dstWidth : dstUVWidth;
                final int dstH = (i == avifChannelIndex.AVIF_CHAN_Y.v) ? dstHeight : dstUVHeight;
                if (image.depth > 8) {
                    final short[] srcPlane = (short[]) srcYUVPlanes[i];
                    final int srcStride = srcYUVRowBytes[i] / 2;
                    final short[] dstPlane = (short[]) image.yuvPlanes[i];
                    final int dstStride = image.yuvRowBytes[i] / 2;
// #if LIBYUV_VERSION >= 1774
                    ScalePlane_12(srcPlane, srcStride, srcW, srcH, dstPlane, dstStride, dstW, dstH, AVIF_LIBYUV_FILTER_MODE);
// #else
//                ScalePlane_16(srcPlane, srcStride, srcW, srcH, dstPlane, dstStride, dstW, dstH, AVIF_LIBYUV_FILTER_MODE);
// #endif
                } else {
                    final byte[] srcPlane = srcYUVPlanes[i];
                    final int srcStride = srcYUVRowBytes[i];
                    final byte[] dstPlane = image.yuvPlanes[i];
                    final int dstStride = image.yuvRowBytes[i];
                    ScalePlane(srcPlane, srcStride, srcW, srcH, dstPlane, dstStride, dstW, dstH, AVIF_LIBYUV_FILTER_MODE);
                }
            }
        }

        if (srcAlphaPlane != null) {
            image.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_A));

            if (image.depth > 8) {
                final short[] srcPlane = (short[]) srcAlphaPlane;
                final int srcStride = srcAlphaRowBytes / 2;
                final short[] dstPlane = (short[]) image.alphaPlane;
                final int dstStride = image.alphaRowBytes / 2;
// #if LIBYUV_VERSION >= 1774
                ScalePlane_12(srcPlane,
                              srcStride,
                              srcWidth,
                              srcHeight,
                              dstPlane,
                              dstStride,
                              dstWidth,
                              dstHeight,
                              AVIF_LIBYUV_FILTER_MODE);
// #else
//            ScalePlane_16(srcPlane, srcStride, srcWidth, srcHeight, dstPlane, dstStride, dstWidth, dstHeight, AVIF_LIBYUV_FILTER_MODE);
// #endif
            } else {
                final byte[] srcPlane = srcAlphaPlane;
                final int srcStride = srcAlphaRowBytes;
                final byte[] dstPlane = image.alphaPlane;
                final int dstStride = image.alphaRowBytes;
                ScalePlane(srcPlane,
                           srcStride,
                           srcWidth,
                           srcHeight,
                           dstPlane,
                           dstStride,
                           dstWidth,
                           dstHeight,
                           AVIF_LIBYUV_FILTER_MODE);
            }
        }

        return true;
    }

}
