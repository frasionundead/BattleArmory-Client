/*
 * Battle Armory Windows allocation backend for spark 1.10.53.
 *
 * This file is distributed under the GNU General Public License v3.0,
 * matching the upstream spark project license.
 */
package me.lucko.spark.common.sampler.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.sampler.AbstractSampler;
import me.lucko.spark.common.sampler.SamplerMode;
import me.lucko.spark.common.sampler.SamplerSettings;
import me.lucko.spark.common.sampler.async.jfr.JfrReader;
import me.lucko.spark.common.sampler.window.ProfilingWindowUtils;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.util.SparkThreadFactory;
import me.lucko.spark.common.ws.ViewerSocket;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Windows fallback allocation profiler using the JDK Flight Recorder.
 *
 * async-profiler has no native Windows backend in spark 1.10.53. Java 17,
 * however, ships JFR on Windows and exposes the same standard allocation event
 * types that spark's existing JfrReader already knows how to parse.
 *
 * ObjectAllocationInNewTLAB samples are weighted by tlabSize and
 * ObjectAllocationOutsideTLAB samples by allocationSize, matching spark's
 * existing AllocationSample.value() semantics.
 */
public final class JfrAllocationSampler extends AbstractSampler {
    private final AsyncDataAggregator dataAggregator;
    private final Object recordingMutex = new Object();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> socketStatisticsTask;
    private Recording recording;
    private Path outputFile;
    private int window;
    private boolean stopped;

    public JfrAllocationSampler(SparkPlatform platform, SamplerSettings settings) {
        super(platform, settings);
        this.dataAggregator = new AsyncDataAggregator(settings.threadGrouper());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat("spark-jfr-allocation-worker-thread")
                        .setUncaughtExceptionHandler(SparkThreadFactory.EXCEPTION_HANDLER)
                        .build()
        );
    }

    public static boolean isSupported() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void start() {
        super.start();

        TickHook tickHook = this.platform.getTickHook();
        if (tickHook != null) {
            this.windowStatisticsCollector.startCountingTicks(tickHook);
        }

        this.window = ProfilingWindowUtils.windowNow();
        this.windowStatisticsCollector.recordWindowStartTime(this.window);

        try {
            this.outputFile = this.platform.getTemporaryFiles().create("spark-windows-", "-allocation.jfr");
            // Recording.dump writes the file itself; retain only the unique path.
            Files.deleteIfExists(this.outputFile);

            this.recording = new Recording();
            this.recording.setName("spark-windows-allocation");
            this.recording.setToDisk(true);
            this.recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
            this.recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
            this.recording.start();
        } catch (Exception e) {
            closeRecordingQuietly();
            throw new RuntimeException("Unable to start the Windows JFR allocation profiler", e);
        }

        recordInitialGcStats();
        scheduleTimeout();
    }

    private void scheduleTimeout() {
        if (this.autoEndTime == -1) {
            return;
        }

        long delay = this.autoEndTime - System.currentTimeMillis();
        if (delay <= 0) {
            return;
        }

        this.scheduler.schedule(() -> {
            try {
                stop(false);
                this.future.complete(this);
            } catch (Throwable t) {
                this.future.completeExceptionally(t);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop(boolean cancelled) {
        synchronized (this.recordingMutex) {
            if (this.stopped) {
                return;
            }
            this.stopped = true;
        }

        super.stop(cancelled);

        try {
            if (this.recording != null) {
                try {
                    this.recording.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped by the JVM; continue with whatever data exists.
                }

                if (!cancelled) {
                    this.recording.dump(this.outputFile);
                    this.windowStatisticsCollector.measureNow(this.window);
                    aggregate();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to finish the Windows JFR allocation profile", e);
        } finally {
            closeRecordingQuietly();
            deleteOutputFileQuietly();

            if (this.socketStatisticsTask != null) {
                this.socketStatisticsTask.cancel(false);
            }
            if (this.scheduler != null) {
                this.scheduler.shutdown();
                this.scheduler = null;
            }
        }
    }

    private void aggregate() throws IOException {
        try (JfrReader reader = new JfrReader(this.outputFile)) {
            List<JfrReader.AllocationSample> samples = reader.readAllEvents(JfrReader.AllocationSample.class);
            for (JfrReader.AllocationSample sample : samples) {
                String threadName = reader.threads.get((long) sample.tid);
                if (threadName == null) {
                    continue;
                }
                if (!this.threadDumper.isThreadIncluded(sample.tid, threadName)) {
                    continue;
                }

                long value = sample.value();
                if (value <= 0) {
                    continue;
                }

                ProfileSegment segment = ProfileSegment.parseSegment(reader, sample, threadName, value);
                this.dataAggregator.insertData(segment, this.window);
            }
        }
    }

    private void closeRecordingQuietly() {
        Recording r = this.recording;
        this.recording = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void deleteOutputFileQuietly() {
        if (this.outputFile != null) {
            try {
                Files.deleteIfExists(this.outputFile);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void attachSocket(ViewerSocket socket) {
        super.attachSocket(socket);
        if (this.socketStatisticsTask == null && this.scheduler != null) {
            this.socketStatisticsTask = this.scheduler.scheduleAtFixedRate(
                    this::sendStatisticsToSocket,
                    10,
                    10,
                    TimeUnit.SECONDS
            );
        }
    }

    @Override
    public SamplerMode getMode() {
        return SamplerMode.ALLOCATION;
    }

    @Override
    public SamplerData toProto(SparkPlatform platform, ExportProps exportProps) {
        SamplerData.Builder proto = SamplerData.newBuilder();
        if (exportProps.channelInfo() != null) {
            proto.setChannelInfo(exportProps.channelInfo());
        }
        writeMetadataToProto(proto, platform, exportProps.creator(), exportProps.comment(), this.dataAggregator);
        writeDataToProto(proto, this.dataAggregator, exportProps.mergeMode().get(), exportProps.classSourceLookup().get());
        return proto.build();
    }
}
