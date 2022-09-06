/*
 * Copyright © 2018, VideoLAN and dav1d authors
 * Copyright © 2018, Two Orioles, LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

// OBU parsing and bit magic all originally from dav1d's obu.c and getbits.c,
// but heavily modified/reduced down to simply find the Sequence Header OBU
// and pull a few interesting pieces from it.
//
// Any other code in here is under this license:
//
// Copyright 2020 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

// #include "avif/internal.h"

// #include <string.h>

// ---------------------------------------------------------------------------
// avifBits - Originally dav1d's GetBits class (see dav1d's getbits.c)

package vavi.awt.image.avif;

import vavi.awt.image.avif.avif.avifChromaSamplePosition;
import vavi.awt.image.avif.avif.avifCodecConfigurationBox;
import vavi.awt.image.avif.avif.avifColorPrimaries;
import vavi.awt.image.avif.avif.avifMatrixCoefficients;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifTransferCharacteristics;

class obu {

    class avifBits
{
    int error, eof;
    long state;
    int bitsLeft;
    byte[]ptr, start, end;
}

private int avifBitsReadPos(final avifBits bits)
{
    return (int)(bits.ptr - bits.start) * 8 - bits.bitsLeft;
}

private void avifBitsInit(avifBits bits, final byte[] data, final int size)
{
    bits.ptr = bits.start = data;
    bits.end = bits.start[size];
    bits.bitsLeft = 0;
    bits.state = 0;
    bits.error = 0;
    bits.eof = 0;
}

private void avifBitsRefill(avifBits  bits, final int n)
{
    long state = 0;
    do {
        state <<= 8;
        bits.bitsLeft += 8;
        if (bits.eof == 0)
            state |= bits.ptr++;
        if (bits.ptr >= bits.end) {
            bits.error = bits.eof;
            bits.eof = 1;
        }
    } while (n > bits.bitsLeft);
    bits.state |= state << (64 - bits.bitsLeft);
}

private int avifBitsRead(avifBits  bits, final int n)
{
    if (n > bits.bitsLeft)
        avifBitsRefill(bits, n);

    final long state = bits.state;
    bits.bitsLeft -= n;
    bits.state <<= n;

    return (int)(state >> (64 - n));
}

private int avifBitsReadUleb128(avifBits  bits)
{
    long val = 0;
    int more;
    int i = 0;

    do {
        final int v = avifBitsRead(bits, 8);
        more = v & 0x80;
        val |= ((long)(v & 0x7F)) << i;
        i += 7;
    } while (more != 0 && i < 56);

    if (val > Integer.MAX_VALUE || more != 0) {
        bits.error = 1;
        return 0;
    }

    return (int)val;
}

private int avifBitsReadVLC(avifBits  bits)
{
    int numBits = 0;
    while (avifBitsRead(bits, 1) == 0)
        if (++numBits == 32)
            return 0xFFFFFFFF;
    return numBits != 0 ? ((1 << numBits) - 1) + avifBitsRead(bits, numBits) : 0;
}

static class avifSequenceHeader
{
    int maxWidth;
    int maxHeight;
    int bitDepth;
    avifPixelFormat yuvFormat;
    avifChromaSamplePosition chromaSamplePosition;
    avifColorPrimaries colorPrimaries;
    avifTransferCharacteristics transferCharacteristics;
    avifMatrixCoefficients matrixCoefficients;
    avifRange range;
    avifCodecConfigurationBox av1C;
}

// ---------------------------------------------------------------------------
// Variables in here use snake_case to self-document from the AV1 spec:
//
// https://aomediacodec.github.io/av1-spec/av1-spec.pdf

// Originally dav1d's parse_seq_hdr() function (heavily modified)
private boolean parseSequenceHeader(avifBits  bits, avifSequenceHeader  header)
{
    int seq_profile = avifBitsRead(bits, 3);
    if (seq_profile > 2) {
        return false;
    }
    header.av1C.seqProfile = (byte)seq_profile;

    int still_picture = avifBitsRead(bits, 1);
    int reduced_still_picture_header = avifBitsRead(bits, 1);
    if (reduced_still_picture_header != 0 && still_picture == 0) {
        return false;
    }

    if (reduced_still_picture_header != 0) {
        header.av1C.seqLevelIdx0 = (byte)avifBitsRead(bits, 5);
        header.av1C.seqTier0 = 0;
    } else {
        int timing_info_present_flag = avifBitsRead(bits, 1);
        int decoder_model_info_present_flag = 0;
        int buffer_delay_length = 0;
        if (timing_info_present_flag != 0) { // timing_info()
            avifBitsRead(bits, 32);     // num_units_in_display_tick
            avifBitsRead(bits, 32);     // time_scale
            int equal_picture_interval = avifBitsRead(bits, 1);
            if (equal_picture_interval != 0) {
                int num_ticks_per_picture_minus_1 = avifBitsReadVLC(bits);
                if (num_ticks_per_picture_minus_1 == 0xFFFFFFFF)
                    return false;
            }

            decoder_model_info_present_flag = avifBitsRead(bits, 1);
            if (decoder_model_info_present_flag != 0) { // decoder_model_info()
                buffer_delay_length = avifBitsRead(bits, 5) + 1;
                avifBitsRead(bits, 32); // num_units_in_decoding_tick
                avifBitsRead(bits, 10); // buffer_removal_time_length_minus_1, frame_presentation_time_length_minus_1
            }
        }

        int initial_display_delay_present_flag = avifBitsRead(bits, 1);
        int operating_points_cnt = avifBitsRead(bits, 5) + 1;
        for (int i = 0; i < operating_points_cnt; i++) {
            avifBitsRead(bits, 12); // operating_point_idc
            int seq_level_idx = avifBitsRead(bits, 5);
            if (i == 0) {
                header.av1C.seqLevelIdx0 = (byte)seq_level_idx;
                header.av1C.seqTier0 = 0;
            }
            if (seq_level_idx > 7) {
                int seq_tier = avifBitsRead(bits, 1);
                if (i == 0) {
                    header.av1C.seqTier0 = (byte)seq_tier;
                }
            }
            if (decoder_model_info_present_flag != 0) {
                int decoder_model_present_for_this_op = avifBitsRead(bits, 1);
                if (decoder_model_present_for_this_op != 0) {     // operating_parameters_info()
                    avifBitsRead(bits, buffer_delay_length); // decoder_buffer_delay
                    avifBitsRead(bits, buffer_delay_length); // encoder_buffer_delay
                    avifBitsRead(bits, 1);                   // low_delay_mode_flag
                }
            }
            if (initial_display_delay_present_flag != 0) {
                int initial_display_delay_present_for_this_op = avifBitsRead(bits, 1);
                if (initial_display_delay_present_for_this_op != 0) {
                    avifBitsRead(bits, 4); // initial_display_delay_minus_1
                }
            }
        }
    }

    int frame_width_bits = avifBitsRead(bits, 4) + 1;
    int frame_height_bits = avifBitsRead(bits, 4) + 1;
    header.maxWidth = avifBitsRead(bits, frame_width_bits) + 1;   // max_frame_width
    header.maxHeight = avifBitsRead(bits, frame_height_bits) + 1; // max_frame_height
    int frame_id_numbers_present_flag = 0;
    if (reduced_still_picture_header == 0) {
        frame_id_numbers_present_flag = avifBitsRead(bits, 1);
    }
    if (frame_id_numbers_present_flag != 0) {
        avifBitsRead(bits, 7); // delta_frame_id_length_minus_2, additional_frame_id_length_minus_1
    }

    avifBitsRead(bits, 3); // use_128x128_superblock, enable_filter_intra, enable_intra_edge_filter

    if (reduced_still_picture_header == 0) {
        avifBitsRead(bits, 4); // enable_interintra_compound, enable_masked_compound, enable_warped_motion, enable_dual_filter
        int enable_order_hint = avifBitsRead(bits, 1);
        if (enable_order_hint != 0) {
            avifBitsRead(bits, 2); // enable_jnt_comp, enable_ref_frame_mvs
        }

        int seq_force_screen_content_tools = 0;
        int seq_choose_screen_content_tools = avifBitsRead(bits, 1);
        if (seq_choose_screen_content_tools != 0) {
            seq_force_screen_content_tools = 2;
        } else {
            seq_force_screen_content_tools = avifBitsRead(bits, 1);
        }
        if (seq_force_screen_content_tools > 0) {
            int seq_choose_integer_mv = avifBitsRead(bits, 1);
            if (seq_choose_integer_mv == 0) {
                avifBitsRead(bits, 1); // seq_force_integer_mv
            }
        }
        if (enable_order_hint != 0) {
            avifBitsRead(bits, 3); // order_hint_bits_minus_1
        }
    }

    avifBitsRead(bits, 3); // enable_superres, enable_cdef, enable_restoration

    // color_config()
    header.bitDepth = 8;
    header.chromaSamplePosition = avifChromaSamplePosition.AVIF_CHROMA_SAMPLE_POSITION_UNKNOWN;
    header.av1C.chromaSamplePosition = (byte) header.chromaSamplePosition.ordinal();
    int high_bitdepth = avifBitsRead(bits, 1);
    header.av1C.highBitdepth = (byte)high_bitdepth;
    if ((seq_profile == 2) && high_bitdepth != 0) {
        int twelve_bit = avifBitsRead(bits, 1);
        header.bitDepth = twelve_bit  != 0 ? 12 : 10;
        header.av1C.twelveBit = (byte)twelve_bit;
    } else /* if (seq_profile <= 2) */ {
        header.bitDepth = high_bitdepth  != 0 ? 10 : 8;
        header.av1C.twelveBit = 0;
    }
    int mono_chrome = 0;
    if (seq_profile != 1) {
        mono_chrome = avifBitsRead(bits, 1);
    }
    header.av1C.monochrome = (byte)mono_chrome;
    int color_description_present_flag = avifBitsRead(bits, 1);
    if (color_description_present_flag != 0) {
        header.colorPrimaries = avifColorPrimaries.valueOf(avifBitsRead(bits, 8));                   // color_primaries
        header.transferCharacteristics = avifTransferCharacteristics.valueOf(avifBitsRead(bits, 8)); // transfer_characteristics
        header.matrixCoefficients = avifMatrixCoefficients.valueOf(avifBitsRead(bits, 8));           // matrix_coefficients
    } else {
        header.colorPrimaries = avifColorPrimaries.AVIF_COLOR_PRIMARIES_UNSPECIFIED;
        header.transferCharacteristics = avifTransferCharacteristics.AVIF_TRANSFER_CHARACTERISTICS_UNSPECIFIED;
        header.matrixCoefficients = avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_UNSPECIFIED;
    }
    if (mono_chrome != 0) {
        header.range = avifBitsRead(bits, 1) != 0 ? avifRange.AVIF_RANGE_FULL : avifRange.AVIF_RANGE_LIMITED; // color_range
        header.av1C.chromaSubsamplingX = 1;
        header.av1C.chromaSubsamplingY = 1;
        header.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
    } else if (header.colorPrimaries == avifColorPrimaries.AVIF_COLOR_PRIMARIES_BT709 &&
               header.transferCharacteristics == avifTransferCharacteristics.AVIF_TRANSFER_CHARACTERISTICS_SRGB &&
               header.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_IDENTITY) {
        header.range = avifRange.AVIF_RANGE_FULL;
        header.av1C.chromaSubsamplingX = 0;
        header.av1C.chromaSubsamplingY = 0;
        header.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
    } else {
        int subsampling_x = 0;
        int subsampling_y = 0;
        header.range = avifBitsRead(bits, 1) != 0 ? avifRange.AVIF_RANGE_FULL : avifRange.AVIF_RANGE_LIMITED; // color_range
        switch (seq_profile) {
            case 0:
                subsampling_x = 1;
                subsampling_y = 1;
                header.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
                break;
            case 1:
                subsampling_x = 0;
                subsampling_y = 0;
                header.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
                break;
            case 2:
                if (header.bitDepth == 12) {
                    subsampling_x = avifBitsRead(bits, 1);
                    if (subsampling_x != 0) {
                        subsampling_y = avifBitsRead(bits, 1);
                    }
                } else {
                    subsampling_x = 1;
                    subsampling_y = 0;
                }
                if (subsampling_x != 0) {
                    header.yuvFormat = subsampling_y  != 0 ? avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420 : avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;
                } else {
                    header.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
                }
                break;
        }

        if (subsampling_x != 0 && subsampling_y != 0) {
            header.chromaSamplePosition = avifChromaSamplePosition.valueOf(avifBitsRead(bits, 2)); // chroma_sample_position
            header.av1C.chromaSamplePosition = (byte)header.chromaSamplePosition.ordinal();
        }
        header.av1C.chromaSubsamplingX = (byte)subsampling_x;
        header.av1C.chromaSubsamplingY = (byte)subsampling_y;
    }

    if (mono_chrome == 0) {
        avifBitsRead(bits, 1); // separate_uv_delta_q
    }
    avifBitsRead(bits, 1); // film_grain_params_present

    return bits.error == 0;
}

boolean avifSequenceHeaderParse(avifSequenceHeader  header, final byte[] /* avifROData */  sample)
{
    final byte[] /* avifROData */ obus = sample;

    // Find the sequence header OBU
    while (obus.length > 0) {
        avifBits bits;
        avifBitsInit(bits, obus, obus.length);

        // obu_header()
        avifBitsRead(bits, 1); // obu_forbidden_bit
        final int obu_type = avifBitsRead(bits, 4);
        final int obu_extension_flag = avifBitsRead(bits, 1);
        final int obu_has_size_field = avifBitsRead(bits, 1);
        avifBitsRead(bits, 1); // obu_reserved_1bit

        if (obu_extension_flag != 0) {   // obu_extension_header()
            avifBitsRead(bits, 8); // temporal_id, spatial_id, extension_header_reserved_3bits
        }

        int obu_size = 0;
        if (obu_has_size_field != 0)
            obu_size = avifBitsReadUleb128(bits);
        else
            obu_size = obus.length - 1 - obu_extension_flag;

        if (bits.error == 0) {
            return false;
        }

        final int init_bit_pos = avifBitsReadPos(bits);
        final int init_byte_pos = init_bit_pos >> 3;
        if (obu_size > obus.length - init_byte_pos)
            return false;

        if (obu_type == 1) { // Sequence Header
            return parseSequenceHeader(bits, header);
        }

        // Skip this OBU
        obus += obu_size + init_byte_pos;
        obus.size -= obu_size + init_byte_pos;
    }
    return false;
}
}