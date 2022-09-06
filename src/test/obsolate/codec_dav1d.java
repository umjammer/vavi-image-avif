// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

// #include "avif/internal.h"

// #if defined(_MSC_VER)
// #pragma warning(disable : 4201) // nonstandard extension used: nameless struct/union
// #endif
// #if defined(__clang__)
// #pragma clang diagnostic push
// #pragma clang diagnostic ignored "-Wc11-extensions" // C11 extension used: nameless struct/union
// #endif
// #include "dav1d/dav1d.h"
// #if defined(__clang__)
// #pragma clang diagnostic pop
// #endif

// #include <string.h>

// For those building with an older version of dav1d (not recommended).
// #ifndef DAV1D_ERR

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.avif.avifAddImageFlag;
import vavi.awt.image.avif.avif.avifChannelIndex;
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


class codec_dav1d extends avifCodec {

    public static final int DAV1D_ERR(int e) {
        return (-(e));
    }

    Dav1dSettings dav1dSettings;
    Dav1dContext dav1dContext;
    Dav1dPicture dav1dPicture;

    boolean hasPicture;

    avifRange colorRange;

    @Override
    public boolean getNextImage(avifDecoder decoder, final avifDecodeSample sample, boolean alpha, avifImage image) {
        if (this.dav1dContext == null) {
            // Give all available threads to decode a single frame as fast as
            // possible
// #if DAV1D_API_VERSION_MAJOR >= 6
            this.dav1dSettings.max_frame_delay = 1;
            this.dav1dSettings.n_threads = avif.AVIF_CLAMP(decoder.maxThreads, 1, DAV1D_MAX_THREADS);
// #else
//            this.dav1dSettings.n_frame_threads = 1;
//            this.dav1dSettings.n_tile_threads = avif.AVIF_CLAMP(decoder.maxThreads, 1, DAV1D_MAX_TILE_THREADS);
// #endif  // DAV1D_API_VERSION_MAJOR >= 6
            // Set a maximum frame size limit to avoid OOM'ing fuzzers. In
            // 32-bit builds, if
            // frame_size_limit > 8192 * 8192, dav1d reduces frame_size_limit to
            // 8192 * 8192 and logs
            // a message, so we set frame_size_limit to at most 8192 * 8192 to
            // avoid the dav1d_log
            // message.
            this.dav1dSettings.frame_size_limit = (Integer.BIT < 8) ? Math.min(decoder.imageSizeLimit, 8192 * 8192)
                                                                    : decoder.imageSizeLimit;
            this.dav1dSettings.operating_point = this.operatingPoint;
            this.dav1dSettings.all_layers = this.allLayers;

            if (dav1d_open(this.dav1dContext, this.dav1dSettings) != 0) {
                return false;
            }
        }

        boolean gotPicture = false;
        Dav1dPicture nextFrame;
        memset(nextFrame, 0, sizeof(Dav1dPicture));

        Dav1dData dav1dData;
        if (dav1d_data_wrap(dav1dData, sample.data, sample.data.length, avifDav1dFreeCallback, null) != 0) {
            return false;
        }

        for (;;) {
            if (dav1dData.data) {
                int res = dav1d_send_data(this.dav1dContext, dav1dData);
                if ((res < 0) && (res != DAV1D_ERR(EAGAIN))) {
                    dav1d_data_unref(dav1dData);
                    return false;
                }
            }

            int res = dav1d_get_picture(this.dav1dContext, nextFrame);
            if (res == DAV1D_ERR(EAGAIN)) {
                if (dav1dData.data) {
                    // send more data
                    continue;
                }
                return false;
            } else if (res < 0) {
                // No more frames
                if (dav1dData.data) {
                    dav1d_data_unref(dav1dData);
                }
                return false;
            } else {
                // Got a picture!
                if ((sample.spatialID != AVIF_SPATIAL_ID_UNSET) && (sample.spatialID != nextFrame.frame_hdr.spatial_id)) {
                    // Layer selection: skip this unwanted layer
                    dav1d_picture_unref(nextFrame);
                } else {
                    gotPicture = true;
                    break;
                }
            }
        }
        if (dav1dData.data) {
            dav1d_data_unref(dav1dData);
        }

        if (gotPicture) {
            dav1d_picture_unref(this.dav1dPicture);
            this.dav1dPicture = nextFrame;
            this.colorRange = this.dav1dPicture.seq_hdr.color_range ? avifRange.AVIF_RANGE_FULL : avifRange.AVIF_RANGE_LIMITED;
            this.hasPicture = true;
        } else {
            if (alpha && this.hasPicture) {
                // Special case: reuse last alpha frame
            } else {
                return false;
            }
        }

        Dav1dPicture dav1dImage = this.dav1dPicture;
        boolean isColor = !alpha;
        if (isColor) {
            // Color (YUV) planes - set image to correct size / format, fill
            // color

            avifPixelFormat yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_NONE;
            switch (dav1dImage.p.layout) {
            case DAV1D_PIXEL_LAYOUT_I400:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
                break;
            case DAV1D_PIXEL_LAYOUT_I420:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
                break;
            case DAV1D_PIXEL_LAYOUT_I422:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;
                break;
            case DAV1D_PIXEL_LAYOUT_I444:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
                break;
            }

            image.width = dav1dImage.p.w;
            image.height = dav1dImage.p.h;
            image.depth = dav1dImage.p.bpc;

            image.yuvFormat = yuvFormat;
            image.yuvRange = this.colorRange;
            image.yuvChromaSamplePosition = (avifChromaSamplePosition) dav1dImage.seq_hdr.chr;

            image.colorPrimaries = (avifColorPrimaries) dav1dImage.seq_hdr.pri;
            image.transferCharacteristics = (avifTransferCharacteristics) dav1dImage.seq_hdr.trc;
            image.matrixCoefficients = (avifMatrixCoefficients) dav1dImage.seq_hdr.mtrx;

            avifPixelFormatInfo formatInfo = yuvFormat.avifGetPixelFormatInfo();

            int yuvPlaneCount = (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) ? 1 : 3;
            for (int yuvPlane = 0; yuvPlane < yuvPlaneCount; ++yuvPlane) {
                image.yuvPlanes[yuvPlane] = dav1dImage.data[yuvPlane];
                image.yuvRowBytes[yuvPlane] = (int) dav1dImage.stride[(yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? 0 : 1];
            }
            image.imageOwnsYUVPlanes = false;
        } else {
            // Alpha plane - ensure image is correct size, fill color

            if (image.width != 0 && image.height != 0) {
                if ((image.width != (int) dav1dImage.p.w) || (image.height != (int) dav1dImage.p.h) ||
                    (image.depth != (int) dav1dImage.p.bpc)) {
                    // Alpha plane doesn't match previous alpha plane decode,
                    // bail out
                    return false;
                }
            }
            image.width = dav1dImage.p.w;
            image.height = dav1dImage.p.h;
            image.depth = dav1dImage.p.bpc;

            image.alphaPlane = dav1dImage.data[0];
            image.alphaRowBytes = (int) dav1dImage.stride[0];
            image.alphaRange = this.colorRange;
            image.imageOwnsAlphaPlane = false;
        }
        return true;
    }

    @Override
    public final String version() {
        return dav1d_version();
    }

    public codec_dav1d() {
        dav1d_default_settings(dav1dSettings);

        // Ensure that we only get the "highest spatial layer" as a single frame
        // for each input sample, instead of getting each spatial layer as its
        // own
        // frame one at a time ("all layers").
        this.dav1dSettings.all_layers = 0;
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
