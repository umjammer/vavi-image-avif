// Copyright 2020 Cloudinary. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.avif.avifAddImageFlag;
import vavi.awt.image.avif.avif.avifCodec;
import vavi.awt.image.avif.avif.avifCodecEncodeOutput;
import vavi.awt.image.avif.avif.avifColorPrimaries;
import vavi.awt.image.avif.avif.avifDecoder;
import vavi.awt.image.avif.avif.avifEncoder;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifMatrixCoefficients;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.avif.avifTransferCharacteristics;
import vavi.awt.image.avif.read.avifDecodeSample;


class codec_svt extends avifCodec {

// The SVT_AV1_VERSION_MAJOR, SVT_AV1_VERSION_MINOR, SVT_AV1_VERSION_PATCHLEVEL, and
// SVT_AV1_CHECK_VERSION macros were added in SVT-AV1 v0.9.0. Define these macros for older
// versions of SVT-AV1.
// #ifndef SVT_AV1_VERSION_MAJOR
    public static final int SVT_AV1_VERSION_MAJOR = SVT_VERSION_MAJOR;

    public static final int SVT_AV1_VERSION_MINOR = SVT_VERSION_MINOR;

    public static final int SVT_AV1_VERSION_PATCHLEVEL = SVT_VERSION_PATCHLEVEL;

// clang-format off
    public static final boolean SVT_AV1_CHECK_VERSION(int major, int minor, int patch) {
        return (SVT_AV1_VERSION_MAJOR > (major) || (SVT_AV1_VERSION_MAJOR == (major) && SVT_AV1_VERSION_MINOR > (minor)) ||
                (SVT_AV1_VERSION_MAJOR == (major) && SVT_AV1_VERSION_MINOR == (minor) &&
                 SVT_AV1_VERSION_PATCHLEVEL >= (patch)));
    }
// clang-format on
// #endif

// #if !SVT_AV1_CHECK_VERSION(0, 9, 0)
//public static final int STR_HELPER(x) #x
//public static final int STR(x) STR_HELPER(x)
    public static final String SVT_FULL_VERSION = "v" + SVT_AV1_VERSION_MAJOR + "." + SVT_AV1_VERSION_MINOR + "." +
                                                  SVT_AV1_VERSION_PATCHLEVEL;
// #endif

    /* SVT-AV1 Encoder Handle */
    EbComponentType svt_encoder;
    EbSvtAv1EncConfiguration svt_config;

    @Override
    public avifResult encodeImage(avifEncoder encoder,
                                  final avifImage image,
                                  boolean alpha,
                                  EnumSet<avifAddImageFlag> addImageFlags,
                                  avifCodecEncodeOutput output) {
        avifResult result = avifResult.AVIF_RESULT_UNKNOWN_ERROR;
        EbColorFormat color_format = EB_YUV420;
        EbBufferHeaderType input_buffer = null;
        EbErrorType res = EB_ErrorNone;

        int y_shift = 0;
        // EbColorRange svt_range;
        if (alpha) {
            // svt_range = (image.alphaRange == AVIF_RANGE_FULL) ?
            // EB_CR_FULL_RANGE : EB_CR_STUDIO_RANGE;
            y_shift = 1;
        } else {
            // svt_range = (image.yuvRange == AVIF_RANGE_FULL) ?
            // EB_CR_FULL_RANGE : EB_CR_STUDIO_RANGE;
            switch (image.yuvFormat) {
            case AVIF_PIXEL_FORMAT_YUV444:
                color_format = EB_YUV444;
                break;
            case AVIF_PIXEL_FORMAT_YUV422:
                color_format = EB_YUV422;
                break;
            case AVIF_PIXEL_FORMAT_YUV420:
                color_format = EB_YUV420;
                y_shift = 1;
                break;
            case AVIF_PIXEL_FORMAT_YUV400:
            case AVIF_PIXEL_FORMAT_NONE:
            default:
                return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
            }
        }

        if (this.svt_encoder == null) {
            EbSvtAv1EncConfiguration svt_config = this.svt_config;
            // Zero-initialize svt_config because svt_av1_enc_init_handle() does
            // not set many fields of svt_config.
            // See https://gitlab.com/AOMediaCodec/SVT-AV1/-/issues/1697.
            memset(svt_config, 0, sizeof(EbSvtAv1EncConfiguration));

            res = svt_av1_enc_init_handle(this.svt_encoder, null, svt_config);
            if (res != EB_ErrorNone) {
                return result;
            }
            svt_config.encoder_color_format = color_format;
            svt_config.encoder_bit_depth = (byte) image.depth;
// #if !SVT_AV1_CHECK_VERSION(0, 9, 0)
//        svt_config.is_16bit_pipeline = image.depth > 8;
// #endif

            // Follow comment in svt header: set if input is HDR10 BT2020 using
            // SMPTE ST2084.
            svt_config.high_dynamic_range_input = (image.depth == 10 &&
                                                   image.colorPrimaries == avifColorPrimaries.AVIF_COLOR_PRIMARIES_BT2020 &&
                                                   image.transferCharacteristics == avifTransferCharacteristics.AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084 &&
                                                   image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_BT2020_NCL);

            svt_config.source_width = image.width;
            svt_config.source_height = image.height;
            svt_config.logical_processors = encoder.maxThreads;
            svt_config.enable_adaptive_quantization = false;
            // disable 2-pass
// #if SVT_AV1_CHECK_VERSION(0, 9, 0)
//        svt_config.rc_stats_buffer = (SvtAv1FixedBuf) { null, 0 };
// #else
            svt_config.rc_firstpass_stats_out = false;
            svt_config.rc_twopass_stats_in = new SvtAv1FixedBuf(null, 0);
// #endif

            if (alpha) {
                svt_config.min_qp_allowed = avif.AVIF_CLAMP(encoder.minQuantizerAlpha, 0, 63);
                svt_config.max_qp_allowed = avif.AVIF_CLAMP(encoder.maxQuantizerAlpha, 0, 63);
            } else {
                svt_config.min_qp_allowed = avif.AVIF_CLAMP(encoder.minQuantizer, 0, 63);
                svt_config.qp = avif.AVIF_CLAMP(encoder.maxQuantizer, 0, 63);
            }

            if (encoder.tileRowsLog2 != 0) {
                int tileRowsLog2 = avif.AVIF_CLAMP(encoder.tileRowsLog2, 0, 6);
                svt_config.tile_rows = 1 << tileRowsLog2;
            }
            if (encoder.tileColsLog2 != 0) {
                int tileColsLog2 = avif.AVIF_CLAMP(encoder.tileColsLog2, 0, 6);
                svt_config.tile_columns = 1 << tileColsLog2;
            }
            if (encoder.speed != avif.AVIF_SPEED_DEFAULT) {
                int speed = avif.AVIF_CLAMP(encoder.speed, 0, 8);
                svt_config.enc_mode = (byte) speed;
            }

            if (color_format == EB_YUV422 || image.depth > 10) {
                svt_config.profile = PROFESSIONAL_PROFILE;
            } else if (color_format == EB_YUV444) {
                svt_config.profile = HIGH_PROFILE;
            }

            res = svt_av1_enc_set_parameter(this.svt_encoder, svt_config);
            if (res == EB_ErrorBadParameter) {
                return result;
            }

            res = svt_av1_enc_init(this.svt_encoder);
            if (res != EB_ErrorNone) {
                return result;
            }
        }

        if (!allocate_svt_buffers(input_buffer)) {
            return result;
        }
        EbSvtIOFormat input_picture_buffer = (EbSvtIOFormat) input_buffer.p_buffer;

        int bytesPerPixel = image.depth > 8 ? 2 : 1;
        if (alpha) {
            input_picture_buffer.y_stride = image.alphaRowBytes / bytesPerPixel;
            input_picture_buffer.luma = image.alphaPlane;
            input_buffer.n_filled_len = image.alphaRowBytes * image.height;
        } else {
            input_picture_buffer.y_stride = image.yuvRowBytes[0] / bytesPerPixel;
            input_picture_buffer.luma = image.yuvPlanes[0];
            input_buffer.n_filled_len = image.yuvRowBytes[0] * image.height;
            int uvHeight = (image.height + y_shift) >> y_shift;
            input_picture_buffer.cb = image.yuvPlanes[1];
            input_buffer.n_filled_len += image.yuvRowBytes[1] * uvHeight;
            input_picture_buffer.cr = image.yuvPlanes[2];
            input_buffer.n_filled_len += image.yuvRowBytes[2] * uvHeight;
            input_picture_buffer.cb_stride = image.yuvRowBytes[1] / bytesPerPixel;
            input_picture_buffer.cr_stride = image.yuvRowBytes[2] / bytesPerPixel;
        }

        input_buffer.flags = 0;
        input_buffer.pts = 0;

        EbAv1PictureType frame_type = EB_AV1_INVALID_PICTURE;
        if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME)) {
            frame_type = EB_AV1_KEY_PICTURE;
        }
        input_buffer.pic_type = frame_type;

        res = svt_av1_enc_send_picture(this.svt_encoder, input_buffer);
        if (res != EB_ErrorNone) {
            return result;
        }

        result = dequeue_frame(output, false);
        return result;
    }

    @Override
    public boolean encodeFinish(avifCodecEncodeOutput output) {
        EbErrorType ret = EB_ErrorNone;

        EbBufferHeaderType input_buffer;
        input_buffer.n_alloc_len = 0;
        input_buffer.n_filled_len = 0;
        input_buffer.n_tick_count = 0;
        input_buffer.p_app_private = null;
        input_buffer.flags = EB_BUFFERFLAG_EOS;
        input_buffer.p_buffer = null;

        // flush
        ret = svt_av1_enc_send_picture(this.svt_encoder, input_buffer);

        if (ret != EB_ErrorNone)
            return false;

        return dequeue_frame(output, true) == avifResult.AVIF_RESULT_OK;
    }

    @Override
    public final String version() {
// #if SVT_AV1_CHECK_VERSION(0, 9, 0)
        return svt_av1_get_version();
// #else
        return SVT_FULL_VERSION;
// #endif
    }

    private boolean allocate_svt_buffers(EbBufferHeaderType input_buf) {
        input_buf = avifAlloc(sizeof(EbBufferHeaderType));
        if (!(input_buf)) {
            return false;
        }
        (input_buf).p_buffer = avifAlloc(sizeof(EbSvtIOFormat));
        if (!(input_buf).p_buffer) {
            return false;
        }
        memset((input_buf).p_buffer, 0, sizeof(EbSvtIOFormat));
        (input_buf).size = sizeof(EbBufferHeaderType);
        (input_buf).p_app_private = null;
        (input_buf).pic_type = EB_AV1_INVALID_PICTURE;

        return true;
    }

    private avifResult dequeue_frame(avifCodecEncodeOutput output, boolean done_sending_pics) {
        EbErrorType res;
        int encode_at_eos = 0;

        do {
            EbBufferHeaderType output_buf = null;

            res = svt_av1_enc_get_packet(this.svt_encoder, output_buf, (byte[]) done_sending_pics);
            if (output_buf != null) {
                encode_at_eos = ((output_buf.flags & EB_BUFFERFLAG_EOS) == EB_BUFFERFLAG_EOS);
                if (output_buf.p_buffer && (output_buf.n_filled_len > 0)) {
                    avifCodecEncodeOutputAddSample(output,
                                                   output_buf.p_buffer,
                                                   output_buf.n_filled_len,
                                                   (output_buf.pic_type == EB_AV1_KEY_PICTURE));
                }
                svt_av1_enc_release_out_buffer(output_buf);
            }
            output_buf = null;
        } while (res == EB_ErrorNone && !encode_at_eos);
        if (!done_sending_pics && ((res == EB_ErrorNone) || (res == EB_NoErrorEmptyQueue)))
            return avifResult.AVIF_RESULT_OK;
        return (res == EB_ErrorNone ? avifResult.AVIF_RESULT_OK : avifResult.AVIF_RESULT_UNKNOWN_ERROR);
    }

    @Override
    public boolean getNextImage(avifDecoder decoder, avifDecodeSample sample, boolean b, avifImage image) {
        return false;
    }
}
