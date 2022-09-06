// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;
import java.util.Map;

import com.sun.jna.Pointer;
import vavi.awt.image.jna.aom_encoder.aom_codec_enc_cfg;
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
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.avif.avifTransferCharacteristics;
import vavi.awt.image.avif.read.avifDecodeSample;


class codec_aom extends avifCodec {

    public static final int HAVE_AOM_CODEC_SET_OPTION = 1;
    public static final int ALL_INTRA_HAS_SPEEDS_7_TO_9 = 1;

    boolean decoderInitialized;
    Pointer /*aom_codec_ctx_t*/ decoder;
    aom_codec_iter_t iter;
    aom_image_t image;

    boolean encoderInitialized;
    aom_codec_ctx_t encoder;
    avifPixelFormatInfo formatInfo;
    aom_img_fmt_t aomFormat;
    boolean monochromeEnabled;

    // Whether cfg.rc_end_usage was set with an
    // avifEncoderSetCodecSpecificOption(encoder, "end-usage", value) call.
    boolean endUsageSet;

    // Whether cq-level was set with an
    // avifEncoderSetCodecSpecificOption(encoder, "cq-level", value) call.
    boolean cqLevelSet;

    @Override
    public boolean getNextImage(avifDecoder decoder,
                                final avifDecodeSample sample,
                                boolean alpha,
                                avifImage image) {
        if (!this.decoderInitialized) {
            aom_codec_dec_cfg_t cfg;
            memset(cfg, 0, sizeof(aom_codec_dec_cfg_t));
            cfg.threads = decoder.maxThreads;
            cfg.allow_lowbitdepth = 1;

            aom_codec_iface_t decoder_interface = aom_codec_av1_dx();
            if (aom_codec_dec_init(this.decoder, decoder_interface, cfg, 0)) {
                return false;
            }
            this.decoderInitialized = true;

            if (aom_codec_control(this.decoder, AV1D_SET_OUTPUT_ALL_LAYERS, this.allLayers)) {
                return false;
            }
            if (aom_codec_control(this.decoder, AV1D_SET_OPERATING_POINT, this.operatingPoint)) {
                return false;
            }

            this.iter = null;
        }

        aom_image_t nextFrame = null;
        byte[] spatialID = AVIF_SPATIAL_ID_UNSET;
        for (;;) {
            nextFrame = aom_codec_get_frame(this.decoder, this.iter);
            if (nextFrame) {
                if (spatialID != AVIF_SPATIAL_ID_UNSET) {
                    // This requires libaom v3.1.2 or later, which has the fix
                    // for
                    // https://crbug.com/aomedia/2993.
                    if (spatialID == nextFrame.spatial_id) {
                        // Found the correct spatial_id.
                        break;
                    }
                } else {
                    // Got an image!
                    break;
                }
            } else if (sample != null) {
                this.iter = null;
                if (aom_codec_decode(this.decoder, sample.data, sample.data.length, null)) {
                    return false;
                }
                spatialID = sample.spatialID;
                sample = null;
            } else {
                break;
            }
        }

        if (nextFrame) {
            this.image = nextFrame;
        } else {
            if (alpha && this.image) {
                // Special case: reuse last alpha frame
            } else {
                return false;
            }
        }

        boolean isColor = !alpha;
        if (isColor) {
            // Color (YUV) planes - set image to correct size / format, fill
            // color

            avifPixelFormat yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_NONE;
            switch (this.image.fmt) {
            case AOM_IMG_FMT_I420:
            case AOM_IMG_FMT_AOMI420:
            case AOM_IMG_FMT_I42016:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
                break;
            case AOM_IMG_FMT_I422:
            case AOM_IMG_FMT_I42216:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;
                break;
            case AOM_IMG_FMT_I444:
            case AOM_IMG_FMT_I44416:
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
                break;
            case AOM_IMG_FMT_NONE:
// #if defined(AOM_HAVE_IMG_FMT_NV12)
                // Although the libaom encoder supports the NV12 image format as
                // an input format, the
                // libaom decoder does not support NV12 as an output format.
            case AOM_IMG_FMT_NV12:
// #endif
            case AOM_IMG_FMT_YV12:
            case AOM_IMG_FMT_AOMYV12:
            case AOM_IMG_FMT_YV1216:
            default:
                return false;
            }
            if (this.image.monochrome) {
                yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
            }

            if (image.width != 0 && image.height != 0) {
                if ((image.width != this.image.d_w) || (image.height != this.image.d_h) ||
                    (image.depth != this.image.bit_depth) || (image.yuvFormat != yuvFormat)) {
                    // Throw it all out
                    avifImageFreePlanes(image, AVIF_PLANES_ALL);
                }
            }
            image.width = this.image.d_w;
            image.height = this.image.d_h;
            image.depth = this.image.bit_depth;

            image.yuvFormat = yuvFormat;
            image.yuvRange = (this.image.range == AOM_CR_STUDIO_RANGE) ? avifRange.AVIF_RANGE_LIMITED
                                                                       : avifRange.AVIF_RANGE_FULL;
            image.yuvChromaSamplePosition = (avifChromaSamplePosition) this.image.csp;

            image.colorPrimaries = (avifColorPrimaries) this.image.cp;
            image.transferCharacteristics = (avifTransferCharacteristics) this.image.tc;
            image.matrixCoefficients = (avifMatrixCoefficients) this.image.mc;

            avifPixelFormatInfo formatInfo = yuvFormat.avifGetPixelFormatInfo();

            // Steal the pointers from the decoder's image directly
            int yuvPlaneCount = (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) ? 1 : 3;
            for (int yuvPlane = 0; yuvPlane < yuvPlaneCount; ++yuvPlane) {
                image.yuvPlanes[yuvPlane] = this.image.planes[yuvPlane];
                image.yuvRowBytes[yuvPlane] = this.image.stride[yuvPlane];
            }
            image.imageOwnsYUVPlanes = false;
        } else {
            // Alpha plane - ensure image is correct size, fill color

            if (image.width != 0 && image.height != 0) {
                if ((image.width != this.image.d_w) || (image.height != this.image.d_h) ||
                    (image.depth != this.image.bit_depth)) {
                    // Alpha plane doesn't match previous alpha plane decode,
                    // bail out
                    return false;
                }
            }
            image.width = this.image.d_w;
            image.height = this.image.d_h;
            image.depth = this.image.bit_depth;

            image.alphaPlane = this.image.planes[0];
            image.alphaRowBytes = this.image.stride[0];
            image.alphaRange = (this.image.range == AOM_CR_STUDIO_RANGE) ? avifRange.AVIF_RANGE_LIMITED
                                                                         : avifRange.AVIF_RANGE_FULL;
            image.imageOwnsAlphaPlane = false;
        }

        return true;
    }

    private aom_img_fmt_t avifImageCalcAOMFmt(final avifImage image, boolean alpha) {
        aom_img_fmt_t fmt;
        if (alpha) {
            // We're going monochrome, who cares about chroma quality
            fmt = AOM_IMG_FMT_I420;
        } else {
            switch (image.yuvFormat) {
            case AVIF_PIXEL_FORMAT_YUV444:
                fmt = AOM_IMG_FMT_I444;
                break;
            case AVIF_PIXEL_FORMAT_YUV422:
                fmt = AOM_IMG_FMT_I422;
                break;
            case AVIF_PIXEL_FORMAT_YUV420:
            case AVIF_PIXEL_FORMAT_YUV400:
                fmt = AOM_IMG_FMT_I420;
                break;
            case AVIF_PIXEL_FORMAT_NONE:
            default:
                return AOM_IMG_FMT_NONE;
            }
        }

        if (image.depth > 8) {
            fmt |= AOM_IMG_FMT_HIGHBITDEPTH;
        }

        return fmt;
    }

// #if !defined(HAVE_AOM_CODEC_SET_OPTION)
    private boolean aomOptionParseInt(final String str, int val) {
        String endptr;
        final long rawval = Long.parseLong(str);

        if (str.charAt(0) != '\0' && endptr[0] == '\0' && rawval >= Integer.MAX_VALUE && rawval <= Integer.MAX_VALUE) {
            val = (int) rawval;
            return true;
        }

        return false;
    }

    private boolean aomOptionParseUInt(final String str, int val) {
        String endptr;
        final long rawval = strtoul(str, endptr, 10);

        if (str[0] != '\0' && endptr[0] == '\0' && rawval <= Integer.MAX_VALUE) {
            val = (int) rawval;
            return true;
        }

        return false;
    }
// #endif // !defined(HAVE_AOM_CODEC_SET_OPTION)

    class aomOptionEnumList {
        public aomOptionEnumList(String name, int val) {
            this.name = name;
            this.val = val;
        }

        final String name;

        int val;
    }

    private boolean aomOptionParseEnum(final String str, final aomOptionEnumList[] enums, int val) {
        final aomOptionEnumList[] listptr;
        long rawval;
        String endptr;

        // First see if the value can be parsed as a raw value.
        rawval = strtol(str, endptr, 10);
        if (str[0] != '\0' && endptr[0] == '\0') {
            // Got a raw value, make sure it's valid.
            for (listptr = enums; listptr.name; listptr++)
                if (listptr.val == rawval) {
                    val = (int) rawval;
                    return true;
                }
        }

        // Next see if it can be parsed as a string.
        for (listptr = enums; listptr.name; listptr++) {
            if (!strcmp(str, listptr.name)) {
                val = listptr.val;
                return true;
            }
        }

        return false;
    }

    private final aomOptionEnumList[] endUsageEnum = {
        new aomOptionEnumList("vbr", AOM_VBR), // Variable Bit Rate (VBR) mode
        new aomOptionEnumList("cbr", AOM_CBR), // Constant Bit Rate (CBR) mode
        new aomOptionEnumList("cq", AOM_CQ), // Constrained Quality (CQ) mode
        new aomOptionEnumList("q", AOM_Q), // Constrained Quality (CQ) mode
        new aomOptionEnumList(null, 0)
    };

    // Returns true if <key> equals <name> or <prefix><name>, where <prefix> is "color:" or "alpha:"
    // or the abbreviated form "c:" or "a:".
    private boolean avifKeyEqualsName(final String key, final String name, boolean alpha) {
        final String prefix = alpha ? "alpha:" : "color:";
        int prefixLen = 6;
        final String shortPrefix = alpha ? "a:" : "c:";
        int shortPrefixLen = 2;
        return key.equals(name) || (key.substring(0, prefixLen).equals(prefix) && !strcmp(key + prefixLen, name)) ||
               (!strncmp(key, shortPrefix, shortPrefixLen) && !strcmp(key + shortPrefixLen, name));
    }

    private boolean avifProcessAOMOptionsPreInit(avifCodec codec, boolean alpha, aom_codec_enc_cfg[] cfg) {
        for (Map.Entry<String, String> entry : codec.csOptions.entrySet()) {
            int val;
            if (avifKeyEqualsName(entry.getKey(), "end-usage", alpha)) { // Rate
                                                                         // control
                                                                         // mode
                if (!aomOptionParseEnum(entry.getValue(), endUsageEnum, val)) {
                    System.err.printf("Invalid value for end-usage: %s", entry.getValue());
                    return false;
                }
                cfg.rc_end_usage = val;
                codec.endUsageSet = true;
            }
        }
        return true;
    }

    enum aomOptionType {
        AVIF_AOM_OPTION_NUL,
        AVIF_AOM_OPTION_STR,
        AVIF_AOM_OPTION_INT,
        AVIF_AOM_OPTION_UINT,
        AVIF_AOM_OPTION_ENUM,
    }

    class aomOptionDef {
        public aomOptionDef(String name, int controlId, aomOptionType type, aomOptionEnumList[] enums) {
            this.name = name;
            this.controlId = controlId;
            this.type = type;
            this.enums = enums;
        }

        final String name;

        int controlId;

        aomOptionType type;

        // If type is AVIF_AOM_OPTION_ENUM, this must be set. Otherwise should
        // be null.
        final aomOptionEnumList[] enums;
    }

    private final aomOptionEnumList[] tuningEnum = { //
        new aomOptionEnumList("psnr", AOM_TUNE_PSNR), //
        new aomOptionEnumList("ssim", AOM_TUNE_SSIM), //
        new aomOptionEnumList(null, 0)
    };

    private final aomOptionDef aomOptionDefs[] = {
        // Adaptive quantization mode
        new aomOptionDef("aq-mode", AV1E_SET_AQ_MODE, aomOptionType.AVIF_AOM_OPTION_UINT, null),
        // Constant/Constrained Quality level
        new aomOptionDef("cq-level", AOME_SET_CQ_LEVEL, aomOptionType.AVIF_AOM_OPTION_UINT, null),
        // Enable delta quantization in chroma planes
        new aomOptionDef("enable-chroma-deltaq", AV1E_SET_ENABLE_CHROMA_DELTAQ, aomOptionType.AVIF_AOM_OPTION_INT, null),
        // Bias towards block sharpness in rate-distortion optimization of
        // transform coefficients
        new aomOptionDef("sharpness", AOME_SET_SHARPNESS, aomOptionType.AVIF_AOM_OPTION_UINT, null),
        // Tune distortion metric
        new aomOptionDef("tune", AOME_SET_TUNING, aomOptionType.AVIF_AOM_OPTION_ENUM, tuningEnum),
        // Film grain test vector
        new aomOptionDef("film-grain-test", AV1E_SET_FILM_GRAIN_TEST_VECTOR, aomOptionType.AVIF_AOM_OPTION_INT, null),
        // Film grain table file
        new aomOptionDef("film-grain-table", AV1E_SET_FILM_GRAIN_TABLE, aomOptionType.AVIF_AOM_OPTION_STR, null),

        // Sentinel
        new aomOptionDef(null, 0, AVIF_AOM_OPTION_NUL, null)
    };

    private boolean avifProcessAOMOptionsPostInit(avifCodec codec, boolean alpha) {
        for (Map.Entry<String, String> entry : codec.csOptions.entrySet()) {
            // Skip options for the other kind of plane.
            final String otherPrefix = alpha ? "color:" : "alpha:";
            int otherPrefixLen = 6;
            final String otherShortPrefix = alpha ? "c:" : "a:";
            int otherShortPrefixLen = 2;
            if (!strncmp(entry.getKey(), otherPrefix, otherPrefixLen) ||
                !strncmp(entry.getKey(), otherShortPrefix, otherShortPrefixLen)) {
                continue;
            }

            // Skip options processed by avifProcessAOMOptionsPreInit.
            if (avifKeyEqualsName(entry.getKey(), "end-usage", alpha)) {
                continue;
            }

// #if defined(HAVE_AOM_CODEC_SET_OPTION)
            final String prefix = alpha ? "alpha:" : "color:";
            int prefixLen = 6;
            final String shortPrefix = alpha ? "a:" : "c:";
            int shortPrefixLen = 2;
            final String key = entry.getKey();
            if (!strncmp(key, prefix, prefixLen)) {
                key += prefixLen;
            } else if (!strncmp(key, shortPrefix, shortPrefixLen)) {
                key += shortPrefixLen;
            }
            if (aom_codec_set_option(codec.encoder, key, entry.value) != AOM_CODEC_OK) {
                System.err.printf("aom_codec_set_option(\"%s\", \"%s\") failed: %s: %s",
                                  key,
                                  entry.value,
                                  aom_codec_error(codec.encoder),
                                  aom_codec_error_detail(codec.encoder));
                return false;
            }
// #else  // !defined(HAVE_AOM_CODEC_SET_OPTION)
            boolean match = false;
            for (int j = 0; aomOptionDefs[j].name; ++j) {
                if (avifKeyEqualsName(entry.getKey(), aomOptionDefs[j].name, alpha)) {
                    match = true;
                    boolean success = false;
                    int valInt;
                    int valUInt;
                    switch (aomOptionDefs[j].type) {
                    case AVIF_AOM_OPTION_NUL:
                        success = false;
                        break;
                    case AVIF_AOM_OPTION_STR:
                        success = aom_codec_control(codec.encoder, aomOptionDefs[j].controlId, entry.value) == AOM_CODEC_OK;
                        break;
                    case AVIF_AOM_OPTION_INT:
                        success = aomOptionParseInt(entry.value, valInt) &&
                                  aom_codec_control(codec.encoder, aomOptionDefs[j].controlId, valInt) == AOM_CODEC_OK;
                        break;
                    case AVIF_AOM_OPTION_UINT:
                        success = aomOptionParseUInt(entry.value, valUInt) &&
                                  aom_codec_control(codec.encoder, aomOptionDefs[j].controlId, valUInt) == AOM_CODEC_OK;
                        break;
                    case AVIF_AOM_OPTION_ENUM:
                        success = aomOptionParseEnum(entry.value, aomOptionDefs[j].enums, valInt) &&
                                  aom_codec_control(codec.encoder, aomOptionDefs[j].controlId, valInt) == AOM_CODEC_OK;
                        break;
                    }
                    if (!success) {
                        return false;
                    }
                    if (aomOptionDefs[j].controlId == AOME_SET_CQ_LEVEL) {
                        codec.cqLevelSet = true;
                    }
                    break;
                }
            }
            if (!match) {
                return false;
            }
// #endif // defined(HAVE_AOM_CODEC_SET_OPTION)
        }
        return true;
    }

    @Override
    public avifResult encodeImage(avifEncoder encoder,
                                  final avifImage image,
                                  boolean alpha,
                                  EnumSet<avifAddImageFlag> addImageFlags,
                                  avifCodecEncodeOutput output) {
        if (!this.encoderInitialized) {
            // Map encoder speed to AOM usage + CpuUsed:
            // Speed 0: GoodQuality CpuUsed 0
            // Speed 1: GoodQuality CpuUsed 1
            // Speed 2: GoodQuality CpuUsed 2
            // Speed 3: GoodQuality CpuUsed 3
            // Speed 4: GoodQuality CpuUsed 4
            // Speed 5: GoodQuality CpuUsed 5
            // Speed 6: GoodQuality CpuUsed 6
            // Speed 7: RealTime CpuUsed 7
            // Speed 8: RealTime CpuUsed 8
            // Speed 9: RealTime CpuUsed 9
            // Speed 10: RealTime CpuUsed 9
            int aomUsage = AOM_USAGE_GOOD_QUALITY;
            // Use the new AOM_USAGE_ALL_INTRA (added in
            // https://crbug.com/aomedia/2959) for still
            // image encoding if it is available.
// #if defined(AOM_USAGE_ALL_INTRA)
            if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
                aomUsage = AOM_USAGE_ALL_INTRA;
            }
// #endif
            int aomCpuUsed = -1;
            if (encoder.speed != AVIF_SPEED_DEFAULT) {
                aomCpuUsed = avif.AVIF_CLAMP(encoder.speed, 0, 9);
                if (aomCpuUsed >= 7) {
// #if defined(AOM_USAGE_ALL_INTRA) && defined(ALL_INTRA_HAS_SPEEDS_7_TO_9)
                    if (!addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
                        aomUsage = AOM_USAGE_REALTIME;
                    }
// #else
//                aomUsage = AOM_USAGE_REALTIME;
// #endif
                }
            }

            // aom_codec.h says: aom_codec_version() == (major<<16 | minor<<8 |
            // patch)
            final int aomVersion_2_0_0 = (2 << 16);
            final int aomVersion = aom_codec_version();
            if ((aomVersion < aomVersion_2_0_0) && (image.depth > 8)) {
                // Due to a known issue with libaom v1.0.0-errata1-avif, 10bpc
                // and
                // 12bpc image encodes will call the wrong variant of
                // aom_subtract_block when cpu-used is 7 or 8, and crash. Until
                // we get
                // a new tagged release from libaom with the fix and can verify
                // we're
                // running with that version of libaom, we must avoid using
                // cpu-used=7/8 on any >8bpc image encodes.
                //
                // Context:
                // * https://github.com/AOMediaCodec/libavif/issues/49
                // * https://bugs.chromium.org/p/aomedia/issues/detail?id=2587
                //
                // Continued bug tracking here:
                // * https://github.com/AOMediaCodec/libavif/issues/56

                if (aomCpuUsed > 6) {
                    aomCpuUsed = 6;
                }
            }

            this.aomFormat = avifImageCalcAOMFmt(image, alpha);
            if (this.aomFormat == AOM_IMG_FMT_NONE) {
                return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
            }

            this.formatInfo = image.yuvFormat.avifGetPixelFormatInfo();

            aom_codec_iface_t encoderInterface = aom_codec_av1_cx();
            aom_codec_enc_cfg cfg;
            aom_codec_err_t err = aom_codec_enc_config_default(encoderInterface, cfg, aomUsage);
            if (err != AOM_CODEC_OK) {
                System.err.printf("aom_codec_enc_config_default() failed: %s", aom_codec_err_to_string(err));
                return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
            }

            // Profile 0. 8-bit and 10-bit 4:2:0 and 4:0:0 only.
            // Profile 1. 8-bit and 10-bit 4:4:4
            // Profile 2. 8-bit and 10-bit 4:2:2
            // 12-bit 4:0:0, 4:2:0, 4:2:2 and 4:4:4
            byte seqProfile = 0;
            if (image.depth == 12) {
                // Only seqProfile 2 can handle 12 bit
                seqProfile = 2;
            } else {
                // 8-bit or 10-bit

                if (alpha) {
                    seqProfile = 0;
                } else {
                    switch (image.yuvFormat) {
                    case AVIF_PIXEL_FORMAT_YUV444:
                        seqProfile = 1;
                        break;
                    case AVIF_PIXEL_FORMAT_YUV422:
                        seqProfile = 2;
                        break;
                    case AVIF_PIXEL_FORMAT_YUV420:
                        seqProfile = 0;
                        break;
                    case AVIF_PIXEL_FORMAT_YUV400:
                        seqProfile = 0;
                        break;
                    case AVIF_PIXEL_FORMAT_NONE:
                    default:
                        break;
                    }
                }
            }

            cfg.g_profile = seqProfile;
            cfg.g_bit_depth = image.depth;
            cfg.g_input_bit_depth = image.depth;
            cfg.g_w = image.width;
            cfg.g_h = image.height;
            if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
                // Set the maximum number of frames to encode to 1. This
                // instructs
                // libaom to set still_picture and reduced_still_picture_header
                // to
                // 1 in AV1 sequence headers.
                cfg.g_limit = 1;

                // Use the default settings of the new AOM_USAGE_ALL_INTRA
                // (added in
                // https://crbug.com/aomedia/2959). Note that
                // AOM_USAGE_ALL_INTRA
                // also sets cfg.rc_end_usage to AOM_Q by default, which we do
                // not
                // set here.
                //
                // Set g_lag_in_frames to 0 to reduce the number of frame
                // buffers
                // (from 20 to 2) in libaom's lookahead structure. This reduces
                // memory consumption when encoding a single image.
                cfg.g_lag_in_frames = 0;
                // Disable automatic placement of key frames by the encoder.
                cfg.kf_mode = AOM_KF_DISABLED;
                // Tell libaom that all frames will be key frames.
                cfg.kf_max_dist = 0;
            }
            if (encoder.maxThreads > 1) {
                cfg.g_threads = encoder.maxThreads;
            }

            int minQuantizer = avif.AVIF_CLAMP(encoder.minQuantizer, 0, 63);
            int maxQuantizer = avif.AVIF_CLAMP(encoder.maxQuantizer, 0, 63);
            if (alpha) {
                minQuantizer = avif.AVIF_CLAMP(encoder.minQuantizerAlpha, 0, 63);
                maxQuantizer = avif.AVIF_CLAMP(encoder.maxQuantizerAlpha, 0, 63);
            }
            boolean lossless = ((minQuantizer == AVIF_QUANTIZER_LOSSLESS) && (maxQuantizer == AVIF_QUANTIZER_LOSSLESS));
            cfg.rc_min_quantizer = minQuantizer;
            cfg.rc_max_quantizer = maxQuantizer;

            this.monochromeEnabled = false;
            if (aomVersion > aomVersion_2_0_0) {
                // There exists a bug in libaom's chroma_check() function where
                // it will attempt to
                // access nonexistent UV planes when encoding monochrome at
                // faster libavif "speeds". It
                // was fixed shortly after the 2.0.0 libaom release, and the fix
                // exists in both the
                // master and applejack branches. This ensures that the next
                // version *after* 2.0.0 will
                // have the fix, and we must avoid cfg.monochrome until then.
                //
                // Bugfix Change-Id:
                // https://aomedia-review.googlesource.com/q/I26a39791f820b4d4e1d63ff7141f594c3c7181f5

                if (alpha || (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400)) {
                    this.monochromeEnabled = true;
                    cfg.monochrome = 1;
                }
            }

            if (!avifProcessAOMOptionsPreInit(this, alpha, cfg)) {
                return avifResult.AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION;
            }

            aom_codec_flags_t encoderFlags = 0;
            if (image.depth > 8) {
                encoderFlags |= AOM_CODEC_USE_HIGHBITDEPTH;
            }
            if (aom_codec_enc_init(this.encoder, encoderInterface, cfg, encoderFlags) != AOM_CODEC_OK) {
                System.err.printf("aom_codec_enc_init() failed: %s: %s",
                                  aom_codec_error(this.encoder),
                                  aom_codec_error_detail(this.encoder));
                return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
            }
            this.encoderInitialized = true;

            if (lossless) {
                aom_codec_control(this.encoder, AV1E_SET_LOSSLESS, 1);
            }
            if (encoder.maxThreads > 1) {
                aom_codec_control(this.encoder, AV1E_SET_ROW_MT, 1);
            }
            if (encoder.tileRowsLog2 != 0) {
                int tileRowsLog2 = avif.AVIF_CLAMP(encoder.tileRowsLog2, 0, 6);
                aom_codec_control(this.encoder, AV1E_SET_TILE_ROWS, tileRowsLog2);
            }
            if (encoder.tileColsLog2 != 0) {
                int tileColsLog2 = avif.AVIF_CLAMP(encoder.tileColsLog2, 0, 6);
                aom_codec_control(this.encoder, AV1E_SET_TILE_COLUMNS, tileColsLog2);
            }
            if (aomCpuUsed != -1) {
                if (aom_codec_control(this.encoder, AOME_SET_CPUUSED, aomCpuUsed) != AOM_CODEC_OK) {
                    return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
                }
            }
            if (!avifProcessAOMOptionsPostInit(this, alpha)) {
                return avifResult.AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION;
            }
// #if defined(AOM_USAGE_ALL_INTRA)
            if (aomUsage == AOM_USAGE_ALL_INTRA && !this.endUsageSet && !this.cqLevelSet) {
                // The default rc_end_usage in all intra mode is AOM_Q, which
                // requires cq-level to
                // function. A libavif user may not know this internal detail
                // and therefore may only
                // set the min and max quantizers in the avifEncoder struct. If
                // this is the case, set
                // cq-level to a reasonable value for the user, otherwise the
                // default cq-level
                // (currently 10) will be unknowingly used.
                assert (cfg.rc_end_usage == AOM_Q);
                int cqLevel = (cfg.rc_min_quantizer + cfg.rc_max_quantizer) / 2;
                aom_codec_control(this.encoder, AOME_SET_CQ_LEVEL, cqLevel);
            }
// #endif
        }

        aom_image_t aomImage;
        // We prefer to simply set the aomImage.planes[] pointers to the plane
        // buffers in 'image'. When
        // doing this, we set aomImage.w equal to aomImage.d_w and aomImage.h
        // equal to aomImage.d_h and
        // do not "align" aomImage.w and aomImage.h. Unfortunately this exposes
        // a bug in libaom
        // (https://crbug.com/aomedia/3113) if chroma is subsampled and
        // image.width or image.height is
        // equal to 1. To work around this libaom bug, we allocate the
        // aomImage.planes[] buffers and
        // copy the image YUV data if image.width or image.height is equal to 1.
        // This bug has been
        // fixed in libaom v3.1.3.
        //
        // Note: The exact condition for the bug is
        // ((image.width == 1) && (chroma is subsampled horizontally)) ||
        // ((image.height == 1) && (chroma is subsampled vertically))
        // Since an image width or height of 1 is uncommon in practice, we test
        // an inexact but simpler
        // condition.
        boolean aomImageAllocated = (image.width == 1) || (image.height == 1);
        if (aomImageAllocated) {
            aom_img_alloc(aomImage, this.aomFormat, image.width, image.height, 16);
        } else {
            memset(aomImage, 0, sizeof(aomImage));
            aomImage.fmt = this.aomFormat;
            aomImage.bit_depth = (image.depth > 8) ? 16 : 8;
            aomImage.w = image.width;
            aomImage.h = image.height;
            aomImage.d_w = image.width;
            aomImage.d_h = image.height;
            // Get sample size for this format.
            int bps;
            if (this.aomFormat == AOM_IMG_FMT_I420) {
                bps = 12;
            } else if (this.aomFormat == AOM_IMG_FMT_I422) {
                bps = 16;
            } else if (this.aomFormat == AOM_IMG_FMT_I444) {
                bps = 24;
            } else if (this.aomFormat == AOM_IMG_FMT_I42016) {
                bps = 24;
            } else if (this.aomFormat == AOM_IMG_FMT_I42216) {
                bps = 32;
            } else if (this.aomFormat == AOM_IMG_FMT_I44416) {
                bps = 48;
            } else {
                bps = 16;
            }
            aomImage.bps = bps;
            aomImage.x_chroma_shift = alpha ? 1 : this.formatInfo.chromaShiftX;
            aomImage.y_chroma_shift = alpha ? 1 : this.formatInfo.chromaShiftY;
        }

        boolean monochromeRequested = false;

        if (alpha) {
            aomImage.range = (image.alphaRange == avifRange.AVIF_RANGE_FULL) ? AOM_CR_FULL_RANGE : AOM_CR_STUDIO_RANGE;
            aom_codec_control(this.encoder, AV1E_SET_COLOR_RANGE, aomImage.range);
            monochromeRequested = true;
            if (aomImageAllocated) {
                final int bytesPerRow = ((image.depth > 8) ? 2 : 1) * image.width;
                for (int j = 0; j < image.height; ++j) {
                    int srcAlphaRow = j * image.alphaRowBytes;
                    int dstAlphaRow = j * aomImage.stride[0];
                    System.arraycopy(image.alphaPlane, srcAlphaRow, aomImage.planes[0], dstAlphaRow, bytesPerRow);
                }
            } else {
                aomImage.planes[0] = image.alphaPlane;
                aomImage.stride[0] = image.alphaRowBytes;
            }

            // Ignore UV planes when monochrome
        } else {
            aomImage.range = (image.yuvRange == avifRange.AVIF_RANGE_FULL) ? AOM_CR_FULL_RANGE : AOM_CR_STUDIO_RANGE;
            aom_codec_control(this.encoder, AV1E_SET_COLOR_RANGE, aomImage.range);
            int yuvPlaneCount = 3;
            if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) {
                yuvPlaneCount = 1; // Ignore UV planes when monochrome
                monochromeRequested = true;
            }
            if (aomImageAllocated) {
                int xShift = this.formatInfo.chromaShiftX;
                int uvWidth = (image.width + xShift) >> xShift;
                int yShift = this.formatInfo.chromaShiftY;
                int uvHeight = (image.height + yShift) >> yShift;
                int bytesPerPixel = (image.depth > 8) ? 2 : 1;
                for (int yuvPlane = 0; yuvPlane < yuvPlaneCount; ++yuvPlane) {
                    int planeWidth = (yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? image.width : uvWidth;
                    int planeHeight = (yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? image.height : uvHeight;
                    int bytesPerRow = bytesPerPixel * planeWidth;

                    for (int j = 0; j < planeHeight; ++j) {
                        int srcRow = j * image.yuvRowBytes[yuvPlane];
                        int dstRow = j * aomImage.stride[yuvPlane];
                        System.arraycopy(image.yuvPlanes[yuvPlane], srcRow, aomImage.planes[yuvPlane], dstRow, bytesPerRow);
                    }
                }
            } else {
                for (int yuvPlane = 0; yuvPlane < yuvPlaneCount; ++yuvPlane) {
                    aomImage.planes[yuvPlane] = image.yuvPlanes[yuvPlane];
                    aomImage.stride[yuvPlane] = image.yuvRowBytes[yuvPlane];
                }
            }

            aomImage.cp = (aom_color_primaries_t) image.colorPrimaries;
            aomImage.tc = (aom_transfer_characteristics_t) image.transferCharacteristics;
            aomImage.mc = (aom_matrix_coefficients_t) image.matrixCoefficients;
            aomImage.csp = (aom_chroma_sample_position_t) image.yuvChromaSamplePosition;
            aom_codec_control(this.encoder, AV1E_SET_COLOR_PRIMARIES, aomImage.cp);
            aom_codec_control(this.encoder, AV1E_SET_TRANSFER_CHARACTERISTICS, aomImage.tc);
            aom_codec_control(this.encoder, AV1E_SET_MATRIX_COEFFICIENTS, aomImage.mc);
            aom_codec_control(this.encoder, AV1E_SET_CHROMA_SAMPLE_POSITION, aomImage.csp);
        }

        byte[] monoUVPlane = null;
        if (monochromeRequested && !this.monochromeEnabled) {
            // The user requested monochrome (via alpha or YUV400) but libaom
            // cannot currently support
            // monochrome (see chroma_check comment above). Manually set UV
            // planes to 0.5.

            // aomImage is always 420 when we're monochrome
            int monoUVWidth = (image.width + 1) >> 1;
            int monoUVHeight = (image.height + 1) >> 1;

            // Allocate the U plane if necessary.
            if (!aomImageAllocated) {
                int channelSize = avifImageUsesU16(image) ? 2 : 1;
                int monoUVRowBytes = channelSize * monoUVWidth;
                int monoUVSize = monoUVHeight * monoUVRowBytes;

                monoUVPlane = new byte[monoUVSize];
                aomImage.planes[1] = monoUVPlane;
                aomImage.stride[1] = monoUVRowBytes;
            }
            // Set the U plane to 0.5.
            if (image.depth > 8) {
                final short half = (short) (1 << (image.depth - 1));
                for (int j = 0; j < monoUVHeight; ++j) {
                    short[] dstRow = (short[]) aomImage.planes[1][(int) j * aomImage.stride[1]];
                    for (int i = 0; i < monoUVWidth; ++i) {
                        dstRow[i] = half;
                    }
                }
            } else {
                final byte half = (byte) 128;
                int planeSize = (int) monoUVHeight * aomImage.stride[1];
                memset(aomImage.planes[1], half, planeSize);
            }
            // Make the V plane the same as the U plane.
            aomImage.planes[2] = aomImage.planes[1];
            aomImage.stride[2] = aomImage.stride[1];
        }

        aom_enc_frame_flags_t encodeFlags = 0;
        if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME)) {
            encodeFlags |= AOM_EFLAG_FORCE_KF;
        }
        aom_codec_err_t encodeErr = aom_codec_encode(this.encoder, aomImage, 0, 1, encodeFlags);
        if (aomImageAllocated) {
            aom_img_free(aomImage);
        }
        if (encodeErr != AOM_CODEC_OK) {
            System.err.printf("aom_codec_encode() failed: %s: %s",
                              aom_codec_error(this.encoder),
                              aom_codec_error_detail(this.encoder));
            return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
        }

        aom_codec_iter_t iter = null;
        for (;;) {
            final aom_codec_cx_pkt_t pkt = aom_codec_get_cx_data(this.encoder, iter);
            if (pkt == null) {
                break;
            }
            if (pkt.kind == AOM_CODEC_CX_FRAME_PKT) {
                avifCodecEncodeOutputAddSample(output,
                                               pkt.data.frame.buf,
                                               pkt.data.frame.sz,
                                               (pkt.data.frame.flags & AOM_FRAME_IS_KEY));
            }
        }

        if (addImageFlags & avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE) {
            // Flush and clean up encoder resources early to save on overhead
            // when encoding alpha or grid images

            if (!encodeFinish(output)) {
                return avifResult.AVIF_RESULT_UNKNOWN_ERROR;
            }
            aom_codec_destroy(this.encoder);
            this.encoderInitialized = false;
        }
        return avifResult.AVIF_RESULT_OK;
    }

    @Override
    public boolean encodeFinish(avifCodecEncodeOutput output) {
        if (!this.encoderInitialized) {
            return true;
        }
        for (;;) {
            // flush encoder
            if (aom_codec_encode(this.encoder, null, 0, 1, 0) != AOM_CODEC_OK) {
                System.err.printf("aom_codec_encode() with img=null failed: %s: %s",
                                  aom_codec_error(this.encoder),
                                  aom_codec_error_detail(this.encoder));
                return false;
            }

            boolean gotPacket = false;
            aom_codec_iter_t iter = null;
            for (;;) {
                final aom_codec_cx_pkt_t pkt = aom_codec_get_cx_data(this.encoder, iter);
                if (pkt == null) {
                    break;
                }
                if (pkt.kind == AOM_CODEC_CX_FRAME_PKT) {
                    gotPacket = true;
                    avifCodecEncodeOutputAddSample(output,
                                                   pkt.data.frame.buf,
                                                   pkt.data.frame.sz,
                                                   (pkt.data.frame.flags & AOM_FRAME_IS_KEY));
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
        return aom_codec_version_str();
    }
}
