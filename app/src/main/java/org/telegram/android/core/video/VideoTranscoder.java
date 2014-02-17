package org.telegram.android.core.video;

import android.media.*;
import android.util.Log;
import org.telegram.android.log.Logger;
import org.telegram.android.util.IOUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by ex3ndr on 15.02.14.
 */
public class VideoTranscoder {

    private static final String TAG = "VideoTranscoder";

    public static boolean transcodeVideo(String source, String dest) throws IOException {

        // IOUtils.copy(new File(source), new File("/sdcard/in.mp4"));

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(source);
        int trackIndex = selectTrack(extractor);
        if (trackIndex < 0) {
            throw new RuntimeException("No video track found in " + source);
        }
        extractor.selectTrack(trackIndex);


        VideoChunks chunks = extractVideo(extractor, trackIndex);

        // chunks.saveToFile(new File("/sdcard/out_1.h264"));

        // saveVideo(chunks, "/sdcard/out_2.mp4");

        VideoChunks edited = editVideo(chunks);

        // saveVideo(edited, "/sdcard/out_3.mp4");

        saveVideo(edited, dest);

        IOUtils.copy(new File(dest), new File("/sdcard/out.mp4"));

        return true;
    }

    private static VideoChunks editVideo(VideoChunks inputData) {
        VideoChunks outputData = new VideoChunks();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        InputSurface inputSurface = null;
        OutputSurface outputSurface = null;

        try {
            MediaFormat inputFormat = inputData.getMediaFormat();

            // Create an encoder format that matches the input format.  (Might be able to just
            // re-use the format used to generate the video, since we want it to be the same.)
            MediaFormat outputFormat = MediaFormat.createVideoFormat("video/avc", 640, 320);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1000000);

            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 15);
//            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE,
//                    inputFormat.getInteger(MediaFormat.KEY_BIT_RATE));
//            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE,
//                    inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
//            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
//                    inputFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));

            // outputData.setMediaFormat(outputFormat);

            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            //outputData.setMediaFormat(encoder.getOutputFormat());
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            // OutputSurface uses the EGL context created by InputSurface.
            decoder = MediaCodec.createDecoderByType("video/avc");
            outputSurface = new OutputSurface();
            // outputSurface.changeFragmentShader(FRAGMENT_SHADER);
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
            decoder.start();

            editVideoData(inputData, decoder, outputSurface, inputSurface, encoder, outputData);
        } finally {
            Log.d(TAG, "shutting down encoder, decoder");
            if (outputSurface != null) {
                outputSurface.release();
            }
            if (inputSurface != null) {
                inputSurface.release();
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
        }

        return outputData;
    }


    private static void editVideoData(VideoChunks inputData, MediaCodec decoder,
                                      OutputSurface outputSurface, InputSurface inputSurface, MediaCodec encoder,
                                      VideoChunks outputData) {
        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputChunk = 0;
        int outputCount = 0;

        boolean outputDone = false;
        boolean inputDone = false;
        boolean decoderDone = false;
        while (!outputDone) {
            // Log.d(TAG, "edit loop");

            // Feed more data to the decoder.
            if (!inputDone) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    if (inputChunk == inputData.getNumChunks()) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        // Log.d(TAG, "sent input EOS (with zero-length frame)");
                    } else {
                        // Copy a chunk of input to the decoder.  The first chunk should have
                        // the BUFFER_FLAG_CODEC_CONFIG flag set.
                        ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                        inputBuf.clear();
                        inputData.getChunkData(inputChunk, inputBuf);
                        int flags = inputData.getChunkFlags(inputChunk);
                        long time = inputData.getChunkTime(inputChunk);
                        decoder.queueInputBuffer(inputBufIndex, 0, inputData.getChunkSize(inputChunk),
                                time, flags);

                        // Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" + inputBuf.position() + " flags=" + flags);
                        inputChunk++;
                    }
                } else {
                    // Log.d(TAG, "input buffer not available");
                }
            }

            // Assume output is available.  Loop until both assumptions are false.
            boolean decoderOutputAvailable = !decoderDone;
            boolean encoderOutputAvailable = true;
            while (decoderOutputAvailable || encoderOutputAvailable) {
                // Start by draining any pending output from the encoder.  It's important to
                // do this before we try to stuff any more data in.
                int encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    // Log.d(TAG, "no output from encoder available");
                    encoderOutputAvailable = false;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                    // Log.d(TAG, "encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    // Log.d(TAG, "encoder output format changed: " + newFormat);
                    outputData.setMediaFormat(newFormat);
                } else if (encoderStatus < 0) {
                    // fail("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        // fail("encoderOutputBuffer " + encoderStatus + " was null");
                    }

                    // Write the data to the output "file".
                    if (info.size != 0) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);

                        outputData.addChunk(encodedData, info.size, info.flags, info.presentationTimeUs);
                        outputCount++;
                    }
                    // Log.d(TAG, "encoder output " + info.size + " bytes");
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue;
                }

                // Encoder is drained, check to see if we've got a new frame of output from
                // the decoder.  (The output is going to a Surface, rather than a ByteBuffer,
                // but we still get information through BufferInfo.)
                if (!decoderDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        // Log.d(TAG, "no output from decoder available");
                        decoderOutputAvailable = false;
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // decoderOutputBuffers = decoder.getOutputBuffers();
                        // Log.d(TAG, "decoder output buffers changed (we don't care)");
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // expected before first buffer of data
                        MediaFormat newFormat = decoder.getOutputFormat();
                        // Log.d(TAG, "decoder output format changed: " + newFormat);
                    } else if (decoderStatus < 0) {
                        // fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                    } else { // decoderStatus >= 0
                        // Log.d(TAG, "surface decoder given buffer " + decoderStatus + " (size=" + info.size + ")");
                        // The ByteBuffers are null references, but we still get a nonzero
                        // size for the decoded data.
                        boolean doRender = (info.size != 0);

                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't
                        // guarantee that the texture will be available before the call
                        // returns, so we need to wait for the onFrameAvailable callback to
                        // fire.  If we don't wait, we risk rendering from the previous frame.
                        decoder.releaseOutputBuffer(decoderStatus, doRender);
                        if (doRender) {
                            // This waits for the image and renders it after it arrives.
                            // Log.d(TAG, "awaiting frame");
                            outputSurface.awaitNewImage();
                            outputSurface.drawImage();

                            // Send it to the encoder.
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                            // Log.d(TAG, "swapBuffers");
                            inputSurface.swapBuffers();
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            // forward decoder EOS to encoder
                            //if (VERBOSE) Log.d(TAG, "signaling input EOS");
                            //if (WORK_AROUND_BUGS) {
                            // Bail early, possibly dropping a frame.
                            // return;
                            //} else {
                            encoder.signalEndOfInputStream();
                            //}
                        }
                    }
                }
            }
        }

//        if (inputChunk != outputCount) {
//            throw new RuntimeException("frame lost: " + inputChunk + " in, " +
//                    outputCount + " out");
//        }
    }

    private static void saveVideo(VideoChunks chunks, String fileName) throws IOException {
        MediaMuxer mediaMuxer = new MediaMuxer(fileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = mediaMuxer.addTrack(chunks.getMediaFormat());
        mediaMuxer.start();
        ByteBuffer buffer = ByteBuffer.allocate(5 * 1024 * 1024);
        for (int i = 0; i < chunks.getNumChunks(); i++) {
            chunks.getChunkData(i, buffer);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            bufferInfo.flags = chunks.getChunkFlags(i);
            bufferInfo.presentationTimeUs = chunks.getChunkTime(i);
            bufferInfo.size = chunks.getChunkSize(i);
            bufferInfo.offset = 0;
            mediaMuxer.writeSampleData(track, buffer, bufferInfo);
        }
        mediaMuxer.stop();
        mediaMuxer.release();
    }

    private static VideoChunks extractVideo(MediaExtractor extractor, int track) {
        VideoChunks dest = new VideoChunks();
        dest.setMediaFormat(extractor.getTrackFormat(track));
        ByteBuffer inputBuffer = ByteBuffer.allocate(5 * 1024 * 1024);
        int size = 0;
        while ((size = extractor.readSampleData(inputBuffer, 0)) >= 0) {
            inputBuffer.limit(size);

            long presentationTimeUs = extractor.getSampleTime();
            int flags = extractor.getSampleFlags();

            dest.addChunk(inputBuffer, size, flags, presentationTimeUs);

            extractor.advance();
            inputBuffer.position(0);
        }
        return dest;
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private static int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                return i;
            }
        }

        return -1;
    }
}
