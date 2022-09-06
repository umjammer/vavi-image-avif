// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.avif.avifAddImageFlag;
import vavi.awt.image.avif.avif.avifCodec;
import vavi.awt.image.avif.avif.avifCodecEncodeOutput;
import vavi.awt.image.avif.avif.avifDecoder;
import vavi.awt.image.avif.avif.avifEncoder;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.read.avifDecodeSample;


class codec_rav1e extends avifCodec {

    RaContext rav1eContext;
    RaChromaSampling chromaSampling;

    int yShift;

// Official support wasn't added until v0.4.0
    private boolean rav1eSupports400() {
        final String rav1eVersionString = rav1e_version_short();

        // Check major version > 0
        int majorVersion = Integer.valueOf(rav1eVersionString);
        if (majorVersion > 0) {
            return true;
        }

        // Check minor version >= 4
        final String minorVersionString = strchr(rav1eVersionString, '.');
        if (!minorVersionString) {
            return false;
        }
        ++minorVersionString;
        if (!(minorVersionString)) {
            return false;
        }
        int minorVersion = Integer.valueOf(minorVersionString);
        return minorVersion >= 4;
    }

    @Override
    public avifResult encodeImage(avifEncoder  encoder,
                                  final avifImage image,
                                  boolean alpha,
                                  EnumSet<avifAddImageFlag> addImageFlags,
                                  avifCodecEncodeOutput output) {
        avifResult result = avifResult.AVIF_RESULT_UNKNOWN_ERROR;

        RaConfig rav1eConfig = null;
        RaFrame rav1eFrame = null;

        if (!this.rav1eContext) {
            if (this.csOptions.count > 0) {
                // None are currently supported!
                return avifResult.AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION;
            }

            final boolean supports400 = rav1eSupports400();
            RaPixelRange rav1eRange;
            if (alpha) {
                rav1eRange = (image.alphaRange == avifRange.AVIF_RANGE_FULL) ? RA_PIXEL_RANGE_FULL : RA_PIXEL_RANGE_LIMITED;
                this.chromaSampling = supports400 ? RA_CHROMA_SAMPLING_CS400 : RA_CHROMA_SAMPLING_CS420;
                this.yShift = 1;
            } else {
                rav1eRange = (image.yuvRange == avifRange.AVIF_RANGE_FULL) ? RA_PIXEL_RANGE_FULL : RA_PIXEL_RANGE_LIMITED;
                this.yShift = 0;
                switch (image.yuvFormat) {
                case AVIF_PIXEL_FORMAT_YUV444:
                    this.chromaSampling = RA_CHROMA_SAMPLING_CS444;
                    break;
                case AVIF_PIXEL_FORMAT_YUV422:
                    this.chromaSampling = RA_CHROMA_SAMPLING_CS422;
                    break;
                case AVIF_PIXEL_FORMAT_YUV420:
                    this.chromaSampling = RA_CHROMA_SAMPLING_CS420;
                    this.yShift = 1;
                    break;
                case AVIF_PIXEL_FORMAT_YUV400:
                    this.chromaSampling = supports400 ? RA_CHROMA_SAMPLING_CS400 : RA_CHROMA_SAMPLING_CS420;
                    this.yShift = 1;
                    break;
                case AVIF_PIXEL_FORMAT_NONE:
                default:
                    return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
                }
            }

            rav1eConfig = rav1e_config_default();
            if (rav1e_config_set_pixel_format(rav1eConfig,
                                              (byte[])image.depth,
                                              this.chromaSampling,
                                              (RaChromaSamplePosition)image.yuvChromaSamplePosition,
                                              rav1eRange) < 0) {
                goto cleanup;
            }

            if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
                if (rav1e_config_parse(rav1eConfig, "still_picture", "true") == -1) {
                    goto cleanup;
                }
            }
            if (rav1e_config_parse_int(rav1eConfig, "width", image.width) == -1) {
                goto cleanup;
            }
            if (rav1e_config_parse_int(rav1eConfig, "height", image.height) == -1) {
                goto cleanup;
            }
            if (rav1e_config_parse_int(rav1eConfig, "threads", encoder.maxThreads) == -1) {
                goto cleanup;
            }

            int minQuantizer = avif.AVIF_CLAMP(encoder.minQuantizer, 0, 63);
            int maxQuantizer = avif.AVIF_CLAMP(encoder.maxQuantizer, 0, 63);
            if (alpha) {
                minQuantizer = avif.AVIF_CLAMP(encoder.minQuantizerAlpha, 0, 63);
                maxQuantizer = avif.AVIF_CLAMP(encoder.maxQuantizerAlpha, 0, 63);
            }
            minQuantizer = (minQuantizer * 255) / 63; // Rescale quantizer values as rav1e's QP range is [0,255]
            maxQuantizer = (maxQuantizer * 255) / 63;
            if (rav1e_config_parse_int(rav1eConfig, "min_quantizer", minQuantizer) == -1) {
                goto cleanup;
            }
            if (rav1e_config_parse_int(rav1eConfig, "quantizer", maxQuantizer) == -1) {
                goto cleanup;
            }
            if (encoder.tileRowsLog2 != 0) {
                int tileRowsLog2 = avif.AVIF_CLAMP(encoder.tileRowsLog2, 0, 6);
                if (rav1e_config_parse_int(rav1eConfig, "tile_rows", 1 << tileRowsLog2) == -1) {
                    goto cleanup;
                }
            }
            if (encoder.tileColsLog2 != 0) {
                int tileColsLog2 = avif.AVIF_CLAMP(encoder.tileColsLog2, 0, 6);
                if (rav1e_config_parse_int(rav1eConfig, "tile_cols", 1 << tileColsLog2) == -1) {
                    goto cleanup;
                }
            }
            if (encoder.speed != AVIF_SPEED_DEFAULT) {
                int speed = avif.AVIF_CLAMP(encoder.speed, 0, 10);
                if (rav1e_config_parse_int(rav1eConfig, "speed", speed) == -1) {
                    goto cleanup;
                }
            }

            rav1e_config_set_color_description(rav1eConfig,
                                               (RaMatrixCoefficients)image.matrixCoefficients,
                                               (RaColorPrimaries)image.colorPrimaries,
                                               (RaTransferCharacteristics)image.transferCharacteristics);

            this.rav1eContext = rav1e_context_new(rav1eConfig);
            if (!this.rav1eContext) {
                goto cleanup;
            }
        }

        rav1eFrame = rav1e_frame_new(this.rav1eContext);

        int byteWidth = (image.depth > 8) ? 2 : 1;
        if (alpha) {
            rav1e_frame_fill_plane(rav1eFrame, 0, image.alphaPlane, image.alphaRowBytes * image.height, image.alphaRowBytes, byteWidth);
        } else {
            rav1e_frame_fill_plane(rav1eFrame, 0, image.yuvPlanes[0], image.yuvRowBytes[0] * image.height, image.yuvRowBytes[0], byteWidth);
            if (image.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) {
                int uvHeight = (image.height + this.yShift) >> this.yShift;
                rav1e_frame_fill_plane(rav1eFrame, 1, image.yuvPlanes[1], image.yuvRowBytes[1] * uvHeight, image.yuvRowBytes[1], byteWidth);
                rav1e_frame_fill_plane(rav1eFrame, 2, image.yuvPlanes[2], image.yuvRowBytes[2] * uvHeight, image.yuvRowBytes[2], byteWidth);
            }
        }

        RaFrameTypeOverride frameType = RA_FRAME_TYPE_OVERRIDE_NO;
        if (addImageFlags & AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME) {
            frameType = RA_FRAME_TYPE_OVERRIDE_KEY;
        }
        rav1e_frame_set_type(rav1eFrame, frameType);

        RaEncoderStatus encoderStatus = rav1e_send_frame(this.rav1eContext, rav1eFrame);
        if (encoderStatus != RA_ENCODER_STATUS_SUCCESS) {
            goto cleanup;
        }

        RaPacket pkt = null;
        for (;;) {
            encoderStatus = rav1e_receive_packet(this.rav1eContext, pkt);
            if (encoderStatus == RA_ENCODER_STATUS_ENCODED) {
                continue;
            }
            if ((encoderStatus != RA_ENCODER_STATUS_SUCCESS) && (encoderStatus != RA_ENCODER_STATUS_NEED_MORE_DATA)) {
                goto cleanup;
            } else if (pkt) {
                if (pkt.data && (pkt.len > 0)) {
                    avifCodecEncodeOutputAddSample(output, pkt.data, pkt.len, (pkt.frame_type == RA_FRAME_TYPE_KEY));
                }
                rav1e_packet_unref(pkt);
                pkt = null;
            } else {
                break;
            }
        }
        result = avifResult.AVIF_RESULT_OK;
cleanup:
        if (rav1eFrame) {
            rav1e_frame_unref(rav1eFrame);
            rav1eFrame = null;
        }
        if (rav1eConfig) {
            rav1e_config_unref(rav1eConfig);
            rav1eConfig = null;
        }
        return result;
    }

    @Override
    public boolean encodeFinish(avifCodecEncodeOutput output) {
        for (;;) {
            RaEncoderStatus encoderStatus = rav1e_send_frame(this.rav1eContext, null); // flush
            if (encoderStatus != RA_ENCODER_STATUS_SUCCESS) {
                return false;
            }

            boolean gotPacket = false;
            RaPacket pkt = null;
            for (;;) {
                encoderStatus = rav1e_receive_packet(this.rav1eContext, pkt);
                if (encoderStatus == RA_ENCODER_STATUS_ENCODED) {
                    continue;
                }
                if ((encoderStatus != RA_ENCODER_STATUS_SUCCESS) && (encoderStatus != RA_ENCODER_STATUS_LIMIT_REACHED)) {
                    return false;
                }
                if (pkt) {
                    gotPacket = true;
                    if (pkt.data && (pkt.len > 0)) {
                        avifCodecEncodeOutputAddSample(output, pkt.data, pkt.len, (pkt.frame_type == RA_FRAME_TYPE_KEY));
                    }
                    rav1e_packet_unref(pkt);
                    pkt = null;
                } else {
                    break;
                }
            }

            if (!gotPacket) {
                break;
            }
        }
        return true;
    }

    @Override
    public final String version() {
        return rav1e_version_full();
    }

    @Override
    boolean getNextImage(avifDecoder decoder, avifDecodeSample sample, boolean b, avifImage image) {
        return false;
    }
}
